package cn.tcm.platform.source;

import cn.tcm.platform.common.ApiResponse;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.nio.charset.StandardCharsets;
import java.security.Principal;

@RestController @RequestMapping("/api/v1")
public class SourceController {
    private final SourceService sources;private final SourceFiles files;private final SourceJobs jobs;private final SourceImportService imports;
    public SourceController(SourceService sources,SourceFiles files,SourceJobs jobs,SourceImportService imports){this.sources=sources;this.files=files;this.jobs=jobs;this.imports=imports;}
    private static long user(Principal p){return Long.parseLong(p.getName());}
    public record Reason(@NotBlank @Size(max=1000)String reason){}
    @PostMapping("/files") public ApiResponse<?> upload(Principal p,@RequestParam("file")MultipartFile file){return ApiResponse.ok(files.upload(user(p),file));}
    @GetMapping("/files/{id}/metadata") public ApiResponse<?> metadata(Principal p,@PathVariable long id){return ApiResponse.ok(files.metadata(user(p),id));}
    @PostMapping("/source-documents/confirm") public ApiResponse<?> confirm(Principal p,@RequestBody ObjectNode input){return ApiResponse.ok(imports.confirm(user(p),input));}
    @GetMapping("/files/{id}") public ResponseEntity<byte[]> file(Principal p,@PathVariable long id){var file=files.read(user(p),id);return ResponseEntity.ok().contentType(MediaType.parseMediaType(file.mimeType())).header(HttpHeaders.CONTENT_DISPOSITION,ContentDisposition.attachment().filename(file.originalName(),StandardCharsets.UTF_8).build().toString()).header("X-Content-Type-Options","nosniff").header("Cache-Control","private, no-store").body(file.bytes());}
    @GetMapping("/source-documents") public ApiResponse<?> list(Principal p,@RequestParam(defaultValue="1")int page,@RequestParam(defaultValue="20")int pageSize,@RequestParam(required=false)String q,@RequestParam(required=false)String state,@RequestParam(defaultValue="false")boolean approved){return ApiResponse.ok(sources.list(user(p),page,pageSize,q,state,approved));}
    @PostMapping("/source-documents") public ApiResponse<?> create(Principal p,@Valid @RequestBody SourceService.Input input){return ApiResponse.ok(sources.create(user(p),input));}
    @GetMapping("/source-documents/{id}") public ApiResponse<?> detail(Principal p,@PathVariable long id){return ApiResponse.ok(sources.detail(user(p),id));}
    @PatchMapping("/source-documents/{id}") public ApiResponse<?> update(Principal p,@PathVariable long id,@Valid @RequestBody SourceService.Input input){return ApiResponse.ok(sources.update(user(p),id,input));}
    @PostMapping("/source-documents/{id}/parse") public ApiResponse<?> parse(Principal p,@PathVariable long id){return ApiResponse.ok(sources.parse(user(p),id));}
    @PostMapping("/source-documents/{id}/parse/retry") public ApiResponse<?> retryParse(Principal p,@PathVariable long id){return ApiResponse.ok(sources.retryParse(user(p),id));}
    @PostMapping("/source-documents/{id}/reader-build") public ApiResponse<?> buildReader(Principal p,@PathVariable long id){return ApiResponse.ok(sources.buildReader(user(p),id));}
    @GetMapping("/source-documents/{id}/segments") public ApiResponse<?> segments(Principal p,@PathVariable long id,@RequestParam(defaultValue="1")int page,@RequestParam(defaultValue="20")int pageSize,@RequestParam(required=false)Long parseRunId){return ApiResponse.ok(sources.segments(user(p),id,page,pageSize,parseRunId));}
    @PostMapping("/source-documents/{id}/submit") public ApiResponse<?> submit(Principal p,@PathVariable long id){return ApiResponse.ok(sources.submit(user(p),id));}
    @PostMapping("/source-documents/{id}/withdraw") public ApiResponse<?> withdraw(Principal p,@PathVariable long id){return ApiResponse.ok(sources.withdraw(user(p),id));}
    @PostMapping("/source-documents/{id}/revise") public ApiResponse<?> revise(Principal p,@PathVariable long id){return ApiResponse.ok(sources.revise(user(p),id));}
    @PostMapping("/source-documents/{id}/suspend") public ApiResponse<?> suspend(Principal p,@PathVariable long id,@Valid @RequestBody Reason reason){return ApiResponse.ok(sources.suspend(user(p),id,reason.reason()));}
    @GetMapping("/admin/jobs") public ApiResponse<?> jobs(Principal p,@RequestParam(defaultValue="1")int page,@RequestParam(defaultValue="20")int pageSize,@RequestParam(required=false)String status,@RequestParam(required=false)String jobType){return ApiResponse.ok(jobs.list(user(p),page,pageSize,status,jobType));}
    @PostMapping("/admin/jobs/{id}/retries") public ApiResponse<?> retry(Principal p,@PathVariable long id){return ApiResponse.ok(jobs.retry(user(p),id));}
}
