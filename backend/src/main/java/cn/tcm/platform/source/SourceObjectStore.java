package cn.tcm.platform.source;

import cn.tcm.platform.common.ApiException;
import io.minio.*;
import java.io.*;
import java.nio.file.*;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Private object storage. Database backend selection never falls back on failure. */
@Component
public class SourceObjectStore {
    private static final int LIMIT=200*1024*1024;
    private final Path legacyRoot;
    private final String bucket;
    private final MinioClient minio;
    public record Stored(String backend,String bucket,String key){}
    public SourceObjectStore(@Value("${tcm.storage.source-directory:${java.io.tmpdir}/tcm-platform-sources}")String root,
            @Value("${tcm.storage.minio.endpoint}")String endpoint,@Value("${tcm.storage.minio.bucket}")String bucket,
            @Value("${tcm.storage.minio.access-key}")String accessKey,@Value("${tcm.storage.minio.secret-key}")String secretKey){
        this.legacyRoot=Path.of(root).toAbsolutePath().normalize();this.bucket=bucket;
        // Construct lazily to allow legacy-only reads when credentials are not configured.
        this.minio=accessKey.isBlank()||secretKey.isBlank()?null:MinioClient.builder().endpoint(endpoint)
                .credentials(accessKey,secretKey).httpClient(new okhttp3.OkHttpClient.Builder()
                .connectTimeout(5,TimeUnit.SECONDS).readTimeout(30,TimeUnit.SECONDS).writeTimeout(30,TimeUnit.SECONDS).build()).build();
    }
    public Stored put(String key,byte[] body,String mimeType){return put(key,new ByteArrayInputStream(body),body.length,mimeType);}
    /** Does not close the caller-owned input. Streaming SDK uses bounded multipart buffers. */
    public Stored put(String key,InputStream body,long size,String mimeType){
        try {
            validKey(key);if(size<=0||size>LIMIT)throw new IOException("Object limit");
            if(minio==null)throw new IOException("Application credentials missing");
            minio.putObject(PutObjectArgs.builder().bucket(bucket).object(key).contentType(mimeType)
                    .stream(body,size,5L*1024*1024).build());
            return new Stored("MINIO",bucket,key);
        }catch(Exception e){throw unavailable();}
    }
    public byte[] read(String backend,String objectBucket,String key){
        try(var input=open(backend,objectBucket,key)){return bounded(input);}catch(IOException e){throw unavailable();}
    }
    /** Caller closes; selection is explicit and never falls back to LOCAL. */
    public InputStream open(String backend,String objectBucket,String key){
        try {
            InputStream result;
            if("LOCAL".equals(backend)){
                if(key==null||!key.matches("[a-f0-9]{64}"))throw new IOException("Legacy key");
                Path realRoot=legacyRoot.toRealPath();Path file=realRoot.resolve(key).toRealPath();
                if(!file.getParent().equals(realRoot)||!Files.isRegularFile(file)||Files.size(file)>LIMIT)throw new IOException("Legacy path");
                result=Files.newInputStream(file);
            }else{
                if(!"MINIO".equals(backend)||!bucket.equals(objectBucket)||minio==null)throw new IOException("Storage backend");
                validKey(key);result=minio.getObject(GetObjectArgs.builder().bucket(bucket).object(key).build());
            }
            return new FilterInputStream(result){long count;
                public int read()throws IOException{int b=super.read();if(b!=-1&&++count>LIMIT)throw new IOException("Object limit");return b;}
                public int read(byte[] b,int off,int len)throws IOException{int n=in.read(b,off,len);if(n>0&&(count+=n)>LIMIT)throw new IOException("Object limit");return n;}
            };
        }catch(Exception e){throw unavailable();}
    }
    private static void validKey(String key)throws IOException{
        if(key==null||key.length()>512||!key.matches("[A-Za-z0-9][A-Za-z0-9/_-]*([.][A-Za-z0-9]+)?")||key.contains("..")||key.endsWith("/"))throw new IOException("Object key");
    }
    private static byte[] bounded(InputStream input)throws IOException{
        byte[] bytes=input.readNBytes(LIMIT+1);if(bytes.length>LIMIT)throw new IOException("Object limit");return bytes;
    }
    private static ApiException unavailable(){return new ApiException(503,"STORAGE_UNAVAILABLE","文件暂时无法保存或读取");}
}
