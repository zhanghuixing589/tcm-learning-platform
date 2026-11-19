package cn.tcm.platform.content;

import cn.tcm.platform.common.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import java.security.Principal;

@RestController @RequestMapping("/api/v1/reviews")
public class ReviewController {
    private final ReviewService service;
    public ReviewController(ReviewService service){this.service=service;}
    private static long actor(Principal p){return Long.parseLong(p.getName());}
    @GetMapping public ApiResponse<?> list(Principal p,@RequestParam(defaultValue="1")int page,@RequestParam(defaultValue="20")int pageSize,@RequestParam(defaultValue="PENDING")String status){return ApiResponse.ok(service.list(actor(p),page,pageSize,status));}
    @GetMapping("/{id}") public ApiResponse<?> detail(Principal p,@PathVariable long id){return ApiResponse.ok(service.detail(actor(p),id));}
    @PostMapping("/{id}/claim") public ApiResponse<?> claim(Principal p,@PathVariable long id,@Valid @RequestBody ReviewDtos.Claim input){return ApiResponse.ok(service.claim(actor(p),id,input));}
    @PostMapping("/{id}/decisions") public ApiResponse<?> decision(Principal p,@PathVariable long id,@Valid @RequestBody ReviewDtos.Decision input){return ApiResponse.ok(service.decide(actor(p),id,input));}
}
