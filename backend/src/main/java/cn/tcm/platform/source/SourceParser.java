package cn.tcm.platform.source;

import cn.tcm.platform.common.ApiException;
import com.fasterxml.jackson.databind.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.io.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.zip.*;
import javax.imageio.ImageIO;

@Component
public class SourceParser {
    private final SourceAccess access;private final ObjectMapper json;private final SourceObjectStore store;private final SourceJobs jobs;private final SourceLimits limits;
    private final String baseUrl,token;private final Path tempRoot;
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build();
    public record DocumentResult(List<SourceJobs.ParsedSegment> segments,JsonNode reading,List<SourceReadingData.Asset> assets){}
    public SourceParser(SourceAccess access,ObjectMapper json,SourceObjectStore store,SourceJobs jobs,SourceLimits limits,
            @Value("${tcm.ai.base-url}")String baseUrl,@Value("${tcm.ai.internal-token}")String token,
            @Value("${tcm.storage.source-temp-directory:${java.io.tmpdir}/tcm-source-staging}")String tempRoot){
        this.access=access;this.json=json;this.store=store;this.jobs=jobs;this.limits=limits;this.baseUrl=baseUrl;this.token=token;this.tempRoot=Path.of(tempRoot);
    }
    public List<SourceJobs.ParsedSegment> parse(SourceJobs.Lease lease){return process(lease).segments();}
    public DocumentResult process(SourceJobs.Lease lease){
        var rows=access.db.queryForList("SELECT f.* FROM file_objects f JOIN source_documents d ON d.file_id=f.id WHERE d.id=?",lease.documentId());if(rows.isEmpty())throw ApiException.invalid("资料未关联文件");var original=rows.get(0);
        Path directory=null;ScheduledExecutorService timer=Executors.newSingleThreadScheduledExecutor(r->{var t=new Thread(r,"source-process-timeout");t.setDaemon(true);return t;});
        try{
            if(!jobs.validLease(lease))throw ApiException.conflict("处理租约已失效");
            Files.createDirectories(tempRoot);directory=Files.createTempDirectory(tempRoot,"process-");Path bundle=directory.resolve("result.zip");
            String body=json.writeValueAsString(Map.of("jobId",Long.toString(lease.id()),"runToken",lease.runToken(),"fileName",original.get("original_name")));
            var request=HttpRequest.newBuilder(URI.create(baseUrl.replaceAll("/+$","")+"/internal/v2/process")).timeout(Duration.ofSeconds(650)).header("Content-Type","application/json").header("X-Internal-Token",token).header("X-Trace-Id",lease.runToken()).POST(HttpRequest.BodyPublishers.ofString(body)).build();
            var pending=http.sendAsync(request,HttpResponse.BodyHandlers.ofInputStream());HttpResponse<InputStream> response;
            try{response=pending.get(650,TimeUnit.SECONDS);}catch(Exception e){pending.cancel(true);throw e;}
            try(var input=response.body()){
                var timeout=timer.schedule(()->{try{input.close();}catch(IOException ignored){}},650,TimeUnit.SECONDS);
                try{
                    if(response.statusCode()!=200||!response.headers().firstValue("Content-Type").orElse("").split(";",2)[0].equals("application/zip"))throw new ApiException(502,"PARSE_FAILED","处理服务未返回有效阅读产物");
                    long declared=response.headers().firstValueAsLong("Content-Length").orElse(-1);if(declared>limits.maxExpandedBytes())throw new IOException("Bundle limit");
                    long size;try(var output=Files.newOutputStream(bundle)){size=copy(input,output,limits.maxExpandedBytes(),null);}
                    if(declared>=0&&declared!=size)throw new IOException("Incomplete bundle");
                }finally{timeout.cancel(false);}
            }
            return ingestBundle(lease,bundle,original,directory);
        }catch(ApiException e){throw e;}catch(InterruptedException e){Thread.currentThread().interrupt();throw new ApiException(503,"PARSE_UNAVAILABLE","处理执行已中断，请重试");}catch(Exception e){throw new ApiException(503,"PARSE_UNAVAILABLE","处理产物不可用或未通过安全校验，请重试");}
        finally{timer.shutdownNow();if(directory!=null){try{try(var children=Files.list(directory)){for(var path:children.toList())Files.deleteIfExists(path);}Files.deleteIfExists(directory);}catch(IOException ignored){}}}
    }
    /** Package entry point for malicious bundle tests; only private validated assets survive. */
    DocumentResult ingestBundle(SourceJobs.Lease lease,Path bundle,Map<String,Object> original,Path temporary)throws Exception{
        try(var zip=new ZipFile(bundle.toFile(),StandardCharsets.UTF_8)){
            var entries=new LinkedHashMap<String,ZipEntry>();var all=zip.entries();long total=0;
            while(all.hasMoreElements()){
                var entry=all.nextElement();String name=entry.getName();
                if(entry.isDirectory()||!SourceArchive.safe(name)||entries.size()>=limits.maxArchiveEntries()||entries.putIfAbsent(name,entry)!=null)throw new IOException("Bundle paths");
                var crc=new CRC32();long count;try(var input=zip.getInputStream(entry)){count=copy(input,OutputStream.nullOutputStream(),limits.maxExpandedBytes()-total,crc);}
                total+=count;if(count!=entry.getSize()||crc.getValue()!=entry.getCrc()||count>Math.max(1,entry.getCompressedSize())*limits.maxCompressionRatio())throw new IOException("Bundle checksum or expansion");
            }
            var manifest=entries.get("result.json");if(manifest==null||manifest.getSize()>32L*1024*1024)throw new IOException("Manifest limit");
            JsonNode data;try(var input=zip.getInputStream(manifest)){data=json.readTree(input);}
            if(!data.path("inputSha256").asText().equals(original.get("sha256"))||data.path("inputBytes").asLong(-1)!=((Number)original.get("byte_size")).longValue()||!data.path("parserName").asText().equals("deterministic-v2"))throw new IOException("Input mismatch");
            var reading=data.path("reading");if(!Set.of("SUCCEEDED","PARTIAL_SUCCESS","FAILED").contains(reading.path("overallOutcome").asText()))throw new IOException("Outcome");SourceReadingData.validate(reading);if("FAILED".equals(reading.path("overallOutcome").asText()))throw new IOException("No usable unit");
            var segments=new ArrayList<SourceJobs.ParsedSegment>();for(var segment:data.path("segments"))segments.add(new SourceJobs.ParsedSegment(segment.path("segmentNo").asInt(),nullable(segment,"pageLabel"),nullable(segment,"headingPath"),segment.path("body").asText()));
            if(segments.size()>10000)throw new IOException("Segments limit");
            Set<String> claimed=new HashSet<>(Set.of("result.json"));Map<String,JsonNode> refs=new HashMap<>();
            if(!reading.path("assets").isArray())throw new IOException("Assets shape");
            for(var asset:reading.path("assets")){
                String path=asset.path("bundlePath").asText(),ref=asset.path("ref").asText();
                if(!ref.matches("[A-Za-z0-9_-]{1,80}")||refs.putIfAbsent(ref,asset)!=null||!path.startsWith("assets/")||!entries.containsKey(path)||!claimed.add(path))throw new IOException("Asset reference");
            }
            if(!claimed.equals(entries.keySet()))throw new IOException("Undeclared bundle member");
            for(var unit:reading.path("units")){checkRefs(unit.path("nodes"),refs);if(unit.hasNonNull("previewRef")){var preview=refs.get(unit.path("previewRef").asText());if(preview==null||!"PAGE_PREVIEW".equals(preview.path("kind").asText())||preview.path("unitNo").asInt()!=unit.path("unitNo").asInt())throw new IOException("Page preview reference");}}
            var assets=new ArrayList<SourceReadingData.Asset>();long pixels=0;
            for(var asset:reading.path("assets")){
                if(!jobs.validLease(lease))throw ApiException.conflict("处理租约已失效");
                String ref=asset.path("ref").asText(),kind=asset.path("kind").asText(),mime=asset.path("mimeType").asText();var entry=entries.get(asset.path("bundlePath").asText());
                Path staged=Files.createTempFile(temporary,"asset-",".bin");var digest=MessageDigest.getInstance("SHA-256");long size;
                try(var input=new java.security.DigestInputStream(zip.getInputStream(entry),digest);var output=Files.newOutputStream(staged)){size=copy(input,output,limits.maxExpandedBytes(),null);}
                String hash=HexFormat.of().formatHex(digest.digest());int width=0,height=0;
                if(Set.of("IMAGE","PAGE_PREVIEW").contains(kind)){
                    if(!Set.of("image/png","image/jpeg").contains(mime))throw new IOException("Active or unknown media");
                    try(var image=ImageIO.createImageInputStream(staged.toFile())){
                        var readers=ImageIO.getImageReaders(image);if(!readers.hasNext())throw new IOException("Invalid image");var reader=readers.next();
                        try{reader.setInput(image,true,true);String actual=reader.getFormatName().toLowerCase(Locale.ROOT);if(!actual.equals(mime.equals("image/png")?"png":"jpeg"))throw new IOException("Image MIME mismatch");width=reader.getWidth(0);height=reader.getHeight(0);long count=(long)width*height;if(width<=0||height<=0||width>20000||height>20000||count>20_000_000||(pixels+=count)>80_000_000)throw new IOException("Image pixel budget");if(reader.read(0)==null)throw new IOException("Image decode");}finally{reader.dispose();}
                    }
                }else if("DERIVED_DOCX".equals(kind)){
                    if(!mime.equals("application/vnd.openxmlformats-officedocument.wordprocessingml.document")||!SourceFiles.validate("derived.docx",staged,limits).equals(mime))throw new IOException("Derived DOCX");
                }else if("DERIVED_PDF".equals(kind)){
                    if(!mime.equals("application/pdf")||!SourceFiles.validate("derived.pdf",staged,limits).equals(mime))throw new IOException("Derived PDF");
                }else throw new IOException("Asset kind");
                Integer unit=asset.hasNonNull("unitNo")?asset.path("unitNo").asInt():null,page=asset.hasNonNull("pageNumber")?asset.path("pageNumber").asInt():null;
                if(unit!=null&&(unit<1||unit>reading.path("units").size())||page!=null&&(page<1||page>500))throw new IOException("Asset location");
                SourceObjectStore.Stored stored;try(var input=Files.newInputStream(staged)){stored=store.put("reading/"+lease.id()+"/"+lease.runToken()+"/"+hash,input,size,mime);}
                assets.add(new SourceReadingData.Asset(ref,kind,nullable(asset,"originalFormat"),unit,page,mime,size,hash,width,height,stored));Files.deleteIfExists(staged);
            }
            if(!jobs.validLease(lease))throw ApiException.conflict("处理租约已失效");
            return new DocumentResult(List.copyOf(segments),reading,List.copyOf(assets));
        }
    }
    private static String nullable(JsonNode node,String key){return node.hasNonNull(key)?node.path(key).asText():null;}
    private static void checkRefs(JsonNode nodes,Map<String,JsonNode> refs)throws IOException{
        for(var node:nodes){if(node.has("assetRef")){var asset=refs.get(node.path("assetRef").asText());if(asset==null||!Set.of("IMAGE","PAGE_PREVIEW").contains(asset.path("kind").asText()))throw new IOException("Unsafe image reference");}checkRefs(node.path("children"),refs);}
    }
    private static long copy(InputStream input,OutputStream output,long max,CRC32 crc)throws IOException{
        byte[] buffer=new byte[16384];long size=0;int n;while((n=input.read(buffer))!=-1){if(Thread.currentThread().isInterrupted())throw new java.io.InterruptedIOException("Task cancelled");size+=n;if(size>max)throw new IOException("Processing bytes limit");if(crc!=null)crc.update(buffer,0,n);output.write(buffer,0,n);}return size;
    }
}
