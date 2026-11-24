package cn.tcm.platform.community.api;
import cn.tcm.platform.community.application.CommunityPolicyService;
import cn.tcm.platform.common.ApiResponse;
import cn.tcm.platform.content.ContentSupport;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import java.security.Principal;
@RestController @RequestMapping("/api/v1")
public class CommunityPolicyController {
 private final CommunityPolicyService service;
 public CommunityPolicyController(CommunityPolicyService service){this.service=service;}
 public record CreateInput(@NotNull JsonNode config,@NotBlank String reason){}
 public record ActivateInput(@NotNull @Min(0) Integer lockVersion,@NotBlank String policyId,@NotBlank String reason){}
 @GetMapping("/community/text-policy") public ApiResponse<?> current(){return ApiResponse.ok(service.publicPolicy());}
 @GetMapping("/admin/community/text-policies") public ApiResponse<?> list(Principal actor,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int pageSize){return ApiResponse.ok(service.list(Long.parseLong(actor.getName()),page,pageSize));}
 @PostMapping("/admin/community/text-policies") @ResponseStatus(HttpStatus.CREATED) public ApiResponse<?> create(Principal actor,@RequestHeader("Idempotency-Key") String key,@Valid @RequestBody CreateInput input){return ApiResponse.ok(service.create(Long.parseLong(actor.getName()),key,input.config(),input.reason()));}
 @PutMapping("/admin/community/text-policy-head") public ApiResponse<?> activate(Principal actor,@RequestHeader("Idempotency-Key") String key,@Valid @RequestBody ActivateInput input){return ApiResponse.ok(service.activate(Long.parseLong(actor.getName()),key,ContentSupport.id(input.policyId()),input.lockVersion(),input.reason()));}
}
