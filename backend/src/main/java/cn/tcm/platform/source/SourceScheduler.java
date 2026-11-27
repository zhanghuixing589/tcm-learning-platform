package cn.tcm.platform.source;

import cn.tcm.platform.common.ApiException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.*;

@Configuration @EnableScheduling @ConditionalOnProperty(name="tcm.jobs.enabled",havingValue="true",matchIfMissing=true)
public class SourceScheduler {
    private static final org.slf4j.Logger LOG=org.slf4j.LoggerFactory.getLogger(SourceScheduler.class);
    private final SourceJobs jobs;private final SourceParser parser;
    private final ExecutorService worker=Executors.newSingleThreadExecutor(r->{var t=new Thread(r,"source-processing");t.setDaemon(true);return t;});
    public SourceScheduler(SourceJobs jobs,SourceParser parser){this.jobs=jobs;this.parser=parser;}
    @PreDestroy public void stop(){worker.shutdownNow();}
    @Scheduled(fixedDelayString="${tcm.jobs.poll-delay-ms:2000}",initialDelay=3000)
    public void process(){
        try{var lease=jobs.claim();if(lease==null)return;Future<SourceParser.DocumentResult> pending=worker.submit(()->parser.process(lease));
            try{
                long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(660);SourceParser.DocumentResult result;
                while(true){
                    try{result=pending.get(20,TimeUnit.SECONDS);break;}
                    catch(TimeoutException e){if(System.nanoTime()>deadline||!jobs.heartbeat(lease))throw new ApiException(503,"PROCESS_LEASE_LOST","处理超时或执行租约已失效，请重试");}
                }
                jobs.completeDocument(lease,result,null);
            }catch(Exception error){
                pending.cancel(true);Throwable cause=error instanceof ExecutionException?error.getCause():error;
                jobs.completeDocument(lease,null,cause instanceof ApiException?cause.getMessage():"资料处理失败，请重试");
                if(error instanceof InterruptedException)Thread.currentThread().interrupt();
            }
        }catch(Exception e){LOG.warn("Source processing task transaction failed type={}",e.getClass().getSimpleName());}
    }
}
