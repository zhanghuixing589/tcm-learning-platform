package cn.tcm.platform.account.api;

import cn.tcm.platform.account.application.*;
import cn.tcm.platform.common.ApiResponse;
import cn.tcm.platform.community.application.CommunityArticleService;
import cn.tcm.platform.community.api.CommunityArticleDtos.RevisionLock;
import jakarta.validation.Valid;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.security.Principal;

@RestController @RequestMapping("/api/v1")
public class PersonalCenterController {
    private final PersonalCenterService service;
    private final AvatarService avatars;
    private final CommunityArticleService articles;
    public PersonalCenterController(PersonalCenterService service, AvatarService avatars, CommunityArticleService articles) {
        this.service = service;
        this.avatars = avatars;
        this.articles = articles;
    }
    private static long id(Principal principal) {return Long.parseLong(principal.getName());}
    @GetMapping("/me/profile-summary") public ApiResponse<?> summary(Principal p){return ApiResponse.ok(service.summary(id(p),id(p)));}
    @GetMapping("/users/{userId}/profile") public ApiResponse<?> profile(Principal p,@PathVariable long userId){return ApiResponse.ok(service.summary(id(p),userId));}
    @PutMapping("/users/{userId}/follow") public ApiResponse<?> follow(Principal p,@PathVariable long userId){return ApiResponse.ok(service.follow(id(p),userId,true));}
    @DeleteMapping("/users/{userId}/follow") public ApiResponse<?> unfollow(Principal p,@PathVariable long userId){return ApiResponse.ok(service.follow(id(p),userId,false));}
    @GetMapping("/me/{kind:following|followers}") public ApiResponse<?> relationships(Principal p,@PathVariable String kind,@RequestParam(defaultValue="1")int page,@RequestParam(defaultValue="20")int pageSize,@RequestParam(required=false)String q){return ApiResponse.ok(service.relationships(id(p),kind.equals("followers"),page,pageSize,q));}
    @GetMapping("/me/{kind:bookmarks|history|publications|likes|received-likes}")
    public ApiResponse<?> contents(Principal p,@PathVariable String kind,@RequestParam(defaultValue="1")int page,@RequestParam(defaultValue="20")int pageSize,@RequestParam(required=false)String q,@RequestParam(required=false)String type,@RequestParam(required=false)String status){return ApiResponse.ok(service.contents(id(p),id(p),kind,page,pageSize,q,type,status));}
    @GetMapping("/users/{userId}/publications")
    public ApiResponse<?> publications(Principal p,@PathVariable long userId,@RequestParam(defaultValue="1")int page,@RequestParam(defaultValue="20")int pageSize,@RequestParam(required=false)String q,@RequestParam(required=false)String type,@RequestParam(required=false)String status){return ApiResponse.ok(service.contents(-1,userId,"publications",page,pageSize,q,type,status));}
    @DeleteMapping("/me/history/{resourceId}") public ApiResponse<?> deleteHistory(Principal p,@PathVariable long resourceId){service.deleteHistory(id(p),resourceId);return ApiResponse.ok(null);}
    @DeleteMapping("/me/history") public ApiResponse<?> clearHistory(Principal p){service.deleteHistory(id(p),null);return ApiResponse.ok(null);}
    @GetMapping("/resources/{resourceId}") public ApiResponse<?> detail(Principal p,@PathVariable long resourceId){return ApiResponse.ok(service.detail(id(p),resourceId));}
    @PutMapping("/resources/{resourceId}/{kind:bookmark|like}") public ApiResponse<?> interact(Principal p,@PathVariable long resourceId,@PathVariable String kind){return ApiResponse.ok(service.interaction(id(p),resourceId,kind.equals("bookmark"),true));}
    @DeleteMapping("/resources/{resourceId}/{kind:bookmark|like}") public ApiResponse<?> removeInteraction(Principal p,@PathVariable long resourceId,@PathVariable String kind){return ApiResponse.ok(service.interaction(id(p),resourceId,kind.equals("bookmark"),false));}
    @PostMapping("/me/articles") @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<?> create(Principal p, @RequestHeader("Idempotency-Key") String key,
                                 @RequestBody PersonalCenterDtos.ArticleInput input) {
        return ApiResponse.ok(articles.create(id(p), key, input));
    }
    @GetMapping("/me/articles/{resourceId}")
    public ApiResponse<?> article(Principal p, @PathVariable long resourceId) {
        return ApiResponse.ok(articles.own(id(p), resourceId));
    }
    @GetMapping("/me/publications/{resourceId}")
    public ApiResponse<?> publication(Principal p, @PathVariable long resourceId) {
        return ApiResponse.ok(service.publication(id(p), resourceId));
    }
    @PatchMapping("/me/articles/{resourceId}")
    public ApiResponse<?> update(Principal p, @PathVariable long resourceId, @RequestBody PersonalCenterDtos.ArticleInput input) {
        return ApiResponse.ok(articles.update(id(p), resourceId, input));
    }
    @DeleteMapping("/me/articles/{resourceId}")
    public ApiResponse<?> delete(Principal p, @PathVariable long resourceId, @RequestParam(required = false) String baseRevisionId,
                                 @RequestParam(required = false) Integer lockVersion) {
        articles.delete(id(p), resourceId, baseRevisionId, lockVersion);
        return ApiResponse.ok(null);
    }
    @PostMapping("/me/articles/{resourceId}/submit")
    public ApiResponse<?> submit(Principal p, @PathVariable long resourceId, @RequestHeader("Idempotency-Key") String key,
                                 @Valid @RequestBody RevisionLock input) {
        return ApiResponse.ok(articles.submit(id(p), resourceId, key, input));
    }
    @PostMapping("/me/articles/{resourceId}/withdraw")
    public ApiResponse<?> withdraw(Principal p, @PathVariable long resourceId, @RequestHeader("Idempotency-Key") String key,
                                   @Valid @RequestBody RevisionLock input) {
        return ApiResponse.ok(articles.withdraw(id(p), resourceId, key, input));
    }
    @PostMapping("/me/articles/{resourceId}/revise")
    public ApiResponse<?> revise(Principal p, @PathVariable long resourceId, @RequestHeader("Idempotency-Key") String key,
                                 @Valid @RequestBody RevisionLock input) {
        return ApiResponse.ok(articles.revise(id(p), resourceId, key, input));
    }
    @PostMapping("/me/avatar") public ApiResponse<?> upload(Principal p,@RequestParam("file")MultipartFile file){return ApiResponse.ok(avatars.upload(id(p),file));}
    @GetMapping("/users/{userId}/avatar") public ResponseEntity<byte[]> avatar(@PathVariable long userId){return ResponseEntity.ok().contentType(MediaType.IMAGE_PNG).header("X-Content-Type-Options","nosniff").header("Cache-Control","private, no-cache").body(avatars.read(userId));}
}
