package cn.tcm.platform.source;

import cn.tcm.platform.common.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import java.io.*;
import java.nio.*;
import java.nio.charset.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.*;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;

@Service
public class SourceFiles {
    public static final int MAX_BYTES=50*1024*1024;
    private final SourceAccess access;
    private final SourceObjectStore store;
    private final SourceLimits limits;
    private final Path tempRoot;
    public SourceFiles(SourceAccess access,SourceObjectStore store,SourceLimits limits,@Value("${tcm.storage.source-temp-directory:${java.io.tmpdir}/tcm-source-staging}")String tempRoot){this.access=access;this.store=store;this.limits=limits;this.tempRoot=Path.of(tempRoot).toAbsolutePath().normalize();}
    public record Download(String originalName,String mimeType,byte[] bytes){}
    @Transactional public Map<String,Object> upload(long user,MultipartFile file){
        access.require(user,"contribution:submit");
        if(file.isEmpty()||file.getSize()>limits.maxOriginalBytes())throw ApiException.invalid("文件须非空且不超过配置的原件大小上限");
        String name=Objects.toString(file.getOriginalFilename(),"");
        // Name is metadata only. Never resolve any part of it as a filesystem path.
        name=name.replace('\\','/'); name=name.substring(name.lastIndexOf('/')+1).replaceAll("[\\p{Cntrl}]","").strip();
        if(name.isEmpty()||name.length()>255)throw ApiException.invalid("文件名长度不符合要求");
        try (var staged=stage(file.getInputStream())) {
            if(staged.size()!=file.getSize())throw ApiException.invalid("上传文件大小不一致");
            String mime=validate(name,staged.path(),limits);String hash=staged.sha256();
            var existing=access.db.queryForList("SELECT id FROM file_objects WHERE sha256=?",hash);
            if(existing.isEmpty()) {
                SourceObjectStore.Stored object;
                try(var input=Files.newInputStream(staged.path())){object=store.put("originals/"+hash,input,staged.size(),mime);}
                // SHA256 is global, grants record every actual successful uploader.
                access.db.update("INSERT INTO file_objects(sha256,storage_backend,storage_bucket,storage_key,original_name,mime_type,byte_size,uploaded_by) VALUES(?,?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE id=id",hash,object.backend(),object.bucket(),object.key(),name,mime,staged.size(),user);
            }
            var stored=access.db.queryForMap("SELECT * FROM file_objects WHERE sha256=?",hash);long id=((Number)stored.get("id")).longValue();
            boolean deduplicated=!stored.get("uploaded_by").toString().equals(Long.toString(user))||access.db.queryForObject("SELECT COUNT(*) FROM file_upload_grants WHERE file_id=? AND user_id=?",Long.class,id,user)>0;
            access.db.update("INSERT INTO file_upload_grants(file_id,user_id,original_name) VALUES(?,?,?) ON DUPLICATE KEY UPDATE file_id=file_id",id,user,name);
            String ownName=access.db.queryForObject("SELECT original_name FROM file_upload_grants WHERE file_id=? AND user_id=?",String.class,id,user);
            return SourceAccess.map("id",Long.toString(id),"originalName",ownName,"mimeType",stored.get("mime_type"),"byteSize",stored.get("byte_size"),"sha256",hash,"deduplicated",deduplicated);
        }catch(IOException e){throw new ApiException(503,"STORAGE_UNAVAILABLE","文件暂时无法保存或读取");}
    }
    public Download read(long user,long id){
        if(!access.uploaded(user,id)){
            String scope=access.permission(user,"knowledge:review")?"1=1":"d.review_state='APPROVED' AND d.availability='ACTIVE' AND d.public_download_allowed=1";
            var args=new ArrayList<Object>();args.add(id);
            if(access.db.queryForObject("SELECT COUNT(*) FROM source_documents d WHERE d.file_id=? AND ("+scope+")",Long.class,args.toArray())==0)throw ApiException.missing();
        }
        var rows=access.db.queryForList("SELECT * FROM file_objects WHERE id=?",id);if(rows.isEmpty())throw ApiException.missing();var f=rows.get(0);
        var names=access.db.queryForList("SELECT original_name FROM file_upload_grants WHERE file_id=? AND user_id=?",String.class,id,user);
        if(names.isEmpty()){
            String scope=access.permission(user,"knowledge:review")?"1=1":"d.review_state='APPROVED' AND d.availability='ACTIVE' AND d.public_download_allowed=1";
            var args=new ArrayList<Object>();args.add(id);
            names=access.db.queryForList("SELECT COALESCE(g.original_name,'source-document') FROM source_documents d LEFT JOIN file_upload_grants g ON g.file_id=d.file_id AND g.user_id=d.submitted_by WHERE d.file_id=? AND ("+scope+") ORDER BY d.id LIMIT 1",String.class,args.toArray());
        }
        return new Download(names.isEmpty()?"source-document":names.get(0),(String)f.get("mime_type"),bytes(f));
    }
    byte[] bytes(Map<String,Object> row){
        var original=openOriginal(row);
        try(var input=original.stream()){
            byte[] bytes=input.readNBytes((int)limits.maxOriginalBytes()+1);
            if(bytes.length>limits.maxOriginalBytes())throw new IOException("Original limit");return bytes;
        }catch(IOException e){throw new ApiException(503,"STORAGE_UNAVAILABLE","原文件暂时不可读取");}
    }
    public record OriginalInput(String originalName,String mimeType,long byteSize,String sha256,InputStream stream){}
    /** Internal trusted-row entry point; request authentication and lease checks belong to the caller. */
    public OriginalInput openOriginal(Map<String,Object> row){
        try {
            var staged=stage(store.open(Objects.toString(row.get("storage_backend"),"LOCAL"),(String)row.get("storage_bucket"),(String)row.get("storage_key")));
            if(staged.size()!=((Number)row.get("byte_size")).longValue()||!staged.sha256().equals(row.get("sha256"))){staged.close();throw new IOException("Original integrity");}
            InputStream input;
            try{input=Files.newInputStream(staged.path());}catch(IOException failure){staged.close();throw failure;}
            var stream=new FilterInputStream(input){public void close()throws IOException{try{super.close();}finally{staged.close();}}};
            return new OriginalInput((String)row.get("original_name"),(String)row.get("mime_type"),staged.size(),staged.sha256(),stream);
        }catch(IOException e){throw new ApiException(503,"STORAGE_UNAVAILABLE","原文件暂时不可读取");}
    }
    public Map<String,Object> metadata(long actor,long fileId){
        if(!access.uploaded(actor,fileId))throw ApiException.missing();
        var rows=access.db.queryForList("SELECT * FROM file_objects WHERE id=?",fileId);if(rows.isEmpty())throw ApiException.missing();
        var names=access.db.queryForList("SELECT original_name FROM file_upload_grants WHERE file_id=? AND user_id=?",String.class,fileId,actor);
        var original=openOriginal(rows.get(0));
        try(var staged=stage(original.stream())){
            return SourceFileMetadata.read(staged.path(),original.mimeType(),names.isEmpty()?original.originalName():names.get(0),limits);
        }catch(IOException e){throw new ApiException(503,"STORAGE_UNAVAILABLE","文件元数据暂时不可读取");}
    }
    private record Staged(Path path,long size,String sha256)implements AutoCloseable{public void close()throws IOException{Files.deleteIfExists(path);}}
    private Staged stage(InputStream source)throws IOException{
        Path temp=null;boolean complete=false;
        try(source){
            Files.createDirectories(tempRoot);temp=Files.createTempFile(tempRoot,"original-",".bin");
            var digest=MessageDigest.getInstance("SHA-256");byte[] buffer=new byte[16384];long size=0;int n;
            try(var output=Files.newOutputStream(temp)){
                while((n=source.read(buffer))!=-1){size+=n;if(size>limits.maxOriginalBytes())throw ApiException.invalid("原件大小超过配置上限");digest.update(buffer,0,n);output.write(buffer,0,n);}
            }
            if(size==0)throw ApiException.invalid("原件不能为空");complete=true;return new Staged(temp,size,HexFormat.of().formatHex(digest.digest()));
        }catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}finally{if(!complete&&temp!=null)Files.deleteIfExists(temp);}
    }
    public static String hash(byte[] bytes){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
    static String validate(String name,byte[] bytes){
        Path temp=null;try{temp=Files.createTempFile("source-validation-",".bin");Files.write(temp,bytes);return validate(name,temp,SourceLimits.DEFAULTS);}
        catch(IOException e){throw ApiException.invalid("文件格式或内容无效");}finally{if(temp!=null)try{Files.deleteIfExists(temp);}catch(IOException ignored){}}
    }
    static String validate(String name,Path file,SourceLimits limits){
        String lower=name.toLowerCase(Locale.ROOT);
        try {
            if(Files.size(file)==0||Files.size(file)>limits.maxOriginalBytes())throw new IOException("Original limit");
            if(lower.endsWith(".txt")){
                var decoder=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT);
                boolean nonblank=false;long chars=0;char[] buffer=new char[8192];
                try(var reader=new InputStreamReader(Files.newInputStream(file),decoder)){int n;while((n=reader.read(buffer))!=-1){chars+=n;if(chars>2_000_000)throw new IOException("Text limit");for(int i=0;i<n;i++){char c=buffer[i];if(c<32&&c!='\n'&&c!='\r'&&c!='\t'&&c!='\f')throw new IOException("Control character");if(!Character.isWhitespace(c))nonblank=true;}}}
                try(var input=Files.newInputStream(file)){String prefix=new String(input.readNBytes(8),StandardCharsets.US_ASCII);if(prefix.startsWith("PK")||prefix.startsWith("%PDF-")||prefix.startsWith("MZ"))throw new IOException("Text signature");}
                if(!nonblank)throw new IOException("Blank text");return "text/plain";
            }
            if(lower.endsWith(".docx")){
                try(var archive=new SourceArchive(file,limits)){
                    var factory=DocumentBuilderFactory.newInstance();factory.setNamespaceAware(true);factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);factory.setFeature("http://xml.org/sax/features/external-general-entities",false);factory.setFeature("http://xml.org/sax/features/external-parameter-entities",false);factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD,"");factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA,"");
                    byte[] type=archive.get("[Content_Types].xml"),document=archive.get("word/document.xml");if(type==null||document==null)throw new IOException("Missing document");
                    var types=factory.newDocumentBuilder().parse(new ByteArrayInputStream(type));boolean standard=false;var overrides=types.getElementsByTagNameNS("http://schemas.openxmlformats.org/package/2006/content-types","Override");for(int i=0;i<overrides.getLength();i++){var item=(org.w3c.dom.Element)overrides.item(i);if(item.getAttribute("PartName").equals("/word/document.xml")&&item.getAttribute("ContentType").equals("application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"))standard=true;}
                    var dom=factory.newDocumentBuilder().parse(new ByteArrayInputStream(document));if(!standard||!"document".equals(dom.getDocumentElement().getLocalName())||!"http://schemas.openxmlformats.org/wordprocessingml/2006/main".equals(dom.getDocumentElement().getNamespaceURI()))throw new IOException("Invalid DOCX");
                }
                return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
            }
            if(lower.endsWith(".doc")){
                try(var ole=new org.apache.poi.poifs.filesystem.POIFSFileSystem(file.toFile(),true)){
                    var root=ole.getRoot();if(!root.hasEntry("WordDocument")||root.hasEntry("EncryptedPackage")||root.hasEntry("EncryptionInfo"))throw new IOException("Not binary Word");
                    byte[] fib;try(var word=root.createDocumentInputStream("WordDocument")){fib=word.readNBytes(32);}
                    if(fib.length<32)throw new IOException("Missing Word FIB");var header=ByteBuffer.wrap(fib).order(ByteOrder.LITTLE_ENDIAN);int signature=Short.toUnsignedInt(header.getShort(0)),version=Short.toUnsignedInt(header.getShort(2)),flags=Short.toUnsignedInt(header.getShort(10));
                    if(signature!=0xA5EC||version<0xC1||(flags&0x8100)!=0||!root.hasEntry((flags&0x200)==0?"0Table":"1Table"))throw new IOException("Unsupported or encrypted binary Word");
                }
                return "application/msword";
            }
            if(lower.endsWith(".epub")){EpubFiles.validate(file,limits);return "application/epub+zip";}
            if(lower.endsWith(".pdf")){
                try(var input=Files.newInputStream(file)){if(!Arrays.equals(input.readNBytes(5),"%PDF-".getBytes(StandardCharsets.US_ASCII)))throw new IOException("PDF signature");}
                try(var pdf=Loader.loadPDF(file.toFile(),org.apache.pdfbox.io.IOUtils.createTempFileOnlyStreamCache())){
                    if(pdf.isEncrypted()||pdf.getNumberOfPages()<1||pdf.getNumberOfPages()>500)throw new IOException("Unsupported PDF");
                    long expanded=0;byte[] buffer=new byte[16384];
                    for(var page:pdf.getPages())for(var streams=page.getContentStreams();streams.hasNext();){try(var input=streams.next().createInputStream()){int n;while((n=input.read(buffer))!=-1){expanded+=n;if(expanded>limits.maxExpandedBytes())throw new IOException("PDF content limit");}}}
                }
                return "application/pdf";
            }
            throw new IOException("Extension");
        }catch(Exception e){throw ApiException.invalid("仅支持有效UTF-8 TXT、普通DOC、DOCX、未加密PDF和文本或固定版式EPUB，文件格式或内容无效");}
    }
}
