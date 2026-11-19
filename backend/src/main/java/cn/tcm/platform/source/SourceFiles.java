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
    public static final int MAX_BYTES=10*1024*1024;
    private final SourceAccess access;
    private final Path root;
    public SourceFiles(SourceAccess access,@Value("${tcm.storage.source-directory:${java.io.tmpdir}/tcm-platform-sources}")String root){this.access=access;this.root=Path.of(root).toAbsolutePath().normalize();}
    public record Download(String originalName,String mimeType,byte[] bytes){}
    @Transactional public Map<String,Object> upload(long user,MultipartFile file){
        access.require(user,"contribution:submit");
        if(file.isEmpty()||file.getSize()>MAX_BYTES)throw ApiException.invalid("文件须非空且最大10MB");
        String name=Objects.toString(file.getOriginalFilename(),"");
        // Name is metadata only. Never resolve any part of it as a filesystem path.
        name=name.replace('\\','/'); name=name.substring(name.lastIndexOf('/')+1).replaceAll("[\\p{Cntrl}]","").strip();
        if(name.isEmpty()||name.length()>255)throw ApiException.invalid("文件名长度不符合要求");
        try {
            byte[] bytes=file.getBytes();String mime=validate(name,bytes);String hash=hash(bytes);Path target=owned(hash);
            Files.createDirectories(root);
            Path staged=Files.createTempFile(root,"upload-",".tmp");
            try{
                Files.write(staged,bytes,StandardOpenOption.TRUNCATE_EXISTING);
                try{Files.move(staged,target);}catch(FileAlreadyExistsException ignored){if(!hash(Files.readAllBytes(target)).equals(hash))throw new IOException("Stored hash mismatch");}
            }finally{Files.deleteIfExists(staged);}
            // SHA256 is global, grants record every actual successful uploader.
            access.db.update("INSERT INTO file_objects(sha256,storage_key,original_name,mime_type,byte_size,uploaded_by) VALUES(?,?,?,?,?,?) ON DUPLICATE KEY UPDATE id=id",hash,hash,name,mime,bytes.length,user);
            var stored=access.db.queryForMap("SELECT * FROM file_objects WHERE sha256=?",hash);long id=((Number)stored.get("id")).longValue();
            boolean deduplicated=!stored.get("uploaded_by").toString().equals(Long.toString(user))||access.db.queryForObject("SELECT COUNT(*) FROM file_upload_grants WHERE file_id=? AND user_id=?",Long.class,id,user)>0;
            access.db.update("INSERT INTO file_upload_grants(file_id,user_id,original_name) VALUES(?,?,?) ON DUPLICATE KEY UPDATE file_id=file_id",id,user,name);
            String ownName=access.db.queryForObject("SELECT original_name FROM file_upload_grants WHERE file_id=? AND user_id=?",String.class,id,user);
            return SourceAccess.map("id",Long.toString(id),"originalName",ownName,"mimeType",stored.get("mime_type"),"byteSize",stored.get("byte_size"),"sha256",hash,"deduplicated",deduplicated);
        }catch(IOException e){throw new ApiException(503,"STORAGE_UNAVAILABLE","文件暂时无法保存或读取");}
    }
    public Download read(long user,long id){
        if(!access.uploaded(user,id)){
            String scope=access.permission(user,"knowledge:review")?"1=1":"d.submitted_by=? OR (d.review_state='APPROVED' AND d.availability='ACTIVE')";
            var args=new ArrayList<Object>();args.add(id);if(!access.permission(user,"knowledge:review"))args.add(user);
            if(access.db.queryForObject("SELECT COUNT(*) FROM source_documents d WHERE d.file_id=? AND ("+scope+")",Long.class,args.toArray())==0)throw ApiException.missing();
        }
        var rows=access.db.queryForList("SELECT * FROM file_objects WHERE id=?",id);if(rows.isEmpty())throw ApiException.missing();var f=rows.get(0);
        var names=access.db.queryForList("SELECT original_name FROM file_upload_grants WHERE file_id=? AND user_id=?",String.class,id,user);
        if(names.isEmpty()){
            String scope=access.permission(user,"knowledge:review")?"1=1":"d.submitted_by=? OR (d.review_state='APPROVED' AND d.availability='ACTIVE')";
            var args=new ArrayList<Object>();args.add(id);if(!access.permission(user,"knowledge:review"))args.add(user);
            names=access.db.queryForList("SELECT COALESCE(g.original_name,'source-document') FROM source_documents d LEFT JOIN file_upload_grants g ON g.file_id=d.file_id AND g.user_id=d.submitted_by WHERE d.file_id=? AND ("+scope+") ORDER BY d.id LIMIT 1",String.class,args.toArray());
        }
        return new Download(names.isEmpty()?"source-document":names.get(0),(String)f.get("mime_type"),bytes(f));
    }
    byte[] bytes(Map<String,Object> f){try{byte[] bytes=Files.readAllBytes(owned((String)f.get("storage_key")));if(bytes.length>MAX_BYTES||!hash(bytes).equals(f.get("sha256")))throw new IOException("Invalid stored bytes");return bytes;}catch(IOException e){throw new ApiException(503,"STORAGE_UNAVAILABLE","原文件暂时不可读取，请重新上传或联系管理员");}}
    private Path owned(String key){if(key==null||!key.matches("[a-f0-9]{64}"))throw ApiException.missing();Path result=root.resolve(key).normalize();if(!root.equals(result.getParent()))throw ApiException.missing();return result;}
    static String hash(byte[] bytes){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
    static String validate(String name,byte[] bytes){
        if(bytes.length==0||bytes.length>MAX_BYTES)throw ApiException.invalid("文件须非空且最大10MB");
        String lower=name.toLowerCase(Locale.ROOT);
        try {
            if(lower.endsWith(".txt")){
                String text=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
                if(text.startsWith("PK")||text.startsWith("%PDF-")||text.startsWith("MZ")||text.isBlank()||text.chars().anyMatch(c->c<32&&c!='\n'&&c!='\r'&&c!='\t'&&c!='\f'))throw new IOException("Invalid text");
                return "text/plain";
            }
            if(lower.endsWith(".docx")){
                var entries=new HashMap<String,byte[]>();long total=0;int count=0;
                try(var zip=new ZipInputStream(new ByteArrayInputStream(bytes))){ZipEntry entry;while((entry=zip.getNextEntry())!=null){if(++count>2000||entries.containsKey(entry.getName()))throw new IOException("Too many or duplicate entries");var out=new ByteArrayOutputStream();byte[] buffer=new byte[8192];int size;while((size=zip.read(buffer))!=-1){total+=size;if(total>32L*1024*1024)throw new IOException("Expanded limit");out.write(buffer,0,size);}if(entry.getCompressedSize()>0&&out.size()>Math.max(1,entry.getCompressedSize())*200L)throw new IOException("Compression ratio");entries.put(entry.getName(),out.toByteArray());}}
                var factory=DocumentBuilderFactory.newInstance();factory.setNamespaceAware(true);factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);factory.setFeature("http://xml.org/sax/features/external-general-entities",false);factory.setFeature("http://xml.org/sax/features/external-parameter-entities",false);factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD,"");factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA,"");
                byte[] type=entries.get("[Content_Types].xml"),document=entries.get("word/document.xml");if(type==null||document==null)throw new IOException("Missing document");
                var types=factory.newDocumentBuilder().parse(new ByteArrayInputStream(type));boolean standard=false;var overrides=types.getElementsByTagNameNS("http://schemas.openxmlformats.org/package/2006/content-types","Override");for(int i=0;i<overrides.getLength();i++){var a=(org.w3c.dom.Element)overrides.item(i);if(a.getAttribute("PartName").equals("/word/document.xml")&&a.getAttribute("ContentType").equals("application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"))standard=true;}
                var dom=factory.newDocumentBuilder().parse(new ByteArrayInputStream(document));if(!standard||!dom.getDocumentElement().getLocalName().equals("document")||!"http://schemas.openxmlformats.org/wordprocessingml/2006/main".equals(dom.getDocumentElement().getNamespaceURI()))throw new IOException("Invalid DOCX");
                return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
            }
            if(lower.endsWith(".pdf")){
                if(bytes.length<5||!new String(bytes,0,5,StandardCharsets.US_ASCII).equals("%PDF-"))throw new IOException("Invalid PDF signature");
                try(var pdf=Loader.loadPDF(bytes)){
                    if(pdf.isEncrypted()||pdf.getNumberOfPages()>500)throw new IOException("Unsupported PDF");
                    long expanded=0;byte[] buffer=new byte[8192];
                    for(var page:pdf.getPages())for(var streams=page.getContentStreams();streams.hasNext();){
                        try(var input=streams.next().createInputStream()){int n;while((n=input.read(buffer))!=-1){expanded+=n;if(expanded>32L*1024*1024)throw new IOException("PDF content limit");}}
                    }
                    var bounded=new Writer(){int count=0;boolean nonblank=false;public void write(char[] c,int off,int len)throws IOException{count+=len;if(count>2_000_000)throw new IOException("PDF text limit");for(int i=off;i<off+len;i++)if(!Character.isWhitespace(c[i]))nonblank=true;}public void flush(){}public void close(){}};
                    new PDFTextStripper().writeText(pdf,bounded);if(!bounded.nonblank)throw new IOException("No text layer");
                }
                return "application/pdf";
            }
            throw new IOException("Unsupported extension");
        }catch(Exception e){throw ApiException.invalid("仅支持完整的UTF-8 TXT、DOCX和有文本层PDF，文件格式或内容无效");}
    }
}
