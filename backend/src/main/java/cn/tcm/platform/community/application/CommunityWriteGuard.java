package cn.tcm.platform.community.application;
import cn.tcm.platform.common.ApiException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.*;
import java.util.*;
import java.util.function.*;

/** All callbacks execute in the same transaction. Callers must acquire sections/resources only after entry. */
@Component
public class CommunityWriteGuard {
 private final JdbcTemplate db;private final TransactionTemplate tx;private final Clock clock;private final CommunityTextPolicy policies;
 public CommunityWriteGuard(JdbcTemplate db,PlatformTransactionManager tx,Clock clock,CommunityTextPolicy policies){this.db=db;this.tx=new TransactionTemplate(tx);this.clock=clock;this.policies=policies;}
 public record Result(String kind,long id,int status){}
 public static void validateKey(String key){if(key==null||key.length()<8||key.length()>128)throw ApiException.invalid("Idempotency-Key必须为8至128位可见ASCII字符");for(int i=0;i<key.length();i++)if(key.charAt(i)<0x21||key.charAt(i)>0x7e)throw ApiException.invalid("Idempotency-Key不可包含空白或非ASCII字符");}
 /** authorize must re-read roles/ownership under account locks, and must also be valid for replay. null rate means an explicitly unmetered internal command. */
 /** Convenience for commands that do not acquire domain locks; metered domain writes use executeLocked. */
 public <T> T execute(long actor,Collection<Long> participants,String operation,String key,String hash,String rate,Runnable authorize,Supplier<Result> command,Function<Result,T> safeRead){
  return executeLocked(actor,participants,operation,key,hash,rate,authorize,gate->{gate.charge();return command.get();},safeRead);
 }
 /** New command acquires section/resource/comment locks, validates using gate.policy(), then charges once before mutations/events. Replays never invoke it. */
 public <T> T executeLocked(long actor,Collection<Long> participants,String operation,String key,String hash,String rate,Runnable authorize,Function<RateGate,Result> command,Function<Result,T> safeRead){
  validateKey(key);if(operation==null||operation.isBlank()||operation.length()>160||hash==null||!hash.matches("[0-9a-f]{64}"))throw ApiException.invalid("写入操作或请求摘要无效");
  return tx.execute(status->{
   lockAccounts(actor, participants);
   authorize.run();
   // Atomic reservation precedes receipt locking: no absent-row SELECT gap lock.
   db.update("INSERT INTO community_write_receipts(actor_id,operation,request_key,request_sha256,result_kind,result_id,http_status) VALUES(?,?,?,?,'RESERVED',0,102) ON DUPLICATE KEY UPDATE id=id",actor,operation,key,hash);
   var receipt=db.queryForMap("SELECT id,request_sha256,result_kind,result_id,http_status FROM community_write_receipts WHERE actor_id=? AND operation=? AND request_key=? FOR UPDATE",actor,operation,key);
   if(!hash.equals(receipt.get("request_sha256")))throw ApiException.conflict("同一幂等键已用于不同请求内容");
   Result result;
   if(!"RESERVED".equals(receipt.get("result_kind"))){result=new Result((String)receipt.get("result_kind"),((Number)receipt.get("result_id")).longValue(),((Number)receipt.get("http_status")).intValue());return safeRead.apply(result);}
   // Caller validates current policy and acquires domain locks here. Charge after domain locks.
   var gate=new RateGate(actor,rate);
   result=Objects.requireNonNull(command.apply(gate));
   if(rate!=null&&!gate.charged)throw new IllegalStateException("Metered command must charge exactly once after domain locks");
   if(result.kind()==null||result.kind().equals("RESERVED")||result.kind().length()>32||result.id()<1||result.status()<200||result.status()>299)throw new IllegalStateException("Command must produce a completed receipt");
   db.update("UPDATE community_write_receipts SET result_kind=?,result_id=?,http_status=? WHERE id=?",result.kind(),result.id(),result.status(),receipt.get("id"));
   return safeRead.apply(result);
  });
 }
 /** Conditional updates use optimistic locks instead of operation receipts. */
 public <T> T executeUnkeyed(long actor, String rate, Function<RateGate,T> command) {
  return tx.execute(status -> {
   lockAccounts(actor, List.of());
   var gate = new RateGate(actor, rate);
   T result = command.apply(gate);
   if (rate != null && !gate.charged) throw new IllegalStateException("Metered command must charge once after domain locks");
   return result;
  });
 }

 /** Also used by article review, whose existing review-task status serves as its decision receipt. */
 public void lockAccounts(long actor, Collection<Long> participants) {
  var ids = new TreeSet<Long>();
  ids.add(actor);
  if (participants != null) ids.addAll(participants);
  for (long id : ids) {
   var rows = db.queryForList("SELECT status FROM sys_users WHERE id=? FOR UPDATE", id);
   if (rows.isEmpty()) {
    if (id == actor) throw ApiException.unauthorized();
    throw ApiException.missing();
   }
   if (id == actor && !"ACTIVE".equals(rows.get(0).get("status"))) throw ApiException.unauthorized();
  }
 }

 public final class RateGate {
  private final long actor;private final String category;private boolean charged;private CommunityTextPolicy snapshot;
  private RateGate(long actor,String category){this.actor=actor;this.category=category;}
  public CommunityTextPolicy policy(){if(snapshot==null)snapshot=policies.current();return snapshot;}
  public void charge(){if(charged)throw new IllegalStateException("Command rate already charged");if(category!=null)CommunityWriteGuard.this.charge(actor,category,policy());charged=true;}
 }
 private void charge(long actor,String category,CommunityTextPolicy policy){Instant now=clock.instant();long retry=0;
  for(var rate:policy.rates(category)){long start=Math.floorDiv(now.getEpochSecond(),rate.seconds())*rate.seconds();var at=LocalDateTime.ofInstant(Instant.ofEpochSecond(start),ZoneOffset.UTC);
   db.update("INSERT INTO community_rate_windows(user_id,operation,window_seconds,window_start,attempt_count,policy_id) VALUES(?,?,?,?,0,?) ON DUPLICATE KEY UPDATE user_id=user_id",actor,category,rate.seconds(),at,policy.id());
   int count=db.queryForObject("SELECT attempt_count FROM community_rate_windows WHERE user_id=? AND operation=? AND window_seconds=? AND window_start=? FOR UPDATE",Integer.class,actor,category,rate.seconds(),at);
   if(count>=rate.limit())retry=Math.max(retry,start+rate.seconds()-now.getEpochSecond());
   db.update("UPDATE community_rate_windows SET attempt_count=attempt_count+1,policy_id=? WHERE user_id=? AND operation=? AND window_seconds=? AND window_start=?",policy.id(),actor,category,rate.seconds(),at);
  }
  if(retry>0)throw new CommunityRateLimitException(retry);
 }
}
