package cn.tcm.platform.source;

import cn.tcm.platform.common.ApiResponse;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.*;
import org.springframework.core.io.InputStreamResource;
import org.springframework.web.bind.annotation.*;
import java.security.Principal;
import java.nio.charset.StandardCharsets;

@RestController @RequestMapping("/api/v1")
public class SourceReaderController {
    private final SourceReader reader;private final SourceRights rights;private final SourceAccess access;private final SourceFiles files;private final SourceObjectStore store;
    public SourceReaderController(SourceReader r,SourceRights rights,SourceAccess a,SourceFiles f,SourceObjectStore s){reader=r;this.rights=rights;access=a;files=f;store=s;}
    private long actor(Principal p){return Long.parseLong(p.getName());}
    @GetMapping("/source-documents/{id}/reader") public ApiResponse<?> reader(Principal p,@PathVariable long id,@RequestParam(required=false)Long editionId){return ApiResponse.ok(reader.reader(actor(p),id,editionId));}
    @GetMapping("/source-documents/{id}/reader/units/{unit}") public ApiResponse<?> unit(Principal p,@PathVariable long id,@PathVariable long unit,@RequestParam(required=false)Long editionId,@RequestParam(required=false)String mode){return ApiResponse.ok(reader.unit(actor(p),id,unit,editionId,mode));}
    @GetMapping("/source-documents/{id}/reader/search") public ApiResponse<?> search(Principal p,@PathVariable long id,@RequestParam(required=false)Long editionId,@RequestParam String q,@RequestParam(defaultValue="1")int page,@RequestParam(defaultValue="20")int pageSize){return ApiResponse.ok(reader.search(actor(p),id,editionId,q,page,pageSize));}
    @GetMapping("/source-documents/{id}/reading-progress") public ApiResponse<?> progress(Principal p,@PathVariable long id,@RequestParam(required=false)Long editionId){return ApiResponse.ok(reader.progress(actor(p),id,editionId));}
    @PutMapping("/source-documents/{id}/reading-progress") public ApiResponse<?> progress(Principal p,@PathVariable long id,@RequestBody JsonNode input){return ApiResponse.ok(reader.saveProgress(actor(p),id,input));}
    @PostMapping("/source-documents/{id}/excerpts") public ApiResponse<?> excerpt(Principal p,@PathVariable long id,@RequestBody JsonNode input){return ApiResponse.ok(reader.excerpt(actor(p),id,input));}
    @GetMapping("/source-documents/{id}/excerpts") public ApiResponse<?> excerpts(Principal p,@PathVariable long id){access.document(actor(p),id,false);return ApiResponse.ok(reader.excerpts(actor(p),id));}
    @GetMapping("/me/source-excerpts") public ApiResponse<?> excerpts(Principal p){return ApiResponse.ok(reader.excerpts(actor(p),null));}
    @PostMapping("/source-documents/{id}/rights/restrict") public ApiResponse<?> restrict(Principal p,@PathVariable long id,@RequestBody SourceRights.Restriction input){return ApiResponse.ok(rights.restrict(actor(p),id,input));}
    @GetMapping("/source-documents/{id}/reader/assets/{asset}") public ResponseEntity<?> asset(Principal p,@PathVariable long id,@PathVariable long asset){var a=reader.asset(actor(p),id,asset);return ResponseEntity.ok().contentType(MediaType.parseMediaType(a.get("mime_type").toString())).contentLength(((Number)a.get("byte_size")).longValue()).header("X-Content-Type-Options","nosniff").header("Cache-Control","private, no-store").body(new InputStreamResource(store.open(a.get("storage_backend").toString(),a.get("storage_bucket").toString(),a.get("storage_key").toString())));}
    @GetMapping("/source-documents/{id}/file") public ResponseEntity<?> file(Principal p,@PathVariable long id,@RequestHeader(value="Range",required=false)String range)throws java.io.IOException{var d=rights.requireDelivery(actor(p),id);if(d.get("file_id")==null)throw cn.tcm.platform.common.ApiException.missing();var f=access.db.queryForMap("SELECT * FROM file_objects WHERE id=?",d.get("file_id"));var original=files.openOriginal(f);long size=original.byteSize(),start=0,end=size-1;
        if(range!=null){try{if(!range.matches("bytes=\\d*-\\d*"))throw new IllegalArgumentException();String[] parts=range.substring(6).split("-",-1);if(parts[0].isEmpty()){long count=Long.parseLong(parts[1]);if(count<=0)throw new IllegalArgumentException();start=Math.max(0,size-count);}else{start=Long.parseLong(parts[0]);if(!parts[1].isEmpty())end=Math.min(end,Long.parseLong(parts[1]));}if(start>end||start>=size)throw new IllegalArgumentException();}catch(Exception e){original.stream().close();return ResponseEntity.status(416).header("Content-Range","bytes */"+size).build();}}
        final long length=end-start+1;original.stream().skipNBytes(start);var bounded=new java.io.FilterInputStream(original.stream()){long left=length;public int read()throws java.io.IOException{if(left<=0)return -1;int value=super.read();if(value>=0)left--;return value;}public int read(byte[] b,int off,int len)throws java.io.IOException{if(left<=0)return -1;int n=in.read(b,off,(int)Math.min(len,left));if(n>0)left-=n;return n;}};
        var response=ResponseEntity.status(range==null?200:206).contentType(MediaType.parseMediaType(original.mimeType())).contentLength(length).header("Accept-Ranges","bytes").header("Cache-Control","private, no-store").header("X-Content-Type-Options","nosniff").header("Content-Disposition",ContentDisposition.attachment().filename(original.originalName(),StandardCharsets.UTF_8).build().toString());if(range!=null)response.header("Content-Range","bytes "+start+"-"+end+"/"+size);return response.body(new InputStreamResource(bounded));
    }
}
