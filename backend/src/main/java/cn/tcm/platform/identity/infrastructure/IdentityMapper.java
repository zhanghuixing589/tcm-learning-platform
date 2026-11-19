package cn.tcm.platform.identity.infrastructure;

import cn.tcm.platform.identity.domain.*;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.*;
import java.util.List;

@Mapper
public interface IdentityMapper extends BaseMapper<UserAccount> {
    @Select("SELECT * FROM sys_users WHERE username=#{username}") UserAccount byUsername(String username);
    @Select("SELECT * FROM sys_users WHERE id=#{id} FOR UPDATE") UserAccount lockUser(long id);
    @Select("SELECT r.code FROM sys_roles r JOIN sys_user_roles ur ON ur.role_id=r.id WHERE ur.user_id=#{id} ORDER BY r.id") List<String> roles(long id);
    @Select("SELECT DISTINCT p.code FROM sys_permissions p JOIN sys_role_permissions rp ON rp.permission_id=p.id JOIN sys_user_roles ur ON ur.role_id=rp.role_id WHERE ur.user_id=#{id} ORDER BY p.code") List<String> permissions(long id);
    @Insert("INSERT IGNORE INTO sys_user_roles(user_id,role_id,granted_by) SELECT #{userId},id,#{actor} FROM sys_roles WHERE code=#{code}")
    int grant(@Param("userId") long userId,@Param("code") String code,@Param("actor") Long actor);
    @Delete("DELETE FROM sys_user_roles WHERE user_id=#{id}") void clearRoles(long id);
    @Select("SELECT * FROM sys_users ORDER BY id DESC LIMIT #{limit} OFFSET #{offset}") List<UserAccount> users(@Param("limit")int limit,@Param("offset")int offset);
    @Select("SELECT COUNT(*) FROM sys_users") long userCount();
    @Update("UPDATE sys_users SET nickname=#{nickname},bio=#{bio} WHERE id=#{id}") void profile(@Param("id")long id,@Param("nickname")String nickname,@Param("bio")String bio);
    @Update("UPDATE sys_users SET last_login_at=UTC_TIMESTAMP(3) WHERE id=#{id}") void loginTime(long id);
    @Update("UPDATE sys_users SET status=#{status},credential_version=credential_version+1 WHERE id=#{id}") void status(@Param("id")long id,@Param("status")String status);
    @Select("SELECT * FROM auth_refresh_tokens WHERE token_hash=#{hash}") RefreshToken token(String hash);
    @Select("SELECT * FROM auth_refresh_tokens WHERE token_hash=#{hash} FOR UPDATE") RefreshToken lockToken(String hash);
    @Insert("INSERT INTO auth_refresh_tokens(user_id,token_hash,family_key,expires_at,absolute_expires_at) VALUES(#{userId},#{tokenHash},#{familyKey},#{expiresAt},#{absoluteExpiresAt})")
    @Options(useGeneratedKeys=true,keyProperty="id") void addToken(RefreshToken token);
    @Update("UPDATE auth_refresh_tokens SET revoked_at=UTC_TIMESTAMP(3),replaced_by_id=#{replacement} WHERE id=#{id}")
    void rotate(@Param("id")long id,@Param("replacement")long replacement);
    @Update("UPDATE auth_refresh_tokens SET revoked_at=COALESCE(revoked_at,UTC_TIMESTAMP(3)) WHERE family_key=#{family}") void revokeFamily(String family);
    @Update("UPDATE auth_refresh_tokens SET revoked_at=COALESCE(revoked_at,UTC_TIMESTAMP(3)) WHERE user_id=#{id}") void revokeUser(long id);
    @Select("SELECT COUNT(*) FROM auth_refresh_tokens WHERE user_id=#{user} AND family_key=#{family} AND revoked_at IS NULL AND expires_at>UTC_TIMESTAMP(3) AND absolute_expires_at>UTC_TIMESTAMP(3)")
    int activeFamily(@Param("user")long user,@Param("family")String family);
    @Select("SELECT COUNT(*) FROM sys_role_applications WHERE user_id=#{user} AND role_id=2 AND status='PENDING'") int pending(long user);
    @Insert("INSERT INTO sys_role_applications(user_id,role_id,statement) SELECT #{userId},id,#{statement} FROM sys_roles WHERE code='CONTRIBUTOR'")
    @Options(useGeneratedKeys=true,keyProperty="id") void apply(RoleApplication application);
    @Select("SELECT a.*,u.username FROM sys_role_applications a JOIN sys_users u ON u.id=a.user_id WHERE a.id=#{id}") RoleApplication application(long id);
    @Select("SELECT * FROM sys_role_applications WHERE id=#{id} FOR UPDATE") RoleApplication lockApplication(long id);
    @Select("<script>SELECT a.*,u.username FROM sys_role_applications a JOIN sys_users u ON u.id=a.user_id <if test='user != null'>WHERE a.user_id=#{user}</if> ORDER BY a.id DESC LIMIT #{limit} OFFSET #{offset}</script>")
    List<RoleApplication> applications(@Param("user")Long user,@Param("limit")int limit,@Param("offset")int offset);
    @Select("<script>SELECT COUNT(*) FROM sys_role_applications <if test='user != null'>WHERE user_id=#{user}</if></script>") long applicationCount(@Param("user")Long user);
    @Update("UPDATE sys_role_applications SET status=#{status},reviewer_id=#{reviewerId},review_note=#{reviewNote},reviewed_at=UTC_TIMESTAMP(3) WHERE id=#{id}") void decision(RoleApplication application);
    @Insert("INSERT INTO sys_audit_logs(actor_id,trace_id,action,object_type,object_key) VALUES(#{actor},#{trace},#{action},#{type},#{key})")
    void audit(@Param("actor")long actor,@Param("trace")String trace,@Param("action")String action,@Param("type")String type,@Param("key")String key);
}
