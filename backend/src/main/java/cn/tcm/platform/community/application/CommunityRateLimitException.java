package cn.tcm.platform.community.application;
import cn.tcm.platform.common.ApiException;
public class CommunityRateLimitException extends ApiException {
 private final long retryAfterSeconds;
 public CommunityRateLimitException(long seconds){super(429,"RATE_LIMITED","操作过于频繁，请稍后重试");this.retryAfterSeconds=Math.max(1,seconds);}
 public long retryAfterSeconds(){return retryAfterSeconds;}
}
