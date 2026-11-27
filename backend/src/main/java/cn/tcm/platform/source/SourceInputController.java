package cn.tcm.platform.source;

import cn.tcm.platform.common.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Internal-only original delivery: both application identity and live job token are mandatory. */
@RestController
public class SourceInputController {
    private final SourceAccess access;private final SourceFiles files;private final SourceJobs jobs;private final byte[] internalToken;
    public SourceInputController(SourceAccess access,SourceFiles files,SourceJobs jobs,@Value("${tcm.ai.internal-token}")String token){this.access=access;this.files=files;this.jobs=jobs;this.internalToken=token.getBytes(StandardCharsets.UTF_8);}
    @GetMapping("/internal/v1/source-inputs/{id}")
    public ResponseEntity<InputStreamResource> input(@PathVariable long id,
            @RequestHeader(value="X-Internal-Token",required=false)String application,
            @RequestHeader(value="X-Job-Run-Token",required=false)String runToken){
        if(internalToken.length==0||application==null||!MessageDigest.isEqual(internalToken,application.getBytes(StandardCharsets.UTF_8)))throw ApiException.unauthorized();
        if(runToken==null||!runToken.matches("[A-Za-z0-9_-]{1,200}"))throw ApiException.missing();
        var lease=jobs.activeLease(id,runToken);if(lease==null)throw ApiException.missing();
        var rows=access.db.queryForList("SELECT f.* FROM file_objects f JOIN source_documents d ON d.file_id=f.id WHERE d.id=?",lease.documentId());if(rows.isEmpty())throw ApiException.missing();
        var original=files.openOriginal(rows.get(0));
        if(!jobs.validLease(lease)){try{original.stream().close();}catch(IOException ignored){}throw ApiException.missing();}
        var guarded=new FilterInputStream(original.stream()){
            long checked=System.nanoTime();
            private void check()throws IOException{if(System.nanoTime()-checked>1_000_000_000L){checked=System.nanoTime();if(!jobs.validLease(lease)){close();throw new IOException("Task lease expired");}}}
            public int read()throws IOException{check();return in.read();}
            public int read(byte[] b,int off,int len)throws IOException{check();return in.read(b,off,len);}
        };
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_OCTET_STREAM).contentLength(original.byteSize())
            .header("X-Content-SHA256",original.sha256()).header("Cache-Control","no-store").header("X-Content-Type-Options","nosniff")
            .body(new InputStreamResource(guarded));
    }
}
