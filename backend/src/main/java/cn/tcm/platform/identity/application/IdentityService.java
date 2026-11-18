package cn.tcm.platform.identity.application;

import cn.tcm.platform.common.*;
import cn.tcm.platform.identity.api.IdentityDtos.*;
import cn.tcm.platform.identity.domain.*;
import cn.tcm.platform.identity.infrastructure.*;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

@Service
public class IdentityService {
    private final IdentityMapper mapper;
    private final PasswordEncoder passwords;
    private final TokenService tokens;
    private final SecurityProperties properties;
    private final String unknownUserHash;
    public record Issued(AuthView body,String refresh) {}
    public IdentityService(IdentityMapper mapper,PasswordEncoder passwords,TokenService tokens,SecurityProperties properties) {
        this.mapper=mapper;this.passwords=passwords;this.tokens=tokens;this.properties=properties;
        this.unknownUserHash=passwords.encode(UUID.randomUUID().toString());
    }
    private static String utc(LocalDateTime time) { return time==null?null:time.toInstant(ZoneOffset.UTC).toString(); }
    private void audit(long actor,String action,String type,long id) {
        String trace=org.slf4j.MDC.get("traceId");
        mapper.audit(actor,trace==null?UUID.randomUUID().toString():trace,action,type,Long.toString(id));
    }
    public UserView view(UserAccount user) {
        return new UserView(user.id.toString(),user.username,user.nickname,user.bio,user.status,
                mapper.roles(user.id),mapper.permissions(user.id),utc(user.createdAt));
    }
    public UserView me(long id) { UserAccount u=mapper.selectById(id);if(u==null)throw ApiException.missing();return view(u); }
    @Transactional public UserView register(Register input) {
        if(input.password().getBytes(StandardCharsets.UTF_8).length>72) throw ApiException.invalid("密码UTF-8编码不能超过72字节");
        if(mapper.byUsername(input.username())!=null) throw ApiException.conflict("用户名已被使用");
        UserAccount user=new UserAccount();user.username=input.username();user.passwordHash=passwords.encode(input.password());
        user.nickname=input.nickname().trim();user.status="ACTIVE";user.credentialVersion=0;
        mapper.insert(user);mapper.grant(user.id,"LEARNER",null);return me(user.id);
    }
    @Transactional public Issued login(Login input) {
        UserAccount found=mapper.byUsername(input.username());
        // Equal BCrypt work for unknown users limits account enumeration by timing.
        String encoded=found==null?unknownUserHash:found.passwordHash;
        boolean matches=passwords.matches(input.password(),encoded);
        if(found==null || !matches) throw new ApiException(401,"UNAUTHENTICATED","用户名或密码不正确，或账号已停用");
        UserAccount user=mapper.lockUser(found.id);
        if(!"ACTIVE".equals(user.status)) throw new ApiException(401,"UNAUTHENTICATED","用户名或密码不正确，或账号已停用");
        mapper.loginTime(user.id);
        return issue(user,UUID.randomUUID().toString());
    }
    private Issued issue(UserAccount user,String family) {
        String raw=tokens.refresh();RefreshToken token=new RefreshToken();
        token.userId=user.id;token.tokenHash=tokens.hash(raw);token.familyKey=family;
        token.expiresAt=LocalDateTime.now(ZoneOffset.UTC).plusDays(properties.refreshDays());mapper.addToken(token);
        return new Issued(new AuthView(tokens.access(user,family),properties.accessSeconds(),view(user)),raw);
    }
    @Transactional(noRollbackFor=ApiException.class)
    public Issued refresh(String raw) {
        if(raw==null || raw.length()>200) throw ApiException.unauthorized();
        RefreshToken found=mapper.token(tokens.hash(raw));if(found==null)throw ApiException.unauthorized();
        UserAccount user=mapper.lockUser(found.userId);
        RefreshToken previous=mapper.lockToken(found.tokenHash);
        if(previous.revokedAt!=null) {mapper.revokeFamily(previous.familyKey);throw ApiException.unauthorized();}
        if(!"ACTIVE".equals(user.status) || !previous.expiresAt.isAfter(LocalDateTime.now(ZoneOffset.UTC))) {
            mapper.revokeFamily(previous.familyKey);throw ApiException.unauthorized();
        }
        Issued next=issue(user,previous.familyKey);
        mapper.rotate(previous.id,mapper.token(tokens.hash(next.refresh())).id);
        return next;
    }
    @Transactional public void logout(String raw) {
        if(raw==null || raw.length()>200)return;
        RefreshToken token=mapper.token(tokens.hash(raw));
        if(token!=null) {mapper.lockUser(token.userId);mapper.revokeFamily(token.familyKey);}
    }
    @Transactional public UserView profile(long id,Profile input) {mapper.profile(id,input.nickname().trim(),input.bio());return me(id);}
    public PageResult<UserView> users(int page,int size) {
        int offset=PageResult.offset(page,size);return new PageResult<>(page,size,mapper.userCount(),mapper.users(size,offset).stream().map(this::view).toList());
    }
    @Transactional public UserView status(long actor,long id,Status input) {
        if(actor==id) throw ApiException.conflict("不能在此禁用自己的账号");
        UserAccount user=mapper.lockUser(id);if(user==null)throw ApiException.missing();
        if(!input.status().equals(user.status)) {mapper.status(id,input.status());mapper.revokeUser(id);audit(actor,"USER_STATUS_CHANGED","USER",id);}
        return me(id);
    }
    @Transactional public UserView roles(long actor,long id,Roles input) {
        if(actor==id)throw ApiException.conflict("不能在此修改自己的管理角色");
        if(mapper.lockUser(id)==null)throw ApiException.missing();
        mapper.clearRoles(id);mapper.grant(id,"LEARNER",actor);
        for(String code:input.roles())mapper.grant(id,code,actor);
        audit(actor,"USER_ROLES_CHANGED","USER",id);
        return me(id);
    }
    private ApplicationView applicationView(RoleApplication a) {
        return new ApplicationView(a.id.toString(),a.userId.toString(),a.username,"CONTRIBUTOR",a.statement,a.status,
                a.reviewerId==null?null:a.reviewerId.toString(),a.reviewNote,utc(a.reviewedAt),utc(a.createdAt));
    }
    @Transactional public ApplicationView apply(long user,Application input) {
        mapper.lockUser(user);
        if(!"CONTRIBUTOR".equals(input.roleCode()))throw ApiException.invalid("仅可申请贡献资格");
        if(mapper.roles(user).contains("CONTRIBUTOR"))throw ApiException.conflict("已具备贡献资格");
        if(mapper.pending(user)>0)throw ApiException.conflict("已有待处理申请");
        RoleApplication a=new RoleApplication();a.userId=user;a.statement=input.statement().trim();mapper.apply(a);
        return applicationView(mapper.application(a.id));
    }
    public PageResult<ApplicationView> applications(Long user,int page,int size) {
        int offset=PageResult.offset(page,size);
        return new PageResult<>(page,size,mapper.applicationCount(user),mapper.applications(user,size,offset).stream().map(this::applicationView).toList());
    }
    @Transactional public ApplicationView decide(long actor,long id,Decision input) {
        RoleApplication found=mapper.application(id);if(found==null)throw ApiException.missing();
        mapper.lockUser(found.userId);RoleApplication a=mapper.lockApplication(id);
        if(actor==a.userId)throw ApiException.conflict("不能审核自己的申请");
        if(!"PENDING".equals(a.status))throw ApiException.conflict("申请已处理，请刷新列表");
        a.status=input.decision();a.reviewerId=actor;a.reviewNote=input.note().trim();mapper.decision(a);
        if("APPROVED".equals(a.status))mapper.grant(a.userId,"CONTRIBUTOR",actor);
        audit(actor,"ROLE_APPLICATION_"+a.status,"ROLE_APPLICATION",id);
        return applicationView(mapper.application(id));
    }
}
