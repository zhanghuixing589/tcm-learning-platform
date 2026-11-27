package cn.tcm.platform;

import cn.tcm.platform.source.SourceJobs;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.DynamicPropertyRegistry;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties="tcm.jobs.enabled=false") @AutoConfigureMockMvc @Transactional
class SourceWorkflowIntegrationTest {
    @org.junit.jupiter.api.io.TempDir static java.nio.file.Path directory;
    @DynamicPropertySource static void storage(DynamicPropertyRegistry p){p.add("tcm.storage.source-directory",()->directory.toString());}
    @Autowired MockMvc mvc; @Autowired ObjectMapper json; @Autowired JdbcTemplate db;
    @Autowired PasswordEncoder encoder; @Autowired org.mybatis.spring.SqlSessionTemplate sqlSession;
    @Autowired SourceJobs jobs;
    record User(long id,String token){}
    private User user(String role) throws Exception {
        String name="sw_"+UUID.randomUUID().toString().replace("-","");
        db.update("INSERT INTO sys_users(username,password_hash,nickname) VALUES(?,?,?)",name,encoder.encode("TestPass891!"),"来源测试");
        long id=db.queryForObject("SELECT id FROM sys_users WHERE username=?",Long.class,name);
        db.update("INSERT INTO sys_user_roles(user_id,role_id) SELECT ?,id FROM sys_roles WHERE code IN ('LEARNER',?)",id,role);
        sqlSession.clearCache();
        return new User(id,call(post("/api/v1/auth/login").contentType("application/json").content(json.writeValueAsString(Map.of("username",name,"password","TestPass891!"))),200).path("accessToken").asText());
    }
    private MockHttpServletRequestBuilder as(User u,MockHttpServletRequestBuilder b){return b.header("Authorization","Bearer "+u.token());}
    private JsonNode call(MockHttpServletRequestBuilder b,int status) throws Exception {return json.readTree(mvc.perform(b).andExpect(status().is(status)).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8)).path("data");}
    private JsonNode body(User u,String method,String path,Map<String,?> data,int status) throws Exception {return call(as(u,(method.equals("PATCH")?patch(path):post(path)).contentType("application/json").content(json.writeValueAsString(data))),status);}
    private JsonNode upload(User u,String value) throws Exception {return call(as(u,multipart("/api/v1/files").file(new MockMultipartFile("file","source.txt","text/plain",value.getBytes(StandardCharsets.UTF_8)))),200);}
    private Map<String,Object> rights(){return Map.of("licenseType","AUTHORIZED","copyrightSource","来源测试作者","authorizationScope","阅读、下载与引用测试授权","publicReadingAllowed",true,"publicDownloadAllowed",true,"quoteDisplayAllowed",true,"ragUseAllowed",false);}
    private JsonNode create(User u,String file) throws Exception {return body(u,"POST","/api/v1/source-documents",Map.of("title","中医资料","sourceType","BOOK","fileId",file,"rights",rights()),200);}
    @Test void actualUploadDeduplicatesAcrossUsersWithoutGrantingGuessersAccess() throws Exception {
        User a=user("CONTRIBUTOR"),b=user("CONTRIBUTOR"),c=user("LEARNER");
        JsonNode one=upload(a,"药材来源 "+UUID.randomUUID()); String id=one.path("id").asText();
        call(as(b,get("/api/v1/files/"+id)),404);
        call(as(c,get("/api/v1/files/"+id)),404);
        String original=new String(mvc.perform(as(a,get("/api/v1/files/"+id))).andReturn().getResponse().getContentAsByteArray(),StandardCharsets.UTF_8);
        JsonNode two=call(as(b,multipart("/api/v1/files").file(new MockMultipartFile("file","my-own-filename.txt","text/plain",original.getBytes(StandardCharsets.UTF_8)))),200);
        assertThat(two.path("id").asText()).isEqualTo(id); assertThat(two.path("deduplicated").asBoolean()).isTrue();
        assertThat(two.path("originalName").asText()).isEqualTo("my-own-filename.txt");
        assertThat(one.has("storageKey")).isFalse();
        assertThat(create(b,id).path("fileName").asText()).isEqualTo("my-own-filename.txt");
        mvc.perform(as(b,get("/api/v1/files/"+id))).andExpect(status().isOk()).andExpect(header().string("Content-Disposition",org.hamcrest.Matchers.containsString("my-own-filename")));
        body(c,"POST","/api/v1/source-documents",Map.of("title","违规","sourceType","BOOK","fileId",id),403);
    }
    @Test void submittedDocumentFreezesAndWithdrawalRequiresCopy() throws Exception {
        User a=user("CONTRIBUTOR"),b=user("CONTRIBUTOR");
        String id=create(a,upload(a,"冻结示例").path("id").asText()).path("id").asText();
        body(b,"PATCH","/api/v1/source-documents/"+id,Map.of("title","越权","sourceType","BOOK"),404);
        call(as(a,post("/api/v1/source-documents/"+id+"/submit")),409); // file has not parsed
        db.update("INSERT INTO document_parse_runs(document_id,parse_version,parser_name,status) VALUES(?,1,'test','SUCCEEDED')",id);
        JsonNode submitted=call(as(a,post("/api/v1/source-documents/"+id+"/submit")),200);
        assertThat(submitted.path("reviewState").asText()).isEqualTo("IN_REVIEW");
        body(a,"PATCH","/api/v1/source-documents/"+id,Map.of("title","改写","sourceType","BOOK"),409);
        call(as(a,post("/api/v1/source-documents/"+id+"/parse")),409);
        assertThat(call(as(a,post("/api/v1/source-documents/"+id+"/withdraw")),200).path("reviewState").asText()).isEqualTo("WITHDRAWN");
        JsonNode revised=call(as(a,post("/api/v1/source-documents/"+id+"/revise")),200);
        assertThat(revised.path("parentDocumentId").asText()).isEqualTo(id);
        assertThat(revised.path("reviewState").asText()).isEqualTo("DRAFT");
        assertThat(revised.path("id").asText()).isNotEqualTo(id);
        assertThat(call(as(a,get("/api/v1/source-documents/"+id)),200).path("reviewState").asText()).isEqualTo("WITHDRAWN");
    }
    @Test void parsesAreImmutableAndOldRunTokensCannotWriteAfterRetry() throws Exception {
        User a=user("CONTRIBUTOR"),admin=user("ADMIN");
        long doc=Long.parseLong(create(a,upload(a,"解析任务").path("id").asText()).path("id").asText());
        JsonNode run=call(as(a,post("/api/v1/source-documents/"+doc+"/parse")),200);
        var lease=jobs.claim(); assertThat(lease).isNotNull();
        db.update("UPDATE async_jobs SET lease_until=UTC_TIMESTAMP(3)-INTERVAL 1 SECOND WHERE id=?",lease.id());
        call(as(admin,post("/api/v1/admin/jobs/"+lease.id()+"/retries")),200);
        assertThat(jobs.complete(lease,List.of(new SourceJobs.ParsedSegment(1,"段落 1",null,"过期内容")),null)).isFalse();
        var second=jobs.claim(); assertThat(second.runToken()).isNotEqualTo(lease.runToken());
        assertThat(jobs.complete(second,List.of(new SourceJobs.ParsedSegment(1,"段落 1",null,"已确认内容")),null)).isTrue();
        assertThat(call(as(a,get("/api/v1/source-documents/"+doc+"/segments")),200).path("items").get(0).path("body").asText()).isEqualTo("已确认内容");
        JsonNode another=call(as(a,post("/api/v1/source-documents/"+doc+"/parse")),200);
        assertThat(another.path("id").asText()).isNotEqualTo(run.path("id").asText());
        assertThat(call(as(a,get("/api/v1/source-documents/"+doc+"/segments").param("parseRunId",run.path("id").asText())),200).path("items").size()).isEqualTo(1);
        assertThat(call(as(admin,get("/api/v1/admin/jobs")),200).path("items").size()).isGreaterThanOrEqualTo(2);
    }
    @Test void failedParseKeepsReasonAndRetryRecordsAttempts() throws Exception {
        User a=user("CONTRIBUTOR"),admin=user("ADMIN");
        long doc=Long.parseLong(create(a,upload(a,"失败重试").path("id").asText()).path("id").asText());
        call(as(a,post("/api/v1/source-documents/"+doc+"/parse")),200);
        var lease=jobs.claim(); assertThat(jobs.complete(lease,null,"解析服务暂不可用")).isTrue();
        assertThat(call(as(a,get("/api/v1/source-documents/"+doc)),200).path("parseRun").path("errorMessage").asText()).contains("暂不可用");
        call(as(a,post("/api/v1/admin/jobs/"+lease.id()+"/retries")),403);
        call(as(admin,post("/api/v1/admin/jobs/"+lease.id()+"/retries")),200);
        var retry=jobs.claim(); jobs.complete(retry,List.of(new SourceJobs.ParsedSegment(1,"段落 1",null,"重试成功")),null);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM async_job_attempts WHERE job_id=?",Integer.class,lease.id())).isEqualTo(2);
    }
    @Test void privateListsAndApprovedFilesRespectProfessionalPermission() throws Exception {
        User a=user("CONTRIBUTOR"),reader=user("LEARNER"),admin=user("ADMIN"),reviewer=user("REVIEWER");
        String file=upload(a,"公开筛选").path("id").asText(),doc=create(a,file).path("id").asText();
        call(as(reader,get("/api/v1/source-documents/"+doc)),404); call(as(admin,get("/api/v1/source-documents/"+doc)),404);
        call(as(reviewer,get("/api/v1/source-documents/"+doc)),200);
        db.update("UPDATE source_documents SET review_state='APPROVED' WHERE id=?",doc);
        call(as(reader,get("/api/v1/source-documents/"+doc)),200);
        mvc.perform(as(reader,get("/api/v1/files/"+file))).andExpect(status().isOk());
        body(reviewer,"POST","/api/v1/source-documents/"+doc+"/suspend",Map.of("reason","书目存疑"),200);
        call(as(reader,get("/api/v1/source-documents/"+doc)),404);
        mvc.perform(as(reader,get("/api/v1/files/"+file))).andExpect(status().isNotFound());
    }
    @Test void uploadRejectsSpoofedUtf8CorruptOfficeAndOversizedBytes() throws Exception {
        User a=user("CONTRIBUTOR");
        for(var sample:List.of(new MockMultipartFile("file","a.pdf","application/pdf","fake".getBytes()),new MockMultipartFile("file","a.docx","application/octet-stream","PK fake".getBytes()),new MockMultipartFile("file","a.txt","text/plain",new byte[]{(byte)0xff}),new MockMultipartFile("file","a.txt","text/plain",new byte[50*1024*1024+1]))){call(as(a,multipart("/api/v1/files").file(sample)),400);}
    }
    @Test void realDocxAndTextPdfUploadAndDocxEntityPayloadIsRejected() throws Exception {
        User a=user("CONTRIBUTOR");
        var office=new java.io.ByteArrayOutputStream();
        try(var zip=new java.util.zip.ZipOutputStream(office)){
            zip.putNextEntry(new java.util.zip.ZipEntry("[Content_Types].xml"));zip.write("<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"><Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/></Types>".getBytes(StandardCharsets.UTF_8));zip.closeEntry();
            zip.putNextEntry(new java.util.zip.ZipEntry("word/document.xml"));zip.write("<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body><w:p><w:r><w:t>真实段落</w:t></w:r></w:p></w:body></w:document>".getBytes(StandardCharsets.UTF_8));zip.closeEntry();
        }
        assertThat(call(as(a,multipart("/api/v1/files").file(new MockMultipartFile("file","valid.docx","text/plain",office.toByteArray()))),200).path("mimeType").asText()).contains("wordprocessingml");
        var pdf=new java.io.ByteArrayOutputStream();
        try(var document=new org.apache.pdfbox.pdmodel.PDDocument()){
            var page=new org.apache.pdfbox.pdmodel.PDPage();document.addPage(page);
            try(var content=new org.apache.pdfbox.pdmodel.PDPageContentStream(document,page)){content.beginText();content.setFont(new org.apache.pdfbox.pdmodel.font.PDType1Font(org.apache.pdfbox.pdmodel.font.Standard14Fonts.FontName.HELVETICA),12);content.newLineAtOffset(20,700);content.showText("Herb reference");content.endText();}
            document.save(pdf);
        }
        assertThat(call(as(a,multipart("/api/v1/files").file(new MockMultipartFile("file","valid.pdf","text/plain",pdf.toByteArray()))),200).path("mimeType").asText()).isEqualTo("application/pdf");
        var malicious=new java.io.ByteArrayOutputStream();
        try(var zip=new java.util.zip.ZipOutputStream(malicious)){
            zip.putNextEntry(new java.util.zip.ZipEntry("[Content_Types].xml"));zip.write("<!DOCTYPE a [<!ENTITY e SYSTEM 'file:///C:/private'>]><a>&e;</a>".getBytes(StandardCharsets.UTF_8));zip.closeEntry();
            zip.putNextEntry(new java.util.zip.ZipEntry("word/document.xml"));zip.write("<a/>".getBytes(StandardCharsets.UTF_8));zip.closeEntry();
        }
        call(as(a,multipart("/api/v1/files").file(new MockMultipartFile("file","bad.docx","application/octet-stream",malicious.toByteArray()))),400);
    }
    @Test void bibliographicWebSourcesAreNotFetchedAndCanSubmitWithoutAFile() throws Exception {
        User a=user("CONTRIBUTOR");
        JsonNode d=body(a,"POST","/api/v1/source-documents",Map.of("title","出版书目","sourceType","WEB","sourceUrl","https://example.org/catalog","rights",rights()),200);
        String id=d.path("id").asText();
        call(as(a,post("/api/v1/source-documents/"+id+"/parse")),400);
        assertThat(call(as(a,post("/api/v1/source-documents/"+id+"/submit")),200).path("reviewState").asText()).isEqualTo("IN_REVIEW");
        body(a,"POST","/api/v1/source-documents",Map.of("title","任意路径","sourceType","WEB","sourceUrl","file:///C:/secret"),400);
    }
    @Test void suspendingSourceListsAndDisablesDependentPublishedKnowledge() throws Exception {
        User a=user("CONTRIBUTOR"),reviewer=user("REVIEWER");
        String doc=create(a,upload(a,"停用影响").path("id").asText()).path("id").asText();
        db.update("UPDATE source_documents SET review_state='APPROVED' WHERE id=?",doc);
        db.update("INSERT INTO content_resources(resource_type,owner_id) VALUES('KNOWLEDGE',?)",a.id());
        long resource=db.queryForObject("SELECT MAX(id) FROM content_resources WHERE owner_id=?",Long.class,a.id());
        db.update("INSERT INTO content_revisions(resource_id,version_no,title,body,revision_state,created_by) VALUES(?,1,'依赖知识','依据原文','APPROVED',?)",resource,a.id());
        long revision=db.queryForObject("SELECT MAX(id) FROM content_revisions WHERE resource_id=?",Long.class,resource);
        db.update("UPDATE content_resources SET visibility_status='PUBLISHED',published_revision_id=? WHERE id=?",revision,resource);
        db.update("INSERT INTO revision_evidence(revision_id,document_id,location_label,quote_text) VALUES(?,?,'第一节','原文')",revision,doc);
        JsonNode impact=body(reviewer,"POST","/api/v1/source-documents/"+doc+"/suspend",Map.of("reason","来源存在勘误"),200);
        assertThat(impact.path("affectedResources").get(0).path("id").asText()).isEqualTo(""+resource);
        assertThat(db.queryForObject("SELECT visibility_status FROM content_resources WHERE id=?",String.class,resource)).isEqualTo("SUSPENDED");
        assertThat(db.queryForObject("SELECT COUNT(*) FROM async_jobs WHERE document_id=? AND job_type='REMOVE_INDEX' AND status='QUEUED'",Long.class,doc)).isEqualTo(1L);
    }
    @Test void reuploadingIdenticalBytesPreservesFrozenSourceFilename() throws Exception {
        User a=user("CONTRIBUTOR");String content="文件名冻结 "+UUID.randomUUID();
        JsonNode first=upload(a,content);String file=first.path("id").asText();String doc=create(a,file).path("id").asText();
        db.update("UPDATE source_documents SET review_state='APPROVED' WHERE id=?",doc);
        JsonNode duplicate=call(as(a,multipart("/api/v1/files").file(new MockMultipartFile("file","changed-private-name.txt","text/plain",content.getBytes(StandardCharsets.UTF_8)))),200);
        assertThat(duplicate.path("deduplicated").asBoolean()).isTrue();
        assertThat(duplicate.path("originalName").asText()).isEqualTo("source.txt");
        assertThat(call(as(a,get("/api/v1/source-documents/"+doc)),200).path("fileName").asText()).isEqualTo("source.txt");
    }
}
