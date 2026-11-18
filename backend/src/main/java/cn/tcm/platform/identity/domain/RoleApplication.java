package cn.tcm.platform.identity.domain;

import java.time.LocalDateTime;

public class RoleApplication {
    public Long id;
    public Long userId;
    public Long roleId;
    public String username;
    public String statement;
    public String status;
    public Long reviewerId;
    public String reviewNote;
    public LocalDateTime reviewedAt;
    public LocalDateTime createdAt;
}
