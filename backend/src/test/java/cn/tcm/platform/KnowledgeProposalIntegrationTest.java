package cn.tcm.platform;

import cn.tcm.platform.common.ApiException;
import cn.tcm.platform.content.ContentSupport;
import cn.tcm.platform.knowledge.api.KnowledgeDtos.*;
import cn.tcm.platform.knowledge.application.KnowledgeProposals;
import cn.tcm.platform.source.SourceFiles;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties="tcm.jobs.enabled=false") @Transactional
class KnowledgeProposalIntegrationTest {
    @Autowired ContentSupport c;@Autowired KnowledgeProposals proposals;@Autowired org.springframework.transaction.PlatformTransactionManager transactions;
    record Fixture(long owner,long contributor,long other,long reviewer,long document,long resource,long base,KnowledgeInput input){}
    long user(String role){String uuid=UUID.randomUUID().toString().replace("-","");long user=c.insert("INSERT INTO sys_users(username,password_hash,nickname) VALUES(?,?,'提案专项')","proposal_"+uuid,"unused-test-hash");c.db.update("INSERT INTO sys_user_roles(user_id,role_id) SELECT ?,id FROM sys_roles WHERE code IN ('LEARNER',?)",user,role);return user;}
    Fixture fixture(){
        long owner=user("CONTRIBUTOR"),contributor=user("CONTRIBUTOR"),other=user("CONTRIBUTOR"),reviewer=user("REVIEWER");String uuid=UUID.randomUUID().toString();
        long doc=c.insert("INSERT INTO source_documents(title,source_type,source_url,submitted_by,review_state,rights_status,license_type,copyright_source,authorization_scope,public_reading_allowed,quote_display_allowed) VALUES('已审依据','WEB','https://example.test',?,'APPROVED','DECLARED','AUTHORIZED','专项作者','站内引用',1,1)",owner);
        long resource=c.insert("INSERT INTO content_resources(resource_type,owner_id) VALUES('KNOWLEDGE',?)",owner);
        long base=c.insert("INSERT INTO content_revisions(resource_id,version_no,title,body,created_by,revision_state,approved_at) VALUES(?,1,'正式标题','正式正文',?,'APPROVED',UTC_TIMESTAMP(3))",resource,owner);
        c.db.update("INSERT INTO knowledge_entries(resource_id,knowledge_type,canonical_name) VALUES(?,'CONCEPT',?)",resource,"正式规范"+uuid);c.db.update("INSERT INTO knowledge_revision_names(revision_id,knowledge_type,canonical_name) VALUES(?,'CONCEPT',?)",base,"正式规范"+uuid);
        c.db.update("INSERT INTO revision_evidence(revision_id,document_id,location_label,quote_text) VALUES(?,?,'第1页','引用原文')",base,doc);c.db.update("UPDATE content_resources SET published_revision_id=?,visibility_status='PUBLISHED' WHERE id=?",base,resource);
        var input=new KnowledgeInput("CONCEPT","提案规范"+uuid,"提案标题","修订建议正文",null,"有据修订",List.of("提案别名"+uuid),null,null,List.of(new Evidence(Long.toString(doc),null,"第1页","引用原文")),List.of(),List.of(),List.of());
        return new Fixture(owner,contributor,other,reviewer,doc,resource,base,input);
    }
    long create(Fixture f){return Long.parseLong(proposals.create(f.contributor(),f.resource(),f.base(),f.input()).get("id").toString());}
    @Test void independentCasDraftDoesNotChangeOwnersRevisionAndRejectsOtherAuthor(){
        var f=fixture();long proposal=create(f);
        assertEquals(404,assertThrows(ApiException.class,()->proposals.detail(f.other(),proposal)).status);
        assertEquals(404,assertThrows(ApiException.class,()->proposals.update(f.other(),proposal,0,f.input())).status);
        var saved=proposals.update(f.contributor(),proposal,0,f.input());assertEquals(1,((Number)saved.get("lockVersion")).intValue());
        assertEquals(409,assertThrows(ApiException.class,()->proposals.update(f.contributor(),proposal,0,f.input())).status);
        assertEquals(1L,c.count("SELECT COUNT(*) FROM content_revisions WHERE resource_id=?",f.resource()));assertEquals(f.owner(),c.db.queryForObject("SELECT owner_id FROM content_resources WHERE id=?",Long.class,f.resource()));
        assertEquals(SourceFiles.hash("引用原文".getBytes(StandardCharsets.UTF_8)),c.db.queryForObject("SELECT quote_hash FROM knowledge_proposal_evidence WHERE proposal_id=?",String.class,proposal));
    }
    @Test void frozenProposalCannotMutateOrSelfReviewAndOwnerDraftBlocksApproval(){
        var f=fixture();long proposal=create(f);proposals.submit(f.contributor(),proposal);
        assertEquals(409,assertThrows(ApiException.class,()->proposals.update(f.contributor(),proposal,1,f.input())).status);
        c.db.update("INSERT INTO sys_user_roles(user_id,role_id) SELECT ?,id FROM sys_roles WHERE code='REVIEWER'",f.contributor());assertEquals(403,assertThrows(ApiException.class,()->proposals.approve(f.contributor(),proposal)).status);
        long draft=c.insert("INSERT INTO content_revisions(resource_id,version_no,base_revision_id,title,body,created_by) VALUES(?,2,?,'原作者草稿','不能被覆盖',?)",f.resource(),f.base(),f.owner());
        assertEquals(409,assertThrows(ApiException.class,()->proposals.approve(f.reviewer(),proposal)).status);
        assertEquals("不能被覆盖",c.db.queryForObject("SELECT body FROM content_revisions WHERE id=?",String.class,draft));assertEquals("IN_REVIEW",proposals.detail(f.contributor(),proposal).get("status"));
    }
    @Test void firstPublicationWinsBaselineAndNeverChangesOriginalOwner(){
        var f=fixture();long first=create(f),second=create(f);proposals.submit(f.contributor(),first);proposals.submit(f.contributor(),second);
        long revision=proposals.approve(f.reviewer(),first);assertNotEquals(f.base(),revision);assertEquals(f.owner(),c.db.queryForObject("SELECT owner_id FROM content_resources WHERE id=?",Long.class,f.resource()));
        assertEquals(f.contributor(),c.db.queryForObject("SELECT created_by FROM content_revisions WHERE id=?",Long.class,revision));assertEquals("提案标题",c.db.queryForObject("SELECT title FROM content_revisions WHERE id=?",String.class,revision));
        assertEquals(409,assertThrows(ApiException.class,()->proposals.approve(f.reviewer(),second)).status);assertEquals(2L,c.count("SELECT COUNT(*) FROM content_revisions WHERE resource_id=?",f.resource()));
        assertEquals(0L,c.count("SELECT COUNT(*) FROM async_jobs WHERE revision_id=? AND job_type IN ('VECTOR_INDEX','GRAPH_SYNC')",revision));
    }
    @Test void invalidatedEvidenceBlocksSubmissionAndWithdrawLeavesFormalSnapshot(){
        var f=fixture();long proposal=create(f);c.db.update("UPDATE source_documents SET availability='SUSPENDED' WHERE id=?",f.document());assertThrows(ApiException.class,()->proposals.submit(f.contributor(),proposal));
        c.db.update("UPDATE source_documents SET availability='ACTIVE' WHERE id=?",f.document());proposals.submit(f.contributor(),proposal);assertEquals("WITHDRAWN",proposals.withdraw(f.contributor(),proposal).get("status"));
        assertEquals(f.base(),c.db.queryForObject("SELECT published_revision_id FROM content_resources WHERE id=?",Long.class,f.resource()));assertEquals("WITHDRAWN",c.db.queryForObject("SELECT status FROM review_tasks WHERE proposal_id=?",String.class,proposal));
    }
    @Test @Transactional(propagation=org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    void twoDatabaseTransactionsPermitExactlyOneProposalPublication()throws Exception{
        var tx=new org.springframework.transaction.support.TransactionTemplate(transactions);var f=tx.execute(status->fixture());var workers=java.util.concurrent.Executors.newFixedThreadPool(2);
        try{
            long first=create(f),second=create(f);proposals.submit(f.contributor(),first);proposals.submit(f.contributor(),second);
            var start=new java.util.concurrent.CountDownLatch(1);
            java.util.concurrent.Callable<Boolean> a=()->{start.await();try{proposals.approve(f.reviewer(),first);return true;}catch(ApiException conflict){assertEquals(409,conflict.status);return false;}};
            java.util.concurrent.Callable<Boolean> b=()->{start.await();try{proposals.approve(f.reviewer(),second);return true;}catch(ApiException conflict){assertEquals(409,conflict.status);return false;}};
            var one=workers.submit(a);var two=workers.submit(b);start.countDown();boolean firstWon=one.get(30,java.util.concurrent.TimeUnit.SECONDS),secondWon=two.get(30,java.util.concurrent.TimeUnit.SECONDS);assertNotEquals(firstWon,secondWon);
            assertEquals(1L,c.count("SELECT COUNT(*) FROM knowledge_revision_proposals WHERE resource_id=? AND status='APPROVED'",f.resource()));assertEquals(2L,c.count("SELECT COUNT(*) FROM content_revisions WHERE resource_id=?",f.resource()));assertEquals(f.owner(),c.db.queryForObject("SELECT owner_id FROM content_resources WHERE id=?",Long.class,f.resource()));
        }finally{
            workers.shutdownNow();workers.awaitTermination(10,java.util.concurrent.TimeUnit.SECONDS);
            tx.execute(status->{cleanup(f);return null;});
        }
    }
    void cleanup(Fixture f){
        c.db.update("DELETE e FROM review_events e JOIN review_tasks t ON t.id=e.review_task_id JOIN knowledge_revision_proposals p ON p.id=t.proposal_id WHERE p.resource_id=?",f.resource());
        c.db.update("DELETE t FROM review_tasks t JOIN knowledge_revision_proposals p ON p.id=t.proposal_id WHERE p.resource_id=?",f.resource());
        c.db.update("DELETE e FROM knowledge_proposal_evidence e JOIN knowledge_revision_proposals p ON p.id=e.proposal_id WHERE p.resource_id=?",f.resource());c.db.update("DELETE FROM knowledge_revision_proposals WHERE resource_id=?",f.resource());
        c.db.update("UPDATE content_resources SET published_revision_id=NULL,visibility_status='DRAFT' WHERE id=?",f.resource());
        c.db.update("DELETE a FROM async_job_attempts a JOIN async_jobs j ON j.id=a.job_id JOIN content_revisions v ON v.id=j.revision_id WHERE v.resource_id=?",f.resource());c.db.update("DELETE j FROM async_jobs j JOIN content_revisions v ON v.id=j.revision_id WHERE v.resource_id=?",f.resource());
        for(String table:List.of("revision_tags","herb_profiles","formula_profiles","knowledge_revision_aliases","knowledge_revision_names","revision_evidence"))c.db.update("DELETE child FROM "+table+" child JOIN content_revisions v ON v.id=child.revision_id WHERE v.resource_id=?",f.resource());
        c.db.update("DELETE FROM knowledge_aliases WHERE knowledge_resource_id=?",f.resource());c.db.update("DELETE FROM knowledge_entries WHERE resource_id=?",f.resource());for(var v:c.db.queryForList("SELECT id FROM content_revisions WHERE resource_id=? ORDER BY version_no DESC",f.resource()))c.db.update("DELETE FROM content_revisions WHERE id=?",v.get("id"));c.db.update("DELETE FROM content_resources WHERE id=?",f.resource());c.db.update("DELETE FROM source_documents WHERE id=?",f.document());
        for(long user:List.of(f.owner(),f.contributor(),f.other(),f.reviewer())){c.db.update("DELETE FROM sys_audit_logs WHERE actor_id=?",user);c.db.update("DELETE FROM sys_user_roles WHERE user_id=?",user);c.db.update("DELETE FROM sys_users WHERE id=?",user);}
    }
}
