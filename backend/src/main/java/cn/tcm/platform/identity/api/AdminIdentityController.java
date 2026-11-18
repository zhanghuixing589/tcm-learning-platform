package cn.tcm.platform.identity.api;

import cn.tcm.platform.common.ApiResponse;
import cn.tcm.platform.identity.application.IdentityService;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.security.Principal;

@RestController
@RequestMapping("/api/v1/admin")
public class AdminIdentityController {
    private final IdentityService service;
    public AdminIdentityController(IdentityService service){this.service=service;}
    @GetMapping("/users") @PreAuthorize("hasAuthority('system:user:manage')")
    public ApiResponse<?> users(@RequestParam(defaultValue="1")int page,@RequestParam(defaultValue="20")int pageSize) {return ApiResponse.ok(service.users(page,pageSize));}
    @PatchMapping("/users/{id}/status") @PreAuthorize("hasAuthority('system:user:manage')")
    public ApiResponse<?> status(Principal actor,@PathVariable long id,@Valid @RequestBody IdentityDtos.Status input) {return ApiResponse.ok(service.status(Long.parseLong(actor.getName()),id,input));}
    @PutMapping("/users/{id}/roles") @PreAuthorize("hasAuthority('system:role:manage')")
    public ApiResponse<?> roles(Principal actor,@PathVariable long id,@Valid @RequestBody IdentityDtos.Roles input) {return ApiResponse.ok(service.roles(Long.parseLong(actor.getName()),id,input));}
    @GetMapping("/role-applications") @PreAuthorize("hasAuthority('system:user:manage')")
    public ApiResponse<?> applications(@RequestParam(defaultValue="1")int page,@RequestParam(defaultValue="20")int pageSize) {return ApiResponse.ok(service.applications(null,page,pageSize));}
    @PatchMapping("/role-applications/{id}") @PreAuthorize("hasAuthority('system:user:manage')")
    public ApiResponse<?> decide(Principal actor,@PathVariable long id,@Valid @RequestBody IdentityDtos.Decision input) {return ApiResponse.ok(service.decide(Long.parseLong(actor.getName()),id,input));}
}
