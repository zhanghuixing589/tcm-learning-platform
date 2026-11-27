package cn.tcm.platform;

import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest @AutoConfigureMockMvc @Transactional
class KnowledgeWorkflowIntegrationTest {
    @Autowired MockMvc mvc; @Autowired ObjectMapper json; @Autowired JdbcTemplate db;
    @Autowired PasswordEncoder passwords;
    record User(long id,String token) {}
    private User user(String role) throws Exception {
        String name="kw_"+UUID.randomUUID().toString().replace("-","");
        db.update("INSERT INTO sys_users(username,password_hash,nickname) VALUES(?,?,?)",name,passwords.encode("TestPass891!"),"知识测试");
        long id=db.queryForObject("SELECT id FROM sys_users WHERE username=?",Long.class,name);
        db.update("INSERT INTO sys_user_roles(user_id,role_id) SELECT ?,id FROM sys_roles WHERE code IN ('LEARNER',?)",id,role);
        return new User(id,call(null,post("/api/v1/auth/login"),Map.of("username",name,"password","TestPass891!"),200).path("accessToken").asText());
    }
    private JsonNode call(User u,MockHttpServletRequestBuilder request,Object body,int status) throws Exception {
        if(u!=null)request.header("Authorization","Bearer "+u.token());
        if(body!=null)request.contentType("application/json").content(json.writeValueAsString(body));
        return json.readTree(mvc.perform(request).andExpect(status().is(status)).andReturn().getResponse().getContentAsString()).path("data");
    }
    private String source(User owner) {
        db.update("INSERT INTO source_documents(title,source_type,source_url,submitted_by,review_state,rights_status,license_type,copyright_source,authorization_scope,public_reading_allowed,quote_display_allowed) VALUES('可靠来源','WEB','https://example.org',?,'APPROVED','DECLARED','AUTHORIZED','Test author','Citation and reading',1,1)",owner.id());
        return db.queryForObject("SELECT MAX(id) FROM source_documents WHERE submitted_by=?",String.class,owner.id());
    }
    private Map<String,Object> input(String document,String name) {
        return new LinkedHashMap<>(Map.of("type","CONCEPT","canonicalName",name,"title",name,"body","可核对的知识正文","aliases",List.of("别名"+name),"evidence",List.of(Map.of("documentId",document,"locationLabel","第1页","quoteText","有出处的原文")),"ingredients",List.of(),"relations",List.of()));
    }
    private JsonNode create(User u,Map<String,Object> input) throws Exception {return call(u,post("/api/v1/knowledge"),input,201);}
    private JsonNode task(User reviewer,String revision) throws Exception {
        String id=db.queryForObject("SELECT id FROM review_tasks WHERE revision_id=?",String.class,revision);
        return call(reviewer,get("/api/v1/reviews/"+id),null,200);
    }
    private JsonNode approve(User reviewer,JsonNode task) throws Exception {
        return call(reviewer,post("/api/v1/reviews/"+task.path("id").asText()+"/decisions"),Map.of("lockVersion",task.path("lockVersion").asInt(),"decision","APPROVED","note","已逐项核实依据"),200);
    }
    @Test void frozenOwnedDraftAndApprovalPublishAnImmutableSnapshot() throws Exception {
        User author=user("CONTRIBUTOR"),other=user("CONTRIBUTOR"),reviewer=user("REVIEWER");
        String document=source(author);Map<String,Object> in=input(document,"概念"+UUID.randomUUID());
        JsonNode draft=create(author,in);String id=draft.path("id").asText(),revision=draft.path("revisionId").asText();
        call(other,patch("/api/v1/knowledge/"+id+"/draft"),in,404);
        call(author,post("/api/v1/revisions/"+revision+"/submit"),null,200);
        call(author,patch("/api/v1/knowledge/"+id+"/draft"),in,409);
        call(other,get("/api/v1/knowledge/"+id),null,404);
        JsonNode review=task(reviewer,revision);approve(reviewer,review);
        approve(reviewer,review); // same actor/decision/note is idempotent even with original lock version
        assertThat(db.queryForObject("SELECT COUNT(*) FROM async_jobs WHERE revision_id=?",Integer.class,revision)).isZero();
        JsonNode published=call(other,get("/api/v1/knowledge/"+id),null,200);
        assertThat(published.path("canonicalName").asText()).isEqualTo(in.get("canonicalName"));
        JsonNode revised=call(author,post("/api/v1/knowledge/"+id+"/revise"),null,200);
        assertThat(revised.path("evidence").size()).isEqualTo(1);
        assertThat(revised.path("evidence").get(0).path("id").asText()).isNotEqualTo(published.path("evidence").get(0).path("id").asText());
        in.put("canonicalName","新规范名");in.put("aliases",List.of("新别名"));in.put("body","尚未发布的修订");
        call(author,patch("/api/v1/knowledge/"+id+"/draft"),in,200);
        assertThat(call(other,get("/api/v1/knowledge/"+id),null,200).path("body").asText()).isEqualTo("可核对的知识正文");
        assertThat(db.queryForObject("SELECT canonical_name FROM knowledge_entries WHERE resource_id=?",String.class,id)).isEqualTo(in.get("title"));
        call(author,post("/api/v1/revisions/"+revised.path("revisionId").asText()+"/submit"),null,200);
        call(author,post("/api/v1/revisions/"+revised.path("revisionId").asText()+"/withdraw"),null,200);
        call(author,patch("/api/v1/knowledge/"+id+"/draft"),in,409);
    }
    @Test void reviewRejectsSelfAdminStaleClaimAndInvalidatedEvidence() throws Exception {
        User author=user("CONTRIBUTOR"),reviewer=user("REVIEWER"),admin=user("ADMIN"),second=user("REVIEWER");
        db.update("INSERT INTO sys_user_roles(user_id,role_id) SELECT ?,id FROM sys_roles WHERE code='REVIEWER'",author.id());
        String document=source(author);JsonNode draft=create(author,input(document,"审核"+UUID.randomUUID()));
        call(author,post("/api/v1/revisions/"+draft.path("revisionId").asText()+"/submit"),null,200);
        JsonNode t=task(reviewer,draft.path("revisionId").asText());String path="/api/v1/reviews/"+t.path("id").asText();
        call(admin,get(path),null,403);
        call(author,post(path+"/decisions"),Map.of("lockVersion",0,"decision","APPROVED","note","自审"),403);
        JsonNode claimed=call(reviewer,post(path+"/claim"),Map.of("lockVersion",0),200);
        call(second,post(path+"/claim"),Map.of("lockVersion",0),409);
        call(reviewer,post(path+"/decisions"),Map.of("lockVersion",0,"decision","APPROVED","note","过期锁"),409);
        db.update("UPDATE source_documents SET availability='SUSPENDED' WHERE id=?",document);
        call(reviewer,post(path+"/decisions"),Map.of("lockVersion",claimed.path("lockVersion").asInt(),"decision","APPROVED","note","来源失效"),409);
        assertThat(db.queryForObject("SELECT revision_state FROM content_revisions WHERE id=?",String.class,draft.path("revisionId").asText())).isEqualTo("IN_REVIEW");
    }
    @Test void validatesForeignEvidenceTargetVersionsAndAtomicImport() throws Exception {
        User a=user("CONTRIBUTOR"),b=user("CONTRIBUTOR");String document=source(a);
        var bad=input(document,"错误关系");bad.put("relations",List.of(Map.of("targetResourceId","999999999","targetRevisionId","999999999","relationType","RELATED_TO","evidenceIndex",0)));
        createRejected(a,bad,400);
        bad=input(document,"错误依据");bad.put("relations",List.of(Map.of("targetResourceId","1","targetRevisionId","1","relationType","RELATED_TO","evidenceIndex",5)));
        createRejected(a,bad,400);
        String privateDocument=source(b);db.update("UPDATE source_documents SET review_state='DRAFT' WHERE id=?",privateDocument);
        createRejected(a,input(privateDocument,"私有来源"),400);
        int before=db.queryForObject("SELECT COUNT(*) FROM content_resources WHERE owner_id=?",Integer.class,a.id());
        call(a,post("/api/v1/knowledge/import"),Map.of("items",List.of(input(document,"导入有效"),input(privateDocument,"导入非法"))),400);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM content_resources WHERE owner_id=?",Integer.class,a.id())).isEqualTo(before);
    }
    private void createRejected(User u,Map<String,Object> body,int code)throws Exception{call(u,post("/api/v1/knowledge"),body,code);}

    @Test void sourceApprovalHasAuditNotificationAndCannotBeSelfReviewed() throws Exception {
        User author=user("CONTRIBUTOR"),reviewer=user("REVIEWER");String document=source(author);
        db.update("UPDATE source_documents SET review_state='IN_REVIEW' WHERE id=?",document);
        db.update("INSERT INTO review_tasks(document_id,submitted_by) VALUES(?,?)",document,author.id());
        String task=db.queryForObject("SELECT id FROM review_tasks WHERE document_id=?",String.class,document);
        JsonNode result=call(reviewer,post("/api/v1/reviews/"+task+"/decisions"),Map.of("lockVersion",0,"decision","APPROVED","note","书目信息和文本已核实"),200);
        assertThat(result.path("document").path("reviewState").asText()).isEqualTo("APPROVED");
        assertThat(result.path("events").size()).isEqualTo(1);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM user_notifications WHERE user_id=? AND event_key=?",Integer.class,author.id(),"review:"+task)).isEqualTo(1);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM sys_audit_logs WHERE action='REVIEW_APPROVED' AND object_key=?",Integer.class,task)).isEqualTo(1);
        call(reviewer,post("/api/v1/reviews/"+task+"/decisions"),Map.of("lockVersion",0,"decision","REJECTED","note","不能改判"),409);
    }
    @Test void formulaRelationsCopyOwnEvidenceAndHideSuspendedTargets() throws Exception {
        User author=user("CONTRIBUTOR"),reviewer=user("REVIEWER"),reader=user("LEARNER");String document=source(author);
        var herbInput=input(document,"药材"+UUID.randomUUID());herbInput.put("type","HERB");herbInput.put("herb",Map.of("nature","温","flavor","甘","precautions","按原文学习"));
        JsonNode herb=create(author,herbInput);call(author,post("/api/v1/revisions/"+herb.path("revisionId").asText()+"/submit"),null,200);approve(reviewer,task(reviewer,herb.path("revisionId").asText()));
        var formulaInput=input(document,"方剂"+UUID.randomUUID());formulaInput.put("type","FORMULA");formulaInput.put("formula",Map.of("originText","测试古籍","functionSummary","仅作知识学习","precautions","不可据此自行用药"));
        formulaInput.put("ingredients",List.of(Map.of("herbResourceId",herb.path("id").asText(),"herbRevisionId",herb.path("revisionId").asText(),"role","JUN","doseValue",3,"doseUnit","g","doseText","原文三克","evidenceIndex",0)));
        formulaInput.put("relations",List.of(Map.of("targetResourceId",herb.path("id").asText(),"targetRevisionId",herb.path("revisionId").asText(),"relationType","RELATED_TO","explanation","原文相联","evidenceIndex",0)));
        JsonNode formula=create(author,formulaInput);call(author,post("/api/v1/revisions/"+formula.path("revisionId").asText()+"/submit"),null,200);approve(reviewer,task(reviewer,formula.path("revisionId").asText()));
        JsonNode published=call(reader,get("/api/v1/knowledge/"+formula.path("id").asText()),null,200);
        assertThat(published.path("ingredients").size()).isEqualTo(1);assertThat(published.path("relations").size()).isEqualTo(1);
        JsonNode revised=call(author,post("/api/v1/knowledge/"+formula.path("id").asText()+"/revise"),null,200);
        assertThat(revised.path("ingredients").get(0).path("evidenceIndex").asInt()).isZero();
        assertThat(db.queryForObject("SELECT COUNT(*) FROM formula_ingredients i JOIN revision_evidence e ON e.id=i.evidence_id AND e.revision_id=i.formula_revision_id WHERE i.formula_revision_id=?",Integer.class,revised.path("revisionId").asText())).isEqualTo(1);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM knowledge_relations i JOIN revision_evidence e ON e.id=i.evidence_id AND e.revision_id=i.source_revision_id WHERE i.source_revision_id=?",Integer.class,revised.path("revisionId").asText())).isEqualTo(1);
        call(reviewer,post("/api/v1/knowledge/"+herb.path("id").asText()+"/suspend"),Map.of("reason","来源待复核"),200);
        JsonNode filtered=call(reader,get("/api/v1/knowledge/"+formula.path("id").asText()),null,200);
        assertThat(filtered.path("ingredients").size()).isZero();assertThat(filtered.path("relations").size()).isZero();
        call(reader,get("/api/v1/knowledge/"+herb.path("id").asText()),null,404);
        db.update("UPDATE source_documents SET availability='SUSPENDED' WHERE id=?",document);
        call(reader,get("/api/v1/knowledge/"+formula.path("id").asText()),null,404);
        call(reader,get("/api/v1/knowledge/"+formula.path("id").asText()+"/versions/"+formula.path("revisionId").asText()),null,404);
    }
    @Test void segmentExtractionRequiresActualDocumentAndProducesEditableDraftAndFeedbackPinsPublishedVersion() throws Exception {
        User author=user("CONTRIBUTOR"),reviewer=user("REVIEWER"),reader=user("LEARNER");String document=source(author);
        db.update("INSERT INTO document_parse_runs(document_id,parse_version,parser_name,status) VALUES(?,1,'test','SUCCEEDED')",document);
        String run=db.queryForObject("SELECT id FROM document_parse_runs WHERE document_id=?",String.class,document);
        db.update("INSERT INTO document_segments(document_id,parse_run_id,segment_no,body,content_hash) VALUES(?,?,1,'人工需校正的原始段落',REPEAT('a',64))",document,run);
        String segment=db.queryForObject("SELECT id FROM document_segments WHERE document_id=?",String.class,document);
        JsonNode extracted=call(author,post("/api/v1/knowledge/extractions"),Map.of("documentId",document,"segmentIds",List.of(segment),"type","CONCEPT"),200).path("items").get(0);
        assertThat(extracted.path("revisionState").asText()).isEqualTo("DRAFT");assertThat(extracted.path("title").asText()).startsWith("待校正：");
        String wrongDoc=source(author);call(author,post("/api/v1/knowledge/extractions"),Map.of("documentId",wrongDoc,"segmentIds",List.of(segment),"type","CONCEPT"),404);
        call(author,post("/api/v1/revisions/"+extracted.path("revisionId").asText()+"/submit"),null,200);approve(reviewer,task(reviewer,extracted.path("revisionId").asText()));
        JsonNode feedback=call(reader,post("/api/v1/resources/"+extracted.path("id").asText()+"/feedback"),Map.of("content","请补充原文定位说明"),200);
        assertThat(feedback.path("revisionId").asText()).isEqualTo(extracted.path("revisionId").asText());
        call(author,patch("/api/v1/feedback/"+feedback.path("id").asText()),Map.of("status","RESOLVED","resolution","本人不能代审核处理"),403);
        assertThat(call(reviewer,patch("/api/v1/feedback/"+feedback.path("id").asText()),Map.of("status","RESOLVED","resolution","已核对并记录"),200).path("resolvedAt").asText()).endsWith("Z");
        var forged=input(document,"伪造引文");forged.put("evidence",List.of(Map.of("documentId",document,"segmentId",segment,"quoteText","实际原文中没有这句话")));
        createRejected(author,forged,400);
    }
    @Test void rejectionRemainsFrozenAndIdentityTypeCannotChangeAcrossRevisions() throws Exception {
        User author=user("CONTRIBUTOR"),reviewer=user("REVIEWER"),reader=user("LEARNER");String document=source(author);var original=input(document,"退回"+UUID.randomUUID());
        JsonNode draft=create(author,original);String id=draft.path("id").asText(),revision=draft.path("revisionId").asText();
        var wrongType=new LinkedHashMap<>(original);wrongType.put("type","HERB");call(author,patch("/api/v1/knowledge/"+id+"/draft"),wrongType,400);
        call(author,post("/api/v1/revisions/"+revision+"/submit"),null,200);
        JsonNode t=task(reviewer,revision);call(reviewer,post("/api/v1/reviews/"+t.path("id").asText()+"/decisions"),Map.of("lockVersion",0,"decision","REJECTED","note","请补充原文定位"),200);
        call(author,patch("/api/v1/knowledge/"+id+"/draft"),original,409);
        JsonNode copy=call(author,post("/api/v1/knowledge/"+id+"/revise"),null,200);assertThat(copy.path("versionNo").asInt()).isEqualTo(2);assertThat(copy.path("revisionState").asText()).isEqualTo("DRAFT");
        call(reader,get("/api/v1/knowledge/"+id+"/versions/"+revision),null,404);
        assertThat(call(author,get("/api/v1/knowledge/"+id+"/versions"),null,200).size()).isEqualTo(2);
    }
    @Test @Transactional(propagation=org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    void concurrentDecisionsHaveOneWinnerAndOneOutboxPublication() throws Exception {
        var users=new ArrayList<User>();var pool=java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            User author=user("CONTRIBUTOR");users.add(author);User one=user("REVIEWER");users.add(one);User two=user("REVIEWER");users.add(two);
            String document=source(author);JsonNode draft=create(author,input(document,"并发"+UUID.randomUUID()));String revision=draft.path("revisionId").asText();
            call(author,post("/api/v1/revisions/"+revision+"/submit"),null,200);String task=task(one,revision).path("id").asText();
            var ready=new java.util.concurrent.CountDownLatch(2);var start=new java.util.concurrent.CountDownLatch(1);
            var results=new ArrayList<java.util.concurrent.Future<Integer>>();for(User reviewer:List.of(one,two))results.add(pool.submit(()->{
                ready.countDown();if(!start.await(15,java.util.concurrent.TimeUnit.SECONDS))throw new IllegalStateException("concurrency barrier timed out");
                return mvc.perform(post("/api/v1/reviews/"+task+"/decisions").header("Authorization","Bearer "+reviewer.token()).contentType("application/json").content(json.writeValueAsString(Map.of("lockVersion",0,"decision","APPROVED","note","并发审核")))).andReturn().getResponse().getStatus();
            }));
            assertThat(ready.await(15,java.util.concurrent.TimeUnit.SECONDS)).isTrue();start.countDown();
            assertThat(List.of(results.get(0).get(30,java.util.concurrent.TimeUnit.SECONDS),results.get(1).get(30,java.util.concurrent.TimeUnit.SECONDS))).containsExactlyInAnyOrder(200,409);
            assertThat(db.queryForObject("SELECT COUNT(*) FROM review_events WHERE review_task_id=? AND action='APPROVE'",Integer.class,task)).isEqualTo(1);
            assertThat(db.queryForObject("SELECT COUNT(*) FROM async_jobs WHERE revision_id=?",Integer.class,revision)).isZero();
        } finally {pool.shutdownNow();cleanupCommittedFixtures(users);}
    }
    private void cleanupCommittedFixtures(List<User> users) {
        for(User user:users) {
            for(Long resource:db.queryForList("SELECT id FROM content_resources WHERE owner_id=?",Long.class,user.id())) {
                db.update("DELETE FROM user_notifications WHERE resource_id=?",resource);db.update("DELETE FROM user_resource_history WHERE resource_id=?",resource);
                for(Long revision:db.queryForList("SELECT id FROM content_revisions WHERE resource_id=? ORDER BY version_no DESC",Long.class,resource)) {
                    db.update("DELETE e FROM review_events e JOIN review_tasks t ON t.id=e.review_task_id WHERE t.revision_id=?",revision);
                    db.update("DELETE FROM review_tasks WHERE revision_id=?",revision);db.update("DELETE FROM async_jobs WHERE revision_id=?",revision);
                    db.update("DELETE FROM formula_ingredients WHERE formula_revision_id=?",revision);db.update("DELETE FROM knowledge_relations WHERE source_revision_id=?",revision);
                    for(String table:List.of("herb_profiles","formula_profiles","revision_evidence","knowledge_revision_aliases","knowledge_revision_names"))db.update("DELETE FROM "+table+" WHERE revision_id=?",revision);
                }
                db.update("UPDATE content_resources SET visibility_status='DRAFT',published_revision_id=NULL WHERE id=?",resource);
                db.update("DELETE FROM content_revisions WHERE resource_id=?",resource);db.update("DELETE FROM knowledge_aliases WHERE knowledge_resource_id=?",resource);db.update("DELETE FROM knowledge_entries WHERE resource_id=?",resource);db.update("DELETE FROM content_resources WHERE id=?",resource);
            }
            db.update("DELETE FROM source_documents WHERE submitted_by=?",user.id());db.update("DELETE FROM sys_audit_logs WHERE actor_id=?",user.id());db.update("DELETE FROM user_notifications WHERE user_id=?",user.id());db.update("DELETE FROM auth_refresh_tokens WHERE user_id=?",user.id());db.update("DELETE FROM sys_user_roles WHERE user_id=?",user.id());db.update("DELETE FROM sys_users WHERE id=?",user.id());
        }
    }
    @Test void professionalAndCommunityReviewPermissionsStaySeparate() throws Exception {
        User author=user("CONTRIBUTOR"),professional=user("LEARNER"),community=user("LEARNER");
        for(var permission:List.of(Map.entry(professional,"knowledge:review"),Map.entry(community,"community:moderate"))){String code="TEST_"+UUID.randomUUID().toString().replace("-","").substring(0,20);db.update("INSERT INTO sys_roles(code,name) VALUES(?,?)",code,"隔离测试角色");long role=db.queryForObject("SELECT id FROM sys_roles WHERE code=?",Long.class,code);db.update("INSERT INTO sys_role_permissions(role_id,permission_id) SELECT ?,id FROM sys_permissions WHERE code=?",role,permission.getValue());db.update("INSERT INTO sys_user_roles(user_id,role_id) VALUES(?,?)",permission.getKey().id(),role);}
        String section=db.queryForObject("SELECT id FROM community_sections WHERE code='study'",String.class);
        JsonNode article=call(author,post("/api/v1/me/articles").header("Idempotency-Key",UUID.randomUUID().toString()),
                Map.of("title","社区稿","body","社区稿件正文","sectionId",section),201);
        call(author,post("/api/v1/me/articles/"+article.path("id").asText()+"/submit").header("Idempotency-Key",UUID.randomUUID().toString()),
                Map.of("baseRevisionId",article.path("revisionId").asText(),"lockVersion",article.path("lockVersion").asInt()),200);
        String articleTask=db.queryForObject("SELECT t.id FROM review_tasks t JOIN content_revisions v ON v.id=t.revision_id WHERE v.resource_id=?",String.class,article.path("id").asText());
        call(professional,get("/api/v1/reviews/"+articleTask),null,403);
        call(community,post("/api/v1/reviews/"+articleTask+"/decisions"),Map.of("lockVersion",0,"decision","APPROVED","note","社区内容审核"),200);
        String document=source(author);JsonNode draft=create(author,input(document,"专业稿"+UUID.randomUUID()));call(author,post("/api/v1/revisions/"+draft.path("revisionId").asText()+"/submit"),null,200);
        String knowledgeTask=db.queryForObject("SELECT id FROM review_tasks WHERE revision_id=?",String.class,draft.path("revisionId").asText());
        call(community,get("/api/v1/reviews/"+knowledgeTask),null,403);
        call(community,post("/api/v1/reviews/"+knowledgeTask+"/decisions"),Map.of("lockVersion",0,"decision","APPROVED","note","社区权不得代审知识"),403);
        approve(professional,task(professional,draft.path("revisionId").asText()));
    }
    @Test void duplicateOfficialNameRollsBackDecisionAndPublicSearchUsesOnlyPublishedAliases() throws Exception {
        User author=user("CONTRIBUTOR"),reviewer=user("REVIEWER"),reader=user("LEARNER");String document=source(author),name="索引"+UUID.randomUUID();var in=input(document,name);
        JsonNode first=create(author,in);String id=first.path("id").asText(),revision=first.path("revisionId").asText();call(author,post("/api/v1/revisions/"+revision+"/submit"),null,200);approve(reviewer,task(reviewer,revision));
        JsonNode second=create(author,in);String duplicateRevision=second.path("revisionId").asText();call(author,post("/api/v1/revisions/"+duplicateRevision+"/submit"),null,200);JsonNode pending=task(reviewer,duplicateRevision);
        call(reviewer,post("/api/v1/reviews/"+pending.path("id").asText()+"/decisions"),Map.of("lockVersion",0,"decision","APPROVED","note","同名不得覆盖"),409);
        assertThat(db.queryForObject("SELECT status FROM review_tasks WHERE id=?",String.class,pending.path("id").asText())).isEqualTo("PENDING");assertThat(db.queryForObject("SELECT COUNT(*) FROM async_jobs WHERE revision_id=?",Integer.class,duplicateRevision)).isZero();
        JsonNode draft=call(author,post("/api/v1/knowledge/"+id+"/revise"),null,200);String privateAlias="未审别名"+UUID.randomUUID();in.put("aliases",List.of(privateAlias));in.put("canonicalName","未审名称"+UUID.randomUUID());call(author,patch("/api/v1/knowledge/"+id+"/draft"),in,200);
        assertThat(call(reader,get("/api/v1/knowledge").param("q",privateAlias),null,200).path("total").asInt()).isZero();
        JsonNode found=call(reader,get("/api/v1/knowledge").param("q","别名"+name),null,200).path("items");assertThat(found.size()).isEqualTo(1);assertThat(found.get(0).path("revisionId").asText()).isEqualTo(revision);
        assertThat(call(reader,get("/api/v1/knowledge/"+id+"/versions"),null,200).size()).isEqualTo(1);
        assertThat(draft.path("revisionId").asText()).isNotEqualTo(revision);
    }
    @Test void feedbackStatusFilterPaginatesOldOpenRecordsWithoutLeakingOtherUsers() throws Exception {
        User author=user("CONTRIBUTOR"),other=user("LEARNER"),reviewer=user("REVIEWER");
        for(String content:List.of("旧待处理记录","新待处理记录"))db.update("INSERT INTO resource_feedback(user_id,kind,content,status) VALUES(?,'KNOWLEDGE_REQUEST',?,'OPEN')",author.id(),content);
        db.update("INSERT INTO resource_feedback(user_id,kind,content,status) VALUES(?,'KNOWLEDGE_REQUEST','已处理记录','RESOLVED')",author.id());
        db.update("INSERT INTO resource_feedback(user_id,kind,content,status) VALUES(?,'KNOWLEDGE_REQUEST','其他用户记录','OPEN')",other.id());
        JsonNode page=call(author,get("/api/v1/contributions/feedback").param("status","OPEN").param("page","2").param("pageSize","1"),null,200);
        assertThat(page.path("total").asInt()).isEqualTo(2);assertThat(page.path("items").size()).isEqualTo(1);assertThat(page.path("items").get(0).path("content").asText()).isEqualTo("旧待处理记录");
        assertThat(call(author,get("/api/v1/contributions/feedback").param("status","RESOLVED"),null,200).path("total").asInt()).isEqualTo(1);
        assertThat(call(author,get("/api/v1/contributions/feedback"),null,200).path("total").asInt()).isEqualTo(3);
        JsonNode reviewed=call(reviewer,get("/api/v1/contributions/feedback").param("status","OPEN").param("pageSize","100"),null,200);
        assertThat(reviewed.path("items").findValuesAsText("content")).contains("其他用户记录");
        call(author,get("/api/v1/contributions/feedback").param("status","UNKNOWN"),null,400);
    }
    @Test void detailAloneRecordsHistoryWhileRelationsFeedbackAndBookmarkStateReadsDoNot() throws Exception {
        User author=user("CONTRIBUTOR"),reviewer=user("REVIEWER"),reader=user("LEARNER");String document=source(author);
        JsonNode draft=create(author,input(document,"浏览记录"+UUID.randomUUID()));String resource=draft.path("id").asText(),revision=draft.path("revisionId").asText();
        call(author,post("/api/v1/revisions/"+revision+"/submit"),null,200);approve(reviewer,task(reviewer,revision));
        JsonNode first=call(reader,get("/api/v1/knowledge/"+resource),null,200);assertThat(first.path("bookmarked").asBoolean()).isFalse();
        assertThat(db.queryForObject("SELECT view_count FROM user_resource_history WHERE user_id=? AND resource_id=?",Integer.class,reader.id(),resource)).isEqualTo(1);
        call(reader,get("/api/v1/knowledge/"+resource+"/relations"),null,200);
        call(reader,post("/api/v1/resources/"+resource+"/feedback"),Map.of("content","核对来源定位"),200);
        assertThat(db.queryForObject("SELECT view_count FROM user_resource_history WHERE user_id=? AND resource_id=?",Integer.class,reader.id(),resource)).isEqualTo(1);
        call(reader,put("/api/v1/resources/"+resource+"/bookmark"),null,200);
        JsonNode second=call(reader,get("/api/v1/knowledge/"+resource),null,200);assertThat(second.path("bookmarked").asBoolean()).isTrue();
        assertThat(db.queryForObject("SELECT view_count FROM user_resource_history WHERE user_id=? AND resource_id=?",Integer.class,reader.id(),resource)).isEqualTo(2);
        db.update("UPDATE source_documents SET availability='SUSPENDED' WHERE id=?",document);
        call(reader,get("/api/v1/knowledge/"+resource+"/relations"),null,404);call(reader,post("/api/v1/resources/"+resource+"/feedback"),Map.of("content","失效内容"),404);
        assertThat(db.queryForObject("SELECT view_count FROM user_resource_history WHERE user_id=? AND resource_id=?",Integer.class,reader.id(),resource)).isEqualTo(2);
    }
}
