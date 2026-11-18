package cn.tcm.platform.identity.api;

import cn.tcm.platform.common.ApiResponse;
import cn.tcm.platform.identity.application.IdentityService;
import cn.tcm.platform.identity.infrastructure.SecurityProperties;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.web.bind.annotation.*;
import java.time.Duration;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {
    private final IdentityService service;
    private final SecurityProperties properties;
    public AuthController(IdentityService service,SecurityProperties properties) {this.service=service;this.properties=properties;}
    @PostMapping("/register") @ResponseStatus(org.springframework.http.HttpStatus.CREATED)
    public ApiResponse<?> register(@Valid @RequestBody IdentityDtos.Register input) { return ApiResponse.ok(service.register(input)); }
    @PostMapping("/login")
    public ApiResponse<?> login(@Valid @RequestBody IdentityDtos.Login input,HttpServletResponse response) {
        var issued=service.login(input);cookie(response,issued.refresh(),Duration.ofSeconds(issued.refreshMaxAgeSeconds()));return ApiResponse.ok(issued.body());
    }
    @PostMapping("/refresh")
    public ApiResponse<?> refresh(@CookieValue(name="tcm_refresh",required=false)String refresh,HttpServletResponse response) {
        try {var issued=service.refresh(refresh);cookie(response,issued.refresh(),Duration.ofSeconds(issued.refreshMaxAgeSeconds()));return ApiResponse.ok(issued.body());}
        catch(cn.tcm.platform.common.ApiException e){cookie(response,"",Duration.ZERO);throw e;}
    }
    @PostMapping("/logout")
    public ApiResponse<?> logout(@CookieValue(name="tcm_refresh",required=false)String refresh,HttpServletResponse response) {
        service.logout(refresh);cookie(response,"",Duration.ZERO);return ApiResponse.ok(null);
    }
    private void cookie(HttpServletResponse response,String value,Duration age) {
        response.addHeader(HttpHeaders.SET_COOKIE,ResponseCookie.from("tcm_refresh",value).httpOnly(true).secure(properties.cookieSecure())
                .sameSite("Strict").path("/api/v1/auth").maxAge(age).build().toString());
    }
}
