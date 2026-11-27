package cn.tcm.platform.knowledge.api;

import cn.tcm.platform.common.ApiResponse;
import cn.tcm.platform.knowledge.application.KnowledgeReading;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.web.bind.annotation.*;
import java.security.Principal;

@RestController @RequestMapping("/api/v1")
public class KnowledgeReadingController {
    private final KnowledgeReading service;public KnowledgeReadingController(KnowledgeReading s){service=s;}
    private long actor(Principal p){return Long.parseLong(p.getName());}
    @GetMapping("/knowledge/matches") public ApiResponse<?> matches(Principal p,@RequestParam String q,@RequestParam String type){return ApiResponse.ok(service.matches(actor(p),q,type));}
    @PostMapping("/contributions/knowledge/{id}/evidence") public ApiResponse<?> append(Principal p,@PathVariable long id,@RequestBody JsonNode request){return ApiResponse.ok(service.append(actor(p),id,request));}
    @GetMapping("/knowledge/{id}/versions/{revisionId}/evidence/{evidenceId}/preview") public ApiResponse<?> preview(Principal p,@PathVariable long id,@PathVariable long revisionId,@PathVariable long evidenceId){return ApiResponse.ok(service.preview(actor(p),id,revisionId,evidenceId));}
}
