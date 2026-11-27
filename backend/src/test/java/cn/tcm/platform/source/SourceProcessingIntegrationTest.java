package cn.tcm.platform.source;

import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties={"tcm.jobs.enabled=false","tcm.ai.internal-token=source-process-test"})
@AutoConfigureMockMvc @Transactional
class SourceProcessingIntegrationTest {
    static Path local;
    static{try{local=Files.createTempDirectory("tcm-job-test-");}catch(Exception e){throw new ExceptionInInitializerError(e);}}
    @DynamicPropertySource static void properties(DynamicPropertyRegistry p){p.add("tcm.storage.source-directory",()->local.toString());p.add("tcm.storage.source-temp-directory",()->local.resolve("temp").toString());}
    @Autowired SourceAccess access;@Autowired SourceJobs jobs;@Autowired ObjectMapper json;@Autowired MockMvc mvc;
    record Fixture(SourceJobs.Lease lease,byte[] original){}
    Fixture fixture(boolean build)throws Exception{
        String uuid=UUID.randomUUID().toString();long user=access.insert("INSERT INTO sys_users(username,password_hash,nickname) VALUES(?,?,?)","process_"+uuid,"unused-test-hash","处理测试");
        byte[] original=("不可变原文 "+uuid).getBytes(StandardCharsets.UTF_8);String hash=SourceFiles.hash(original);Files.write(local.resolve(hash),original);
        long file=access.insert("INSERT INTO file_objects(sha256,storage_key,original_name,mime_type,byte_size,uploaded_by,storage_backend) VALUES(?,?,'test.txt','text/plain',?,?,'LOCAL')",hash,hash,original.length,user);
        long doc=access.insert("INSERT INTO source_documents(file_id,title,source_type,submitted_by,review_state) VALUES(?,'处理测试','BOOK',?,?)",file,user,build?"APPROVED":"DRAFT");
        long run=access.insert("INSERT INTO document_parse_runs(document_id,parse_version,parser_name,status) VALUES(?,1,'legacy',?)",doc,build?"SUCCEEDED":"RUNNING");
        String token=UUID.randomUUID().toString(),type=build?"BUILD_READER":"PARSE";
        long job=access.insert("INSERT INTO async_jobs(job_type,idempotency_key,document_id,payload,status,attempt_count,run_token,lease_until) VALUES(?,?,?,JSON_OBJECT('parseRunId',?),'RUNNING',1,?,UTC_TIMESTAMP(3)+INTERVAL 90 SECOND)",type,"test:"+uuid,doc,run,token);
        access.db.update("INSERT INTO async_job_attempts(job_id,attempt_no,run_token,started_at,status) VALUES(?,1,?,UTC_TIMESTAMP(3),'RUNNING')",job,token);
        return new Fixture(new SourceJobs.Lease(job,doc,run,token,type),original);
    }
    @Test void internalOriginalRequiresBothTokensAndUnexpiredLease()throws Exception{
        var f=fixture(false);String path="/internal/v1/source-inputs/"+f.lease().id();
        mvc.perform(get(path)).andExpect(status().isUnauthorized());
        mvc.perform(get(path).header("X-Internal-Token","source-process-test").header("X-Job-Run-Token","wrong")).andExpect(status().isNotFound());
        var response=mvc.perform(get(path).header("X-Internal-Token","source-process-test").header("X-Job-Run-Token",f.lease().runToken())).andExpect(status().isOk()).andReturn().getResponse();
        assertArrayEquals(f.original(),response.getContentAsByteArray());assertEquals(SourceFiles.hash(f.original()),response.getHeader("X-Content-SHA256"));
        access.db.update("UPDATE async_jobs SET lease_until=UTC_TIMESTAMP(3)-INTERVAL 1 SECOND WHERE id=?",f.lease().id());assertFalse(jobs.heartbeat(f.lease()));
        mvc.perform(get(path).header("X-Internal-Token","source-process-test").header("X-Job-Run-Token",f.lease().runToken())).andExpect(status().isNotFound());
    }
    JsonNode reading()throws Exception{return json.readTree("""
        {"schemaVersion":2,"format":"TXT","layoutMode":"REFLOWABLE","overallOutcome":"SUCCEEDED","capabilities":{"textSelectable":true},"availableReadingModes":["PLAIN_TEXT"],"metadata":{},"outline":[],"warnings":[],"stages":{},"units":[{"unitNo":1,"unitType":"TEXT_SECTION","title":"全文","sourcePath":"","nodes":[{"type":"paragraph","id":"u1-b1","children":[{"type":"text","text":"阅读快照"}]}],"blocks":[{"blockNo":1,"localId":"u1-b1","text":"阅读快照"}],"capabilities":{"preciseCitationSupported":true},"availableReadingModes":["PLAIN_TEXT"],"warnings":[]}],"assets":[]}
        """);}
    @Test void historicalBuildPublishesSnapshotWithoutRewritingTextRunOrSegments()throws Exception{
        var f=fixture(true);var lease=f.lease();access.db.update("INSERT INTO document_segments(document_id,parse_run_id,segment_no,body,content_hash) VALUES(?,?,1,'历史原文',?)",lease.documentId(),lease.parseRunId(),SourceFiles.hash("历史原文".getBytes(StandardCharsets.UTF_8)));
        assertTrue(jobs.heartbeat(lease));assertTrue(jobs.completeDocument(lease,new SourceParser.DocumentResult(List.of(new SourceJobs.ParsedSegment(1,null,null,"阅读快照")),reading(),List.of()),null));
        assertEquals("历史原文",access.db.queryForObject("SELECT body FROM document_segments WHERE parse_run_id=?",String.class,lease.parseRunId()));
        assertEquals("SUCCEEDED",access.db.queryForObject("SELECT status FROM document_parse_runs WHERE id=?",String.class,lease.parseRunId()));
        assertEquals(1L,access.db.queryForObject("SELECT COUNT(*) FROM source_reading_editions WHERE build_job_id=?",Long.class,lease.id()));
        assertFalse(jobs.completeDocument(lease,new SourceParser.DocumentResult(List.of(),reading(),List.of()),null));
    }
    @Test void expiredAttemptCannotPublishOrBeRevived()throws Exception{
        var f=fixture(false);access.db.update("UPDATE async_jobs SET lease_until=UTC_TIMESTAMP(3)-INTERVAL 1 SECOND WHERE id=?",f.lease().id());
        assertFalse(jobs.validLease(f.lease()));assertFalse(jobs.heartbeat(f.lease()));assertFalse(jobs.completeDocument(f.lease(),new SourceParser.DocumentResult(List.of(),reading(),List.of()),null));
        assertEquals(0L,access.db.queryForObject("SELECT COUNT(*) FROM source_reading_editions WHERE build_job_id=?",Long.class,f.lease().id()));
    }
    @Test void newerParseVersionBlocksOldLeaseInputHeartbeatAndPublication()throws Exception{
        var f=fixture(false);long newer=access.insert("INSERT INTO document_parse_runs(document_id,parse_version,parser_name,status) VALUES(?,2,'deterministic-v2','QUEUED')",f.lease().documentId());
        assertTrue(newer>f.lease().parseRunId());assertFalse(jobs.validLease(f.lease()));assertFalse(jobs.heartbeat(f.lease()));
        assertFalse(jobs.completeDocument(f.lease(),new SourceParser.DocumentResult(List.of(new SourceJobs.ParsedSegment(1,null,null,"旧批次结果")),reading(),List.of()),null));
        assertEquals(0L,access.db.queryForObject("SELECT COUNT(*) FROM source_reading_editions WHERE document_id=?",Long.class,f.lease().documentId()));
        assertEquals("QUEUED",access.db.queryForObject("SELECT status FROM document_parse_runs WHERE id=?",String.class,newer));
    }
    @Test void parseStoresOutcomeAndStagesWhileHistoricalBuildKeepsOriginalSummary()throws Exception{
        var f=fixture(false);var output=(com.fasterxml.jackson.databind.node.ObjectNode)reading();output.put("overallOutcome","PARTIAL_SUCCESS");output.with("stages").putObject("text").put("status","SUCCEEDED");output.withArray("warnings").add("局部媒体未生成");
        assertTrue(jobs.completeDocument(f.lease(),new SourceParser.DocumentResult(List.of(new SourceJobs.ParsedSegment(1,null,null,"阅读快照")),output,List.of()),null));
        var row=access.db.queryForMap("SELECT * FROM document_parse_runs WHERE id=?",f.lease().parseRunId());assertEquals("PARTIAL_SUCCESS",row.get("overall_outcome"));assertEquals("SUCCEEDED",json.readTree(row.get("stage_results").toString()).path("text").path("status").asText());
        var historical=fixture(true);access.db.update("UPDATE document_parse_runs SET overall_outcome='SUCCEEDED',stage_results=JSON_OBJECT('legacy',true) WHERE id=?",historical.lease().parseRunId());
        assertTrue(jobs.completeDocument(historical.lease(),new SourceParser.DocumentResult(List.of(new SourceJobs.ParsedSegment(1,null,null,"阅读快照")),reading(),List.of()),null));
        var old=access.db.queryForMap("SELECT overall_outcome,stage_results FROM document_parse_runs WHERE id=?",historical.lease().parseRunId());assertEquals("SUCCEEDED",old.get("overall_outcome"));assertTrue(json.readTree(old.get("stage_results").toString()).path("legacy").asBoolean());
    }
}
