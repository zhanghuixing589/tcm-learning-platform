package cn.tcm.platform.community.api;

import cn.tcm.platform.common.ApiResponse;
import cn.tcm.platform.community.application.CommunityArticleService;
import cn.tcm.platform.community.application.CommunityQuotaService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;

import static cn.tcm.platform.community.api.CommunityArticleDtos.*;

@RestController
@RequestMapping("/api/v1")
public class CommunityArticleController {
    private final CommunityArticleService articles;
    private final CommunityQuotaService quotas;

    public CommunityArticleController(CommunityArticleService articles, CommunityQuotaService quotas) {
        this.articles = articles;
        this.quotas = quotas;
    }

    private static long actor(Principal principal) { return Long.parseLong(principal.getName()); }

    @PostMapping("/me/articles/draft-identities")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<?> identity(Principal p, @RequestHeader("Idempotency-Key") String key,
                                   @Valid @RequestBody DraftIdentity input) {
        return ApiResponse.ok(articles.draftIdentity(actor(p), key, input));
    }

    @PostMapping("/me/articles/{resource}/references/reorder")
    public ApiResponse<?> reorder(Principal p, @PathVariable long resource, @RequestHeader("Idempotency-Key") String key,
                                  @Valid @RequestBody ReferenceOrder input) {
        return ApiResponse.ok(articles.reorder(actor(p), resource, key, input));
    }

    @GetMapping("/me/articles/{resource}/quota")
    public ApiResponse<?> quota(Principal p, @PathVariable long resource) {
        return ApiResponse.ok(quotas.view(actor(p), resource));
    }

    @PostMapping("/me/articles/{resource}/quota-requests")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<?> request(Principal p, @PathVariable long resource, @RequestHeader("Idempotency-Key") String key,
                                  @Valid @RequestBody QuotaInput input) {
        return ApiResponse.ok(quotas.request(actor(p), resource, key, input));
    }

    @GetMapping("/me/articles/{resource}/quota-requests")
    public ApiResponse<?> history(Principal p, @PathVariable long resource,
                                  @RequestParam(defaultValue = "1") int page,
                                  @RequestParam(defaultValue = "20") int pageSize) {
        return ApiResponse.ok(quotas.history(actor(p), resource, page, pageSize));
    }

    @GetMapping("/admin/community/quota-requests")
    public ApiResponse<?> requests(Principal p, @RequestParam(defaultValue = "PENDING") String status,
                                   @RequestParam(defaultValue = "1") int page,
                                   @RequestParam(defaultValue = "20") int pageSize) {
        return ApiResponse.ok(quotas.list(actor(p), status, page, pageSize));
    }

    @PostMapping("/admin/community/quota-requests/{request}/decisions")
    public ApiResponse<?> decide(Principal p, @PathVariable long request, @RequestHeader("Idempotency-Key") String key,
                                 @Valid @RequestBody QuotaDecision input) {
        return ApiResponse.ok(quotas.decide(actor(p), request, key, input));
    }
}
