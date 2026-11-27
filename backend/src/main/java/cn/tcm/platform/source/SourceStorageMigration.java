package cn.tcm.platform.source;

import java.nio.file.*;
import java.sql.*;
import java.util.*;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Explicit operator CLI; no Spring context, jobs, web server, or automatic migration. */
public final class SourceStorageMigration {
    public static void main(String[] args)throws Exception{
        boolean apply=Arrays.asList(args).contains("--apply");
        String backup=null;for(int i=0;i<args.length-1;i++)if(args[i].equals("--database-backup"))backup=args[i+1];
        if(apply&&(backup==null||!Files.isRegularFile(Path.of(backup))||Files.size(Path.of(backup))==0))throw new IllegalArgumentException("Apply requires a nonempty database backup via --database-backup");
        var env=System.getenv();String url=required(env,"DB_URL");
        var store=new SourceObjectStore(required(env,"SOURCE_STORAGE_DIR"),env.getOrDefault("TCM_MINIO_ENDPOINT","http://127.0.0.1:19000"),env.getOrDefault("TCM_MINIO_BUCKET","tcm-learning-sources"),required(env,"TCM_MINIO_APP_ACCESS_KEY"),required(env,"TCM_MINIO_APP_SECRET_KEY"));
        try(var db=DriverManager.getConnection(url,required(env,"DB_USERNAME"),required(env,"DB_PASSWORD"))){
            // Fail before any object or SQL mutation when the migration is not installed.
            try(var s=db.createStatement();var r=s.executeQuery("SELECT storage_backend,storage_bucket FROM file_objects LIMIT 0")){}
            var files=rows(db,"SELECT id,sha256,storage_backend,storage_bucket,storage_key,byte_size,original_name,uploaded_by,mime_type FROM file_objects ORDER BY id");
            var grants=rows(db,"SELECT * FROM file_upload_grants ORDER BY file_id,user_id");
            long pending=files.stream().filter(f->"LOCAL".equals(f.get("storage_backend"))).count();
            System.out.println("Plan: "+pending+" LOCAL objects; file IDs and grants preserved; original files retained.");
            if(!apply)return;
            Path dir=Path.of("runtime","storage-migration-"+java.time.Instant.now().toEpochMilli()).toAbsolutePath();Files.createDirectories(dir.resolve("local-backups"));
            writeManifest(dir.resolve("manifest-before.json"),Map.of("files",files,"grants",grants,"databaseBackup",Path.of(backup).toAbsolutePath().toString()));
            // Preflight all legacy rows before switching any row.
            for(var f:files)if("LOCAL".equals(f.get("storage_backend"))){
                try(var input=store.open("LOCAL",null,(String)f.get("storage_key"));var output=Files.newOutputStream(dir.resolve("local-backups").resolve(f.get("id")+"-"+f.get("sha256")),StandardOpenOption.CREATE_NEW)){verify(f,input,output);}
            }
            for(var f:files){
                if(!"LOCAL".equals(f.get("storage_backend")))continue;
                SourceObjectStore.Stored object;
                try(var input=Files.newInputStream(dir.resolve("local-backups").resolve(f.get("id")+"-"+f.get("sha256")))){object=store.put("originals/"+f.get("sha256"),input,((Number)f.get("byte_size")).longValue(),(String)f.get("mime_type"));}
                try(var input=store.open(object.backend(),object.bucket(),object.key())){verify(f,input,java.io.OutputStream.nullOutputStream());}
                db.setAutoCommit(false);
                try(var update=db.prepareStatement("UPDATE file_objects SET storage_backend=?,storage_bucket=?,storage_key=? WHERE id=? AND storage_backend='LOCAL' AND storage_key=? AND sha256=? AND byte_size=?")){
                    update.setString(1,object.backend());update.setString(2,object.bucket());update.setString(3,object.key());update.setObject(4,f.get("id"));update.setObject(5,f.get("storage_key"));update.setObject(6,f.get("sha256"));update.setObject(7,f.get("byte_size"));if(update.executeUpdate()!=1)throw new SQLException("Concurrent storage change");db.commit();
                }catch(Exception failure){db.rollback();throw failure;}finally{db.setAutoCommit(true);}
                System.out.println("Verified and switched fileId="+f.get("id"));
            }
            // Verify every original in the final manifest, including already migrated rows.
            var finalFiles=rows(db,"SELECT id,sha256,storage_backend,storage_bucket,storage_key,byte_size,original_name,uploaded_by,mime_type FROM file_objects ORDER BY id");
            for(var f:finalFiles)try(var input=store.open((String)f.get("storage_backend"),(String)f.get("storage_bucket"),(String)f.get("storage_key"))){verify(f,input,java.io.OutputStream.nullOutputStream());}
            var afterGrants=rows(db,"SELECT * FROM file_upload_grants ORDER BY file_id,user_id");if(!grants.equals(afterGrants))throw new IllegalStateException("Upload grants changed during migration; review snapshots");
            writeManifest(dir.resolve("manifest-after.json"),Map.of("files",rows(db,"SELECT id,sha256,storage_backend,storage_bucket,storage_key,byte_size,original_name,uploaded_by,mime_type FROM file_objects ORDER BY id"),"grants",afterGrants));
            System.out.println("Migration verified. Backups retained at "+dir);
        }
    }
    static void writeManifest(Path target,Object value)throws java.io.IOException{new ObjectMapper().findAndRegisterModules().disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS).writerWithDefaultPrettyPrinter().writeValue(target.toFile(),value);}
    private static void verify(Map<String,Object> row,java.io.InputStream input,java.io.OutputStream backup)throws Exception{
        var digest=java.security.MessageDigest.getInstance("SHA-256");byte[] buffer=new byte[16384];long total=0;int n;
        while((n=input.read(buffer))!=-1){total+=n;if(total>SourceLimits.DEFAULTS.maxOriginalBytes())throw new IllegalStateException("Original limit");digest.update(buffer,0,n);backup.write(buffer,0,n);}
        if(((Number)row.get("byte_size")).longValue()!=total||!row.get("sha256").equals(HexFormat.of().formatHex(digest.digest())))throw new IllegalStateException("Byte verification failed for fileId="+row.get("id"));
    }
    private static String required(Map<String,String> env,String key){String value=env.get(key);if(value==null||value.isBlank())throw new IllegalArgumentException("Missing environment variable "+key);return value;}
    private static List<Map<String,Object>> rows(Connection db,String query)throws SQLException{
        var rows=new ArrayList<Map<String,Object>>();try(var s=db.createStatement();var r=s.executeQuery(query)){var m=r.getMetaData();while(r.next()){var row=new LinkedHashMap<String,Object>();for(int i=1;i<=m.getColumnCount();i++)row.put(m.getColumnLabel(i),r.getObject(i));rows.add(row);}}return rows;
    }
}
