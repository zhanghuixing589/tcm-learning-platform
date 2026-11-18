package cn.tcm.platform.operations;

import cn.tcm.platform.common.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import java.util.Map;

@RestController
public class CapabilitiesController {
    @GetMapping("/api/v1/capabilities")
    public ApiResponse<?> capabilities() {
        return ApiResponse.ok(Map.of("identity",true,"qa",Map.of("available",false,"code","NOT_IMPLEMENTED",
                "message","智能问答将在检索与问答阶段开放")));
    }
}
