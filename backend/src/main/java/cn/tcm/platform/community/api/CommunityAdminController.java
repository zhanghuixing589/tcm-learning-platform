package cn.tcm.platform.community.api;

import cn.tcm.platform.common.ApiResponse;
import cn.tcm.platform.community.application.CommunitySectionService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;

import static cn.tcm.platform.community.api.CommunityController.actor;
import static cn.tcm.platform.community.api.CommunityDtos.*;
import static cn.tcm.platform.content.ContentSupport.id;

@RestController
@RequestMapping("/api/v1/admin/community/sections")
public class CommunityAdminController {
    private final CommunitySectionService sections;

    public CommunityAdminController(CommunitySectionService sections) { this.sections = sections; }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<?> create(Principal actor, @RequestHeader("Idempotency-Key") String key,
                                 @Valid @RequestBody SectionCreate input) {
        return ApiResponse.ok(sections.create(actor(actor), key, input));
    }

    @PatchMapping("/{sectionId}")
    public ApiResponse<?> update(Principal actor, @PathVariable String sectionId, @Valid @RequestBody SectionUpdate input) {
        return ApiResponse.ok(sections.update(actor(actor), id(sectionId), input));
    }
}
