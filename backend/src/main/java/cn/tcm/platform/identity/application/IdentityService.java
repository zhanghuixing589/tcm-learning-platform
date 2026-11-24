package cn.tcm.platform.identity.application;
//应用服务
import cn.tcm.platform.common.*;
import cn.tcm.platform.community.infrastructure.CommunitySectionRepository;
import cn.tcm.platform.identity.api.IdentityDtos.*;
import cn.tcm.platform.identity.domain.*;
import cn.tcm.platform.identity.infrastructure.*;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.Clock;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
//身份与权限相关服务：注册、登录、登出、刷新令牌、修改资料、用户管理、角色申请与审批
@Service
public class IdentityService {
    private final IdentityMapper mapper; //DB
    private final PasswordEncoder passwords; //密码加密
    private final TokenService tokens; //令牌服务
    private final SecurityProperties properties; //安全属性
    private final Clock clock;
    private final CommunitySectionRepository community;
    private final String unknownUserHash; //未知用户哈希
    public record Issued(AuthView body,String refresh,long refreshMaxAgeSeconds) {}
    public IdentityService(IdentityMapper mapper,PasswordEncoder passwords,TokenService tokens,SecurityProperties properties,Clock clock,CommunitySectionRepository community) {
        this.mapper=mapper;this.passwords=passwords;this.tokens=tokens;this.properties=properties;
        this.clock=clock;
        this.community=community;
        this.unknownUserHash=passwords.encode(UUID.randomUUID().toString());
    }
//    把LocalDateTime转换为UTC字符串，如果为null则返回null 这样避免time的空指针 补全时区信息
    private static String utc(LocalDateTime time) { return time==null?null:time.toInstant(ZoneOffset.UTC).toString(); }
//   审计日志记录方法，记录操作的执行者、操作类型、对象类型和对象ID，并生成traceId
    private void audit(long actor,String action,String type,long id) {
        String trace=org.slf4j.MDC.get("traceId");//从MDC中获取当前请求traceId,方便排查问题
//        如果没有traceId则随机生成一个新的UUID作为traceId
        mapper.audit(actor,trace==null?UUID.randomUUID().toString():trace,action,type,Long.toString(id));
    }
//    封装用户信息，放回给前端
    public UserView view(UserAccount user) {
        return new UserView(user.id.toString(),user.username,user.nickname,user.bio,user.status,
                mapper.roles(user.id),mapper.permissions(user.id),utc(user.createdAt));
    }
//    查找用户
    public UserView me(long id) { UserAccount u=mapper.selectById(id);if(u==null)throw ApiException.missing();return view(u); }
//    注册用户
    @Transactional public UserView register(Register input) {
        // 业务规则：10–12位ASCII可见字符，含大写、小写及数字或特殊符号。
        if(!PasswordPolicy.isValid(input.password())) throw ApiException.invalid(PasswordPolicy.MESSAGE);
        // BCrypt底层字节上限的防御性检查，与上面的业务规则分开。
        if(input.password().getBytes(StandardCharsets.UTF_8).length>72) throw ApiException.invalid("密码UTF-8编码不能超过72字节");
        if(mapper.byUsername(input.username())!=null) throw ApiException.conflict("用户名已被使用");
        UserAccount user=new UserAccount();user.username=input.username();user.passwordHash=passwords.encode(input.password());
        user.nickname=input.nickname().trim();user.status="ACTIVE";user.credentialVersion=0;
        mapper.insert(user);mapper.grant(user.id,"LEARNER",null);return me(user.id);
    }
//    登录 BCrypt的密码验证是恒定耗时的，即使用户不存在也会执行一次完整的BCrypt校验，这样可以防止时间攻击。登录成功后，会锁定用户行，更新登录时间，并生成访问令牌和刷新令牌。
    @Transactional public Issued login(Login input) {
        UserAccount found=mapper.byUsername(input.username());
        // 恒定耗时匹配 用户不存在时，用一个预先生成的固定 BCrypt 哈希代替
        String encoded=found==null?unknownUserHash:found.passwordHash;
        boolean matches=passwords.matches(input.password(),encoded);//执行一次完整的 BCrypt 校验（无论用户是否存在）
        if(found==null || !matches) throw new ApiException(401,"UNAUTHENTICATED","用户名或密码不正确，或账号已停用");
        UserAccount user=mapper.lockUser(found.id);//用 SELECT FOR UPDATE 锁住该行，防止并发登录/改密竞争
        if(!"ACTIVE".equals(user.status)) throw new ApiException(401,"UNAUTHENTICATED","用户名或密码不正确，或账号已停用");
        mapper.loginTime(user.id);
        return issue(user,UUID.randomUUID().toString(),LocalDateTime.now(clock).plusDays(properties.sessionAbsoluteDays()));
    }
//    访问令牌是签名JWT，刷新令牌是随机字符串；两者均不能超过本次登录的绝对期限。
    private Issued issue(UserAccount user,String family,LocalDateTime absoluteExpiresAt) {
        var now=clock.instant();
        var deadline=absoluteExpiresAt.toInstant(ZoneOffset.UTC);
        var accessExpiry=now.plusSeconds(properties.accessSeconds());
        if(accessExpiry.isAfter(deadline)) accessExpiry=deadline;
        accessExpiry=accessExpiry.truncatedTo(ChronoUnit.SECONDS);
        if(!accessExpiry.isAfter(now)) throw ApiException.unauthorized();
        String raw=tokens.refresh();RefreshToken token=new RefreshToken();
        token.userId=user.id;
        token.tokenHash=tokens.hash(raw);//将刷新令牌进行哈希处理，存储在数据库中，避免明文存储
        token.familyKey=family;//家族键，用于刷新令牌时锁定用户行，防止并发刷新，关联同一登录会话
        token.absoluteExpiresAt=absoluteExpiresAt; //首次登录确定，刷新时原样继承，绝不重新加30天
        token.expiresAt=LocalDateTime.ofInstant(now,ZoneOffset.UTC).plusDays(properties.refreshDays());
        if(token.expiresAt.isAfter(absoluteExpiresAt)) token.expiresAt=absoluteExpiresAt;
        mapper.addToken(token);
        long accessSeconds=Duration.between(now.truncatedTo(ChronoUnit.SECONDS),accessExpiry).getSeconds();
        long refreshSeconds=Duration.between(now,token.expiresAt.toInstant(ZoneOffset.UTC)).getSeconds();
        return new Issued(new AuthView(tokens.access(user,family,now,accessExpiry),accessSeconds,view(user)),raw,refreshSeconds);//原始刷新令牌只返回一次
    }
//    刷新令牌 access token（JWT）有效期15分钟，refresh token7天
    @Transactional(noRollbackFor=ApiException.class)
    public Issued refresh(String raw) {
        if(raw==null || raw.length()>200) throw ApiException.unauthorized();
        RefreshToken found=mapper.token(tokens.hash(raw));if(found==null)throw ApiException.unauthorized();
        UserAccount user=mapper.lockUser(found.userId);
        RefreshToken previous=mapper.lockToken(found.tokenHash);
        if(previous.revokedAt!=null) {mapper.revokeFamily(previous.familyKey);throw ApiException.unauthorized();}
        var now=LocalDateTime.now(clock);
        if(!"ACTIVE".equals(user.status) || !previous.expiresAt.isAfter(now) || !previous.absoluteExpiresAt.isAfter(now)) {
            mapper.revokeFamily(previous.familyKey);throw ApiException.unauthorized();
        }
        Issued next=issue(user,previous.familyKey,previous.absoluteExpiresAt);
        mapper.rotate(previous.id,mapper.token(tokens.hash(next.refresh())).id);
        return next;
    }
//    登出
    @Transactional public void logout(String raw) {
        if(raw==null || raw.length()>200)return;
        RefreshToken token=mapper.token(tokens.hash(raw));
        if(token!=null) {mapper.lockUser(token.userId);mapper.revokeFamily(token.familyKey);}
    }
//    修改资料
    @Transactional public UserView profile(long id,Profile input) {mapper.profile(id,input.nickname().trim(),input.bio());return me(id);}
//    用户列表
    public PageResult<UserView> users(int page,int size) {
        int offset=PageResult.offset(page,size);return new PageResult<>(page,size,mapper.userCount(),mapper.users(size,offset).stream().map(this::view).toList());
//    状态修改
    }
    @Transactional public UserView status(long actor,long id,Status input) {
        if(actor==id) throw ApiException.conflict("不能在此禁用自己的账号");
        UserAccount user=lockManagementTarget(actor,id,"system:user:manage");
        if(!input.status().equals(user.status)) {
            if("DISABLED".equals(input.status())) community.removeDisabledAccount(actor,id);
            mapper.status(id,input.status());
            mapper.revokeUser(id);
            audit(actor,"USER_STATUS_CHANGED","USER",id);
        }
        return me(id);
//    角色修改
    }
    @Transactional public UserView roles(long actor,long id,Roles input) {
        if(actor==id)throw ApiException.conflict("不能在此修改自己的管理角色");
        lockManagementTarget(actor,id,"system:role:manage");
        mapper.clearRoles(id);mapper.grant(id,"LEARNER",actor);
        for(String code:input.roles())mapper.grant(id,code,actor);
//    角色申请
        audit(actor,"USER_ROLES_CHANGED","USER",id);
        return me(id);
    }
    private UserAccount lockManagementTarget(long actor,long target,String permission) {
        UserAccount first=mapper.lockUser(Math.min(actor,target));
        UserAccount second=mapper.lockUser(Math.max(actor,target));
        UserAccount operator=actor<target?first:second;
        if(operator==null||!"ACTIVE".equals(operator.status))throw ApiException.unauthorized();
        if(!mapper.permissions(actor).contains(permission))throw new ApiException(403,"FORBIDDEN","没有执行此操作的权限");
        UserAccount subject=actor<target?second:first;
        if(subject==null)throw ApiException.missing();
        return subject;
    }
//    角色申请列表
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
//    申请详情
    public PageResult<ApplicationView> applications(Long user,int page,int size) {
        int offset=PageResult.offset(page,size);
        return new PageResult<>(page,size,mapper.applicationCount(user),mapper.applications(user,size,offset).stream().map(this::applicationView).toList());
    }
    //    申请审核
    @Transactional(isolation=Isolation.READ_COMMITTED)
    public ApplicationView decide(long actor,long id,Decision input) {
        RoleApplication found=mapper.application(id);if(found==null)throw ApiException.missing();
        if(actor==found.userId)throw ApiException.conflict("不能审核自己的申请");
        lockManagementTarget(actor,found.userId,"system:user:manage");
        RoleApplication a=mapper.lockApplication(id);
        if(a==null)throw ApiException.missing();
        if(!found.userId.equals(a.userId))throw ApiException.conflict("申请归属已变化，请刷新");
        if(!"PENDING".equals(a.status))throw ApiException.conflict("申请已处理，请刷新列表");
        a.status=input.decision();a.reviewerId=actor;a.reviewNote=input.note().trim();mapper.decision(a);
        if("APPROVED".equals(a.status))mapper.grant(a.userId,"CONTRIBUTOR",actor);
        audit(actor,"ROLE_APPLICATION_"+a.status,"ROLE_APPLICATION",id);
        return applicationView(mapper.application(id));
    }
}
