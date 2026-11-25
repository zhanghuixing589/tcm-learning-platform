package cn.tcm.platform.knowledge.application;

import cn.tcm.platform.common.*;
import cn.tcm.platform.content.ContentSupport;
import cn.tcm.platform.knowledge.api.KnowledgeDtos.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;
import static cn.tcm.platform.content.ContentSupport.*;

@Service
public class KnowledgeService {
    private final ContentSupport c;
    private final KnowledgeTaxonomy taxonomy;
//    基础常量
//    知识类型：概念、药材、方剂、功效、症状、经络
    private static final Set<String> TYPES=Set.of("CONCEPT","HERB","FORMULA","EFFECT","SYMPTOM","MERIDIAN");
//    关系类型：具功效、主治、归经、配伍、相关
    private static final Set<String> RELATIONS=Set.of("HAS_EFFECT","INDICATED_FOR","ENTERS_MERIDIAN","COMPATIBLE_WITH","RELATED_TO");
//    角色类型：君、臣、佐、使、未指定
    private static final Set<String> ROLES=Set.of("JUN","CHEN","ZUO","SHI","UNSPECIFIED");
    /**
     * 初始化知识服务，并注入通用内容数据库与权限操作支持类。
     *
     * @param c 内容支撑组件
     */
    public KnowledgeService(ContentSupport c,KnowledgeTaxonomy taxonomy){this.c=c;this.taxonomy=taxonomy;}
    /** 查询知识分类目录。 */
    public List<Map<String,Object>> categories(String type){return taxonomy.directories(type);}
    /** 按资源编号查询知识资源，并根据需要申请数据库行锁。 */
    private Map<String,Object> resource(long resource,boolean lock){return c.row("SELECT * FROM content_resources WHERE id=? AND resource_type='KNOWLEDGE'"+(lock?" FOR UPDATE":""),resource);}
    /** 查询指定知识资源的最新版本。 */
    private Map<String,Object> latest(long resource){return c.row("SELECT * FROM content_revisions WHERE resource_id=? ORDER BY version_no DESC LIMIT 1",resource);}
    /** 校验当前操作人是否为知识资源的所有者。 */
    private void owner(long actor,Map<String,Object> r){if(number(r.get("owner_id"))!=actor)throw ApiException.missing();}
    /** 校验当前操作人是否可读取草稿或审核中的知识内容。 */
    private void readable(long actor,Map<String,Object> r){if(number(r.get("owner_id"))!=actor&&!c.has(actor,"knowledge:review"))throw ApiException.missing();}
    /** 将可为空的集合转换为空集合，避免后续遍历出现空指针。 */
    private static <T> List<T> list(List<T> input){return input==null?List.of():input;}
    /** 校验知识类型是否属于系统允许的枚举值。 */
    private static void type(String type){if(!TYPES.contains(type))throw ApiException.invalid("知识类型不正确");}
    /** 综合校验知识稿件的基础信息、来源依据、组成和关联关系。 */
    private void validate(long actor,KnowledgeInput in,long self){
        type(in.type());
        text(in.canonicalName(),160,true);//规范名必填≤160
        text(in.title(),255,true);//标题必填≤255
        text(in.body(),100000,true);//内容必填≤100000
        text(in.changeNote(),1000,false);//修改说明可填≤1000
        taxonomy.requireDirectory(in.categoryId(),in.type(),true);
        taxonomy.requireTags(in.tagIds(),true);
        if(list(in.aliases()).size()>50||list(in.evidence()).size()>100||list(in.ingredients()).size()>100||list(in.relations()).size()>100)throw ApiException.invalid("结构化条目过多");
        var aliases=new HashSet<String>();for(String a:list(in.aliases()))if(!aliases.add(text(a,160,true).toLowerCase(Locale.ROOT)))throw ApiException.invalid("别名不能重复");
//       别名大小写归一化去重
        if(!in.type().equals("HERB")&&in.herb()!=null||!in.type().equals("FORMULA")&&(in.formula()!=null||!list(in.ingredients()).isEmpty()))throw ApiException.invalid("结构化属性与知识类型不匹配");
        for(Evidence e:list(in.evidence())){
            long doc=id(e.documentId());
            if(c.count("SELECT COUNT(*) FROM source_documents WHERE id=? AND availability='ACTIVE' AND (submitted_by=? OR review_state='APPROVED')",doc,actor)==0)throw ApiException.invalid("来源不存在、不可访问或已停用");
            text(e.quoteText(),20000,true);text(e.locationLabel(),255,false);
            if(e.segmentId()!=null&&!e.segmentId().isBlank()){
//                片段必须属于该文档的成功解析批次，且引文必须是该片段的内容
                if(c.count("SELECT COUNT(*) FROM document_segments s JOIN document_parse_runs p ON p.id=s.parse_run_id WHERE s.id=? AND s.document_id=? AND p.status='SUCCEEDED'",id(e.segmentId()),doc)==0)throw ApiException.invalid("片段必须属于所选来源的成功解析批次");
                String original=c.db.queryForObject("SELECT body FROM document_segments WHERE id=? AND document_id=?",String.class,id(e.segmentId()),doc);
                if(original==null||!original.contains(e.quoteText().trim()))throw ApiException.invalid("引文必须是所选原文片段中的内容");
            }else if(e.locationLabel()==null||e.locationLabel().isBlank())throw ApiException.invalid("来源依据需要片段或人工定位");
        }
        var herbs=new HashSet<Long>();for(Ingredient i:list(in.ingredients())){
            long herb=id(i.herbResourceId());if(!herbs.add(herb)||herb==self)throw ApiException.invalid("药材组成不能重复或指向自己");
            if(!ROLES.contains(i.role()))throw ApiException.invalid("组成角色不正确");evidenceIndex(i.evidenceIndex(),in);target(herb,id(i.herbRevisionId()),"HERB");
            if(i.doseValue()!=null&&(i.doseValue().signum()<=0||i.doseValue().precision()-i.doseValue().scale()>8||i.doseValue().scale()>4))throw ApiException.invalid("剂量数值超出范围");
        }
        var relations=new HashSet<String>();for(Relation r:list(in.relations())){
            long target=id(r.targetResourceId());if(target==self||!RELATIONS.contains(r.relationType())||!relations.add(target+":"+r.relationType()))throw ApiException.invalid("关系类型、重复关系或目标不正确");
            evidenceIndex(r.evidenceIndex(),in);target(target,id(r.targetRevisionId()),null);
        }
    }
    /** 校验组成或关系引用的来源依据下标是否位于当前稿件的依据列表中。 */
    private void evidenceIndex(Integer index,KnowledgeInput in){if(index==null||index<0||index>=list(in.evidence()).size())throw ApiException.invalid("组成和关系的依据必须属于本稿");}
    /** 校验关联目标资源和版本是否存在且为有效的已审核知识版本。 */
    private void target(long resource,long revision,String type){
        if(c.count("SELECT COUNT(*) FROM content_resources r JOIN content_revisions v ON v.resource_id=r.id JOIN knowledge_revision_names n ON n.revision_id=v.id WHERE r.id=? AND v.id=? AND "
                +visible("r","v")+(type==null?"":" AND n.knowledge_type='HERB'"),resource,revision)==0)throw ApiException.invalid("目标必须是有效且已审核的知识版本");
    }
//    生命周期方法
//    创建方法
    /** 检查权限并校验输入后创建一份新的知识资源和草稿版本。 */
    @Transactional public Map<String,Object> create(long actor,KnowledgeInput in){c.require(actor,"contribution:submit");validate(actor,in,0);return createValidated(actor,in);}
    /** 在已通过校验的情况下创建知识资源、基础条目和初始修订版本。 */
    private Map<String,Object> createValidated(long actor,KnowledgeInput in){
        long resource=c.insert("INSERT INTO content_resources(resource_type,owner_id) VALUES('KNOWLEDGE',?)",actor);
        c.db.update("INSERT INTO knowledge_entries(resource_id,knowledge_type,canonical_name) VALUES(?,?,NULL)",resource,in.type());
        long revision=c.insert("INSERT INTO content_revisions(resource_id,version_no,title,body,category_id,change_note,created_by) VALUES(?,1,?,?,?,?,?)",resource,in.title().trim(),in.body(),nullableId(in.categoryId()),in.changeNote(),actor);
        saveChildren(resource,revision,in);c.audit(actor,"KNOWLEDGE_CREATE","RESOURCE",resource);return detail(revision,false);
    }
    /** 将空白字符串分类编号转换为数据库可接受的空值。 */
    private static Long nullableId(String value){return value==null||value.isBlank()?null:id(value);}
    /** 保存知识版本的规范名、别名、药材或方剂属性、来源依据及结构化关系。 */
    private void saveChildren(long resource,long revision,KnowledgeInput in){
        c.db.update("INSERT INTO knowledge_revision_names(revision_id,knowledge_type,canonical_name) VALUES(?,?,?)",revision,in.type(),in.canonicalName().trim());
        for(String tag:list(in.tagIds()))c.db.update("INSERT INTO revision_tags(revision_id,tag_id) VALUES(?,?)",revision,id(tag));
        for(String alias:list(in.aliases()))c.db.update("INSERT INTO knowledge_revision_aliases(revision_id,alias) VALUES(?,?)",revision,alias.trim());
        if(in.herb()!=null){
            var h=in.herb();
            c.db.update("INSERT INTO herb_profiles(revision_id,nature,flavor,origin_note,precautions) VALUES(?,?,?,?,?)"
                    ,revision,h.nature(),h.flavor(),h.originNote(),h.precautions());
        }
        if(in.formula()!=null){
            var f=in.formula();
            c.db.update("INSERT INTO formula_profiles(revision_id,origin_text,function_summary,indication_summary,precautions) VALUES(?,?,?,?,?)",
                    revision,f.originText(),f.functionSummary(),f.indicationSummary(),f.precautions());
        }
        var evidenceIds=new ArrayList<Long>();
        for(Evidence e:list(in.evidence()))evidenceIds.add(c.insert("INSERT INTO revision_evidence(revision_id,document_id,segment_id,location_label,quote_text) VALUES(?,?,?,?,?)",revision,id(e.documentId()),nullableId(e.segmentId()),e.locationLabel(),e.quoteText()));
        int order=0;
        for(Ingredient i:list(in.ingredients()))c.db.update("INSERT INTO formula_ingredients(formula_resource_id,formula_revision_id,herb_resource_id,herb_revision_id,role,dose_value,dose_unit,dose_text,sort_order,evidence_id) VALUES(?,?,?,?,?,?,?,?,?,?)",resource,revision,id(i.herbResourceId()),id(i.herbRevisionId()),i.role(),i.doseValue(),i.doseUnit(),i.doseText(),order++,evidenceIds.get(i.evidenceIndex()));
        for(Relation r:list(in.relations()))c.db.update("INSERT INTO knowledge_relations(source_resource_id,source_revision_id,target_resource_id,target_revision_id,relation_type,explanation,evidence_id) VALUES(?,?,?,?,?,?,?)",resource,revision,id(r.targetResourceId()),id(r.targetRevisionId()),r.relationType(),r.explanation(),evidenceIds.get(r.evidenceIndex()));
    }
    /** 清除指定修订版本下的别名、来源依据、组成、关系和类型属性子记录。 */
    private void clear(long revision){for(String table:List.of("formula_ingredients","knowledge_relations","herb_profiles","formula_profiles","knowledge_revision_aliases","knowledge_revision_names","revision_evidence")){String col=switch(table){case "formula_ingredients"->"formula_revision_id";case "knowledge_relations"->"source_revision_id";default->"revision_id";};c.db.update("DELETE FROM "+table+" WHERE "+col+"=?",revision);}}
    /** 修改本人已有草稿，并固定知识身份类型且更新资源锁版本。 */
    @Transactional public Map<String,Object> update(long actor,long resource,KnowledgeInput in){c.require(actor,"contribution:submit");var r=resource(resource,true);owner(actor,r);var v=latest(resource);if(!"DRAFT".equals(v.get("revision_state")))throw ApiException.conflict("仅本人草稿可以修改");String established=c.db.queryForObject("SELECT knowledge_type FROM knowledge_entries WHERE resource_id=?",String.class,resource);if(!Objects.equals(established,in.type()))throw ApiException.invalid("知识身份类型不可变，请新建相应类型的知识");validate(actor,in,resource);long revision=number(v.get("id"));if(in.tagIds()!=null)c.db.update("DELETE FROM revision_tags WHERE revision_id=?",revision);clear(revision);c.db.update("UPDATE content_revisions SET title=?,body=?,category_id=?,change_note=? WHERE id=?",in.title().trim(),in.body(),nullableId(in.categoryId()),in.changeNote(),revision);saveChildren(resource,revision,in);c.db.update("UPDATE content_resources SET lock_version=lock_version+1 WHERE id=?",resource);return detail(revision,false);}
    /** 查询本人或审核人员可查看的知识草稿版本详情。 */
    public Map<String,Object> draft(long actor,long resource){var r=resource(resource,false);readable(actor,r);return detail(number(latest(resource).get("id")),false);}
//    修订方法--版本分支复制
    /** 基于最新已审核版本复制一份新草稿修订，并复制其全部结构化数据。 */
    @Transactional public Map<String,Object> revise(long actor,long resource){
        c.require(actor,"contribution:submit");
        var r=resource(resource,true);
        owner(actor,r);
        var old=latest(resource);
        if(Set.of("DRAFT","IN_REVIEW").contains(old.get("revision_state")))throw ApiException.conflict("当前已有草稿或待审版本");
        long from=number(old.get("id"));
        long to=c.insert("INSERT INTO content_revisions(resource_id,version_no,base_revision_id,category_id,title,body,change_note,created_by) SELECT v.resource_id,v.version_no+1,v.id,CASE WHEN cat.active=1 AND cat.legacy_archive=0 THEN v.category_id ELSE NULL END,v.title,v.body,v.change_note,? FROM content_revisions v LEFT JOIN content_categories cat ON cat.id=v.category_id WHERE v.id=?",actor,from);
        c.db.update("INSERT INTO knowledge_revision_names SELECT ?,knowledge_type,canonical_name FROM knowledge_revision_names WHERE revision_id=?",to,from);
        c.db.update("INSERT INTO knowledge_revision_aliases SELECT ?,alias FROM knowledge_revision_aliases WHERE revision_id=?",to,from);
        c.db.update("INSERT INTO revision_tags(revision_id,tag_id) SELECT ?,tag_id FROM revision_tags WHERE revision_id=?",to,from);
        c.db.update("INSERT INTO herb_profiles(revision_id,nature,flavor,origin_note,precautions) SELECT ?,nature,flavor,origin_note,precautions FROM herb_profiles WHERE revision_id=?",to,from);
        c.db.update("INSERT INTO formula_profiles(revision_id,origin_text,function_summary,indication_summary,precautions) SELECT ?,origin_text,function_summary,indication_summary,precautions FROM formula_profiles WHERE revision_id=?",to,from);
        var evidence=new HashMap<Long,Long>();
        for(var e:c.db.queryForList("SELECT * FROM revision_evidence WHERE revision_id=? ORDER BY id",from))evidence.put(number(e.get("id")),c.insert("INSERT INTO revision_evidence(revision_id,document_id,segment_id,location_label,quote_text) VALUES(?,?,?,?,?)",to,e.get("document_id"),e.get("segment_id"),e.get("location_label"),e.get("quote_text")));
        for(var i:c.db.queryForList("SELECT * FROM formula_ingredients WHERE formula_revision_id=?",from))c.db.update("INSERT INTO formula_ingredients(formula_resource_id,formula_revision_id,herb_resource_id,herb_revision_id,role,dose_value,dose_unit,dose_text,sort_order,evidence_id) VALUES(?,?,?,?,?,?,?,?,?,?)",resource,to,i.get("herb_resource_id"),i.get("herb_revision_id"),i.get("role"),i.get("dose_value"),i.get("dose_unit"),i.get("dose_text"),i.get("sort_order"),evidence.get(number(i.get("evidence_id"))));
        for(var rel:c.db.queryForList("SELECT * FROM knowledge_relations WHERE source_revision_id=?",from))c.db.update("INSERT INTO knowledge_relations(source_resource_id,source_revision_id,target_resource_id,target_revision_id,relation_type,explanation,evidence_id) VALUES(?,?,?,?,?,?,?)",resource,to,rel.get("target_resource_id"),rel.get("target_revision_id"),rel.get("relation_type"),rel.get("explanation"),evidence.get(number(rel.get("evidence_id"))));
        c.audit(actor,"KNOWLEDGE_REVISE","REVISION",to);
        return detail(to,false);
    }
    /** 收集当前修订及其方剂组成、关联关系涉及的全部知识版本编号。 */
    private List<Long> publicationRevisions(long revision,boolean current){var result=new TreeSet<Long>();result.add(revision);for(var r:c.db.queryForList("SELECT herb_revision_id AS id FROM formula_ingredients WHERE formula_revision_id=?"+(current?" FOR UPDATE":""),revision))result.add(number(r.get("id")));for(var r:c.db.queryForList("SELECT target_revision_id AS id FROM knowledge_relations WHERE source_revision_id=?"+(current?" FOR UPDATE":""),revision))result.add(number(r.get("id")));return new ArrayList<>(result);}
    /** 收集全部相关修订版本引用的来源文档编号，并可选地加行锁。 */
    private List<Long> publicationDocuments(List<Long> revisions,boolean current){var result=new TreeSet<Long>();for(long revision:revisions)for(var e:c.db.queryForList("SELECT document_id FROM revision_evidence WHERE revision_id=?"+(current?" FOR UPDATE":""),revision))result.add(number(e.get("document_id")));return new ArrayList<>(result);}
    /** 根据修订版本编号收集对应的知识资源编号。 */
    private List<Long> publicationResources(List<Long> revisions){var result=new TreeSet<Long>();for(long revision:revisions)result.add(c.db.queryForObject("SELECT resource_id FROM content_revisions WHERE id=?",Long.class,revision));return new ArrayList<>(result);}
    /** Global lock order: all involved source documents, then resources, then review tasks. */
    /** 按统一顺序锁定发布涉及的文档、资源及相关修订，防止并发修改冲突。 */
    public void lockPublication(long revision){
        var revisions=publicationRevisions(revision,false);var docs=publicationDocuments(revisions,false);var resources=publicationResources(revisions);
        for(long doc:docs)c.row("SELECT id FROM source_documents WHERE id=? FOR UPDATE",doc);
        for(long resource:resources)c.row("SELECT id FROM content_resources WHERE id=? FOR UPDATE",resource);
        var currentRevisions=publicationRevisions(revision,true);
        if(!revisions.equals(currentRevisions)||!docs.equals(publicationDocuments(currentRevisions,true))||!resources.equals(publicationResources(currentRevisions)))throw ApiException.conflict("草稿依据已变化，请刷新后重试");
    }
    /** 校验提交发布所需的来源依据、目标药材和关联目标是否仍有效。 */
    public void publicationReady(long revision){
        var classification=c.row("SELECT v.category_id,n.knowledge_type FROM content_revisions v JOIN knowledge_revision_names n ON n.revision_id=v.id WHERE v.id=?",revision);
        try { taxonomy.requireDirectory(sid(classification.get("category_id")),sid(classification.get("knowledge_type")),true);
            taxonomy.requireTags(c.db.queryForList("SELECT tag_id FROM revision_tags WHERE revision_id=? ORDER BY tag_id",String.class,revision),true);
        } catch(ApiException e) { throw ApiException.conflict("目录或标签已失效，请退回修订后重新选择"); }
        if(c.count("SELECT COUNT(*) FROM revision_evidence WHERE revision_id=?",revision)==0)throw ApiException.conflict("提交需要至少一条来源依据");
        for(var d:c.db.queryForList("SELECT d.id,d.review_state,d.availability FROM source_documents d JOIN revision_evidence e ON e.document_id=d.id WHERE e.revision_id=? ORDER BY d.id FOR UPDATE",revision))if(!"APPROVED".equals(d.get("review_state"))||!"ACTIVE".equals(d.get("availability")))throw ApiException.conflict("来源必须审核通过且有效");
        for(var i:c.db.queryForList("SELECT herb_resource_id,herb_revision_id FROM formula_ingredients WHERE formula_revision_id=? FOR UPDATE",revision))readyTarget(number(i.get("herb_resource_id")),number(i.get("herb_revision_id")),"HERB");
        for(var rel:c.db.queryForList("SELECT target_resource_id,target_revision_id FROM knowledge_relations WHERE source_revision_id=? FOR UPDATE",revision))readyTarget(number(rel.get("target_resource_id")),number(rel.get("target_revision_id")),null);
    }
    /** 校验结构化关联目标已发布、已审核、用户有效且来源依据仍有效。 */
    private void readyTarget(long resource,long revision,String type){
        var rows=c.db.queryForList("SELECT r.visibility_status,v.revision_state,u.status,n.knowledge_type FROM content_resources r JOIN content_revisions v ON v.resource_id=r.id JOIN sys_users u ON u.id=r.owner_id JOIN knowledge_revision_names n ON n.revision_id=v.id WHERE r.id=? AND v.id=? FOR UPDATE",resource,revision);
        if(rows.isEmpty())throw ApiException.conflict("目标知识版本不存在");var r=rows.get(0);
        if(!"PUBLISHED".equals(r.get("visibility_status"))||!"APPROVED".equals(r.get("revision_state"))||!"ACTIVE".equals(r.get("status"))||type!=null&&!type.equals(r.get("knowledge_type")))throw ApiException.conflict("目标知识已经失效，请重新选择");
        var docs=c.db.queryForList("SELECT d.review_state,d.availability FROM revision_evidence e JOIN source_documents d ON d.id=e.document_id WHERE e.revision_id=? FOR UPDATE",revision);
        if(docs.isEmpty()||docs.stream().anyMatch(d->!"APPROVED".equals(d.get("review_state"))||!"ACTIVE".equals(d.get("availability"))))throw ApiException.conflict("目标知识依据来源已经失效");
    }
    /** 提交草稿进入审核流程，并创建待处理的审核任务。 */
    @Transactional public Map<String,Object> submit(long actor,long revision){c.require(actor,"contribution:submit");var v=c.row("SELECT * FROM content_revisions WHERE id=?",revision);long resource=number(v.get("resource_id"));var initial=resource(resource,false);owner(actor,initial);lockPublication(revision);var r=resource(resource,true);owner(actor,r);v=c.row("SELECT * FROM content_revisions WHERE id=? FOR UPDATE",revision);if(!"DRAFT".equals(v.get("revision_state")))throw ApiException.conflict("只有草稿可提交");publicationReady(revision);c.db.update("UPDATE content_revisions SET revision_state='IN_REVIEW',submitted_at=UTC_TIMESTAMP(3) WHERE id=?",revision);c.db.update("INSERT INTO review_tasks(revision_id,submitted_by) VALUES(?,?)",revision,actor);c.audit(actor,"KNOWLEDGE_SUBMIT","REVISION",revision);return detail(revision,false);}
    /** 撤回仍处于待处理状态的审核任务，并将修订状态恢复为已撤回。 */
    @Transactional public Map<String,Object> withdraw(long actor,long revision){var v=c.row("SELECT * FROM content_revisions WHERE id=?",revision);var r=resource(number(v.get("resource_id")),true);owner(actor,r);var task=c.row("SELECT * FROM review_tasks WHERE revision_id=? FOR UPDATE",revision);if(!"PENDING".equals(task.get("status")))throw ApiException.conflict("审核任务已处理");c.db.update("UPDATE review_tasks SET status='WITHDRAWN',lock_version=lock_version+1 WHERE id=?",task.get("id"));c.db.update("UPDATE content_revisions SET revision_state='WITHDRAWN' WHERE id=?",revision);c.db.update("INSERT INTO review_events(review_task_id,actor_id,action) VALUES(?,?,'WITHDRAW')",task.get("id"),actor);c.audit(actor,"KNOWLEDGE_WITHDRAW","REVISION",revision);return detail(revision,false);}
    /** 公开查看已发布知识，并记录用户最近浏览时间和浏览次数。 */
    @Transactional public Map<String,Object> published(long actor,long resource){var out=publishedView(actor,resource);c.db.update("INSERT INTO user_resource_history(user_id,resource_id,last_viewed_at) VALUES(?,?,UTC_TIMESTAMP(3)) ON DUPLICATE KEY UPDATE last_viewed_at=UTC_TIMESTAMP(3),view_count=LEAST(view_count,2147483646)+1",actor,resource);return out;}
    /** 查询已发布知识详情，并标记当前用户是否收藏。 */
    public Map<String,Object> publishedView(long actor,long resource){var r=resource(resource,false);if(r.get("published_revision_id")==null)throw ApiException.missing();long revision=number(r.get("published_revision_id"));if(!isVisible(resource,revision))throw ApiException.missing();var out=detail(revision,true);out.put("bookmarked",c.count("SELECT COUNT(*) FROM resource_bookmarks WHERE user_id=? AND resource_id=?",actor,resource)>0);return out;}
    /** 判断指定资源的修订版本是否符合公开可见规则。 */
    private boolean isVisible(long resource,long revision){return c.count("SELECT COUNT(*) FROM content_resources r JOIN content_revisions v ON v.resource_id=r.id WHERE r.id=? AND v.id=? AND "+visible("r","v"),resource,revision)>0;}
    /** 查询资源的全部可访问版本列表。 */
    public List<Map<String,Object>> versions(long actor,long resource){var r=resource(resource,false);boolean all=number(r.get("owner_id"))==actor||c.has(actor,"knowledge:review");var rows=c.db.queryForList("SELECT id FROM content_revisions WHERE resource_id=? ORDER BY version_no DESC",resource);return rows.stream().filter(v->all||isVisible(resource,number(v.get("id")))).map(v->detail(number(v.get("id")),!all)).toList();}
    /** 查询指定资源的某个修订版本，并校验资源归属或公开可见权限。 */
    public Map<String,Object> version(long actor,long resource,long revision){var r=resource(resource,false);if(c.count("SELECT COUNT(*) FROM content_revisions WHERE id=? AND resource_id=?",revision,resource)==0)throw ApiException.missing();boolean own=number(r.get("owner_id"))==actor||c.has(actor,"knowledge:review");if(!own&&!isVisible(resource,revision))throw ApiException.missing();return detail(revision,!own);}
    /** 分页搜索公开知识或当前用户的贡献稿件，支持关键词、类型、分类和状态筛选。 */
    public PageResult<Map<String,Object>> search(long actor,boolean own,int page,int size,String q,String type,String category,String state){return search(actor,own,page,size,q,type,category,state,null);}
    public PageResult<Map<String,Object>> search(long actor,boolean own,int page,int size,String q,String type,String category,String state,String tagId){
        if(category!=null&&!category.isBlank()){if(type==null||type.isBlank())throw ApiException.invalid("筛选目录前请先选择知识类型");taxonomy.requireDirectory(category,type,false);}
        if(tagId!=null&&!tagId.isBlank())taxonomy.requireTags(List.of(tagId),false);
        int offset=PageResult.offset(page,size);var args=new ArrayList<Object>();String from=" FROM content_resources r JOIN content_revisions v ON v.resource_id=r.id AND "+(own?"v.version_no=(SELECT MAX(x.version_no) FROM content_revisions x WHERE x.resource_id=r.id)":"v.id=r.published_revision_id")+" JOIN knowledge_revision_names n ON n.revision_id=v.id WHERE r.resource_type='KNOWLEDGE'";
        if(own){from+=" AND r.owner_id=?";args.add(actor);}else from+=" AND "+visible("r","v");
        if(q!=null&&!q.isBlank()){text(q,100,false);from+=" AND (v.title LIKE ? ESCAPE '!' OR n.canonical_name LIKE ? ESCAPE '!' OR EXISTS(SELECT 1 FROM knowledge_revision_aliases a WHERE a.revision_id=v.id AND a.alias LIKE ? ESCAPE '!') OR EXISTS(SELECT 1 FROM revision_tags rt JOIN content_tags t ON t.id=rt.tag_id WHERE rt.revision_id=v.id AND t.active=1 AND t.name LIKE ? ESCAPE '!'))";String like="%"+q.trim().replace("!","!!").replace("%","!%").replace("_","!_")+"%";args.add(like);args.add(like);args.add(like);args.add(like);}
        if(type!=null&&!type.isBlank()){type(type);from+=" AND n.knowledge_type=?";args.add(type);}if(category!=null&&!category.isBlank()){from+=" AND v.category_id=?";args.add(id(category));}
        if(tagId!=null&&!tagId.isBlank()){from+=" AND EXISTS(SELECT 1 FROM revision_tags rt WHERE rt.revision_id=v.id AND rt.tag_id=?)";args.add(id(tagId));}
        if(own&&state!=null&&!state.isBlank()){if(!Set.of("DRAFT","IN_REVIEW","APPROVED","REJECTED","WITHDRAWN").contains(state))throw ApiException.invalid("状态不正确");from+=" AND v.revision_state=?";args.add(state);}
        long total=c.count("SELECT COUNT(*)"+from,args.toArray());args.add(size);args.add(offset);var ids=c.db.queryForList("SELECT v.id"+from+" ORDER BY v.updated_at DESC,v.id DESC LIMIT ? OFFSET ?",args.toArray());var items=ids.stream().map(v->detail(number(v.get("id")),!own)).toList();return new PageResult<>(page,size,total,items);
    }
    /** 组装知识修订的完整详情，包括基础内容、别名、类型属性、来源、组成和关系。 */
    public Map<String,Object> detail(long revision,boolean publicView){
        var v=c.row("SELECT v.*,r.owner_id,r.visibility_status,r.lock_version,n.knowledge_type,n.canonical_name,cat.name AS category_name,cat.legacy_archive AS category_legacy,cat.active AS category_active FROM content_revisions v JOIN content_resources r ON r.id=v.resource_id JOIN knowledge_revision_names n ON n.revision_id=v.id LEFT JOIN content_categories cat ON cat.id=v.category_id WHERE v.id=?",revision);
        long resource=number(v.get("resource_id"));String body=sid(v.get("body"));var out=map("id",""+resource,"revisionId",""+revision,"type",v.get("knowledge_type"),"canonicalName",v.get("canonical_name"),"title",v.get("title"),"summary",body.substring(0,Math.min(180,body.length())),"body",body,"categoryId",sid(v.get("category_id")),"categoryName",v.get("category_name"),"visibilityStatus",v.get("visibility_status"),"revisionState",v.get("revision_state"),"versionNo",v.get("version_no"),"ownerId",sid(v.get("owner_id")),"lockVersion",v.get("lock_version"),"createdAt",utc(v.get("created_at")),"updatedAt",utc(v.get("updated_at")),"changeNote",v.get("change_note"));
        out.put("categoryLegacy",v.get("category_legacy")!=null&&number(v.get("category_legacy"))==1);
        out.put("categoryActive",v.get("category_active")!=null&&number(v.get("category_active"))==1);
        out.put("tags",c.db.queryForList("SELECT t.id,t.name,t.active FROM revision_tags rt JOIN content_tags t ON t.id=rt.tag_id WHERE rt.revision_id=? ORDER BY t.name,t.id",revision).stream().map(t->map("id",sid(t.get("id")),"name",t.get("name"),"active",number(t.get("active"))==1)).toList());
        out.put("aliases",c.db.query("SELECT alias FROM knowledge_revision_aliases WHERE revision_id=? ORDER BY alias",(r,n)->r.getString(1),revision));
        out.put("herb",profile("herb_profiles",revision));out.put("formula",profile("formula_profiles",revision));
        var evidence=new ArrayList<Map<String,Object>>();var indices=new HashMap<Long,Integer>();for(var e:c.db.queryForList("SELECT e.*,d.title AS document_title FROM revision_evidence e JOIN source_documents d ON d.id=e.document_id WHERE e.revision_id=?"+(publicView?" AND d.review_state='APPROVED' AND d.availability='ACTIVE'":"")+" ORDER BY e.id",revision)){indices.put(number(e.get("id")),evidence.size());evidence.add(map("id",sid(e.get("id")),"documentId",sid(e.get("document_id")),"segmentId",sid(e.get("segment_id")),"locationLabel",e.get("location_label"),"quoteText",e.get("quote_text"),"documentTitle",e.get("document_title")));}out.put("evidence",evidence);
        var ingredients=new ArrayList<Map<String,Object>>();for(var i:c.db.queryForList("SELECT i.*,n.canonical_name FROM formula_ingredients i JOIN knowledge_revision_names n ON n.revision_id=i.herb_revision_id WHERE i.formula_revision_id=? ORDER BY i.sort_order,i.id",revision)){if(publicView&&!isVisible(number(i.get("herb_resource_id")),number(i.get("herb_revision_id"))))continue;ingredients.add(map("id",sid(i.get("id")),"herbResourceId",sid(i.get("herb_resource_id")),"herbRevisionId",sid(i.get("herb_revision_id")),"herbName",i.get("canonical_name"),"role",i.get("role"),"doseValue",i.get("dose_value"),"doseUnit",i.get("dose_unit"),"doseText",i.get("dose_text"),"evidenceIndex",indices.get(number(i.get("evidence_id")))));}out.put("ingredients",ingredients);
        var relations=new ArrayList<Map<String,Object>>();for(var rel:c.db.queryForList("SELECT i.*,n.canonical_name FROM knowledge_relations i JOIN knowledge_revision_names n ON n.revision_id=i.target_revision_id WHERE i.source_revision_id=? ORDER BY i.id",revision)){if(publicView&&!isVisible(number(rel.get("target_resource_id")),number(rel.get("target_revision_id"))))continue;relations.add(map("id",sid(rel.get("id")),"targetResourceId",sid(rel.get("target_resource_id")),"targetRevisionId",sid(rel.get("target_revision_id")),"targetName",rel.get("canonical_name"),"relationType",rel.get("relation_type"),"explanation",rel.get("explanation"),"evidenceIndex",indices.get(number(rel.get("evidence_id")))));}out.put("relations",relations);
        var notes=c.db.queryForList("SELECT review_note FROM review_tasks WHERE revision_id=?",revision);out.put("reviewNote",notes.isEmpty()?null:notes.get(0).get("review_note"));return out;
    }
    /** 查询药材或方剂的类型专属属性并转换为接口返回结构。 */
    private Map<String,Object> profile(String table,long revision){var rows=c.db.queryForList("SELECT * FROM "+table+" WHERE revision_id=?",revision);if(rows.isEmpty())return null;var r=rows.get(0);return table.equals("herb_profiles")?map("nature",r.get("nature"),"flavor",r.get("flavor"),"originNote",r.get("origin_note"),"precautions",r.get("precautions")):map("originText",r.get("origin_text"),"functionSummary",r.get("function_summary"),"indicationSummary",r.get("indication_summary"),"precautions",r.get("precautions"));}
    /** 批量校验并导入最多 50 条知识稿件。 */
    @Transactional public Map<String,Object> imports(long actor,ImportInput input){c.require(actor,"contribution:submit");if(input.items()==null||input.items().isEmpty()||input.items().size()>50)throw ApiException.invalid("每次导入1至50项");for(var in:input.items())validate(actor,in,0);return map("items",input.items().stream().map(in->createValidated(actor,in)).toList());}
    /** 从指定文档的成功解析片段中提取草稿，生成待人工校正的知识条目。 */
    @Transactional public Map<String,Object> extract(long actor,ExtractionInput input){
        c.require(actor,"contribution:submit");type(input.type());long document=id(input.documentId());if(c.count("SELECT COUNT(*) FROM source_documents WHERE id=? AND availability='ACTIVE' AND (submitted_by=? OR review_state='APPROVED')",document,actor)==0)throw ApiException.missing();
        if(input.segmentIds()==null||input.segmentIds().isEmpty()||input.segmentIds().size()>50||new HashSet<>(input.segmentIds()).size()!=input.segmentIds().size())throw ApiException.invalid("选择1至50个不同片段");
        var inputs=new ArrayList<KnowledgeInput>();for(String segment:input.segmentIds()){var s=c.row("SELECT s.* FROM document_segments s JOIN document_parse_runs p ON p.id=s.parse_run_id AND p.status='SUCCEEDED' WHERE s.id=? AND s.document_id=?",id(segment),document);String body=text(sid(s.get("body")),20000,true);String title="待校正："+body.substring(0,Math.min(60,body.length()));inputs.add(new KnowledgeInput(input.type(),title,title,body,null,"从来源片段整理，待人工校正",List.of(),null,null,List.of(new Evidence(""+document,segment,sid(s.get("page_label")),body)),List.of(),List.of(),List.of()));}
        for(var in:inputs)validate(actor,in,0);return map("items",inputs.stream().map(in->createValidated(actor,in)).toList());
    }
    /** 暂停已发布知识的公开可见性，并记录原因和移除索引任务。 */
    @Transactional public Map<String,Object> suspend(long actor,long resource,String reason){c.require(actor,"knowledge:review");var r=resource(resource,true);if(r.get("published_revision_id")==null)throw ApiException.conflict("此知识尚未发布");c.db.update("UPDATE content_resources SET visibility_status='SUSPENDED',suspended_reason=?,lock_version=lock_version+1 WHERE id=?",text(reason,1000,true),resource);c.job("REMOVE_INDEX",number(r.get("published_revision_id")),"suspend:"+r.get("lock_version"));c.audit(actor,"KNOWLEDGE_SUSPEND","RESOURCE",resource);return map("id",""+resource,"visibilityStatus","SUSPENDED");}
    /** 针对已发布知识提交纠错反馈，并关联用户、资源和修订版本。 */
    @Transactional public Map<String,Object> feedback(long actor,long resource,String content){c.require(actor,"content:feedback");var detail=publishedView(actor,resource);long feedback=c.insert("INSERT INTO resource_feedback(user_id,resource_id,revision_id,kind,content) VALUES(?,?,?,'CORRECTION',?)",actor,resource,id((String)detail.get("revisionId")),text(content,10000,true));return feedbackView(c.row("SELECT * FROM resource_feedback WHERE id=?",feedback));}
    /** 分页查询反馈；普通用户只能查看自己的反馈，审核人员可按状态查看全部反馈。 */
    public PageResult<Map<String,Object>> feedbacks(long actor,int page,int size,String status){
        int offset=PageResult.offset(page,size);
        if(status==null||status.isBlank())status="ALL";
        if(!Set.of("OPEN","PROCESSING","RESOLVED","REJECTED","ALL").contains(status))throw ApiException.invalid("纠错状态不正确");
        var args=new ArrayList<Object>();String where=" WHERE 1=1";
        if(!c.has(actor,"knowledge:review")){where+=" AND user_id=?";args.add(actor);}
        if(!status.equals("ALL")){where+=" AND status=?";args.add(status);}
        long total=c.count("SELECT COUNT(*) FROM resource_feedback"+where,args.toArray());
        args.add(size);args.add(offset);
        return new PageResult<>(page,size,total,c.db.queryForList("SELECT * FROM resource_feedback"+where+" ORDER BY id DESC LIMIT ? OFFSET ?",args.toArray()).stream().map(this::feedbackView).toList());
    }
    /** 将数据库反馈记录转换为统一的接口返回结构。 */
    private Map<String,Object> feedbackView(Map<String,Object> r){return map("id",sid(r.get("id")),"userId",sid(r.get("user_id")),"resourceId",sid(r.get("resource_id")),"revisionId",sid(r.get("revision_id")),"content",r.get("content"),"status",r.get("status"),"handlerId",sid(r.get("handler_id")),"resolution",r.get("resolution"),"createdAt",utc(r.get("created_at")),"resolvedAt",utc(r.get("resolved_at")));}
    /** 由审核人员更新反馈处理状态、处理人、解决说明和处理时间。 */
    @Transactional public Map<String,Object> resolveFeedback(long actor,long feedback,FeedbackResolution input){c.require(actor,"knowledge:review");if(!Set.of("PROCESSING","RESOLVED","REJECTED").contains(input.status()))throw ApiException.invalid("处理状态不正确");c.row("SELECT id FROM resource_feedback WHERE id=? FOR UPDATE",feedback);c.db.update("UPDATE resource_feedback SET status=?,handler_id=?,resolution=?,resolved_at=IF(?='PROCESSING',NULL,UTC_TIMESTAMP(3)) WHERE id=?",input.status(),actor,text(input.resolution(),10000,true),input.status(),feedback);c.audit(actor,"FEEDBACK_RESOLVE","FEEDBACK",feedback);return feedbackView(c.row("SELECT * FROM resource_feedback WHERE id=?",feedback));}
    /** 校验并并排查询 2 至 3 个已公开方剂的完整详情。 */
    public Map<String,Object> compare(long actor,CompareInput input){if(input.ids()==null||input.ids().size()<2||input.ids().size()>3||new HashSet<>(input.ids()).size()!=input.ids().size())throw ApiException.invalid("请选择2至3个不同方剂");var items=new ArrayList<Map<String,Object>>();for(String value:input.ids()){var r=resource(id(value),false);if(r.get("published_revision_id")==null||!isVisible(id(value),number(r.get("published_revision_id"))))throw ApiException.missing();var detail=detail(number(r.get("published_revision_id")),true);if(!"FORMULA".equals(detail.get("type")))throw ApiException.invalid("只能比较已公开的方剂");items.add(detail);}return map("formulas",items);}
}
