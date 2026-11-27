package cn.tcm.platform.source;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public record SourceLimits(long maxOriginalBytes,long maxExpandedBytes,int maxArchiveEntries,int maxCompressionRatio){
    public static final SourceLimits DEFAULTS=new SourceLimits(50L*1024*1024,200L*1024*1024,5000,200);
    public SourceLimits(@Value("${tcm.source.limits.max-original-bytes:52428800}")long maxOriginalBytes,
            @Value("${tcm.source.limits.max-expanded-bytes:209715200}")long maxExpandedBytes,
            @Value("${tcm.source.limits.max-archive-entries:5000}")int maxArchiveEntries,
            @Value("${tcm.source.limits.max-compression-ratio:200}")int maxCompressionRatio){
        if(maxOriginalBytes<1||maxOriginalBytes>50L*1024*1024||maxExpandedBytes<1||maxExpandedBytes>200L*1024*1024||maxArchiveEntries<1||maxArchiveEntries>5000||maxCompressionRatio<1||maxCompressionRatio>200)throw new IllegalArgumentException("Source limits exceed approved candidate ceilings");
        this.maxOriginalBytes=maxOriginalBytes;this.maxExpandedBytes=maxExpandedBytes;this.maxArchiveEntries=maxArchiveEntries;this.maxCompressionRatio=maxCompressionRatio;
    }
}
