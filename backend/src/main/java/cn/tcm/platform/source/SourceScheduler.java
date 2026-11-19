package cn.tcm.platform.source;

import cn.tcm.platform.common.ApiException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

@Configuration @EnableScheduling @ConditionalOnProperty(name="tcm.jobs.enabled",havingValue="true",matchIfMissing=true)
public class SourceScheduler {
    private static final org.slf4j.Logger LOG=org.slf4j.LoggerFactory.getLogger(SourceScheduler.class);
    private final SourceJobs jobs;private final SourceParser parser;
    public SourceScheduler(SourceJobs jobs,SourceParser parser){this.jobs=jobs;this.parser=parser;}
    @Scheduled(fixedDelayString="${tcm.jobs.poll-delay-ms:2000}",initialDelay=3000)
    public void process(){
        try{var lease=jobs.claim();if(lease==null)return;
            try{jobs.complete(lease,parser.parse(lease),null);}catch(Exception e){jobs.complete(lease,null,e instanceof ApiException?e.getMessage():"解析执行失败，请重试");}
        }catch(Exception e){LOG.warn("Source parse task transaction failed type={}",e.getClass().getSimpleName());}
    }
}
