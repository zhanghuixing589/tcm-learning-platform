package cn.tcm.platform.knowledge.api;

import cn.tcm.platform.common.ApiResponse;
import cn.tcm.platform.knowledge.application.KnowledgeService;
import cn.tcm.platform.knowledge.api.KnowledgeDtos.*;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import java.security.Principal;

@RestController @RequestMapping("/api/v1")
public class KnowledgeController {
    private final KnowledgeService service;
    public KnowledgeController(KnowledgeService service){this.service=service;}
    private static long actor(Principal p){return Long.parseLong(p.getName());}
    @GetMapping("/knowledge/categories") public ApiResponse<?> categories(){return ApiResponse.ok(service.categories());}
    @GetMapping("/knowledge") public ApiResponse<?> search(Principal p,@RequestParam(defaultValue="1")int page,@RequestParam(defaultValue="20")int pageSize,@RequestParam(required=false)String q,@RequestParam(required=false)String type,@RequestParam(required=false)String categoryId){return ApiResponse.ok(service.search(actor(p),false,page,pageSize,q,type,categoryId,null));}
    @GetMapping("/contributions/knowledge") public ApiResponse<?> contributions(Principal p,@RequestParam(defaultValue="1")int page,@RequestParam(defaultValue="20")int pageSize,@RequestParam(required=false)String q,@RequestParam(required=false)String state){return ApiResponse.ok(service.search(actor(p),true,page,pageSize,q,null,null,state));}
    @PostMapping("/knowledge") @ResponseStatus(HttpStatus.CREATED) public ApiResponse<?> create(Principal p,@Valid @RequestBody KnowledgeInput input){return ApiResponse.ok(service.create(actor(p),input));}
    @GetMapping("/knowledge/{id}") public ApiResponse<?> detail(Principal p,@PathVariable long id){return ApiResponse.ok(service.published(actor(p),id));}
    @GetMapping("/knowledge/{id}/relations") public ApiResponse<?> relations(Principal p,@PathVariable long id){return ApiResponse.ok(service.publishedView(actor(p),id).get("relations"));}
    @GetMapping("/knowledge/{id}/draft") public ApiResponse<?> draft(Principal p,@PathVariable long id){return ApiResponse.ok(service.draft(actor(p),id));}
    @PatchMapping("/knowledge/{id}/draft") public ApiResponse<?> update(Principal p,@PathVariable long id,@Valid @RequestBody KnowledgeInput input){return ApiResponse.ok(service.update(actor(p),id,input));}
    @PostMapping("/knowledge/{id}/revise") public ApiResponse<?> revise(Principal p,@PathVariable long id){return ApiResponse.ok(service.revise(actor(p),id));}
    @GetMapping("/knowledge/{id}/versions") public ApiResponse<?> versions(Principal p,@PathVariable long id){return ApiResponse.ok(service.versions(actor(p),id));}
    @GetMapping("/knowledge/{id}/versions/{revisionId}") public ApiResponse<?> version(Principal p,@PathVariable long id,@PathVariable long revisionId){return ApiResponse.ok(service.version(actor(p),id,revisionId));}
    @PostMapping("/revisions/{revisionId}/submit") public ApiResponse<?> submit(Principal p,@PathVariable long revisionId){return ApiResponse.ok(service.submit(actor(p),revisionId));}
    @PostMapping("/revisions/{revisionId}/withdraw") public ApiResponse<?> withdraw(Principal p,@PathVariable long revisionId){return ApiResponse.ok(service.withdraw(actor(p),revisionId));}
    @PostMapping("/knowledge/import") public ApiResponse<?> imports(Principal p,@Valid @RequestBody ImportInput input){return ApiResponse.ok(service.imports(actor(p),input));}
    @PostMapping("/knowledge/extractions") public ApiResponse<?> extract(Principal p,@Valid @RequestBody ExtractionInput input){return ApiResponse.ok(service.extract(actor(p),input));}
    @PostMapping("/knowledge/{id}/suspend") public ApiResponse<?> suspend(Principal p,@PathVariable long id,@Valid @RequestBody Reason input){return ApiResponse.ok(service.suspend(actor(p),id,input.reason()));}
    @PostMapping("/resources/{id}/feedback") public ApiResponse<?> feedback(Principal p,@PathVariable long id,@Valid @RequestBody FeedbackInput input){return ApiResponse.ok(service.feedback(actor(p),id,input.content()));}
    @GetMapping("/contributions/feedback") public ApiResponse<?> feedbacks(Principal p,@RequestParam(defaultValue="1")int page,@RequestParam(defaultValue="20")int pageSize,@RequestParam(defaultValue="ALL")String status){return ApiResponse.ok(service.feedbacks(actor(p),page,pageSize,status));}
    @PatchMapping("/feedback/{id}") public ApiResponse<?> resolve(Principal p,@PathVariable long id,@Valid @RequestBody FeedbackResolution input){return ApiResponse.ok(service.resolveFeedback(actor(p),id,input));}
    @PostMapping("/formulas/compare") public ApiResponse<?> compare(Principal p,@Valid @RequestBody CompareInput input){return ApiResponse.ok(service.compare(actor(p),input));}
}
