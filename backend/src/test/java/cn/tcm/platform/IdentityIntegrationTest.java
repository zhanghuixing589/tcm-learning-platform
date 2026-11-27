package cn.tcm.platform;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import java.util.Map;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class IdentityIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate db;
    @Autowired cn.tcm.platform.identity.infrastructure.SecurityProperties securityProperties;
    private static final String PASSWORD = "TestPass891!";
    record Login(String id, String access, Cookie refresh) {}

    @Test void applicationFiltersUseMatchingCountsAndPreserveOwnershipAndPermissions() throws Exception {
        Login owner=register();
        try {
            for(String state:new String[]{"PENDING","APPROVED","REJECTED","WITHDRAWN"})
                db.update("INSERT INTO sys_role_applications(user_id,role_id,statement,status) SELECT ?,id,'filter fixture',? FROM sys_roles WHERE code='CONTRIBUTOR'",owner.id(),state);
            var manager=org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user("1")
                    .authorities(new org.springframework.security.core.authority.SimpleGrantedAuthority("system:user:manage"));
            for(String filter:new String[]{"ALL","PENDING","PROCESSED"}) {
                String predicate=filter.equals("ALL")?"1=1":filter.equals("PENDING")?"status='PENDING'":"status IN ('APPROVED','REJECTED','WITHDRAWN')";
                long expected=db.queryForObject("SELECT COUNT(*) FROM sys_role_applications WHERE "+predicate,Long.class);
                var result=mvc.perform(get("/api/v1/admin/role-applications").with(manager).param("status",filter).param("pageSize","1"))
                        .andExpect(status().isOk()).andReturn();
                JsonNode body=json.readTree(result.getResponse().getContentAsString()).path("data");
                assertThat(body.path("total").asLong()).isEqualTo(expected);
                assertThat(body.path("items").size()).isEqualTo(1);
                String state=body.path("items").get(0).path("status").asText();
                if(filter.equals("PENDING"))assertThat(state).isEqualTo("PENDING");
                if(filter.equals("PROCESSED"))assertThat(state).isIn("APPROVED","REJECTED","WITHDRAWN");
            }
            mvc.perform(get("/api/v1/admin/role-applications").with(manager).param("status","INVALID")).andExpect(status().isBadRequest());
            mvc.perform(get("/api/v1/admin/role-applications")).andExpect(status().isUnauthorized());
            mvc.perform(get("/api/v1/admin/role-applications").with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user(owner.id())))
                    .andExpect(status().isForbidden());
            var own=mvc.perform(get("/api/v1/me/role-applications").with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user(owner.id())))
                    .andExpect(status().isOk()).andReturn();
            JsonNode ownBody=json.readTree(own.getResponse().getContentAsString()).path("data");
            assertThat(ownBody.path("total").asInt()).isEqualTo(4);
            for(JsonNode item:ownBody.path("items"))assertThat(item.path("userId").asText()).isEqualTo(owner.id());
        } finally { db.update("DELETE FROM sys_role_applications WHERE user_id=?",owner.id()); }
    }

    private Login register() throws Exception {
        String name = "test_" + UUID.randomUUID().toString().replace("-", "").substring(0, 15);
        mvc.perform(post("/api/v1/auth/register").contentType("application/json")
                .content(json.writeValueAsString(Map.of("username",name,"password",PASSWORD,"nickname","学习者"))))
                .andExpect(status().isCreated());
        return login(name);
    }
    private Login login(String name) throws Exception {
        MvcResult r = mvc.perform(post("/api/v1/auth/login").contentType("application/json")
                .content(json.writeValueAsString(Map.of("username",name,"password",PASSWORD))))
                .andExpect(status().isOk()).andReturn();
        JsonNode data = json.readTree(r.getResponse().getContentAsString()).path("data");
        return new Login(data.path("user").path("id").asText(), data.path("accessToken").asText(), r.getResponse().getCookie("tcm_refresh"));
    }
    private Login admin() throws Exception {
        Login u = register();
        db.update("INSERT INTO sys_user_roles(user_id,role_id) SELECT ?,id FROM sys_roles WHERE code='ADMIN'",u.id());
        return u;
    }
    private MvcResult refresh(Cookie cookie) throws Exception {
        return mvc.perform(post("/api/v1/auth/refresh").cookie(cookie).header("Origin",securityProperties.allowedOrigins().get(0)).header("X-CSRF-Token","1"))
                .andReturn();
    }
    @Test void registrationDefaultsToLearnerAndNeverExposesSecrets() throws Exception {
        Login u = register();
        mvc.perform(get("/api/v1/me").header("Authorization","Bearer " + u.access()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.roles[0]").value("LEARNER"))
                .andExpect(jsonPath("$.data.passwordHash").doesNotExist()).andExpect(jsonPath("$.data.id").isString())
                .andExpect(jsonPath("$.traceId").isNotEmpty()).andExpect(header().exists("X-Trace-Id"));
        assertThat(db.queryForObject("SELECT password_hash FROM sys_users WHERE id=?",String.class,u.id())).startsWith("$2");
        assertThat(u.refresh().isHttpOnly()).isTrue();
        assertThat(u.refresh().getAttribute("SameSite")).isEqualTo("Strict");
    }
    @Test void unauthenticatedAndLearnerCannotUseAdminApis() throws Exception {
        mvc.perform(get("/api/v1/me")).andExpect(status().isUnauthorized());
        Login u = register();
        mvc.perform(get("/api/v1/admin/users").header("Authorization","Bearer " + u.access()))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mvc.perform(get("/api/v1/admin/users").header("Authorization","Bearer broken")).andExpect(status().isUnauthorized());
    }
    @Test void validationAndDuplicateUserAreControlledErrors() throws Exception {
        String body = json.writeValueAsString(Map.of("username","short","password","123","nickname","x"));
        mvc.perform(post("/api/v1/auth/register").contentType("application/json").content(body))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        Login u = register();
        String username=db.queryForObject("SELECT username FROM sys_users WHERE id=?",String.class,u.id());
        body=json.writeValueAsString(Map.of("username",username,"password",PASSWORD,"nickname","x"));
        mvc.perform(post("/api/v1/auth/register").contentType("application/json").content(body)).andExpect(status().isConflict());
        mvc.perform(post("/api/v1/auth/login").contentType("application/json")
                .content(json.writeValueAsString(Map.of("username",username,"password","incorrect")))).andExpect(status().isUnauthorized());
    }
    @Test void registrationPasswordPolicyIsEnforcedByHttpWithoutCreatingInvalidAccounts() throws Exception {
        String username="policy_"+UUID.randomUUID().toString().replace("-","").substring(0,15);
        for(String password:new String[]{"Abcdefg12","Abcdefghijk1!","abcdefgh12","Abcdefghij","Abcdef12中文","Abcdef12 !","Abcdefgh１!"}) {
            mvc.perform(post("/api/v1/auth/register").contentType("application/json")
                    .content(json.writeValueAsString(Map.of("username",username,"password",password,"nickname","学习者"))))
                    .andExpect(status().isBadRequest());
        }
        assertThat(db.queryForObject("SELECT COUNT(*) FROM sys_users WHERE username=?",Integer.class,username)).isZero();
        for(String password:new String[]{"Abcdefgh12","Abcdefghij1!"}) {
            String validName=username+password.length();
            mvc.perform(post("/api/v1/auth/register").contentType("application/json")
                    .content(json.writeValueAsString(Map.of("username",validName,"password",password,"nickname","学习者"))))
                    .andExpect(status().isCreated());
            mvc.perform(post("/api/v1/auth/login").contentType("application/json")
                    .content(json.writeValueAsString(Map.of("username",validName,"password",password))))
                    .andExpect(status().isOk());
        }
    }
    @Test void refreshRotatesAndReplayRevokesTheWholeFamily() throws Exception {
        Login u=register();
        MvcResult next=refresh(u.refresh());
        assertThat(next.getResponse().getStatus()).isEqualTo(200);
        Cookie replacement=next.getResponse().getCookie("tcm_refresh");
        assertThat(replacement.getValue()).isNotEqualTo(u.refresh().getValue());
        assertThat(refresh(u.refresh()).getResponse().getStatus()).isEqualTo(401);
        assertThat(refresh(replacement).getResponse().getStatus()).isEqualTo(401);
        mvc.perform(get("/api/v1/me").header("Authorization","Bearer "+u.access())).andExpect(status().isUnauthorized());
        assertThat(db.queryForObject("SELECT COUNT(*) FROM auth_refresh_tokens WHERE user_id=? AND revoked_at IS NULL",Integer.class,u.id())).isZero();
    }
    @Test void sessionDeadlineIsThirtyDaysAndNeverMovesDuringRotation() throws Exception {
        Login u=register();
        var deadline=db.queryForObject("SELECT absolute_expires_at FROM auth_refresh_tokens WHERE user_id=?",java.sql.Timestamp.class,u.id());
        assertThat(deadline.toInstant()).isBetween(java.time.Instant.now().plusSeconds(30L*86400-10),java.time.Instant.now().plusSeconds(30L*86400));
        Cookie cookie=u.refresh();
        for(int i=0;i<2;i++) {
            MvcResult r=refresh(cookie);
            assertThat(r.getResponse().getStatus()).isEqualTo(200);
            cookie=r.getResponse().getCookie("tcm_refresh");
        }
        assertThat(db.queryForList("SELECT DISTINCT absolute_expires_at FROM auth_refresh_tokens WHERE user_id=?",java.sql.Timestamp.class,u.id())).containsExactly(deadline);
    }
    @Test void nearDeadlineCapsRefreshCookieAndAccessJwt() throws Exception {
        Login u=register();
        db.update("UPDATE auth_refresh_tokens SET absolute_expires_at=UTC_TIMESTAMP(3)+INTERVAL 45 SECOND,expires_at=UTC_TIMESTAMP(3)+INTERVAL 45 SECOND WHERE user_id=?",u.id());
        var deadline=db.queryForObject("SELECT absolute_expires_at FROM auth_refresh_tokens WHERE user_id=?",java.sql.Timestamp.class,u.id()).toInstant();
        MvcResult r=refresh(u.refresh());
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        assertThat(r.getResponse().getCookie("tcm_refresh").getMaxAge()).isBetween(1,45);
        JsonNode data=json.readTree(r.getResponse().getContentAsString()).path("data");
        assertThat(data.path("expiresIn").asLong()).isBetween(1L,45L);
        JsonNode jwt=json.readTree(java.util.Base64.getUrlDecoder().decode(data.path("accessToken").asText().split("\\.")[1]));
        assertThat(java.time.Instant.ofEpochSecond(jwt.path("exp").asLong())).isBeforeOrEqualTo(deadline);
        assertThat(db.queryForList("SELECT expires_at FROM auth_refresh_tokens WHERE user_id=?",java.sql.Timestamp.class,u.id())).allSatisfy(t->assertThat(t.toInstant()).isBeforeOrEqualTo(deadline));
    }
    @Test void absoluteDeadlineRejectsBothRefreshAndExistingAccessAndAllowsNewLogin() throws Exception {
        Login u=register();
        db.update("UPDATE auth_refresh_tokens SET expires_at=UTC_TIMESTAMP(3)-INTERVAL 1 SECOND,absolute_expires_at=UTC_TIMESTAMP(3)-INTERVAL 1 SECOND WHERE user_id=?",u.id());
        mvc.perform(get("/api/v1/me").header("Authorization","Bearer "+u.access())).andExpect(status().isUnauthorized());
        MvcResult r=refresh(u.refresh());
        assertThat(r.getResponse().getStatus()).isEqualTo(401);
        assertThat(r.getResponse().getCookie("tcm_refresh").getMaxAge()).isZero();
        assertThat(db.queryForObject("SELECT COUNT(*) FROM auth_refresh_tokens WHERE user_id=? AND revoked_at IS NULL",Integer.class,u.id())).isZero();
        String name=db.queryForObject("SELECT username FROM sys_users WHERE id=?",String.class,u.id());
        Login renewed=login(name);
        mvc.perform(get("/api/v1/me").header("Authorization","Bearer "+renewed.access())).andExpect(status().isOk());
    }
    @Test void refreshRequiresTrustedOriginAndCsrfHeader() throws Exception {
        Login u=register();
        mvc.perform(post("/api/v1/auth/refresh").cookie(u.refresh())).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/auth/refresh").cookie(u.refresh()).header("Origin","https://evil.example").header("X-CSRF-Token","1"))
                .andExpect(status().isForbidden());
        assertThat(refresh(u.refresh()).getResponse().getStatus()).isEqualTo(200);
    }
    @Test void logoutRevokesSessionAndClearsCookie() throws Exception {
        Login u=register();
        mvc.perform(post("/api/v1/auth/logout").cookie(u.refresh()).header("Origin",securityProperties.allowedOrigins().get(0)).header("X-CSRF-Token","1"))
                .andExpect(status().isOk()).andExpect(cookie().maxAge("tcm_refresh",0));
        assertThat(refresh(u.refresh()).getResponse().getStatus()).isEqualTo(401);
        mvc.perform(get("/api/v1/me").header("Authorization","Bearer "+u.access())).andExpect(status().isUnauthorized());
    }
    @Test void profileAndApplicationsArePrivateAndOnlyContributorCanBeRequested() throws Exception {
        Login a=register(), b=register();
        mvc.perform(patch("/api/v1/me").header("Authorization","Bearer "+a.access()).contentType("application/json")
                .content("{\"nickname\":\"更新昵称\",\"bio\":\"学习笔记\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.nickname").value("更新昵称"));
        mvc.perform(post("/api/v1/me/role-applications").header("Authorization","Bearer "+a.access()).contentType("application/json")
                .content("{\"roleCode\":\"ADMIN\",\"statement\":\"test\"}")).andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/me/role-applications").header("Authorization","Bearer "+a.access()).contentType("application/json")
                .content("{\"roleCode\":\"CONTRIBUTOR\",\"statement\":\"希望贡献资料\"}"))
                .andExpect(status().isCreated());
        mvc.perform(get("/api/v1/me/role-applications").header("Authorization","Bearer "+b.access()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(0));
        mvc.perform(post("/api/v1/me/role-applications").header("Authorization","Bearer "+a.access()).contentType("application/json")
                .content("{\"roleCode\":\"CONTRIBUTOR\",\"statement\":\"重复\"}")).andExpect(status().isConflict());
    }
    @Test void adminApprovesContributionWithoutGainingProfessionalReviewRights() throws Exception {
        Login a=admin(), learner=register();
        MvcResult created=mvc.perform(post("/api/v1/me/role-applications").header("Authorization","Bearer "+learner.access())
                .contentType("application/json").content("{\"roleCode\":\"CONTRIBUTOR\",\"statement\":\"希望贡献资料\"}"))
                .andExpect(status().isCreated()).andReturn();
        String id=json.readTree(created.getResponse().getContentAsString()).path("data").path("id").asText();
        mvc.perform(patch("/api/v1/admin/role-applications/"+id).header("Authorization","Bearer "+a.access())
                .contentType("application/json").content("{\"decision\":\"APPROVED\",\"note\":\"通过\"}"))
                .andExpect(status().isOk());
        mvc.perform(patch("/api/v1/admin/role-applications/"+id).header("Authorization","Bearer "+a.access())
                .contentType("application/json").content("{\"decision\":\"REJECTED\",\"note\":\"重复处理\"}"))
                .andExpect(status().isConflict());
        mvc.perform(get("/api/v1/me").header("Authorization","Bearer "+learner.access()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.roles").value(org.hamcrest.Matchers.hasItem("CONTRIBUTOR")));
        mvc.perform(get("/api/v1/me").header("Authorization","Bearer "+a.access()))
                .andExpect(jsonPath("$.data.permissions").value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.hasItem("knowledge:review"))));
    }
    @Test void disablingAccountImmediatelyRevokesAccessAndRefresh() throws Exception {
        Login a=admin(), u=register();
        mvc.perform(patch("/api/v1/admin/users/"+u.id()+"/status").header("Authorization","Bearer "+a.access())
                .contentType("application/json").content("{\"status\":\"DISABLED\"}"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/me").header("Authorization","Bearer "+u.access())).andExpect(status().isUnauthorized());
        assertThat(refresh(u.refresh()).getResponse().getStatus()).isEqualTo(401);
    }
    @Test void flywayCreatedCompleteSchemaAndPaginationRejectsOversizedRequests() throws Exception {
        assertThat(db.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name <> 'flyway_schema_history'",Integer.class)).isEqualTo(95);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM flyway_schema_history WHERE success=1",Integer.class)).isEqualTo(13);
        Login a=admin();
        mvc.perform(get("/api/v1/admin/users?pageSize=101").header("Authorization","Bearer "+a.access())).andExpect(status().isBadRequest());
    }
    @Test void explicitRoleGrantIsAuditedAndSelfReviewIsRejected() throws Exception {
        Login a=admin(), u=register();
        mvc.perform(put("/api/v1/admin/users/"+u.id()+"/roles").header("Authorization","Bearer "+a.access())
                .contentType("application/json").content("{\"roles\":[\"LEARNER\",\"REVIEWER\"]}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.permissions").value(org.hamcrest.Matchers.hasItem("knowledge:review")));
        assertThat(db.queryForObject("SELECT COUNT(*) FROM sys_audit_logs WHERE actor_id=? AND object_key=? AND action='USER_ROLES_CHANGED'",Integer.class,a.id(),u.id())).isEqualTo(1);
        mvc.perform(put("/api/v1/admin/users/"+u.id()+"/roles").header("Authorization","Bearer "+a.access())
                .contentType("application/json").content("{\"roles\":[null]}"))
                .andExpect(status().isBadRequest());
        assertThat(db.queryForObject("SELECT COUNT(*) FROM sys_user_roles ur JOIN sys_roles r ON r.id=ur.role_id WHERE ur.user_id=? AND r.code='REVIEWER'",Integer.class,u.id())).isEqualTo(1);
        MvcResult r=mvc.perform(post("/api/v1/me/role-applications").header("Authorization","Bearer "+a.access())
                .contentType("application/json").content("{\"roleCode\":\"CONTRIBUTOR\",\"statement\":\"本人申请\"}"))
                .andExpect(status().isCreated()).andReturn();
        String id=json.readTree(r.getResponse().getContentAsString()).path("data").path("id").asText();
        mvc.perform(patch("/api/v1/admin/role-applications/"+id).header("Authorization","Bearer "+a.access())
                .contentType("application/json").content("{\"decision\":\"APPROVED\",\"note\":\"本人处理\"}"))
                .andExpect(status().isConflict());
    }
}
