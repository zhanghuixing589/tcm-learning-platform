package cn.tcm.platform.community.application;
import cn.tcm.platform.common.*;
import cn.tcm.platform.content.ContentSupport;
import com.fasterxml.jackson.databind.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import java.util.*;
@Service
public class CommunityPolicyService {
 private final JdbcTemplate db;private final CommunityTextPolicy policies;private final CommunityWriteGuard guard;private final ContentSupport support;
 public CommunityPolicyService(JdbcTemplate db,PlatformTransactionManager tx,CommunityTextPolicy policies,CommunityWriteGuard guard,ObjectMapper json){this.db=db;this.policies=policies;this.guard=guard;this.support=new ContentSupport(db);}
 public record PolicyView(String policyId,int versionNo,JsonNode config,String reason,String createdBy,String createdAt){}
 public record HeadView(String policyId,int lockVersion){}
 public record PublicPolicy(String policyId,JsonNode config,String characterUnit,int lockVersion){}
 public PublicPolicy publicPolicy(){return db.queryForObject("SELECT p.id,p.config,h.lock_version FROM community_text_policy_heads h JOIN community_text_policies p ON p.id=h.policy_id WHERE h.id=1",(rs,n)->{var policy=CommunityTextPolicy.parse(rs.getLong(1),CommunityTextPolicy.read(rs.getString(2)));return new PublicPolicy(rs.getString(1),policy.config(),"UNICODE_CODE_POINT",rs.getInt(3));});}
 public HeadView current(){return db.queryForObject("SELECT policy_id,lock_version FROM community_text_policy_heads WHERE id=1",(rs,n)->new HeadView(rs.getString(1),rs.getInt(2)));}
 public PageResult<PolicyView> list(long actor,int page,int pageSize){require(actor);int offset=PageResult.offset(page,pageSize);return new PageResult<>(page,pageSize,db.queryForObject("SELECT COUNT(*) FROM community_text_policies",Long.class),db.query("SELECT * FROM community_text_policies ORDER BY version_no DESC LIMIT ? OFFSET ?",(rs,n)->view(rs),pageSize,offset));}
 private PolicyView view(java.sql.ResultSet rs) throws java.sql.SQLException{return new PolicyView(rs.getString("id"),rs.getInt("version_no"),CommunityTextPolicy.read(rs.getString("config")),rs.getString("reason"),rs.getString("created_by"),ContentSupport.utc(rs.getObject("created_at")));}
 public PolicyView get(long id){var rows=db.query("SELECT * FROM community_text_policies WHERE id=?",(rs,n)->view(rs),id);if(rows.isEmpty())throw ApiException.missing();return rows.get(0);}
 private void require(long actor){var status=db.queryForList("SELECT status FROM sys_users WHERE id=?",actor);if(status.isEmpty()||!"ACTIVE".equals(status.get(0).get("status")))throw ApiException.unauthorized();support.require(actor,"community:section:manage");}
 public PolicyView create(long actor,String key,JsonNode config,String reason){return guard.execute(actor,List.of(),"TEXT_POLICY_CREATE",key,CommunitySourceHash.canonical(ContentSupport.map("config",config,"reason",reason)),null,()->require(actor),()->{
  var valid=CommunityTextPolicy.parse(config);String note=reason(reason);db.queryForObject("SELECT lock_version FROM community_text_policy_heads WHERE id=1 FOR UPDATE",Integer.class);int next=db.queryForObject("SELECT version_no+1 FROM community_text_policies ORDER BY version_no DESC LIMIT 1 FOR UPDATE",Integer.class);
  long id=support.insert("INSERT INTO community_text_policies(version_no,config,created_by,reason) VALUES(?,?,?,?)",next,valid.config().toString(),actor,note);support.audit(actor,"COMMUNITY_TEXT_POLICY_CREATE","COMMUNITY_TEXT_POLICY",id);return new CommunityWriteGuard.Result("TEXT_POLICY",id,201);
 },result->get(result.id()));}
 public HeadView activate(long actor,String key,long policyId,Integer lockVersion,String reason){return guard.execute(actor,List.of(),"TEXT_POLICY_ACTIVATE",key,CommunitySourceHash.canonical(ContentSupport.map("policyId",Long.toString(policyId),"lockVersion",lockVersion,"reason",reason)),null,()->require(actor),()->{
  String note=reason(reason);if(lockVersion==null||lockVersion<0)throw ApiException.invalid("必须提供非负lockVersion");var head=db.queryForMap("SELECT policy_id,lock_version FROM community_text_policy_heads WHERE id=1 FOR UPDATE");if(((Number)head.get("lock_version")).intValue()!=lockVersion)throw ApiException.conflict("文本政策已更新，请刷新后重试");CommunityTextPolicy.parse(policyId,get(policyId).config());db.update("UPDATE community_text_policy_heads SET policy_id=?,lock_version=lock_version+1,updated_by=? WHERE id=1",policyId,actor);
  String trace=org.slf4j.MDC.get("traceId");db.update("INSERT INTO sys_audit_logs(actor_id,trace_id,action,object_type,object_key,summary) VALUES(?,?,?,?,?,?)",actor,trace==null?UUID.randomUUID().toString():trace,"COMMUNITY_TEXT_POLICY_ACTIVATE","COMMUNITY_TEXT_POLICY",Long.toString(policyId),CommunitySourceHash.json(Map.of("reason",note)));
  return new CommunityWriteGuard.Result("TEXT_POLICY_HEAD",1,200);
 },result->current());}
 private String reason(String reason){if(reason==null||reason.isBlank()||CommunityTextValidator.chars(reason)>1000)throw ApiException.invalid("政策变更原因必须为1至1000字符");return reason.strip();}
}
