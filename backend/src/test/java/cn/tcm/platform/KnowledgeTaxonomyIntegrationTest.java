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
class KnowledgeTaxonomyIntegrationTest {
    @Autowired MockMvc mvc; @Autowired ObjectMapper json; @Autowired JdbcTemplate db; @Autowired PasswordEncoder passwords;
    record User(long id,String token){}
    private JsonNode call(User user,MockHttpServletRequestBuilder request,Object body,int status)throws Exception{
        if(user!=null)request.header("Authorization","Bearer "+user.token());if(body!=null)request.contentType("application/json").content(json.writeValueAsString(body));
        return json.readTree(mvc.perform(request).andExpect(status().is(status)).andReturn().getResponse().getContentAsString()).path("data");
    }
    private User user(String role)throws Exception{String name="taxonomy_"+UUID.randomUUID().toString().replace("-","");db.update("INSERT INTO sys_users(username,password_hash,nickname) VALUES(?,?,?)",name,passwords.encode("TestPass891!"),"分类验收");long id=db.queryForObject("SELECT id FROM sys_users WHERE username=?",Long.class,name);db.update("INSERT INTO sys_user_roles(user_id,role_id) SELECT ?,id FROM sys_roles WHERE code IN ('LEARNER',?)",id,role);return new User(id,call(null,post("/api/v1/auth/login"),Map.of("username",name,"password","TestPass891!"),200).path("accessToken").asText());}
    private String category(String type){return db.queryForObject("SELECT id FROM content_categories WHERE knowledge_type=? AND active=1 ORDER BY sort_order,id LIMIT 1",String.class,type);}
    private Map<String,Object> input(String type,String category){var in=new LinkedHashMap<String,Object>();in.put("type",type);in.put("categoryId",category);in.put("canonicalName","分类验收"+UUID.randomUUID());in.put("title",in.get("canonicalName"));in.put("body","仅验证分类约束，不提供医学内容。");in.put("aliases",List.of());in.put("evidence",List.of());in.put("ingredients",List.of());in.put("relations",List.of());return in;}
    @Test void sixTypedDirectoriesAndAdminPermissionVersionsAndDisable()throws Exception{
        var learner=user("LEARNER");var admin=user("ADMIN");
        for(String type:List.of("CONCEPT","HERB","FORMULA","EFFECT","SYMPTOM","MERIDIAN")){var rows=call(learner,get("/api/v1/knowledge/categories").param("type",type),null,200);assertThat(rows.size()).isGreaterThan(0);for(var row:rows)assertThat(row.path("type").asText()).isEqualTo(type);}
        call(learner,get("/api/v1/knowledge/categories").param("type","INVALID"),null,400);
        call(learner,get("/api/v1/admin/knowledge/categories"),null,403);
        var body=Map.of("type","FORMULA","name","可维护目录","sortOrder",1,"active",true);
        call(learner,post("/api/v1/admin/knowledge/categories"),body,403);
        var created=call(admin,post("/api/v1/admin/knowledge/categories"),body,201);String id=created.path("id").asText();
        call(admin,post("/api/v1/admin/knowledge/categories"),body,409);
        var edit=Map.of("name","已改名目录","sortOrder",0,"active",false,"lockVersion",0);
        var updated=call(admin,patch("/api/v1/admin/knowledge/categories/"+id),edit,200);assertThat(updated.path("lockVersion").asInt()).isEqualTo(1);
        call(admin,patch("/api/v1/admin/knowledge/categories/"+id),edit,409);
        assertThat(call(learner,get("/api/v1/knowledge/categories").param("type","FORMULA"),null,200).toString()).doesNotContain("已改名目录");
        String legacy=db.queryForObject("SELECT id FROM content_categories WHERE legacy_archive=1 LIMIT 1",String.class);
        call(admin,patch("/api/v1/admin/knowledge/categories/"+legacy),Map.of("name","禁止重启","sortOrder",0,"active",true,"lockVersion",0),409);
    }
    @Test void rejectsCrossTypeWritesImportsAndFiltersAndPreservesSharedTags()throws Exception{
        var writer=user("CONTRIBUTOR");var admin=user("ADMIN");String herb=category("HERB"),formula=category("FORMULA");
        call(writer,post("/api/v1/knowledge"),input("FORMULA",herb),400);
        call(writer,get("/api/v1/knowledge").param("type","FORMULA").param("categoryId",herb),null,400);
        call(writer,get("/api/v1/knowledge").param("categoryId",formula),null,400);
        var tag=call(admin,post("/api/v1/admin/knowledge/tags"),Map.of("name","跨类型标签"+UUID.randomUUID(),"active",true),201);String tagId=tag.path("id").asText();
        for(String type:List.of("HERB","FORMULA")){var in=input(type,category(type));in.put("tagIds",List.of(tagId));var draft=call(writer,post("/api/v1/knowledge"),in,201);assertThat(draft.path("tags").get(0).path("id").asText()).isEqualTo(tagId);var invalid=input(type,type.equals("HERB")?formula:herb);call(writer,patch("/api/v1/knowledge/"+draft.path("id").asText()+"/draft"),invalid,400);in.remove("tagIds");assertThat(call(writer,patch("/api/v1/knowledge/"+draft.path("id").asText()+"/draft"),in,200).path("tags").size()).isEqualTo(1);}
        var bad=input("HERB",herb);bad.put("tagIds",List.of(tagId,tagId));call(writer,post("/api/v1/knowledge"),bad,400);
        long before=db.queryForObject("SELECT COUNT(*) FROM content_resources WHERE owner_id=?",Long.class,writer.id());call(writer,post("/api/v1/knowledge/import"),Map.of("items",List.of(input("HERB",herb),input("FORMULA",herb))),400);assertThat(db.queryForObject("SELECT COUNT(*) FROM content_resources WHERE owner_id=?",Long.class,writer.id())).isEqualTo(before);
    }
    @Test void sharedTagsRemainVersionedSearchableAndInactiveTagsCannotBeReused()throws Exception{
        var writer=user("CONTRIBUTOR");var reviewer=user("REVIEWER");var admin=user("ADMIN");
        String name="跨类型检索"+UUID.randomUUID();var tag=call(admin,post("/api/v1/admin/knowledge/tags"),Map.of("name",name,"active",true),201);String tagId=tag.path("id").asText();
        db.update("INSERT INTO source_documents(title,source_type,source_url,submitted_by,review_state,rights_status,license_type,copyright_source,authorization_scope,public_reading_allowed,quote_display_allowed) VALUES('标签验收来源','WEB','https://example.invalid',?,'APPROVED','DECLARED','AUTHORIZED','Test author','Citation and reading',1,1)",writer.id());String doc=db.queryForObject("SELECT MAX(id) FROM source_documents WHERE submitted_by=?",String.class,writer.id());
        String resource="";
        for(String type:List.of("CONCEPT","HERB")){var in=input(type,category(type));in.put("tagIds",List.of(tagId));in.put("evidence",List.of(Map.of("documentId",doc,"locationLabel","第1条","quoteText","非医学验收记录")));var draft=call(writer,post("/api/v1/knowledge"),in,201);resource=draft.path("id").asText();String revision=draft.path("revisionId").asText();call(writer,post("/api/v1/revisions/"+revision+"/submit"),null,200);String task=db.queryForObject("SELECT id FROM review_tasks WHERE revision_id=?",String.class,revision);call(reviewer,post("/api/v1/reviews/"+task+"/decisions"),Map.of("decision","APPROVED","note","标签校验","lockVersion",0),200);}
        assertThat(call(writer,get("/api/v1/knowledge").param("tagId",tagId),null,200).path("total").asInt()).isEqualTo(2);
        assertThat(call(writer,get("/api/v1/knowledge").param("q",name),null,200).path("total").asInt()).isEqualTo(2);
        var revised=call(writer,post("/api/v1/knowledge/"+resource+"/revise"),null,200);assertThat(revised.path("tags").get(0).path("id").asText()).isEqualTo(tagId);
        call(admin,patch("/api/v1/admin/knowledge/tags/"+tagId),Map.of("name",name,"active",false,"lockVersion",0),200);
        assertThat(call(writer,get("/api/v1/knowledge/"+resource),null,200).path("tags").get(0).path("active").asBoolean()).isFalse();
        call(writer,post("/api/v1/revisions/"+revised.path("revisionId").asText()+"/submit"),null,409);
        call(writer,get("/api/v1/knowledge").param("tagId",tagId),null,400);
        var in=input("CONCEPT",category("CONCEPT"));in.put("tagIds",List.of(tagId));call(writer,post("/api/v1/knowledge"),in,400);
    }
    @Test void disabledDirectoryBlocksSubmissionAndApprovalButNotExistingPublishedReading()throws Exception{
        var writer=user("CONTRIBUTOR");var admin=user("ADMIN");var reviewer=user("REVIEWER");
        var dir=call(admin,post("/api/v1/admin/knowledge/categories"),Map.of("type","CONCEPT","name","发布校验目录","sortOrder",0,"active",true),201);String category=dir.path("id").asText();
        db.update("INSERT INTO source_documents(title,source_type,source_url,submitted_by,review_state,rights_status,license_type,copyright_source,authorization_scope,public_reading_allowed,quote_display_allowed) VALUES('验收来源','WEB','https://example.invalid',?,'APPROVED','DECLARED','AUTHORIZED','Test author','Citation and reading',1,1)",writer.id());String doc=db.queryForObject("SELECT MAX(id) FROM source_documents WHERE submitted_by=?",String.class,writer.id());
        var in=input("CONCEPT",category);in.put("evidence",List.of(Map.of("documentId",doc,"locationLabel","第1条","quoteText","非医学验收记录")));
        var draft=call(writer,post("/api/v1/knowledge"),in,201);String revision=draft.path("revisionId").asText();
        call(admin,patch("/api/v1/admin/knowledge/categories/"+category),Map.of("name","发布校验目录","sortOrder",0,"active",false,"lockVersion",0),200);
        call(writer,post("/api/v1/revisions/"+revision+"/submit"),null,409);
        call(admin,patch("/api/v1/admin/knowledge/categories/"+category),Map.of("name","发布校验目录","sortOrder",0,"active",true,"lockVersion",1),200);
        call(writer,post("/api/v1/revisions/"+revision+"/submit"),null,200);
        String task=db.queryForObject("SELECT id FROM review_tasks WHERE revision_id=?",String.class,revision);
        call(admin,patch("/api/v1/admin/knowledge/categories/"+category),Map.of("name","发布校验目录","sortOrder",0,"active",false,"lockVersion",2),200);
        call(reviewer,post("/api/v1/reviews/"+task+"/decisions"),Map.of("decision","APPROVED","note","校验","lockVersion",0),409);
        call(admin,patch("/api/v1/admin/knowledge/categories/"+category),Map.of("name","发布校验目录","sortOrder",0,"active",true,"lockVersion",3),200);
        call(reviewer,post("/api/v1/reviews/"+task+"/decisions"),Map.of("decision","APPROVED","note","校验","lockVersion",0),200);
        call(admin,patch("/api/v1/admin/knowledge/categories/"+category),Map.of("name","发布校验目录","sortOrder",0,"active",false,"lockVersion",4),200);
        assertThat(call(writer,get("/api/v1/knowledge/"+draft.path("id").asText()),null,200).path("categoryActive").asBoolean()).isFalse();
        var revised=call(writer,post("/api/v1/knowledge/"+draft.path("id").asText()+"/revise"),null,200);assertThat(revised.path("categoryId").isNull()).isTrue();
    }
}
