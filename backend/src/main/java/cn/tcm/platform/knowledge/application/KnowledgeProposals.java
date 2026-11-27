package cn.tcm.platform.knowledge.application;

import cn.tcm.platform.common.ApiException;
import cn.tcm.platform.content.ContentSupport;
import cn.tcm.platform.knowledge.api.KnowledgeDtos.*;
import cn.tcm.platform.source.SourceEvidence;
import cn.tcm.platform.source.SourceFiles;
import cn.tcm.platform.source.SourceReadingData;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static cn.tcm.platform.content.ContentSupport.*;

/** Independent contributor payloads; the original owner's formal drafts are never edited. */
@Service
public class KnowledgeProposals {
    private final ContentSupport c;private final KnowledgeService knowledge;private final SourceEvidence evidence;private final SourceReadingData data;private final ObjectMapper json;
    public KnowledgeProposals(ContentSupport c,KnowledgeService knowledge,SourceEvidence evidence,SourceReadingData data,ObjectMapper json){this.c=c;this.knowledge=knowledge;this.evidence=evidence;this.data=data;this.json=json;}
    private Map<String,Object> proposal(long id,boolean lock){return c.row("SELECT * FROM knowledge_revision_proposals WHERE id=?"+(lock?" FOR UPDATE":""),id);}
    private KnowledgeInput payload(Map<String,Object> p){try{return json.readValue(p.get("payload").toString(),KnowledgeInput.class);}catch(Exception e){throw ApiException.conflict("提案内容不可读取，请联系维护人员");}}
    private void owner(long actor,Map<String,Object> p){if(number(p.get("submitted_by"))!=actor)throw ApiException.missing();}
    private void draft(Map<String,Object> p){if(!"DRAFT".equals(p.get("status")))throw ApiException.conflict("只有本人提案草稿可以修改或提交");}
    private void baseline(Map<String,Object> p){
        var r=c.row("SELECT * FROM content_resources WHERE id=? AND resource_type='KNOWLEDGE' FOR UPDATE",p.get("resource_id"));
        if(!Objects.equals(sid(r.get("published_revision_id")),sid(p.get("base_revision_id")))||!"PUBLISHED".equals(r.get("visibility_status")))throw ApiException.conflict("正式版本已变化，请基于新版本重新创建提案");
    }
    private KnowledgeInput normalized(long actor,KnowledgeInput input,long resource){
        knowledge.validate(actor,input,resource);String type=c.db.queryForObject("SELECT knowledge_type FROM knowledge_entries WHERE resource_id=?",String.class,resource);if(!Objects.equals(type,input.type()))throw ApiException.invalid("知识身份类型不可变");
        var refs=new ArrayList<Evidence>();if(input.evidence()!=null)for(var ref:input.evidence())refs.add(evidence.resolve(actor,ref));
        return new KnowledgeInput(input.type(),input.canonicalName(),input.title(),input.body(),input.categoryId(),input.changeNote(),input.aliases(),input.herb(),input.formula(),List.copyOf(refs),input.ingredients(),input.relations(),input.tagIds(),null);
    }
    private void saveEvidence(long proposal,KnowledgeInput input){
        c.db.update("DELETE FROM knowledge_proposal_evidence WHERE proposal_id=?",proposal);int order=0;
        for(var ref:input.evidence()){
            Long run=nullable(ref.parseRunId());if(run==null&&ref.segmentId()!=null)run=c.db.queryForObject("SELECT parse_run_id FROM document_segments WHERE id=?",Long.class,id(ref.segmentId()));
            c.db.update("INSERT INTO knowledge_proposal_evidence(proposal_id,evidence_no,document_id,segment_id,parse_run_id,reading_edition_id,locator,location_label,quote_text,quote_hash) VALUES(?,?,?,?,?,?,?,?,?,?)",proposal,++order,id(ref.documentId()),nullable(ref.segmentId()),run,nullable(ref.readingEditionId()),ref.locator()==null?null:data.encoded(ref.locator()),ref.locationLabel(),ref.quoteText(),SourceFiles.hash(ref.quoteText().getBytes(StandardCharsets.UTF_8)));
        }
    }
    private static Long nullable(String id){return id==null||id.isBlank()?null:id(id);}
    public Map<String,Object> detail(long actor,long id){var p=proposal(id,false);if(number(p.get("submitted_by"))!=actor&&!c.has(actor,"knowledge:review"))throw ApiException.missing();return view(p);}
    private Map<String,Object> view(Map<String,Object> p){return map("id",sid(p.get("id")),"resourceId",sid(p.get("resource_id")),"baseRevisionId",sid(p.get("base_revision_id")),"status",p.get("status"),"lockVersion",p.get("lock_version"),"submittedBy",sid(p.get("submitted_by")),"knowledge",payload(p),"publishedRevisionId",sid(p.get("published_revision_id")));}
    @Transactional public Map<String,Object> create(long actor,long resource,long baseRevision,KnowledgeInput input){
        c.require(actor,"contribution:submit");knowledge.publishedView(actor,resource);
        var r=c.row("SELECT * FROM content_resources WHERE id=? AND resource_type='KNOWLEDGE' FOR UPDATE",resource);
        if(!Objects.equals(sid(r.get("published_revision_id")),Long.toString(baseRevision)))throw ApiException.conflict("提案基准不是当前正式版本");
        var normalized=normalized(actor,input,resource);long proposal=c.insert("INSERT INTO knowledge_revision_proposals(resource_id,base_revision_id,submitted_by,payload) VALUES(?,?,?,?)",resource,baseRevision,actor,data.encoded(normalized));saveEvidence(proposal,normalized);c.audit(actor,"KNOWLEDGE_PROPOSAL_CREATE","PROPOSAL",proposal);return view(proposal(proposal,false));
    }
    @Transactional public Map<String,Object> update(long actor,long id,int expectedVersion,KnowledgeInput input){
        c.require(actor,"contribution:submit");var p=reviewLock(id);owner(actor,p);draft(p);if(number(p.get("lock_version"))!=expectedVersion)throw ApiException.conflict("提案已更新，请刷新");
        var normalized=normalized(actor,input,number(p.get("resource_id")));if(c.db.update("UPDATE knowledge_revision_proposals SET payload=?,lock_version=lock_version+1 WHERE id=? AND status='DRAFT' AND lock_version=?",data.encoded(normalized),id,expectedVersion)!=1)throw ApiException.conflict("提案已更新，请刷新");saveEvidence(id,normalized);return view(proposal(id,false));
    }
    /** Caller owns a transaction. Global order: source documents, resources, proposal, review task. */
    public Map<String,Object> reviewLock(long id){
        var initial=proposal(id,false);var input=payload(initial);var revisions=new TreeSet<Long>();revisions.add(number(initial.get("base_revision_id")));var resources=new TreeSet<Long>();resources.add(number(initial.get("resource_id")));var documents=new TreeSet<Long>();
        for(var row:c.db.queryForList("SELECT document_id FROM knowledge_proposal_evidence WHERE proposal_id=?",id))documents.add(number(row.get("document_id")));
        if(input.ingredients()!=null)for(var ingredient:input.ingredients()){revisions.add(id(ingredient.herbRevisionId()));resources.add(id(ingredient.herbResourceId()));}
        if(input.relations()!=null)for(var relation:input.relations()){revisions.add(id(relation.targetRevisionId()));resources.add(id(relation.targetResourceId()));}
        for(long revision:revisions)for(var row:c.db.queryForList("SELECT document_id FROM revision_evidence WHERE revision_id=?",revision))documents.add(number(row.get("document_id")));
        for(long document:documents)c.row("SELECT id FROM source_documents WHERE id=? FOR UPDATE",document);
        for(long resource:resources)c.row("SELECT id FROM content_resources WHERE id=? FOR UPDATE",resource);
        var locked=proposal(id,true);if(!Objects.equals(initial.get("payload"),locked.get("payload"))||!Objects.equals(initial.get("lock_version"),locked.get("lock_version")))throw ApiException.conflict("提案内容已变化，请刷新");return locked;
    }
    @Transactional public Map<String,Object> submit(long actor,long id){
        c.require(actor,"contribution:submit");var p=reviewLock(id);owner(actor,p);draft(p);baseline(p);
        var input=normalized(actor,payload(p),number(p.get("resource_id")));if(input.evidence().isEmpty())throw ApiException.conflict("提交需要至少一条来源依据");
        for(var ref:c.db.queryForList("SELECT * FROM knowledge_proposal_evidence WHERE proposal_id=? ORDER BY evidence_no",id))evidence.publication(ref);
        c.db.update("UPDATE knowledge_revision_proposals SET status='IN_REVIEW',payload=?,lock_version=lock_version+1 WHERE id=?",data.encoded(input),id);
        c.db.update("INSERT INTO review_tasks(proposal_id,submitted_by) VALUES(?,?)",id,actor);c.audit(actor,"KNOWLEDGE_PROPOSAL_SUBMIT","PROPOSAL",id);return view(proposal(id,false));
    }
    @Transactional public Map<String,Object> withdraw(long actor,long id){
        var p=reviewLock(id);owner(actor,p);if(!"IN_REVIEW".equals(p.get("status")))throw ApiException.conflict("只有待审提案可以撤回");
        var task=c.row("SELECT * FROM review_tasks WHERE proposal_id=? FOR UPDATE",id);if(!"PENDING".equals(task.get("status")))throw ApiException.conflict("审核任务已经处理");
        c.db.update("UPDATE knowledge_revision_proposals SET status='WITHDRAWN',lock_version=lock_version+1 WHERE id=?",id);c.db.update("UPDATE review_tasks SET status='WITHDRAWN',lock_version=lock_version+1 WHERE id=?",task.get("id"));c.db.update("INSERT INTO review_events(review_task_id,actor_id,action) VALUES(?,?,'WITHDRAW')",task.get("id"),actor);c.audit(actor,"KNOWLEDGE_PROPOSAL_WITHDRAW","PROPOSAL",id);return view(proposal(id,false));
    }
    private void reviewer(long actor,Map<String,Object> p){c.require(actor,"knowledge:review");if(number(p.get("submitted_by"))==actor)throw new ApiException(403,"SELF_REVIEW_FORBIDDEN","不能审核本人提案");if(!"IN_REVIEW".equals(p.get("status")))throw ApiException.conflict("提案状态已变化");}
    @Transactional public long approve(long actor,long id){
        var p=reviewLock(id);reviewer(actor,p);baseline(p);long resource=number(p.get("resource_id"));
        if(c.count("SELECT COUNT(*) FROM content_revisions WHERE resource_id=? AND revision_state IN ('DRAFT','IN_REVIEW')",resource)>0)throw ApiException.conflict("原作者已有草稿或待审修订，请先完成原作者流程");
        var input=normalized(number(p.get("submitted_by")),payload(p),resource);int version=c.db.queryForObject("SELECT COALESCE(MAX(version_no),0)+1 FROM content_revisions WHERE resource_id=?",Integer.class,resource);
        long revision=c.insert("INSERT INTO content_revisions(resource_id,version_no,base_revision_id,title,body,category_id,change_note,created_by,revision_state,submitted_at) VALUES(?,?,?,?,?,?,?,?,'IN_REVIEW',UTC_TIMESTAMP(3))",resource,version,p.get("base_revision_id"),input.title().trim(),input.body(),nullable(input.categoryId()),input.changeNote(),p.get("submitted_by"));
        knowledge.saveChildren(resource,revision,input);knowledge.lockPublication(revision);knowledge.publicationReady(revision);
        try{c.db.update("UPDATE knowledge_entries SET knowledge_type=?,canonical_name=? WHERE resource_id=?",input.type(),input.canonicalName().trim(),resource);}catch(DuplicateKeyException conflict){throw ApiException.conflict("已有相同类型的正式规范名称");}
        c.db.update("DELETE FROM knowledge_aliases WHERE knowledge_resource_id=?",resource);c.db.update("INSERT INTO knowledge_aliases(knowledge_resource_id,alias) SELECT ?,alias FROM knowledge_revision_aliases WHERE revision_id=?",resource,revision);
        c.db.update("UPDATE content_revisions SET revision_state='APPROVED',approved_at=UTC_TIMESTAMP(3) WHERE id=?",revision);
        c.db.update("UPDATE content_resources SET published_revision_id=?,visibility_status='PUBLISHED',published_at=COALESCE(published_at,UTC_TIMESTAMP(3)),suspended_reason=NULL,lock_version=lock_version+1 WHERE id=?",revision,resource);
        boolean ragReady=knowledge.indexable(revision);
        if(ragReady){c.job("VECTOR_INDEX",revision,"v1");c.job("GRAPH_SYNC",revision,"v1");}
        c.db.update("UPDATE knowledge_revision_proposals SET status='APPROVED',published_revision_id=?,lock_version=lock_version+1 WHERE id=?",revision,id);c.audit(actor,"KNOWLEDGE_PROPOSAL_APPROVE","PROPOSAL",id);return revision;
    }
    @Transactional public void reject(long actor,long id){var p=reviewLock(id);reviewer(actor,p);c.db.update("UPDATE knowledge_revision_proposals SET status='REJECTED',lock_version=lock_version+1 WHERE id=?",id);c.audit(actor,"KNOWLEDGE_PROPOSAL_REJECT","PROPOSAL",id);}
}
