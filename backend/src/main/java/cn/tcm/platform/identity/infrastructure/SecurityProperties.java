package cn.tcm.platform.identity.infrastructure;

import jakarta.validation.constraints.*;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;
import java.util.List;

@Validated
@ConfigurationProperties("tcm.security")
public record SecurityProperties(@NotBlank @Size(min=32) String jwtSecret,@NotEmpty List<String> allowedOrigins,
                                 boolean cookieSecure,@Min(60) @Max(3600) long accessSeconds,@Min(1) @Max(30) long refreshDays,
                                 @Min(1) @Max(30) long sessionAbsoluteDays) {}
