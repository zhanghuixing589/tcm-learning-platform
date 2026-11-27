package cn.tcm.platform.source;

import cn.tcm.platform.common.ApiException;
import io.minio.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.net.*;
import java.io.*;
import static org.junit.jupiter.api.Assertions.*;

class SourceObjectStoreTest {
    @TempDir Path root;
    private SourceObjectStore store(){return new SourceObjectStore(root.toString(),"http://127.0.0.1:19000","tcm-learning-sources",System.getenv().getOrDefault("TCM_MINIO_APP_ACCESS_KEY",""),System.getenv().getOrDefault("TCM_MINIO_APP_SECRET_KEY",""));}
    @Test void localReadsOnlyKnownShaKeysAndNeverFallsBack()throws Exception{
        byte[] body="legacy原文".getBytes(StandardCharsets.UTF_8);String hash=SourceFiles.hash(body);Files.write(root.resolve(hash),body);
        assertArrayEquals(body,store().read("LOCAL",null,hash));
        assertThrows(ApiException.class,()->store().read("LOCAL",null,"../"+hash));
        assertThrows(ApiException.class,()->store().read("MINIO","wrong-bucket",hash));
        assertThrows(ApiException.class,()->store().read("MINIO","tcm-learning-sources",hash));
    }
    @Test void realPrivateMinioRoundTripAndAnonymousForbidden()throws Exception{
        assertNotNull(System.getenv("TCM_MINIO_APP_SECRET_KEY"),"Run through scripts/test-backend.ps1 with project MinIO provisioned");
        byte[] body="真实对象存储往返".getBytes(StandardCharsets.UTF_8);String key="tests/"+java.util.UUID.randomUUID();var store=store();
        try{
            var saved=store.put(key,body,"text/plain");assertEquals("MINIO",saved.backend());assertArrayEquals(body,store.read(saved.backend(),saved.bucket(),saved.key()));
            var connection=(HttpURLConnection)new URL("http://127.0.0.1:19000/tcm-learning-sources/"+key).openConnection(Proxy.NO_PROXY);connection.setConnectTimeout(5000);connection.setReadTimeout(5000);
            try{assertEquals(403,connection.getResponseCode());}finally{connection.disconnect();}
        }finally{
            MinioClient.builder().endpoint("http://127.0.0.1:19000").credentials(System.getenv("TCM_MINIO_APP_ACCESS_KEY"),System.getenv("TCM_MINIO_APP_SECRET_KEY")).build().removeObject(RemoveObjectArgs.builder().bucket("tcm-learning-sources").object(key).build());
        }
    }
    @Test void realMinioStreamsLargerOriginalWithoutMaterializingBody()throws Exception{
        long size=11L*1024*1024+17;String key="tests/"+java.util.UUID.randomUUID();var store=store();
        var client=MinioClient.builder().endpoint("http://127.0.0.1:19000").credentials(System.getenv("TCM_MINIO_APP_ACCESS_KEY"),System.getenv("TCM_MINIO_APP_SECRET_KEY")).build();
        try{
            InputStream generated=new InputStream(){long position;public int read(){return position<size?(int)(position++%251):-1;}public int read(byte[] bytes,int off,int len){if(position==size)return -1;int n=(int)Math.min(len,size-position);for(int i=0;i<n;i++)bytes[off+i]=(byte)(position++%251);return n;}};
            var saved=store.put(key,generated,size,"application/octet-stream");long position=0;byte[] buffer=new byte[16384];
            try(var input=store.open(saved.backend(),saved.bucket(),saved.key())){int n;while((n=input.read(buffer))!=-1)for(int i=0;i<n;i++)assertEquals((byte)(position++%251),buffer[i]);}
            assertEquals(size,position);
        }finally{client.removeObject(RemoveObjectArgs.builder().bucket("tcm-learning-sources").object(key).build());}
    }
}
