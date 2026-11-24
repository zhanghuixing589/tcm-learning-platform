package cn.tcm.platform.content;

import cn.tcm.platform.common.*;
import cn.tcm.platform.community.application.CommunityArticleService;
import cn.tcm.platform.community.infrastructure.CommunityArticleRepository.Locked;
import cn.tcm.platform.knowledge.application.KnowledgeService;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;
import static cn.tcm.platform.content.ContentSupport.*;

// 审核服务：来源、知识和普通文章分别检查相应权限与目标状态。
@Service
public class ReviewService {
    private final ContentSupport c;
    private final KnowledgeService knowledge;
    private final CommunityArticleService articles;
    private final TransactionTemplate existingReview;
    private final TransactionTemplate articleReview;

    public ReviewService(ContentSupport c, KnowledgeService knowledge, CommunityArticleService articles,
                         PlatformTransactionManager transactions) {
        this.c = c;
        this.knowledge = knowledge;
        this.articles = articles;
        this.existingReview = new TransactionTemplate(transactions);
        this.articleReview = new TransactionTemplate(transactions);
        articleReview.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }
    // 连接审核任务、提交版本、资源、来源文档与提交人，供列表和详情读取。
    private static final String FROM=" FROM review_tasks t LEFT JOIN content_revisions v ON v.id=t.revision_id LEFT JOIN content_resources r ON r.id=v.resource_id LEFT JOIN source_documents d ON d.id=t.document_id JOIN sys_users u ON u.id=t.submitted_by ";
    // 统一选择任务字段和标题，并补充资源类型与提交人信息。
    private static final String SELECT="SELECT t.*,COALESCE(v.title,d.title) AS title,r.resource_type,r.id AS resource_id,u.nickname AS submitter_name";
    private Map<String,Object> task(long id,boolean lock){
        if(!lock)return c.row(SELECT+FROM+" WHERE t.id=?",id);
        // Lock only the task here. Targets are locked explicitly first by decisions.
        // 这里只锁任务；目标由调用方按照所属领域的锁序先行锁定。
        var current=c.row("SELECT * FROM review_tasks WHERE id=? FOR UPDATE",id);
        var enriched=c.row(SELECT+FROM+" WHERE t.id=?",id);enriched.putAll(current);return enriched;
    }
    // 按审核对象判断知识审核或社区审核权限。
    private void permitted(long actor,Map<String,Object> task){if(task.get("document_id")!=null||"KNOWLEDGE".equals(task.get("resource_type")))c.require(actor,"knowledge:review");else if("ARTICLE".equals(task.get("resource_type")))c.require(actor,"community:moderate");else throw ApiException.missing();}
    // 禁止审核本人提交的内容。
    private void notSelf(long actor,Map<String,Object> task){if(number(task.get("submitted_by"))==actor)throw new ApiException(403,"SELF_REVIEW_FORBIDDEN","不能审核本人提交的内容");}
    // 将数据库行转换为前端使用的驼峰字段视图。
    private Map<String,Object> view(Map<String,Object> t){return map("id",sid(t.get("id")),"revisionId",sid(t.get("revision_id")),"documentId",sid(t.get("document_id")),"title",t.get("title"),"kind",t.get("document_id")!=null?"SOURCE":t.get("resource_type"),"submittedBy",sid(t.get("submitted_by")),"submitterName",t.get("submitter_name"),"reviewerId",sid(t.get("reviewer_id")),"status",t.get("status"),"reviewNote",t.get("review_note"),"lockVersion",t.get("lock_version"),"createdAt",utc(t.get("created_at")));}
    // 按权限范围、状态和分页查询任务。
    public PageResult<Map<String,Object>> list(long actor,int page,int size,String status){
        int offset=PageResult.offset(page,size);boolean professional=c.has(actor,"knowledge:review"),community=c.has(actor,"community:moderate");if(!professional&&!community)throw new ApiException(403,"FORBIDDEN","没有审核权限");
        if(status==null||status.isBlank())status="PENDING";if(!Set.of("PENDING","APPROVED","REJECTED","WITHDRAWN","ALL").contains(status))throw ApiException.invalid("审核状态不正确");
        String where=" WHERE ("+(professional?"t.document_id IS NOT NULL OR r.resource_type='KNOWLEDGE'":"1=0")+(community?" OR r.resource_type='ARTICLE'":"")+")";var args=new ArrayList<Object>();
        if(!status.equals("ALL")){where+=" AND t.status=?";args.add(status);}if(status.equals("PENDING")){where+=" AND t.submitted_by<>?";args.add(actor);}long total=c.count("SELECT COUNT(*)"+FROM+where,args.toArray());args.add(size);args.add(offset);return new PageResult<>(page,size,total,c.db.queryForList(SELECT+FROM+where+" ORDER BY t.created_at,t.id LIMIT ? OFFSET ?",args.toArray()).stream().map(this::view).toList());
    }
    // 审核详情读取任务绑定的冻结版本，而不是作者后来的草稿。
    public Map<String,Object> detail(long actor,long id){var t=task(id,false);permitted(actor,t);var out=view(t);
        if(t.get("document_id")!=null)out.put("document",document(number(t.get("document_id"))));
        else if("KNOWLEDGE".equals(t.get("resource_type")))out.put("knowledge",knowledge.detail(number(t.get("revision_id")),false));
        else out.put("article", articles.frozen(number(t.get("resource_id")), number(t.get("revision_id"))));
        out.put("events",c.db.queryForList("SELECT * FROM review_events WHERE review_task_id=? ORDER BY id",id).stream().map(e->map("id",sid(e.get("id")),"actorId",sid(e.get("actor_id")),"action",e.get("action"),"note",e.get("note"),"createdAt",utc(e.get("created_at")))).toList());return out;
    }
    // 查询来源资料及最近解析批次。
    private Map<String,Object> document(long id){
        var d=c.row("SELECT d.*,g.original_name AS file_name FROM source_documents d LEFT JOIN file_upload_grants g ON g.file_id=d.file_id AND g.user_id=d.submitted_by WHERE d.id=?",id);
        var out=map("id",""+id,"title",d.get("title"),"sourceType",d.get("source_type"),"author",d.get("author"),"publisher",d.get("publisher"),"publicationYear",d.get("publication_year"),"sourceUrl",d.get("source_url"),"bibliographicNote",d.get("bibliographic_note"),"fileId",sid(d.get("file_id")),"fileName",d.get("file_name"),"reviewState",d.get("review_state"),"availability",d.get("availability"),"submittedBy",sid(d.get("submitted_by")),"parentDocumentId",sid(d.get("parent_document_id")),"createdAt",utc(d.get("created_at")));
        var runs=c.db.queryForList("SELECT * FROM document_parse_runs WHERE document_id=? ORDER BY id DESC LIMIT 1",id);out.put("parseRun",runs.isEmpty()?null:map("id",sid(runs.get(0).get("id")),"status",runs.get(0).get("status"),"errorMessage",runs.get(0).get("error_message"),"parseVersion",runs.get(0).get("parse_version")));return out;
    }
    private static boolean article(Map<String, Object> task) {
        return "ARTICLE".equals(task.get("resource_type"));
    }

    // 文章先锁参与账号及分区，再锁资源和修订；来源与知识维持原有锁序。
    private Locked lockArticle(long actor, Map<String, Object> located) {
        notSelf(actor, located);
        return articles.lockReview(actor, number(located.get("submitted_by")), number(located.get("resource_id")),
                number(located.get("revision_id")));
    }

    private static void verifyArticleTask(Map<String, Object> located, Map<String, Object> locked) {
        for (String field : List.of("resource_id", "revision_id", "submitted_by", "resource_type")) {
            if (!Objects.equals(located.get(field), locked.get(field)))
                throw ApiException.conflict("审核目标已变化，请刷新");
        }
    }

    // 领取任务时检查当前权限、本人回避、任务状态和版本。
    public Map<String,Object> claim(long actor, long id, ReviewDtos.Claim input) {
        var located = task(id, false);
        TransactionTemplate tx = article(located) ? articleReview : existingReview;
        return tx.execute(status -> {
            Locked articleTarget = article(located) ? lockArticle(actor, located) : null;
            // 目标锁定后再锁任务，防止读取和取得锁之间状态已被他人改变。
        var t = task(id, true);
            permitted(actor, t);
            notSelf(actor, t);
            if (articleTarget != null) verifyArticleTask(located, t);
            if (!"PENDING".equals(t.get("status")) || number(t.get("lock_version")) != input.lockVersion())
                throw ApiException.conflict("审核任务已更新，请刷新");
            if (articleTarget != null) articles.requireReviewTarget(articleTarget, number(t.get("submitted_by")),
                    number(t.get("revision_id")), false);
            if (t.get("reviewer_id") != null) {
                if (number(t.get("reviewer_id")) == actor) return detail(actor, id);
                throw ApiException.conflict("任务已由其他审核者领取");
            }
            c.db.update("UPDATE review_tasks SET reviewer_id=?,lock_version=lock_version+1 WHERE id=?", actor, id);
            c.db.update("INSERT INTO review_events(review_task_id,actor_id,action) VALUES(?,?,'CLAIM')", id, actor);
            c.audit(actor, "REVIEW_CLAIM", "REVIEW", id);
            return detail(actor, id);
        });
    }

    // 处理批准或退回决定；actor 是审核人，id 是审核任务编号。
    public Map<String,Object> decide(long actor, long id, ReviewDtos.Decision input) {
        if (!Set.of("APPROVED", "REJECTED").contains(input.decision())) throw ApiException.invalid("审核决策不正确");
        String note = text(input.note(), 10000, true);
        var located = task(id, false);
        if (article(located)) return articleReview.execute(status -> decideLocked(actor, id, input, note, located));
        return existingReview.execute(status -> decideLocked(actor, id, input, note, task(id, false)));
    }

    private Map<String,Object> decideLocked(long actor, long id, ReviewDtos.Decision input, String note,
                                            Map<String, Object> initial) {
        boolean isArticle = article(initial);
        Locked articleTarget = null;
        Map<String, Object> target;
        if (isArticle) {
            articleTarget = lockArticle(actor, initial);
            target = articleTarget.revision();
        } else {
            // 先做权限与本人回避检查，拿到任务锁后还需重新核验。
            permitted(actor, initial);
            notSelf(actor, initial);
            if (initial.get("revision_id") != null) {
                // 知识发布先取得其来源和关联资源锁。
                knowledge.lockPublication(number(initial.get("revision_id")));
                target = c.row("SELECT * FROM content_revisions WHERE id=? FOR UPDATE", initial.get("revision_id"));
            } else {
                // 来源审核直接锁定对应文档。
                target = c.row("SELECT * FROM source_documents WHERE id=? FOR UPDATE", initial.get("document_id"));
            }
        }
        // 目标锁定后再锁任务，防止读取和取得锁之间状态已被他人改变。
        var t = task(id, true);
        permitted(actor, t);
        notSelf(actor, t);
        if (isArticle) verifyArticleTask(initial, t);
        if (!"PENDING".equals(t.get("status"))) {
            if (input.decision().equals(t.get("status")) && t.get("reviewer_id") != null
                    && number(t.get("reviewer_id")) == actor && note.equals(t.get("review_note"))) return detail(actor, id);
            throw ApiException.conflict("审核任务已经处理");
        }
        // 校验乐观版本及领取人，避免覆盖另一位审核人的处理。
        if (number(t.get("lock_version")) != input.lockVersion()
                || t.get("reviewer_id") != null && number(t.get("reviewer_id")) != actor)
            throw ApiException.conflict("审核版本已变化或任务由他人领取");
        boolean approved = "APPROVED".equals(input.decision());
        Long revision = t.get("revision_id") == null ? null : number(t.get("revision_id"));
        Long resource = null;
        if (revision != null) {
            resource = number(target.get("resource_id"));
            if (isArticle) {
                articles.requireReviewTarget(articleTarget, number(t.get("submitted_by")), revision, approved);
            } else {
                if (!"IN_REVIEW".equals(target.get("revision_state"))) throw ApiException.conflict("提交版本状态已变化");
                if (approved) {
                    knowledge.publicationReady(revision);
                    publishKnowledge(resource, revision);
                }
            }
            c.db.update("UPDATE content_revisions SET revision_state=?,approved_at=IF(?='APPROVED',UTC_TIMESTAMP(3),NULL) WHERE id=?",
                    input.decision(), input.decision(), revision);
            if (isArticle) {
                articles.completeReview(articleTarget, resource, revision, approved);
            } else if (approved) {
                c.db.update("""
                        UPDATE content_resources SET published_revision_id=?,visibility_status='PUBLISHED',
                            published_at=COALESCE(published_at,UTC_TIMESTAMP(3)),suspended_reason=NULL,lock_version=lock_version+1 WHERE id=?
                        """, revision, resource);
                c.job("VECTOR_INDEX", revision, "v1");
                c.job("GRAPH_SYNC", revision, "v1");
            }
        } else {
            if (!"IN_REVIEW".equals(target.get("review_state"))) throw ApiException.conflict("来源状态已变化");
            if (approved && !"ACTIVE".equals(target.get("availability"))) throw ApiException.conflict("停用来源不能通过审核");
            c.db.update("UPDATE source_documents SET review_state=? WHERE id=?", input.decision(), t.get("document_id"));
        }
        c.db.update("""
                UPDATE review_tasks SET status=?,reviewer_id=?,review_note=?,reviewed_at=UTC_TIMESTAMP(3),
                    lock_version=lock_version+1 WHERE id=?
                """, input.decision(), actor, note, id);
        c.db.update("INSERT INTO review_events(review_task_id,actor_id,action,note) VALUES(?,?,?,?)", id, actor,
                approved ? "APPROVE" : "REJECT", note);
        c.audit(actor, "REVIEW_" + input.decision(), "REVIEW", id);
        String path = resource == null ? "/contribute/sources/" + t.get("document_id")
                : (isArticle ? "/account/articles/" : "/contribute/knowledge/") + resource;
        c.db.update("""
                INSERT INTO user_notifications(user_id,event_key,kind,title,body,resource_id,relative_path)
                VALUES(?,?,'REVIEW_RESULT',?,?,?,?) ON DUPLICATE KEY UPDATE id=id
                """, t.get("submitted_by"), "review:" + id, "内容审核结果", note, resource, path);
        return detail(actor, id);
    }

    // 知识批准后更新正式名称和别名投影，草稿字段不提前变成正式数据。
    private void publishKnowledge(long resource,long revision){
        var name=c.row("SELECT * FROM knowledge_revision_names WHERE revision_id=?",revision);
        try{c.db.update("UPDATE knowledge_entries SET knowledge_type=?,canonical_name=? WHERE resource_id=?",name.get("knowledge_type"),name.get("canonical_name"),resource);}catch(DuplicateKeyException e){throw ApiException.conflict("已有相同类型的正式规范名称");}
        c.db.update("DELETE FROM knowledge_aliases WHERE knowledge_resource_id=?",resource);c.db.update("INSERT INTO knowledge_aliases(knowledge_resource_id,alias) SELECT ?,alias FROM knowledge_revision_aliases WHERE revision_id=?",resource,revision);
    }
}
