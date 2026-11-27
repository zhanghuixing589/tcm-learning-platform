package cn.tcm.platform;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties="tcm.jobs.enabled=false") @AutoConfigureMockMvc @Transactional
class SourceReadingIntegrationTest {
    @Autowired MockMvc mvc; @Autowired ObjectMapper json; @Autowired JdbcTemplate db;
    @Autowired PasswordEncoder encoder; @Autowired org.mybatis.spring.SqlSessionTemplate sqlSession;
    @Autowired cn.tcm.platform.source.SourceJobs jobs;
    @Autowired cn.tcm.platform.source.SourceReadingData readingData;
    record User(long id,String token){}
    private User user(String role) throws Exception {
        String name="reader_"+UUID.randomUUID().toString().replace("-", "");
        db.update("INSERT INTO sys_users(username,password_hash,nickname) VALUES(?,?,?)",name,encoder.encode("TestPass891!"),"阅读行为测试");
        long id=db.queryForObject("SELECT id FROM sys_users WHERE username=?",Long.class,name);
        db.update("INSERT INTO sys_user_roles(user_id,role_id) SELECT ?,id FROM sys_roles WHERE code IN ('LEARNER',?)",id,role);
        sqlSession.clearCache();
        JsonNode login=call(post("/api/v1/auth/login").contentType("application/json").content(json.writeValueAsString(Map.of("username",name,"password","TestPass891!"))),200);
        return new User(id,login.path("accessToken").asText());
    }
    private JsonNode call(MockHttpServletRequestBuilder request,int expected) throws Exception {
        return json.readTree(mvc.perform(request).andExpect(status().is(expected)).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8)).path("data");
    }
    private JsonNode body(User user,String path,Map<String,?> input,int expected) throws Exception {
        return call(post("/api/v1"+path).header("Authorization","Bearer "+user.token()).contentType("application/json").content(json.writeValueAsString(input)),expected);
    }
    private String upload(User user) throws Exception {
        return call(multipart("/api/v1/files").file(new MockMultipartFile("file","reader.txt","text/plain",("原文阅读测试 "+UUID.randomUUID()).getBytes(StandardCharsets.UTF_8))).header("Authorization","Bearer "+user.token()),200).path("id").asText();
    }
    private Map<String,Object> confirmation(String file,String key) {
        return Map.of("fileId",file,"title","原文阅读资料","sourceType","BOOK","requestKey",key,"rights",Map.of("licenseType","AUTHORIZED","copyrightSource","本测试作者授权","authorizationScope","站内学习引用","publicReadingAllowed",true,"publicDownloadAllowed",false,"quoteDisplayAllowed",true,"ragUseAllowed",false));
    }
    @Test void confirmationPersistsRightsAndQueuesExactlyOneParse() throws Exception {
        var owner=user("CONTRIBUTOR");
        var input=confirmation(upload(owner),UUID.randomUUID().toString());
        var first=body(owner,"/source-documents/confirm",input,200);
        assertThat(first.path("parseRun").path("status").asText()).isEqualTo("QUEUED");
        assertThat(first.path("rights").path("publicDownloadAllowed").asBoolean()).isFalse();
        var second=body(owner,"/source-documents/confirm",input,200);
        assertThat(second.path("id").asText()).isEqualTo(first.path("id").asText());
        assertThat(db.queryForObject("SELECT COUNT(*) FROM document_parse_runs WHERE document_id=?",Long.class,first.path("id").asText())).isEqualTo(1);
    }
    @Test void confirmationCannotUseAnotherUploadAndConflictingReplayIsRejected() throws Exception {
        var owner=user("CONTRIBUTOR");var other=user("CONTRIBUTOR");
        String file=upload(owner),key=UUID.randomUUID().toString();
        body(other,"/source-documents/confirm",confirmation(file,key),404);
        body(owner,"/source-documents/confirm",confirmation(file,key),200);
        var changed=new HashMap<>(confirmation(file,key));changed.put("title","不同请求");
        body(owner,"/source-documents/confirm",changed,409);
    }
    @Test void ownerCanRetryWaitingFailureWithoutGrantingOtherContributorsAccess()throws Exception{
        var owner=user("CONTRIBUTOR");var other=user("CONTRIBUTOR");var doc=body(owner,"/source-documents/confirm",confirmation(upload(owner),UUID.randomUUID().toString()),200);String id=doc.path("id").asText();long run=Long.parseLong(doc.path("parseRun").path("id").asText());long job=db.queryForObject("SELECT id FROM async_jobs WHERE document_id=? AND job_type='PARSE'",Long.class,id);String token=UUID.randomUUID().toString();db.update("UPDATE async_jobs SET status='RUNNING',run_token=?,lease_until=UTC_TIMESTAMP(3)+INTERVAL 90 SECOND,attempt_count=1 WHERE id=?",token,job);
        var lease=new cn.tcm.platform.source.SourceJobs.Lease(job,Long.parseLong(id),run,token);assertThat(jobs.complete(lease,null,"转换未完成，请重试")).isTrue();body(other,"/source-documents/"+id+"/parse/retry",Map.of(),404);var retry=body(owner,"/source-documents/"+id+"/parse/retry",Map.of(),200);assertThat(retry.path("processingJob").path("status").asText()).isEqualTo("QUEUED");assertThat(jobs.complete(lease,List.of(new cn.tcm.platform.source.SourceJobs.ParsedSegment(1,null,null,"旧执行结果")),null)).isFalse();
    }
    record Book(String document,String edition,String unit,String block,String run){}
    private Book book(User owner) throws Exception {
        var doc=body(owner,"/source-documents/confirm",confirmation(upload(owner),UUID.randomUUID().toString()),200);String id=doc.path("id").asText(),run=doc.path("parseRun").path("id").asText();
        var job=db.queryForMap("SELECT id FROM async_jobs WHERE document_id=? AND job_type='PARSE'",id);long jid=((Number)job.get("id")).longValue();String token=UUID.randomUUID().toString();
        db.update("UPDATE async_jobs SET status='RUNNING',attempt_count=1,run_token=?,lease_until=UTC_TIMESTAMP(3)+INTERVAL 90 SECOND WHERE id=?",token,jid);
        var cap=Map.of("hasToc",true,"hasImages",false,"hasTables",false,"hasFootnotes",false,"textSelectable",true,"searchable",true,"preciseCitationSupported",true,"fontSizeAdjustable",true);
        String text="桂枝😀汤，解肌发表。";var unit=Map.of("unitNo",1,"unitType","CHAPTER","title","辨太阳病","nodes",List.of(Map.of("type","paragraph","id","u1-b1","children",List.of(Map.of("type","text","text",text)))),"blocks",List.of(Map.of("blockNo",1,"localId","u1-b1","text",text)),"capabilities",cap,"availableReadingModes",List.of("STRUCTURED"),"warnings",List.of());
        var reading=json.createObjectNode();reading.put("schemaVersion",2);reading.put("format","EPUB");reading.put("layoutMode","REFLOWABLE");reading.put("overallOutcome","SUCCEEDED");reading.set("capabilities",json.valueToTree(cap));reading.set("availableReadingModes",json.valueToTree(List.of("STRUCTURED")));reading.set("units",json.valueToTree(List.of(unit)));reading.set("outline",json.valueToTree(List.of(Map.of("title","辨太阳病","unitNo",1,"children",List.of()))));reading.set("metadata",json.createObjectNode());reading.set("stages",json.createObjectNode());reading.set("warnings",json.createArrayNode());
        assertThat(jobs.completeDocument(new cn.tcm.platform.source.SourceJobs.Lease(jid,Long.parseLong(id),Long.parseLong(run),token),new cn.tcm.platform.source.SourceParser.DocumentResult(List.of(new cn.tcm.platform.source.SourceJobs.ParsedSegment(1,null,"辨太阳病",text)),reading,List.of()),null)).isTrue();
        var r=call(get("/api/v1/source-documents/"+id+"/reader").header("Authorization","Bearer "+owner.token()),200);String eid=r.path("edition").path("id").asText(),uid=r.path("units").get(0).path("id").asText();String bid=db.queryForObject("SELECT id FROM source_reading_blocks WHERE edition_id=?",String.class,eid);return new Book(id,eid,uid,bid,run);
    }
    private Map<String,Object> selection(Book b,String quote,int start,int end){return Map.of("editionId",b.edition(),"ranges",List.of(Map.of("blockId",b.block(),"start",start,"end",end)),"quoteText",quote);}
    @Test void exactUnicodeSelectionIsIdempotentAndForgedOrForeignAnchorsFail() throws Exception {
        var owner=user("CONTRIBUTOR");var outsider=user("CONTRIBUTOR");var a=book(owner);var b=book(owner);
        var clip=body(owner,"/source-documents/"+a.document()+"/excerpts",selection(a,"😀",2,3),200);
        assertThat(body(owner,"/source-documents/"+a.document()+"/excerpts",selection(a,"😀",2,3),200).path("id").asText()).isEqualTo(clip.path("id").asText());
        body(owner,"/source-documents/"+a.document()+"/excerpts",selection(a,"伪造",2,3),400);
        body(owner,"/source-documents/"+b.document()+"/excerpts",selection(a,"😀",2,3),404);
        body(outsider,"/source-documents/"+a.document()+"/excerpts",selection(a,"😀",2,3),404);
        assertThat(clip.path("parseRunId").asText()).isEqualTo(a.run());assertThat(clip.path("locator").path("ranges").get(0).path("unitId").asText()).isEqualTo(a.unit());
    }
    @Test void readingProgressUsesVersionAndValidatesBlockOwnership() throws Exception {
        var owner=user("CONTRIBUTOR");var a=book(owner);var b=book(owner);var input=new HashMap<String,Object>(Map.of("editionId",a.edition(),"unitId",a.unit(),"blockId",a.block(),"offset",3,"fontSize",20,"readingMode","STRUCTURED"));
        String path="/api/v1/source-documents/"+a.document()+"/reading-progress";var first=call(put(path).header("Authorization","Bearer "+owner.token()).contentType("application/json").content(json.writeValueAsString(input)),200);
        input.put("expectedVersion",first.path("lockVersion").asInt());call(put(path).header("Authorization","Bearer "+owner.token()).contentType("application/json").content(json.writeValueAsString(input)),200);
        call(put(path).header("Authorization","Bearer "+owner.token()).contentType("application/json").content(json.writeValueAsString(input)),409);
        input.put("expectedVersion",1);input.put("blockId",b.block());call(put(path).header("Authorization","Bearer "+owner.token()).contentType("application/json").content(json.writeValueAsString(input)),400);
    }
    @Test void pendingReaderBuildCannotRaceWithReviewFreeze() throws Exception {
        var owner=user("CONTRIBUTOR");var b=book(owner);body(owner,"/source-documents/"+b.document()+"/reader-build",Map.of(),200);body(owner,"/source-documents/"+b.document()+"/submit",Map.of(),409);
        assertThat(db.queryForObject("SELECT review_state FROM source_documents WHERE id=?",String.class,b.document())).isEqualTo("DRAFT");assertThat(db.queryForObject("SELECT COUNT(*) FROM review_tasks WHERE document_id=?",Long.class,b.document())).isZero();
    }
    @Test void referenceOnlyApprovalDoesNotGrantPrecisionExtraction() throws Exception {
        var owner=user("CONTRIBUTOR");var reviewer=user("REVIEWER");var b=book(owner);body(owner,"/source-documents/"+b.document()+"/submit",Map.of(),200);String task=db.queryForObject("SELECT id FROM review_tasks WHERE document_id=?",String.class,b.document());var review=call(get("/api/v1/reviews/"+task).header("Authorization","Bearer "+reviewer.token()),200);
        body(reviewer,"/reviews/"+task+"/decisions",Map.of("lockVersion",0,"decision","APPROVED","note","只批准阅读参考","acknowledgedWarnings",List.of(),"approvedUse","REFERENCE_ONLY","approvedCoverage",Map.of("unitIds",List.of()),"coverageSnapshotHash",review.path("document").path("processingSummary").path("coverageSnapshotHash").asText()),200);
        var view=call(get("/api/v1/source-documents/"+b.document()+"/reader").header("Authorization","Bearer "+owner.token()),200);assertThat(view.path("capabilities").path("extract").asBoolean()).isFalse();body(owner,"/source-documents/"+b.document()+"/excerpts",selection(b,"桂枝",0,2),409);
    }
    @Test void reviewCoverageAndPublicFilePermissionAreSeparate() throws Exception {
        var owner=user("CONTRIBUTOR");var reviewer=user("REVIEWER");var reader=user("LEARNER");var b=book(owner);
        body(owner,"/source-documents/"+b.document()+"/submit",Map.of(),200);String task=db.queryForObject("SELECT id FROM review_tasks WHERE document_id=?",String.class,b.document());
        body(reviewer,"/reviews/"+task+"/decisions",Map.of("lockVersion",0,"decision","APPROVED","note","未选可靠范围"),409);
        var detail=call(get("/api/v1/reviews/"+task).header("Authorization","Bearer "+reviewer.token()),200);String hash=detail.path("document").path("processingSummary").path("coverageSnapshotHash").asText();
        body(reviewer,"/reviews/"+task+"/decisions",Map.of("lockVersion",0,"decision","APPROVED","note","已核对版权与文字覆盖","acknowledgedWarnings",List.of(),"approvedUse","TEXT_CITABLE","approvedCoverage",Map.of("unitIds",List.of(b.unit())),"coverageSnapshotHash",hash),200);
        call(get("/api/v1/source-documents/"+b.document()+"/reader").header("Authorization","Bearer "+reader.token()),200);
        mvc.perform(get("/api/v1/source-documents/"+b.document()+"/file").header("Authorization","Bearer "+reader.token())).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/source-documents/"+b.document()+"/file").header("Authorization","Bearer "+owner.token()).header("Range","bytes=0-2")).andExpect(status().isPartialContent());
        mvc.perform(get("/api/v1/source-documents/"+b.document()+"/file").header("Authorization","Bearer "+owner.token()).header("Range","bytes=999999-")).andExpect(status().isRequestedRangeNotSatisfiable());
    }
    @Test void unsafeReaderAstIsRejectedBeforePublishing() throws Exception {
        var owner=user("CONTRIBUTOR");var b=book(owner);var e=db.queryForMap("SELECT * FROM source_reading_editions WHERE id=?",b.edition());
        assertThat(e.get("status")).isEqualTo("READY");var malicious=json.readTree("{\"schemaVersion\":2,\"format\":\"EPUB\",\"units\":[{\"unitNo\":1,\"unitType\":\"CHAPTER\",\"nodes\":[{\"type\":\"paragraph\",\"html\":\"<script>alert(1)</script>\"}],\"blocks\":[],\"availableReadingModes\":[\"STRUCTURED\"]}]}");
        org.assertj.core.api.Assertions.assertThatThrownBy(()->cn.tcm.platform.source.SourceReadingData.validate(malicious)).isInstanceOf(cn.tcm.platform.common.ApiException.class);
    }
    private void approveBook(User owner,User reviewer,Book b) throws Exception {body(owner,"/source-documents/"+b.document()+"/submit",Map.of(),200);String task=db.queryForObject("SELECT id FROM review_tasks WHERE document_id=?",String.class,b.document());var detail=call(get("/api/v1/reviews/"+task).header("Authorization","Bearer "+reviewer.token()),200);body(reviewer,"/reviews/"+task+"/decisions",Map.of("lockVersion",0,"decision","APPROVED","note","逐项核对","acknowledgedWarnings",List.of(),"approvedUse","TEXT_CITABLE","approvedCoverage",Map.of("unitIds",List.of(b.unit())),"coverageSnapshotHash",detail.path("document").path("processingSummary").path("coverageSnapshotHash").asText()),200);}
    @Test void curatedDraftKeepsAnchorsAndQuoteOnlyPreviewSurvivesFullReadRestriction() throws Exception {
        var owner=user("CONTRIBUTOR");var reviewer=user("REVIEWER");var publicUser=user("CONTRIBUTOR");var b=book(owner);approveBook(owner,reviewer,b);
        var clip=body(publicUser,"/source-documents/"+b.document()+"/excerpts",selection(b,"桂枝😀汤",0,4),200);
        var input=new HashMap<String,Object>(Map.of("type","CONCEPT","canonicalName","可追溯概念"+UUID.randomUUID(),"title","阅读整理知识","body","人工核对知识正文","evidence",List.of(Map.of("documentId",b.document(),"excerptId",clip.path("id").asText(),"quoteText","桂枝😀汤"))));
        var draft=body(publicUser,"/knowledge",input,201);String resource=draft.path("id").asText(),revision=draft.path("revisionId").asText();
        assertThat(draft.path("evidence").get(0).path("readingEditionId").asText()).isEqualTo(b.edition());body(publicUser,"/revisions/"+revision+"/submit",Map.of(),200);String task=db.queryForObject("SELECT id FROM review_tasks WHERE revision_id=?",String.class,revision);body(reviewer,"/reviews/"+task+"/decisions",Map.of("lockVersion",0,"decision","APPROVED","note","已核对引文"),200);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM async_jobs WHERE revision_id=?",Long.class,revision)).isZero();
        var restriction=Map.of("expectedVersion",0,"publicReadingAllowed",false,"publicDownloadAllowed",false,"quoteDisplayAllowed",true,"ragUseAllowed",false,"reason","仅保留引文授权");body(owner,"/source-documents/"+b.document()+"/rights/restrict",restriction,200);
        var frozen=json.readTree(db.queryForObject("SELECT reviewed_rights FROM source_documents WHERE id=?",String.class,b.document()));assertThat(frozen.path("publicReadingAllowed").asBoolean()).isTrue();assertThat(db.queryForObject("SELECT public_reading_allowed FROM source_documents WHERE id=?",Boolean.class,b.document())).isFalse();
        call(get("/api/v1/source-documents/"+b.document()+"/reader").header("Authorization","Bearer "+publicUser.token()),404);
        String evidence=draft.path("evidence").get(0).path("id").asText();var preview=call(get("/api/v1/knowledge/"+resource+"/versions/"+revision+"/evidence/"+evidence+"/preview").header("Authorization","Bearer "+publicUser.token()),200);
        assertThat(preview.path("canOpenReader").asBoolean()).isFalse();assertThat(preview.path("evidence").path("quoteText").asText()).isEqualTo("桂枝😀汤");assertThat(preview.has("units")).isFalse();
        var other=user("CONTRIBUTOR");var proposal=Map.of("baseRevisionId",revision,"knowledge",Map.of("type","CONCEPT","canonicalName",input.get("canonicalName"),"title","引文修订提案","body","人工修订","evidence",List.of(Map.of("documentId",b.document(),"quoteText","桂枝😀汤","locationLabel","辨太阳病","readingEditionId",b.edition(),"parseRunId",b.run(),"quoteHash",preview.path("evidence").path("quoteHash").asText(),"locator",json.convertValue(preview.path("evidence").path("locator"),Map.class)))));var suggestion=body(other,"/knowledge/"+resource+"/proposals",proposal,200);String proposalId=suggestion.path("id").asText();body(other,"/knowledge-proposals/"+proposalId+"/submit",Map.of(),200);String proposalTask=db.queryForObject("SELECT id FROM review_tasks WHERE proposal_id=?",String.class,proposalId);body(reviewer,"/reviews/"+proposalTask+"/decisions",Map.of("lockVersion",0,"decision","APPROVED","note","独立修订已核对"),200);assertThat(db.queryForObject("SELECT relative_path FROM user_notifications WHERE event_key=?",String.class,"review:"+proposalTask)).isEqualTo("/contribute/proposals/"+proposalId);
        body(owner,"/source-documents/"+b.document()+"/rights/restrict",Map.of("expectedVersion",1,"publicReadingAllowed",false,"publicDownloadAllowed",false,"quoteDisplayAllowed",false,"ragUseAllowed",false,"reason","撤回公开引用"),200);
        call(get("/api/v1/knowledge/"+resource).header("Authorization","Bearer "+other.token()),404);assertThat(db.queryForObject("SELECT quote_text FROM revision_evidence WHERE id=?",String.class,evidence)).isEqualTo("桂枝😀汤");
    }
    @Test void migratedLegacyQuoteRemainsVisibleUntilExplicitlyRestricted() throws Exception {
        var owner=user("CONTRIBUTOR");var reader=user("CONTRIBUTOR");
        db.update("INSERT INTO source_documents(title,source_type,source_url,submitted_by,review_state) VALUES('历史书目','WEB','https://example.test/history',?,'APPROVED')",owner.id());String doc=db.queryForObject("SELECT MAX(id) FROM source_documents WHERE submitted_by=?",String.class,owner.id());
        db.update("INSERT INTO content_resources(resource_type,owner_id) VALUES('KNOWLEDGE',?)",owner.id());String resource=db.queryForObject("SELECT MAX(id) FROM content_resources WHERE owner_id=?",String.class,owner.id());
        db.update("INSERT INTO content_revisions(resource_id,version_no,title,body,created_by,revision_state) VALUES(?,1,'历史知识','保留的历史正文',?,'APPROVED')",resource,owner.id());String revision=db.queryForObject("SELECT id FROM content_revisions WHERE resource_id=?",String.class,resource);
        db.update("INSERT INTO knowledge_entries(resource_id,knowledge_type,canonical_name) VALUES(?,'CONCEPT',?)",resource,"历史规范"+UUID.randomUUID());db.update("INSERT INTO knowledge_revision_names(revision_id,knowledge_type,canonical_name) VALUES(?,'CONCEPT','历史规范')",revision);
        db.update("INSERT INTO revision_evidence(revision_id,document_id,location_label,quote_text,quote_hash) VALUES(?,?,'旧书第1页','保留的旧引文',SHA2('保留的旧引文',256))",revision,doc);String evidence=db.queryForObject("SELECT id FROM revision_evidence WHERE revision_id=?",String.class,revision);db.update("UPDATE content_resources SET published_revision_id=?,visibility_status='PUBLISHED' WHERE id=?",revision,resource);
        call(get("/api/v1/knowledge/"+resource).header("Authorization","Bearer "+reader.token()),200);var quote=call(get("/api/v1/knowledge/"+resource+"/versions/"+revision+"/evidence/"+evidence+"/preview").header("Authorization","Bearer "+reader.token()),200);
        assertThat(quote.path("evidence").path("quoteText").asText()).isEqualTo("保留的旧引文");assertThat(quote.path("canOpenReader").asBoolean()).isFalse();assertThat(quote.path("reason").asText()).contains("历史片段");
        var proposed=body(reader,"/knowledge/"+resource+"/proposals",Map.of("baseRevisionId",revision,"knowledge",Map.of("type","CONCEPT","canonicalName","历史规范","title","有据的历史修订建议","body","人工修订草稿","evidence",List.of(Map.of("documentId",doc,"locationLabel","旧书第1页","quoteText","保留的旧引文")))),200);body(reader,"/knowledge-proposals/"+proposed.path("id").asText()+"/submit",Map.of(),409);
        body(owner,"/source-documents/"+doc+"/rights/restrict",Map.of("expectedVersion",0,"publicReadingAllowed",false,"publicDownloadAllowed",false,"quoteDisplayAllowed",false,"ragUseAllowed",false,"reason","明确撤回旧引文公开授权"),200);call(get("/api/v1/knowledge/"+resource).header("Authorization","Bearer "+reader.token()),404);assertThat(db.queryForObject("SELECT quote_text FROM revision_evidence WHERE id=?",String.class,evidence)).isEqualTo("保留的旧引文");
    }
}
