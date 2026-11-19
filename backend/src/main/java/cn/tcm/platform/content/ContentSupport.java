package cn.tcm.platform.content;

import cn.tcm.platform.common.ApiException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Component;
import java.sql.Statement;
import java.time.*;
import java.util.*;

@Component
public class ContentSupport {
    public final JdbcTemplate db;
    public ContentSupport(JdbcTemplate db){this.db=db;}
    public long count(String sql,Object...args){return Objects.requireNonNull(db.queryForObject(sql,Long.class,args));}
    public long insert(String sql,Object...args){var keys=new GeneratedKeyHolder();db.update(c->{var s=c.prepareStatement(sql,Statement.RETURN_GENERATED_KEYS);for(int i=0;i<args.length;i++)s.setObject(i+1,args[i]);return s;},keys);return Objects.requireNonNull(keys.getKey()).longValue();}
    public Map<String,Object> row(String sql,Object...args){var rows=db.queryForList(sql,args);if(rows.isEmpty())throw ApiException.missing();return rows.get(0);}
    public boolean has(long user,String permission){return count("SELECT COUNT(*) FROM sys_user_roles ur JOIN sys_role_permissions rp ON rp.role_id=ur.role_id JOIN sys_permissions p ON p.id=rp.permission_id WHERE ur.user_id=? AND p.code=?",user,permission)>0;}
    public void require(long user,String permission){if(!has(user,permission))throw new ApiException(403,"FORBIDDEN","没有执行此操作的权限");}
    public static long id(String value){try{long n=Long.parseLong(value);if(n<1)throw new NumberFormatException();return n;}catch(Exception e){throw ApiException.invalid("ID格式不正确");}}
    public static String sid(Object value){return value==null?null:value.toString();}
    public static long number(Object value){return ((Number)value).longValue();}
    public static String utc(Object value){if(value==null)return null;if(value instanceof LocalDateTime t)return t.toInstant(ZoneOffset.UTC).toString();if(value instanceof java.sql.Timestamp t)return t.toLocalDateTime().toInstant(ZoneOffset.UTC).toString();return value.toString();}
    public static Map<String,Object> map(Object...pairs){var result=new LinkedHashMap<String,Object>();for(int i=0;i<pairs.length;i+=2)result.put((String)pairs[i],pairs[i+1]);return result;}
    public static String text(String value,int max,boolean required){if(value==null){if(required)throw ApiException.invalid("必填文本不能为空");return null;}String s=value.trim();if((required&&s.isEmpty())||s.length()>max||s.indexOf('\0')>=0)throw ApiException.invalid("文本长度或内容不正确");return s;}
    public static String visible(String resource,String revision){return resource+".resource_type='KNOWLEDGE' AND "+resource+".visibility_status='PUBLISHED' AND "+revision+".revision_state='APPROVED' AND EXISTS(SELECT 1 FROM sys_users owner WHERE owner.id="+resource+".owner_id AND owner.status='ACTIVE') AND "+cn.tcm.platform.common.PublishedKnowledge.evidenceIsActive(revision);}
    public void audit(long actor,String action,String kind,long id){String trace=org.slf4j.MDC.get("traceId");db.update("INSERT INTO sys_audit_logs(actor_id,trace_id,action,object_type,object_key) VALUES(?,?,?,?,?)",actor,trace==null?UUID.randomUUID().toString():trace,action,kind,""+id);}
    public void job(String type,long revision,String suffix){db.update("INSERT INTO async_jobs(job_type,idempotency_key,revision_id) VALUES(?,?,?) ON DUPLICATE KEY UPDATE id=id",type,type+":"+revision+":"+suffix,revision);}
}
