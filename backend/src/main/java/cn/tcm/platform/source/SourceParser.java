package cn.tcm.platform.source;

import cn.tcm.platform.common.ApiException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.Flow;
import java.nio.ByteBuffer;
import java.io.ByteArrayOutputStream;

@Component
public class SourceParser {
    private final SourceAccess access;private final SourceFiles files;private final ObjectMapper json;private final String baseUrl,token;
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build();
    public SourceParser(SourceAccess access,SourceFiles files,ObjectMapper json,@Value("${tcm.ai.base-url}")String baseUrl,@Value("${tcm.ai.internal-token}")String token){this.access=access;this.files=files;this.json=json;this.baseUrl=baseUrl;this.token=token;}
    static final class BoundedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> result=new CompletableFuture<>();
        private final ByteArrayOutputStream output=new ByteArrayOutputStream();
        private Flow.Subscription subscription;
        public CompletionStage<byte[]> getBody(){return result;}
        public void onSubscribe(Flow.Subscription subscription){this.subscription=subscription;subscription.request(1);}
        public void onNext(List<ByteBuffer> buffers){
            for(var b:buffers){if(output.size()+(long)b.remaining()>12*1024*1024){subscription.cancel();result.completeExceptionally(new IllegalStateException("Response size limit"));return;}byte[] part=new byte[b.remaining()];b.get(part);output.writeBytes(part);}
            subscription.request(1);
        }
        public void onError(Throwable failure){result.completeExceptionally(failure);}
        public void onComplete(){result.complete(output.toByteArray());}
    }
    public List<SourceJobs.ParsedSegment> parse(SourceJobs.Lease lease){
        var records=access.db.queryForList("SELECT f.* FROM file_objects f JOIN source_documents d ON d.file_id=f.id WHERE d.id=?",lease.documentId());if(records.isEmpty())throw ApiException.invalid("资料未关联文件");var f=records.get(0);
        try{
            var body=json.writeValueAsString(Map.of("fileName",f.get("original_name"),"contentBase64",Base64.getEncoder().encodeToString(files.bytes(f))));
            var request=HttpRequest.newBuilder(URI.create(baseUrl.replaceAll("/+$","")+"/internal/v1/parse")).timeout(Duration.ofSeconds(45)).header("Content-Type","application/json").header("X-Internal-Token",token).header("X-Trace-Id",lease.runToken()).POST(HttpRequest.BodyPublishers.ofString(body)).build();
            var pending=http.sendAsync(request,info->new BoundedBody());HttpResponse<byte[]> response;
            try{response=pending.get(45,TimeUnit.SECONDS);}catch(InterruptedException|ExecutionException|TimeoutException e){pending.cancel(true);throw e;}
            var result=json.readTree(response.body());if(response.statusCode()!=200||!result.path("code").asText().equals("OK"))throw new ApiException(502,"PARSE_FAILED",response.statusCode()==400?"文档损坏、没有文本层或格式超出解析限制":"解析服务暂不可用，请稍后重试");
            var segments=new ArrayList<SourceJobs.ParsedSegment>();for(var s:result.path("data").path("segments")){segments.add(new SourceJobs.ParsedSegment(s.path("segmentNo").asInt(),s.path("pageLabel").isNull()?null:s.path("pageLabel").asText(),s.path("headingPath").isNull()?null:s.path("headingPath").asText(),s.path("body").asText()));}return segments;
        }catch(ApiException e){throw e;}catch(InterruptedException e){Thread.currentThread().interrupt();throw new ApiException(503,"PARSE_UNAVAILABLE","解析执行已中断，请重试");}catch(Exception e){throw new ApiException(503,"PARSE_UNAVAILABLE","解析服务暂不可用，请稍后重试");}
    }
}
