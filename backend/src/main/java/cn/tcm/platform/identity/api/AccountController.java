package cn.tcm.platform.identity.api;

import cn.tcm.platform.common.ApiResponse;
import cn.tcm.platform.identity.application.IdentityService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import java.security.Principal;

@RestController
@RequestMapping("/api/v1/me")
public class AccountController {
    private final IdentityService service;
    public AccountController(IdentityService service) {this.service=service;}
    @GetMapping public ApiResponse<?> me(Principal user) {return ApiResponse.ok(service.me(Long.parseLong(user.getName())));}
    @PatchMapping public ApiResponse<?> profile(Principal user,@Valid @RequestBody IdentityDtos.Profile input) {return ApiResponse.ok(service.profile(Long.parseLong(user.getName()),input));}
    @GetMapping("/role-applications")
    public ApiResponse<?> applications(Principal user,@RequestParam(defaultValue="1")int page,@RequestParam(defaultValue="20")int pageSize) {
        return ApiResponse.ok(service.applications(Long.parseLong(user.getName()),page,pageSize));
    }
    @PostMapping("/role-applications") @ResponseStatus(org.springframework.http.HttpStatus.CREATED)
    public ApiResponse<?> apply(Principal user,@Valid @RequestBody IdentityDtos.Application input) {return ApiResponse.ok(service.apply(Long.parseLong(user.getName()),input));}
}
