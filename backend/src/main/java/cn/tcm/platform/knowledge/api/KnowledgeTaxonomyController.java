package cn.tcm.platform.knowledge.api;

import cn.tcm.platform.common.ApiResponse;
import cn.tcm.platform.knowledge.application.KnowledgeTaxonomy;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import java.security.Principal;

@RestController @RequestMapping("/api/v1")
public class KnowledgeTaxonomyController {
    private final KnowledgeTaxonomy taxonomy;
    public KnowledgeTaxonomyController(KnowledgeTaxonomy taxonomy){this.taxonomy=taxonomy;}
    public record DirectoryCreate(@NotBlank String type,@NotBlank @Size(max=80) String name,@Min(0) @Max(10000) int sortOrder,@NotNull Boolean active){}
    public record DirectoryEdit(@NotBlank @Size(max=80) String name,@Min(0) @Max(10000) int sortOrder,@NotNull Boolean active,@NotNull @Min(0) Integer lockVersion){}
    public record TagCreate(@NotBlank @Size(max=64) String name,@NotNull Boolean active){}
    public record TagEdit(@NotBlank @Size(max=64) String name,@NotNull Boolean active,@NotNull @Min(0) Integer lockVersion){}
    private long actor(Principal p){return Long.parseLong(p.getName());}
    @GetMapping("/knowledge/tags") public ApiResponse<?> tags(){return ApiResponse.ok(taxonomy.tags());}
    @GetMapping("/admin/knowledge/categories") public ApiResponse<?> directories(Principal p){return ApiResponse.ok(taxonomy.adminDirectories(actor(p)));}
    @PostMapping("/admin/knowledge/categories") @ResponseStatus(HttpStatus.CREATED) public ApiResponse<?> createDirectory(Principal p,@Valid @RequestBody DirectoryCreate in){return ApiResponse.ok(taxonomy.createDirectory(actor(p),in.type(),in.name(),in.sortOrder(),in.active()));}
    @PatchMapping("/admin/knowledge/categories/{id}") public ApiResponse<?> updateDirectory(Principal p,@PathVariable long id,@Valid @RequestBody DirectoryEdit in){return ApiResponse.ok(taxonomy.updateDirectory(actor(p),id,in.name(),in.sortOrder(),in.active(),in.lockVersion()));}
    @GetMapping("/admin/knowledge/tags") public ApiResponse<?> adminTags(Principal p){return ApiResponse.ok(taxonomy.adminTags(actor(p)));}
    @PostMapping("/admin/knowledge/tags") @ResponseStatus(HttpStatus.CREATED) public ApiResponse<?> createTag(Principal p,@Valid @RequestBody TagCreate in){return ApiResponse.ok(taxonomy.createTag(actor(p),in.name(),in.active()));}
    @PatchMapping("/admin/knowledge/tags/{id}") public ApiResponse<?> updateTag(Principal p,@PathVariable long id,@Valid @RequestBody TagEdit in){return ApiResponse.ok(taxonomy.updateTag(actor(p),id,in.name(),in.active(),in.lockVersion()));}
}
