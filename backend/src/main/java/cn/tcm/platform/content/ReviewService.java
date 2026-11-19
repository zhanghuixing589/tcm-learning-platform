package cn.tcm.platform.content;

import cn.tcm.platform.common.*;
import cn.tcm.platform.knowledge.application.KnowledgeService;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;
import static cn.tcm.platform.content.ContentSupport.*;

@Service
public class ReviewService {
    private final ContentSupport c;private final KnowledgeService knowledge;
    public ReviewService(ContentSupport c,KnowledgeService knowledge){this.c=c;this.knowledge=knowledge;}
    private static final String FROM=" FROM review_tasks t LEFT JOIN content_revisions v ON v.id=t.revision_id LEFT JOIN content_resources r ON r.id=v.resource_id LEFT JOIN source_documents d ON d.id=t.document_id JOIN sys_users u ON u.id=t.submitted_by ";
    private static final String SELECT="SELECT t.*,COALESCE(v.title,d.title) AS title,r.resource_type,u.nickname AS submitter_name";
    private Map<String,Object> task(long id,boolean lock){
        if(!lock)return c.row(SELECT+FROM+" WHERE t.id=?",id);
        // Lock only the task here. Targets are locked explicitly first by decisions.
        var current=c.row("SELECT * FROM review_tasks WHERE id=? FOR UPDATE",id);
        var enriched=c.row(SELECT+FROM+" WHERE t.id=?",id);enriched.putAll(current);return enriched;
    }
    private void permitted(long actor,Map<String,Object> task){if(task.get("document_id")!=null||"KNOWLEDGE".equals(task.get("resource_type")))c.require(actor,"knowledge:review");else if("ARTICLE".equals(task.get("resource_type")))c.require(actor,"community:moderate");else throw ApiException.missing();}
    private void notSelf(long actor,Map<String,Object> task){if(number(task.get("submitted_by"))==actor)throw new ApiException(403,"SELF_REVIEW_FORBIDDEN","不能审核本人提交的内容");}
    private Map<String,Object> view(Map<String,Object> t){return map("id",sid(t.get("id")),"revisionId",sid(t.get("revision_id")),"documentId",sid(t.get("document_id")),"title",t.get("title"),"kind",t.get("document_id")!=null?"SOURCE":t.get("resource_type"),"submittedBy",sid(t.get("submitted_by")),"submitterName",t.get("submitter_name"),"reviewerId",sid(t.get("reviewer_id")),"status",t.get("status"),"reviewNote",t.get("review_note"),"lockVersion",t.get("lock_version"),"createdAt",utc(t.get("created_at")));}
    public PageResult<Map<String,Object>> list(long actor,int page,int size,String status){
        int offset=PageResult.offset(page,size);boolean professional=c.has(actor,"knowledge:review"),community=c.has(actor,"community:moderate");if(!professional&&!community)throw new ApiException(403,"FORBIDDEN","没有审核权限");
        if(status==null||status.isBlank())status="PENDING";if(!Set.of("PENDING","APPROVED","REJECTED","WITHDRAWN","ALL").contains(status))throw ApiException.invalid("审核状态不正确");
        String where=" WHERE ("+(professional?"t.document_id IS NOT NULL OR r.resource_type='KNOWLEDGE'":"1=0")+(community?" OR r.resource_type='ARTICLE'":"")+")";var args=new ArrayList<Object>();
        if(!status.equals("ALL")){where+=" AND t.status=?";args.add(status);}if(status.equals("PENDING")){where+=" AND t.submitted_by<>?";args.add(actor);}long total=c.count("SELECT COUNT(*)"+FROM+where,args.toArray());args.add(size);args.add(offset);return new PageResult<>(page,size,total,c.db.queryForList(SELECT+FROM+where+" ORDER BY t.created_at,t.id LIMIT ? OFFSET ?",args.toArray()).stream().map(this::view).toList());
    }
    public Map<String,Object> detail(long actor,long id){var t=task(id,false);permitted(actor,t);var out=view(t);
        if(t.get("document_id")!=null)out.put("document",document(number(t.get("document_id"))));
        else if("KNOWLEDGE".equals(t.get("resource_type")))out.put("knowledge",knowledge.detail(number(t.get("revision_id")),false));
        else {var v=c.row("SELECT * FROM content_revisions WHERE id=?",t.get("revision_id"));out.put("article",map("id",sid(v.get("resource_id")),"revisionId",sid(v.get("id")),"title",v.get("title"),"body",v.get("body"),"revisionState",v.get("revision_state")));}
        out.put("events",c.db.queryForList("SELECT * FROM review_events WHERE review_task_id=? ORDER BY id",id).stream().map(e->map("id",sid(e.get("id")),"actorId",sid(e.get("actor_id")),"action",e.get("action"),"note",e.get("note"),"createdAt",utc(e.get("created_at")))).toList());return out;
    }
    private Map<String,Object> document(long id){
        var d=c.row("SELECT d.*,g.original_name AS file_name FROM source_documents d LEFT JOIN file_upload_grants g ON g.file_id=d.file_id AND g.user_id=d.submitted_by WHERE d.id=?",id);
        var out=map("id",""+id,"title",d.get("title"),"sourceType",d.get("source_type"),"author",d.get("author"),"publisher",d.get("publisher"),"publicationYear",d.get("publication_year"),"sourceUrl",d.get("source_url"),"bibliographicNote",d.get("bibliographic_note"),"fileId",sid(d.get("file_id")),"fileName",d.get("file_name"),"reviewState",d.get("review_state"),"availability",d.get("availability"),"submittedBy",sid(d.get("submitted_by")),"parentDocumentId",sid(d.get("parent_document_id")),"createdAt",utc(d.get("created_at")));
        var runs=c.db.queryForList("SELECT * FROM document_parse_runs WHERE document_id=? ORDER BY id DESC LIMIT 1",id);out.put("parseRun",runs.isEmpty()?null:map("id",sid(runs.get(0).get("id")),"status",runs.get(0).get("status"),"errorMessage",runs.get(0).get("error_message"),"parseVersion",runs.get(0).get("parse_version")));return out;
    }
    @Transactional public Map<String,Object> claim(long actor,long id,ReviewDtos.Claim input){
        var t=task(id,true);permitted(actor,t);notSelf(actor,t);if(!"PENDING".equals(t.get("status"))||number(t.get("lock_version"))!=input.lockVersion())throw ApiException.conflict("审核任务已更新，请刷新");
        if(t.get("reviewer_id")!=null){if(number(t.get("reviewer_id"))==actor)return detail(actor,id);throw ApiException.conflict("任务已由其他审核者领取");}
        c.db.update("UPDATE review_tasks SET reviewer_id=?,lock_version=lock_version+1 WHERE id=?",actor,id);c.db.update("INSERT INTO review_events(review_task_id,actor_id,action) VALUES(?,?,'CLAIM')",id,actor);c.audit(actor,"REVIEW_CLAIM","REVIEW",id);return detail(actor,id);
    }
    @Transactional public Map<String,Object> decide(long actor,long id,ReviewDtos.Decision input){
        if(!Set.of("APPROVED","REJECTED").contains(input.decision()))throw ApiException.invalid("审核决策不正确");String note=text(input.note(),10000,true);
        var initial=task(id,false);permitted(actor,initial);notSelf(actor,initial);
        Map<String,Object> target=null;
        if(initial.get("revision_id")!=null){var locate=c.row("SELECT resource_id FROM content_revisions WHERE id=?",initial.get("revision_id"));if("KNOWLEDGE".equals(initial.get("resource_type")))knowledge.lockPublication(number(initial.get("revision_id")));else c.row("SELECT id FROM content_resources WHERE id=? FOR UPDATE",locate.get("resource_id"));target=c.row("SELECT * FROM content_revisions WHERE id=? FOR UPDATE",initial.get("revision_id"));}
        else target=c.row("SELECT * FROM source_documents WHERE id=? FOR UPDATE",initial.get("document_id"));
        var t=task(id,true);permitted(actor,t);notSelf(actor,t);
        if(!"PENDING".equals(t.get("status"))){if(input.decision().equals(t.get("status"))&&t.get("reviewer_id")!=null&&number(t.get("reviewer_id"))==actor&&note.equals(t.get("review_note")))return detail(actor,id);throw ApiException.conflict("审核任务已经处理");}
        if(number(t.get("lock_version"))!=input.lockVersion()||t.get("reviewer_id")!=null&&number(t.get("reviewer_id"))!=actor)throw ApiException.conflict("审核版本已变化或任务由他人领取");
        Long revision=t.get("revision_id")==null?null:number(t.get("revision_id"));Long resource=null;
        if(revision!=null){var v=target;resource=number(v.get("resource_id"));if(!"IN_REVIEW".equals(v.get("revision_state")))throw ApiException.conflict("提交版本状态已变化");
            if(input.decision().equals("APPROVED")&&"KNOWLEDGE".equals(t.get("resource_type"))){knowledge.publicationReady(revision);publishKnowledge(resource,revision);}
            c.db.update("UPDATE content_revisions SET revision_state=?,approved_at=IF(?='APPROVED',UTC_TIMESTAMP(3),NULL) WHERE id=?",input.decision(),input.decision(),revision);
            if(input.decision().equals("APPROVED")){
                c.db.update("UPDATE content_resources SET published_revision_id=?,visibility_status='PUBLISHED',published_at=COALESCE(published_at,UTC_TIMESTAMP(3)),suspended_reason=NULL,lock_version=lock_version+1 WHERE id=?",revision,resource);
                if("KNOWLEDGE".equals(t.get("resource_type"))){c.job("VECTOR_INDEX",revision,"v1");c.job("GRAPH_SYNC",revision,"v1");}
            }
        }else {
            var d=target;if(!"IN_REVIEW".equals(d.get("review_state")))throw ApiException.conflict("来源状态已变化");
            if(input.decision().equals("APPROVED")&&!"ACTIVE".equals(d.get("availability")))throw ApiException.conflict("停用来源不能通过审核");
            c.db.update("UPDATE source_documents SET review_state=? WHERE id=?",input.decision(),t.get("document_id"));
        }
        c.db.update("UPDATE review_tasks SET status=?,reviewer_id=?,review_note=?,reviewed_at=UTC_TIMESTAMP(3),lock_version=lock_version+1 WHERE id=?",input.decision(),actor,note,id);
        c.db.update("INSERT INTO review_events(review_task_id,actor_id,action,note) VALUES(?,?,?,?)",id,actor,input.decision().equals("APPROVED")?"APPROVE":"REJECT",note);
        c.audit(actor,"REVIEW_"+input.decision(),"REVIEW",id);
        String path=resource==null?"/contribute/sources/"+t.get("document_id"):("ARTICLE".equals(t.get("resource_type"))?"/account/articles/":"/contribute/knowledge/")+resource;
        c.db.update("INSERT INTO user_notifications(user_id,event_key,kind,title,body,resource_id,relative_path) VALUES(?,?,'REVIEW_RESULT',?,?,?,?) ON DUPLICATE KEY UPDATE id=id",t.get("submitted_by"),"review:"+id,"内容审核结果",note,resource,path);
        return detail(actor,id);
    }
    private void publishKnowledge(long resource,long revision){
        var name=c.row("SELECT * FROM knowledge_revision_names WHERE revision_id=?",revision);
        try{c.db.update("UPDATE knowledge_entries SET knowledge_type=?,canonical_name=? WHERE resource_id=?",name.get("knowledge_type"),name.get("canonical_name"),resource);}catch(DuplicateKeyException e){throw ApiException.conflict("已有相同类型的正式规范名称");}
        c.db.update("DELETE FROM knowledge_aliases WHERE knowledge_resource_id=?",resource);c.db.update("INSERT INTO knowledge_aliases(knowledge_resource_id,alias) SELECT ?,alias FROM knowledge_revision_aliases WHERE revision_id=?",resource,revision);
    }
}
