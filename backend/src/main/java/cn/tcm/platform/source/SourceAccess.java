package cn.tcm.platform.source;

import cn.tcm.platform.common.ApiException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Component;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.ZoneOffset;
import java.util.*;

@Component
public class SourceAccess {
    final JdbcTemplate db;
    public SourceAccess(JdbcTemplate db){this.db=db;}
    boolean permission(long user,String code){return db.queryForObject("SELECT COUNT(*) FROM sys_user_roles ur JOIN sys_role_permissions rp ON rp.role_id=ur.role_id JOIN sys_permissions p ON p.id=rp.permission_id JOIN sys_users u ON u.id=ur.user_id AND u.status='ACTIVE' WHERE ur.user_id=? AND p.code=?",Long.class,user,code)>0;}
    void require(long user,String code){if(!permission(user,code))throw new ApiException(403,"FORBIDDEN","没有执行此操作的权限");}
    boolean visible(long user,Map<String,Object> d){return Objects.equals(d.get("submitted_by").toString(),Long.toString(user))||permission(user,"knowledge:review")||("APPROVED".equals(d.get("review_state"))&&"ACTIVE".equals(d.get("availability")));}
    Map<String,Object> document(long user,long id,boolean lock){var rows=db.queryForList("SELECT * FROM source_documents WHERE id=?"+(lock?" FOR UPDATE":""),id);if(rows.isEmpty()||!visible(user,rows.get(0)))throw ApiException.missing();return rows.get(0);}
    void owner(long user,Map<String,Object>d){if(!d.get("submitted_by").toString().equals(Long.toString(user)))throw ApiException.missing();}
    void draft(long user,Map<String,Object>d){owner(user,d);if(!"DRAFT".equals(d.get("review_state")))throw ApiException.conflict("已提交资料不可修改，请复制修订");}
    boolean uploaded(long user,long file){return db.queryForObject("SELECT COUNT(*) FROM file_objects f WHERE f.id=? AND (f.uploaded_by=? OR EXISTS(SELECT 1 FROM file_upload_grants g WHERE g.file_id=f.id AND g.user_id=?))",Long.class,file,user,user)>0;}
    long insert(String sql,Object...args){var keys=new GeneratedKeyHolder();db.update(c->{var p=c.prepareStatement(sql,Statement.RETURN_GENERATED_KEYS);for(int i=0;i<args.length;i++)p.setObject(i+1,args[i]);return p;},keys);return Objects.requireNonNull(keys.getKey()).longValue();}
    void audit(long user,String action,String type,long id){String trace=org.slf4j.MDC.get("traceId");db.update("INSERT INTO sys_audit_logs(actor_id,trace_id,action,object_type,object_key) VALUES(?,?,?,?,?)",user,trace==null?UUID.randomUUID().toString():trace,action,type,Long.toString(id));}
    static String utc(Object value){if(value==null)return null;if(value instanceof Timestamp t)return t.toLocalDateTime().toInstant(ZoneOffset.UTC).toString();if(value instanceof java.time.LocalDateTime t)return t.toInstant(ZoneOffset.UTC).toString();throw new IllegalArgumentException("Unsupported database timestamp");}
    static Map<String,Object> map(Object... pairs){var result=new LinkedHashMap<String,Object>();for(int i=0;i<pairs.length;i+=2)result.put((String)pairs[i],pairs[i+1]);return result;}
    static String id(Object value){return value==null?null:value.toString();}
}
