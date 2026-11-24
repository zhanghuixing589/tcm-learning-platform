package cn.tcm.platform;
import cn.tcm.platform.community.infrastructure.CommunityRequestLimitFilter;
import cn.tcm.platform.community.application.*;
import cn.tcm.platform.common.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
class CommunityRequestLimitTest {
 @org.springframework.boot.test.context.TestComponent @RestController static class Endpoint {@PostMapping("/api/v1/posts") Object echo(@RequestBody String body){if(body.equals("rate"))throw new CommunityRateLimitException(42);return ApiResponse.ok(body);}}
 MockMvc mvc(int limit){return MockMvcBuilders.standaloneSetup(new Endpoint()).setControllerAdvice(new GlobalExceptionHandler()).addFilters(new TraceFilter(),new CommunityRequestLimitFilter(new ObjectMapper(),()->limit)).build();}
 @Test void boundsActualBytesBeforeJacksonPreservesTraceAndEnvelope() throws Exception {
  mvc(8).perform(post("/api/v1/posts").contentType("application/json").header("Content-Length","1").content("中中中")).andExpect(status().is(413)).andExpect(jsonPath("$.code").value("PAYLOAD_TOO_LARGE")).andExpect(jsonPath("$.data").isEmpty()).andExpect(jsonPath("$.traceId").isNotEmpty());
  mvc(8).perform(post("/api/v1/posts").contentType("application/json").content("12345678")).andExpect(status().isOk()).andExpect(jsonPath("$.data").value("12345678"));
 }
 @Test void rejectsCompressionAndDoesNotTouchMultipart() throws Exception {
  mvc(8).perform(post("/api/v1/posts").contentType("application/json").header("Content-Encoding","gzip").content("x")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
  var filter=new CommunityRequestLimitFilter(new ObjectMapper(),()->8);var req=new MockHttpServletRequest("POST","/api/v1/sources");req.setContentType("multipart/form-data");req.setContent(new byte[50]);var res=new MockHttpServletResponse();var chain=new MockFilterChain();filter.doFilter(req,res,chain);assertThat(chain.getRequest()).isNotNull();
 }
 @Test void configurableLimitDoesNotDisableLoginOrPolicyRecovery() throws Exception {
  var filter=new CommunityRequestLimitFilter(new ObjectMapper(),()->1);
  for(String path:java.util.List.of("/api/v1/auth/login","/api/v1/admin/community/text-policy-head")){
   var req=new MockHttpServletRequest("POST",path);req.setContentType("application/json");req.setContent("{\"reason\":\"restore\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8));var res=new MockHttpServletResponse();var chain=new MockFilterChain();filter.doFilter(req,res,chain);assertThat(chain.getRequest()).as(path).isNotNull();
  }
 }
 @Test void rateLimitHasRetryAfterAndEnvelope() throws Exception {mvc(8).perform(post("/api/v1/posts").contentType("application/json").content("rate")).andExpect(status().is(429)).andExpect(header().string("Retry-After","42")).andExpect(jsonPath("$.code").value("RATE_LIMITED"));}
}
