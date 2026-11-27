package cn.tcm.platform.knowledge.api;

import cn.tcm.platform.common.ApiResponse;
import cn.tcm.platform.knowledge.application.KnowledgeProposals;
import cn.tcm.platform.knowledge.api.KnowledgeDtos.KnowledgeInput;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.web.bind.annotation.*;
import java.security.Principal;

@RestController @RequestMapping("/api/v1")
public class KnowledgeProposalController {
    private final KnowledgeProposals proposals;
    public KnowledgeProposalController(KnowledgeProposals proposals){this.proposals=proposals;}
    public record Create(@NotBlank String baseRevisionId,@NotNull @Valid KnowledgeInput knowledge){}
    public record Update(@NotNull @Min(0) Integer expectedVersion,@NotNull @Valid KnowledgeInput knowledge){}
    private static long actor(Principal p){return Long.parseLong(p.getName());}
    @PostMapping("/knowledge/{resource}/proposals") public ApiResponse<?> create(Principal p,@PathVariable long resource,@Valid @RequestBody Create input){return ApiResponse.ok(proposals.create(actor(p),resource,cn.tcm.platform.content.ContentSupport.id(input.baseRevisionId()),input.knowledge()));}
    @GetMapping("/knowledge-proposals/{id}") public ApiResponse<?> detail(Principal p,@PathVariable long id){return ApiResponse.ok(proposals.detail(actor(p),id));}
    @PatchMapping("/knowledge-proposals/{id}") public ApiResponse<?> update(Principal p,@PathVariable long id,@Valid @RequestBody Update input){return ApiResponse.ok(proposals.update(actor(p),id,input.expectedVersion(),input.knowledge()));}
    @PostMapping("/knowledge-proposals/{id}/submit") public ApiResponse<?> submit(Principal p,@PathVariable long id){return ApiResponse.ok(proposals.submit(actor(p),id));}
    @PostMapping("/knowledge-proposals/{id}/withdraw") public ApiResponse<?> withdraw(Principal p,@PathVariable long id){return ApiResponse.ok(proposals.withdraw(actor(p),id));}
}
