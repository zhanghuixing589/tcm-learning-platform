package cn.tcm.platform.identity.domain;

import java.time.LocalDateTime;

public class RefreshToken {
    public Long id;
    public Long userId;
    public String tokenHash;
    public String familyKey;
    public LocalDateTime expiresAt;
    public LocalDateTime absoluteExpiresAt;
    public LocalDateTime revokedAt;
    public Long replacedById;
}
