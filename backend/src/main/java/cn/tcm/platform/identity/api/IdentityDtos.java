package cn.tcm.platform.identity.api;

import jakarta.validation.constraints.*;
import java.util.List;
import java.util.Set;

public final class IdentityDtos {
    private IdentityDtos() {}
    public record Register(@NotBlank @Pattern(regexp="[A-Za-z0-9_]{3,64}") String username,
                           @NotBlank @Size(min=10,max=72) String password,@NotBlank @Size(max=80) String nickname) {}
    public record Login(@NotBlank @Size(max=64) String username,@NotBlank @Size(max=72) String password) {}
    public record Profile(@NotBlank @Size(max=80) String nickname,@Size(max=500) String bio) {}
    public record Application(@NotBlank @Pattern(regexp="CONTRIBUTOR") String roleCode,@NotBlank @Size(max=2000) String statement) {}
    public record Decision(@NotBlank @Pattern(regexp="APPROVED|REJECTED") String decision,@NotBlank @Size(max=2000) String note) {}
    public record Status(@NotBlank @Pattern(regexp="ACTIVE|DISABLED") String status) {}
    public record Roles(@NotEmpty @Size(max=4) Set<@NotNull @Pattern(regexp="LEARNER|CONTRIBUTOR|REVIEWER|ADMIN") String> roles) {}
    public record UserView(String id,String username,String nickname,String bio,String status,List<String> roles,List<String> permissions,String createdAt) {}
    public record ApplicationView(String id,String userId,String username,String roleCode,String statement,String status,String reviewerId,String reviewNote,String reviewedAt,String createdAt) {}
    public record AuthView(String accessToken,long expiresIn,UserView user) {}
}
