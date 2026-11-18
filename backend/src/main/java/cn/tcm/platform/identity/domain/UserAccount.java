package cn.tcm.platform.identity.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

@TableName("sys_users")
public class UserAccount {
    @TableId(type=IdType.AUTO) public Long id;
    public String username;
    public String passwordHash;
    public String nickname;
    public String avatarPath;
    public String bio;
    public String status;
    public Integer credentialVersion;
    public LocalDateTime lastLoginAt;
    public LocalDateTime createdAt;
    public LocalDateTime updatedAt;
}
