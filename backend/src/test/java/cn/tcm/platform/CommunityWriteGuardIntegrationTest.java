package cn.tcm.platform;
import cn.tcm.platform.community.application.*;
import cn.tcm.platform.common.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;
@SpringBootTest
@org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
@org.springframework.context.annotation.Import(CommunityWriteGuardIntegrationTest.RateEndpoint.class)
class CommunityWriteGuardIntegrationTest {
 @Autowired JdbcTemplate db; @Autowired PlatformTransactionManager tx; @Autowired CommunityTextPolicy policies;
 @Autowired org.springframework.test.web.servlet.MockMvc mvc;
 @Autowired cn.tcm.platform.identity.infrastructure.SecurityProperties securityProperties;
 @org.springframework.boot.test.context.TestComponent @org.springframework.web.bind.annotation.RestController public static class RateEndpoint {
  @org.springframework.web.bind.annotation.GetMapping("/api/v1/task2-rate-test") Object rate(){throw new CommunityRateLimitException(23);}
 }
 Long originalPolicy;
 @BeforeEach void snapshotHead(){originalPolicy=db.queryForObject("SELECT policy_id FROM community_text_policy_heads WHERE id=1",Long.class);}
 final List<Long> users=new ArrayList<>();
 long user(){String name="guard_"+UUID.randomUUID().toString().replace("-","");db.update("INSERT INTO sys_users(username,password_hash,nickname) VALUES(?,'test','guard')",name);long id=db.queryForObject("SELECT id FROM sys_users WHERE username=?",Long.class,name);users.add(id);return id;}
 CommunityWriteGuard guard(Clock clock){return new CommunityWriteGuard(db,tx,clock,policies);}
 CommunityWriteGuard.Result run(CommunityWriteGuard g,long user,String key,String hash,String rate,Runnable auth,Runnable command){return g.execute(user,List.of(),"TEST:1",key,hash,rate,auth,()->{command.run();return new CommunityWriteGuard.Result("TEST",user,201);},r->r);}
 @AfterEach void cleanup(){
  if(originalPolicy!=null)db.update("UPDATE community_text_policy_heads SET policy_id=?,lock_version=lock_version+1,updated_by=NULL WHERE id=1",originalPolicy);
  for(long id:users){db.update("DELETE FROM community_rate_windows WHERE user_id=?",id);db.update("DELETE FROM community_write_receipts WHERE actor_id=?",id);db.update("DELETE FROM sys_audit_logs WHERE actor_id=?",id);db.update("DELETE FROM community_text_policies WHERE created_by=?",id);db.update("DELETE FROM sys_user_roles WHERE user_id=?",id);db.update("DELETE FROM sys_users WHERE id=?",id);}
 }
 @Test void realAppCorsAllowsIdempotencyAndExposesRetryAndRegisteredFilterLimitsBytes() throws Exception {
  long id=user();var as=org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user(Long.toString(id));
  mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options("/api/v1/admin/community/text-policies").header("Origin",securityProperties.allowedOrigins().get(0)).header("Access-Control-Request-Method","POST").header("Access-Control-Request-Headers","Idempotency-Key,Content-Type")).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk()).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string("Access-Control-Allow-Headers",org.hamcrest.Matchers.containsString("Idempotency-Key")));
  mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/task2-rate-test").with(as).header("Origin",securityProperties.allowedOrigins().get(0))).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().is(429)).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string("Retry-After","23")).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string("Access-Control-Expose-Headers",org.hamcrest.Matchers.containsString("Retry-After"))).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code").value("RATE_LIMITED"));
  mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/admin/community/text-policies").with(as).contentType("application/json").header("Content-Length","1").content(new byte[8388609])).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().is(413)).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.traceId").isNotEmpty());
  mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/community/text-policy").with(as)).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk()).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data.characterUnit").value("UNICODE_CODE_POINT"));
 }
 @Test void exactKeysReplayConflictAndCurrentAuthorization(){long u=user();var g=guard(Clock.systemUTC());var calls=new AtomicInteger();String hash=CommunitySourceHash.comment("a");
  run(g,u,"Abcdefgh",hash,null,()->{},calls::incrementAndGet);run(g,u,"Abcdefgh",hash,null,()->{},calls::incrementAndGet);run(g,u,"abcdefgh",hash,null,()->{},calls::incrementAndGet);assertThat(calls.get()).isEqualTo(2);
  assertThatThrownBy(()->run(g,u,"Abcdefgh",CommunitySourceHash.comment("b"),null,()->{},()->{})).isInstanceOf(ApiException.class).extracting(e->((ApiException)e).status).isEqualTo(409);
  assertThatThrownBy(()->run(g,u,"Abcdefgh",hash,null,()->{throw ApiException.missing();},()->{})).isInstanceOf(ApiException.class);
  for(String key:List.of(" abcdefgh","abcdefgh ","abc defg","中文abcdefgh","abcdefg","abcdefgh\n"))assertThatThrownBy(()->run(g,u,key,hash,null,()->{},()->{})).isInstanceOf(ApiException.class);
  db.update("UPDATE sys_users SET status='DISABLED' WHERE id=?",u);assertThatThrownBy(()->run(g,u,"Abcdefgh",hash,null,()->{},()->{})).isInstanceOf(ApiException.class);
 }
 @Test void rollbackAndFixedWindowsAndReplayDoesNotCharge(){long u=user();var time=Clock.fixed(Instant.parse("2026-11-20T00:00:00Z"),ZoneOffset.UTC);var g=guard(time);String hash=CommunitySourceHash.comment("a");
  assertThatThrownBy(()->run(g,u,"rollback",hash,"POST_CREATE",()->{},()->{db.update("UPDATE sys_users SET nickname='changed' WHERE id=?",u);throw ApiException.conflict("rollback");})).isInstanceOf(ApiException.class);
  assertThat(db.queryForObject("SELECT COUNT(*) FROM community_write_receipts WHERE actor_id=?",Integer.class,u)).isZero();assertThat(db.queryForObject("SELECT nickname FROM sys_users WHERE id=?",String.class,u)).isEqualTo("guard");
  for(int i=0;i<3;i++)run(g,u,"key0000"+i,hash,"POST_CREATE",()->{},()->{});
  run(g,u,"key00000",hash,"POST_CREATE",()->{},()->{});
  assertThatThrownBy(()->run(g,u,"key00004",hash,"POST_CREATE",()->{},()->{})).isInstanceOf(CommunityRateLimitException.class).satisfies(e->assertThat(((CommunityRateLimitException)e).retryAfterSeconds()).isEqualTo(60));
  run(guard(Clock.offset(time,Duration.ofMinutes(1))),u,"key00004",hash,"POST_CREATE",()->{},()->{});
  assertThat(db.queryForObject("SELECT COUNT(*) FROM community_write_receipts WHERE actor_id=? AND result_kind='RESERVED'",Integer.class,u)).isZero();
 }
 @Test void realConcurrentActorsAndSameKeyRetries() throws Exception {long a=user(),b=user();var g=guard(Clock.systemUTC());var calls=new AtomicInteger();var start=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(4);try{var futures=new ArrayList<Future<?>>();for(long u:List.of(a,a,b,b))futures.add(pool.submit(()->{try{start.await();run(g,u,"parallel",CommunitySourceHash.comment("a"),null,()->{},calls::incrementAndGet);}catch(InterruptedException e){throw new RuntimeException(e);}}));start.countDown();for(var f:futures)f.get(20,TimeUnit.SECONDS);assertThat(calls.get()).isEqualTo(2);}finally{pool.shutdownNow();}}
 @Test void lockedRateGateRequiresExactlyOneChargeAndRollbackIsAtomic(){long id=user();var g=guard(Clock.fixed(Instant.parse("2026-11-20T00:00:00Z"),ZoneOffset.UTC));String hash=CommunitySourceHash.comment("x");
  assertThatThrownBy(()->g.executeLocked(id,List.of(),"LOCKED", "missing1",hash,"POST_CREATE",()->{},rate->{db.update("UPDATE sys_users SET nickname='changed' WHERE id=?",id);return new CommunityWriteGuard.Result("TEST",id,201);},r->r)).isInstanceOf(IllegalStateException.class);
  assertThat(db.queryForObject("SELECT nickname FROM sys_users WHERE id=?",String.class,id)).isEqualTo("guard");
  assertThatThrownBy(()->g.executeLocked(id,List.of(),"LOCKED", "double01",hash,"POST_CREATE",()->{},rate->{rate.charge();rate.charge();return new CommunityWriteGuard.Result("TEST",id,201);},r->r)).isInstanceOf(IllegalStateException.class);
  g.executeLocked(id,List.of(),"LOCKED","success1",hash,"POST_CREATE",()->{},rate->{assertThat(rate.policy().id()).isPositive();rate.charge();return new CommunityWriteGuard.Result("TEST",id,201);},r->r);
  g.executeLocked(id,List.of(),"LOCKED","success1",hash,"POST_CREATE",()->{},rate->{throw new AssertionError("Replay must not validate/charge/run command");},r->r);
  assertThat(db.queryForObject("SELECT SUM(attempt_count) FROM community_rate_windows WHERE user_id=?",Integer.class,id)).isEqualTo(2);
 }
 @Test void sortedParticipantAccountLocksSerializeOppositeActorsAndPermitDisabledSubject() throws Exception {long a=user(),b=user();var g=guard(Clock.systemUTC());var pool=Executors.newFixedThreadPool(2);var start=new CountDownLatch(1);try{var jobs=new ArrayList<Future<?>>();for(long actor:List.of(a,b))jobs.add(pool.submit(()->{try{start.await();g.execute(actor,List.of(a,b),"PARTICIPANTS","shared01",CommunitySourceHash.comment("x"),null,()->{},()->new CommunityWriteGuard.Result("TEST",actor,201),r->r);}catch(InterruptedException e){throw new RuntimeException(e);}}));start.countDown();for(var j:jobs)j.get(20,TimeUnit.SECONDS);}finally{pool.shutdownNow();}
  db.update("UPDATE sys_users SET status='DISABLED' WHERE id=?",b);g.execute(a,List.of(b),"DISABLED_SUBJECT","allowed1",CommunitySourceHash.comment("x"),null,()->{},()->new CommunityWriteGuard.Result("TEST",b,200),r->r);
 }
 @Test void unitEchoFixtureIsNotScanned(){assertThat(applicationContext.getBeansOfType(CommunityRequestLimitTest.Endpoint.class)).isEmpty();}
 @Autowired org.springframework.context.ApplicationContext applicationContext;
 @Test void policiesAreAppendOnlyAndHeadActivationUsesCurrentPermissionAndLock(){long admin=user(),learner=user();db.update("INSERT INTO sys_user_roles(user_id,role_id) SELECT ?,id FROM sys_roles WHERE code='ADMIN'",admin);
  var service=new CommunityPolicyService(db,tx,policies,guard(Clock.systemUTC()),new ObjectMapper());var head=service.current();String before=policies.current().config().toString();
  assertThatThrownBy(()->service.create(learner,"create01",policies.current().config(),"test")).isInstanceOf(ApiException.class);
  var created=service.create(admin,"create01",policies.current().config().put("maxTitleChars",119),"test");long id=Long.parseLong(created.policyId());assertThat(policies.current().maxTitleChars()).isEqualTo(120);
  service.activate(admin,"activate",id,head.lockVersion(),"test");assertThat(policies.current().maxTitleChars()).isEqualTo(119);
  assertThatThrownBy(()->service.activate(admin,"activate2",1,head.lockVersion(),"stale")).isInstanceOf(ApiException.class);
  assertThat(db.queryForObject("SELECT config FROM community_text_policies WHERE id=1",String.class)).contains("120");
  new TransactionTemplate(tx).execute(s->{db.update("UPDATE community_text_policy_heads SET policy_id=1,lock_version=lock_version+1,updated_by=NULL WHERE id=1");db.update("DELETE FROM sys_audit_logs WHERE actor_id=?",admin);db.update("DELETE FROM community_write_receipts WHERE actor_id=?",admin);db.update("DELETE FROM community_text_policies WHERE id=?",id);return null;});
 }
}
