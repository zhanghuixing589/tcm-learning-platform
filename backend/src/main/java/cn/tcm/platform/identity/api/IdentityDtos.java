package cn.tcm.platform.identity.api;

import jakarta.validation.constraints.*;
import cn.tcm.platform.identity.domain.PasswordPolicy;
import java.util.List;
import java.util.Set;

public final class IdentityDtos {
    private IdentityDtos() {}
//    注册
    public record Register(@NotBlank @Pattern(regexp="[A-Za-z0-9_]{3,64}") String username,
                           @NotBlank(message=PasswordPolicy.MESSAGE)
                           @Size(min=PasswordPolicy.MIN_LENGTH,max=PasswordPolicy.MAX_LENGTH,message=PasswordPolicy.MESSAGE)
                           @Pattern(regexp=PasswordPolicy.REGEX,message=PasswordPolicy.MESSAGE) String password,
                           @NotBlank @Size(max=80) String nickname) {}
//    登录
    public record Login(@NotBlank @Size(max=64) String username,@NotBlank @Size(max=72) String password) {}
//    修改资料
    public record Profile(@NotBlank @Size(max=80) String nickname,@Size(max=500) String bio) {}
//    提交申请
    public record Application(@NotBlank @Pattern(regexp="CONTRIBUTOR") String roleCode,@NotBlank @Size(max=2000) String statement) {}
//    审核申请
    public record Decision(@NotBlank @Pattern(regexp="APPROVED|REJECTED") String decision,@NotBlank @Size(max=2000) String note) {}
//    修改状态
    public record Status(@NotBlank @Pattern(regexp="ACTIVE|DISABLED") String status) {}
//    修改角色
    public record Roles(@NotEmpty @Size(max=4) Set<@NotNull @Pattern(regexp="LEARNER|CONTRIBUTOR|REVIEWER|ADMIN") String> roles) {}
//    封装放回给前端的用户信息
    public record UserView(String id,String username,String nickname,String bio,String status,List<String> roles,List<String> permissions,String createdAt) {}
//    封装放回给前端的申请信息
    public record ApplicationView(String id,String userId,String username,String roleCode,String statement,String status,String reviewerId,String reviewNote,String reviewedAt,String createdAt) {}
//    封装放回给前端的认证信息
    public record AuthView(String accessToken,long expiresIn,UserView user) {}
}
