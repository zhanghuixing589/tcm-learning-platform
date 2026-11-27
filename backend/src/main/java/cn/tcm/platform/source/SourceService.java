package cn.tcm.platform.source;

import cn.tcm.platform.common.*;
import jakarta.validation.constraints.*;
import jakarta.validation.Valid;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.net.URI;
import java.util.*;
import static cn.tcm.platform.source.SourceAccess.*;

@Service
public class SourceService {
    private final SourceAccess access;
    private final SourceRights rights;
    private final ObjectMapper json;
    public SourceService(SourceAccess access,SourceRights rights,ObjectMapper json){this.access=access;this.rights=rights;this.json=json;}
    public record Input(String fileId,@NotBlank @Size(max=255)String title,@NotBlank String sourceType,@Size(max=255)String author,@Size(max=255)String publisher,@Min(1)@Max(9999)Integer publicationYear,@Size(max=1500)String sourceUrl,@Size(max=10000)String bibliographicNote,@Valid SourceRights.Input rights,Integer lockVersion,Integer expectedVersion){
        public Input(String fileId,String title,String sourceType,String author,String publisher,Integer publicationYear,String sourceUrl,String bibliographicNote){this(fileId,title,sourceType,author,publisher,publicationYear,sourceUrl,bibliographicNote,null,null,null);}
    }
    private static Long fileId(String id){if(id==null||id.isBlank())return null;try{long value=Long.parseLong(id);if(value>0)return value;}catch(NumberFormatException ignored){}throw ApiException.invalid("fileId无效");}
    private void validate(long user,Input input,Long previousFile){
        if(input.title()==null||input.title().isBlank()||input.title().length()>255||!Set.of("BOOK","PAPER","WEB","COMMUNITY","OTHER").contains(input.sourceType()))throw ApiException.invalid("标题或来源类型无效");
        Long file=fileId(input.fileId());if(file!=null&&!file.equals(previousFile)&&!access.uploaded(user,file))throw ApiException.missing();
        String url=blank(input.sourceUrl());if(url!=null){try{URI uri=URI.create(url);if(!Set.of("http","https").contains(uri.getScheme())||uri.getHost()==null||uri.getUserInfo()!=null)throw new IllegalArgumentException();}catch(IllegalArgumentException e){throw ApiException.invalid("来源链接须为HTTP(S)书目地址");}}
        if(file==null&&url==null)throw ApiException.invalid("请上传文件或提供来源书目链接");
    }
    private static String blank(String value){return value==null||value.isBlank()?null:value.strip();}
    public Map<String,Object> detail(long user,long id){return view(access.document(user,id,false));}
    public Map<String,Object> view(Map<String,Object>d){
        Object file=d.get("file_id");String name=null;
        if(file!=null){var names=access.db.queryForList("SELECT original_name FROM file_upload_grants WHERE file_id=? AND user_id=?",String.class,file,d.get("submitted_by"));name=names.isEmpty()?"source-document":names.get(0);}
        var runs=access.db.queryForList("SELECT * FROM document_parse_runs WHERE document_id=? ORDER BY parse_version DESC LIMIT 1",d.get("id"));
        var out=map("id",id(d.get("id")),"title",d.get("title"),"sourceType",d.get("source_type"),"author",d.get("author"),"publisher",d.get("publisher"),"publicationYear",d.get("publication_year"),"sourceUrl",d.get("source_url"),"bibliographicNote",d.get("bibliographic_note"),"fileId",id(file),"fileName",name,"reviewState",d.get("review_state"),"availability",d.get("availability"),"submittedBy",id(d.get("submitted_by")),"parentDocumentId",id(d.get("parent_document_id")),"createdAt",utc(d.get("created_at")),"parseRun",runs.isEmpty()?null:runView(runs.get(0)));
        out.put("rights",SourceRights.view(d));out.put("reviewedRights",decoded(d.get("reviewed_rights"),null));out.put("rightsStatus",d.get("rights_status"));out.put("lockVersion",d.get("lock_version"));out.put("readingEditionId",id(d.get("active_reading_edition_id")));out.put("approvedUse",d.get("approved_use"));
        out.put("processingSummary",processingSummary(((Number)d.get("id")).longValue()));
        for(String type:List.of("PARSE","BUILD_READER")){var jobs=access.db.queryForList("SELECT id,status,last_error FROM async_jobs WHERE document_id=? AND job_type=? ORDER BY id DESC LIMIT 1",d.get("id"),type);out.put(type.equals("PARSE")?"processingJob":"readerBuild",jobs.isEmpty()?null:map("id",id(jobs.get(0).get("id")),"status",jobs.get(0).get("status"),"errorMessage",jobs.get(0).get("last_error")));}return out;
    }
    private Object decoded(Object value,Object fallback){if(value==null)return fallback;try{return json.readValue(value.toString(),Object.class);}catch(Exception e){return fallback;}}
    public Map<String,Object> processingSummary(long document){
        var editions=access.db.queryForList("SELECT * FROM source_reading_editions WHERE document_id=? ORDER BY reading_version_no DESC LIMIT 1",document);
        if(editions.isEmpty())return null;
        var e=editions.get(0);var units=access.db.queryForList("SELECT id,unit_no,title,capabilities FROM source_reading_units WHERE edition_id=? ORDER BY unit_no",e.get("id")).stream().map(u->{var caps=(Map<?,?>)decoded(u.get("capabilities"),Map.of());return map("id",id(u.get("id")),"unitNo",u.get("unit_no"),"title",u.get("title"),"textReliable",Boolean.TRUE.equals(caps.get("preciseCitationSupported")));}).toList();
        String fingerprint=SourceFiles.hash((e.get("id")+":"+e.get("file_sha256")+":"+e.get("warnings")+":"+units).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return map("overallOutcome",e.get("overall_outcome"),"stages",decoded(e.get("stage_results"),Map.of()),"warnings",decoded(e.get("warnings"),List.of()),"units",units,"coverageSnapshotHash",fingerprint,"readingEditionId",id(e.get("id")));
    }
    static Map<String,Object> runView(Map<String,Object>r){return map("id",id(r.get("id")),"status",r.get("status"),"errorMessage",r.get("error_message"),"parseVersion",r.get("parse_version"));}
    public PageResult<Map<String,Object>> list(long user,int page,int size,String q,String state,boolean approved){
        int offset=PageResult.offset(page,size);var args=new ArrayList<Object>();StringBuilder where=new StringBuilder(" WHERE 1=1");
        if(approved){where.append(" AND review_state='APPROVED' AND availability='ACTIVE'");}else if(!access.permission(user,"knowledge:review")){where.append(" AND submitted_by=?");args.add(user);}
        if(q!=null&&!q.isBlank()){if(q.length()>255)throw ApiException.invalid("搜索词太长");where.append(" AND title LIKE ?");args.add("%"+q.strip()+"%");}
        if(state!=null&&!state.isBlank()){if(!Set.of("DRAFT","IN_REVIEW","APPROVED","REJECTED","WITHDRAWN").contains(state))throw ApiException.invalid("审核状态无效");where.append(" AND review_state=?");args.add(state);}
        long total=access.db.queryForObject("SELECT COUNT(*) FROM source_documents"+where,Long.class,args.toArray());args.add(size);args.add(offset);
        return new PageResult<>(page,size,total,access.db.queryForList("SELECT * FROM source_documents"+where+" ORDER BY created_at DESC,id DESC LIMIT ? OFFSET ?",args.toArray()).stream().map(this::view).toList());
    }
    @Transactional public Map<String,Object> create(long user,Input input){access.require(user,"contribution:submit");validate(user,input,null);long id=access.insert("INSERT INTO source_documents(file_id,title,source_type,author,publisher,publication_year,source_url,bibliographic_note,submitted_by,rights_status) VALUES(?,?,?,?,?,?,?,?,?,'UNCONFIRMED')",fileId(input.fileId()),input.title().strip(),input.sourceType(),blank(input.author()),blank(input.publisher()),input.publicationYear(),blank(input.sourceUrl()),blank(input.bibliographicNote()),user);rights.apply(id,input.rights());access.audit(user,"SOURCE_CREATE","SOURCE",id);return detail(user,id);}
    @Transactional public Map<String,Object> update(long user,long id,Input input){
        access.require(user,"contribution:submit");var d=access.document(user,id,true);access.draft(user,d);Long previous=d.get("file_id")==null?null:((Number)d.get("file_id")).longValue();validate(user,input,previous);
        Integer expected=input.expectedVersion()!=null?input.expectedVersion():input.lockVersion();if(expected!=null&&((Number)d.get("lock_version")).intValue()!=expected)throw ApiException.conflict("资料已被修改，请保留输入并重新读取版本");
        Long next=fileId(input.fileId());
        if(!Objects.equals(previous,next)&&access.db.queryForObject("SELECT COUNT(*) FROM document_parse_runs WHERE document_id=?",Long.class,id)>0)throw ApiException.conflict("已有解析的资料不能更换文件，请新建资料以保留证据归属");
        access.db.update("UPDATE source_documents SET file_id=?,title=?,source_type=?,author=?,publisher=?,publication_year=?,source_url=?,bibliographic_note=?,lock_version=lock_version+1 WHERE id=?",next,input.title().strip(),input.sourceType(),blank(input.author()),blank(input.publisher()),input.publicationYear(),blank(input.sourceUrl()),blank(input.bibliographicNote()),id);rights.apply(id,input.rights());access.audit(user,"SOURCE_UPDATE","SOURCE",id);return detail(user,id);
    }
    @Transactional public Map<String,Object> parse(long user,long id){
        var d=access.document(user,id,true);if(!d.get("submitted_by").toString().equals(Long.toString(user))&&!access.permission(user,"knowledge:review"))throw ApiException.missing();
        if(!"DRAFT".equals(d.get("review_state")))throw ApiException.conflict("已提交资料不能重新解析，请复制修订");if(d.get("file_id")==null)throw ApiException.invalid("书目链接不由服务端抓取，请先上传文件");
        long busy=access.db.queryForObject("SELECT COUNT(*) FROM async_jobs WHERE document_id=? AND job_type IN ('PARSE','BUILD_READER') AND status IN ('QUEUED','RUNNING','RETRY_WAIT')",Long.class,id);if(busy>0)throw ApiException.conflict("资料已有待处理解析任务");
        int version=access.db.queryForObject("SELECT COALESCE(MAX(parse_version),0)+1 FROM document_parse_runs WHERE document_id=?",Integer.class,id);
        long run=access.insert("INSERT INTO document_parse_runs(document_id,parse_version,parser_name) VALUES(?,?,'deterministic-v2')",id,version);
        access.db.update("INSERT INTO async_jobs(job_type,idempotency_key,document_id,payload) VALUES('PARSE',?,?,JSON_OBJECT('parseRunId',?))","parse:"+id+":"+run,id,Long.toString(run));access.audit(user,"SOURCE_PARSE","SOURCE",id);
        return runView(access.db.queryForMap("SELECT * FROM document_parse_runs WHERE id=?",run));
    }
    @Transactional public Map<String,Object> retryParse(long user,long id){var initial=access.document(user,id,false);access.draft(user,initial);var jobs=access.db.queryForList("SELECT * FROM async_jobs WHERE document_id=? AND job_type='PARSE' ORDER BY id DESC LIMIT 1 FOR UPDATE",id);if(jobs.isEmpty())throw ApiException.conflict("尚未开始处理");var d=access.document(user,id,true);access.draft(user,d);var job=jobs.get(0);if(!Set.of("DEAD","RETRY_WAIT").contains(job.get("status")))throw ApiException.conflict("当前处理不可重试");access.db.update("UPDATE async_jobs SET status='QUEUED',available_at=UTC_TIMESTAMP(3),run_token=NULL,lease_until=NULL,last_error=NULL,max_attempts=GREATEST(max_attempts,attempt_count+1) WHERE id=?",job.get("id"));access.audit(user,"SOURCE_RETRY","SOURCE",id);return detail(user,id);}
    @Transactional public Map<String,Object> buildReader(long user,long id){var d=access.document(user,id,true);if(!rights.privileged(user,d))throw ApiException.missing();if(!"ACTIVE".equals(d.get("availability"))||"IN_REVIEW".equals(d.get("review_state")))throw ApiException.conflict("停用或审核中的来源不可构建阅读版本");
        var runs=access.db.queryForList("SELECT id FROM document_parse_runs WHERE document_id=? AND status='SUCCEEDED' ORDER BY parse_version DESC LIMIT 1",Long.class,id);if(runs.isEmpty())throw ApiException.conflict("请先完成文件解析");
        if(access.db.queryForObject("SELECT COUNT(*) FROM async_jobs WHERE document_id=? AND job_type IN ('PARSE','BUILD_READER') AND status IN ('QUEUED','RUNNING','RETRY_WAIT')",Long.class,id)>0)throw ApiException.conflict("资料已有待处理任务");
        access.db.update("INSERT INTO async_jobs(job_type,idempotency_key,document_id,payload) VALUES('BUILD_READER',?,?,JSON_OBJECT('parseRunId',?))","reader:"+id+":"+UUID.randomUUID(),id,runs.get(0).toString());access.db.update("UPDATE source_documents SET reader_required=1 WHERE id=?",id);access.audit(user,"SOURCE_READER_BUILD","SOURCE",id);return detail(user,id);}
    public PageResult<Map<String,Object>> segments(long user,long id,int page,int size,Long run){var document=rights.requireRead(user,id);int offset=PageResult.offset(page,size);
        if(!rights.privileged(user,document)){Object approved=document.get("reviewed_parse_run_id");if(approved==null)throw ApiException.missing();if(run!=null&&!run.toString().equals(approved.toString()))throw ApiException.missing();run=Long.valueOf(approved.toString());}
        if(run==null){var runs=access.db.queryForList("SELECT id FROM document_parse_runs WHERE document_id=? AND status='SUCCEEDED' ORDER BY parse_version DESC LIMIT 1",Long.class,id);if(runs.isEmpty())return new PageResult<>(page,size,0,List.of());run=runs.get(0);}
        if(access.db.queryForObject("SELECT COUNT(*) FROM document_parse_runs WHERE id=? AND document_id=?",Long.class,run,id)==0)throw ApiException.missing();
        long total=access.db.queryForObject("SELECT COUNT(*) FROM document_segments WHERE document_id=? AND parse_run_id=?",Long.class,id,run);
        var items=access.db.queryForList("SELECT * FROM document_segments WHERE document_id=? AND parse_run_id=? ORDER BY segment_no LIMIT ? OFFSET ?",id,run,size,offset).stream().map(r->map("id",id(r.get("id")),"documentId",id(r.get("document_id")),"parseRunId",id(r.get("parse_run_id")),"segmentNo",r.get("segment_no"),"pageLabel",r.get("page_label"),"headingPath",r.get("heading_path"),"body",r.get("body"))).toList();return new PageResult<>(page,size,total,items);
    }
    @Transactional public Map<String,Object> submit(long user,long id){access.require(user,"contribution:submit");var d=access.document(user,id,true);access.draft(user,d);
        if(!"DECLARED".equals(d.get("rights_status")))throw ApiException.conflict("请先完善版权来源与授权范围");
        if(access.db.queryForObject("SELECT COUNT(*) FROM async_jobs WHERE document_id=? AND job_type IN ('PARSE','BUILD_READER') AND status IN ('QUEUED','RUNNING','RETRY_WAIT')",Long.class,id)>0)throw ApiException.conflict("请等待当前处理或阅读构建结束，再提交固定版本审核");
        if(d.get("file_id")!=null){var states=access.db.queryForList("SELECT status FROM document_parse_runs WHERE document_id=? ORDER BY parse_version DESC LIMIT 1",String.class,id);if(states.isEmpty()||!states.get(0).equals("SUCCEEDED"))throw ApiException.conflict("上传资料须完成最新解析后才能提交审核");}
        if(SourceRights.flag(d.get("reader_required"))&&d.get("active_reading_edition_id")==null)throw ApiException.conflict("资料尚无可安全阅读的处理结果");
        access.db.update("UPDATE source_documents SET reviewed_parse_run_id=(SELECT p.id FROM document_parse_runs p WHERE p.document_id=? AND p.status='SUCCEEDED' ORDER BY p.parse_version DESC LIMIT 1),reviewed_reading_edition_id=active_reading_edition_id WHERE id=?",id,id);
        try{access.db.update("UPDATE source_documents SET reviewed_rights=? WHERE id=?",json.writeValueAsString(SourceRights.view(d)),id);}catch(com.fasterxml.jackson.core.JsonProcessingException e){throw new IllegalStateException(e);}
        access.db.update("UPDATE source_documents SET review_state='IN_REVIEW' WHERE id=?",id);access.db.update("INSERT INTO review_tasks(document_id,submitted_by) VALUES(?,?)",id,user);access.audit(user,"SOURCE_SUBMIT","SOURCE",id);return detail(user,id);
    }
    @Transactional public Map<String,Object> withdraw(long user,long id){
        var d=access.document(user,id,true);access.owner(user,d);var tasks=access.db.queryForList("SELECT * FROM review_tasks WHERE document_id=? FOR UPDATE",id);
        if(!"IN_REVIEW".equals(d.get("review_state"))||tasks.isEmpty()||!"PENDING".equals(tasks.get(0).get("status")))throw ApiException.conflict("当前资料不可撤回");
        access.db.update("UPDATE source_documents SET review_state='WITHDRAWN' WHERE id=?",id);long task=((Number)tasks.get(0).get("id")).longValue();access.db.update("UPDATE review_tasks SET status='WITHDRAWN',lock_version=lock_version+1 WHERE id=?",task);access.db.update("INSERT INTO review_events(review_task_id,actor_id,action,note) VALUES(?,?,'WITHDRAW','提交人撤回')",task,user);access.audit(user,"SOURCE_WITHDRAW","SOURCE",id);return detail(user,id);
    }
    @Transactional public Map<String,Object> revise(long user,long id){access.require(user,"contribution:submit");var d=access.document(user,id,true);access.owner(user,d);if("DRAFT".equals(d.get("review_state"))||"IN_REVIEW".equals(d.get("review_state")))throw ApiException.conflict("请先完成或撤回本次提交，再复制修订");
        long next=access.insert("INSERT INTO source_documents(file_id,origin_revision_id,parent_document_id,title,source_type,author,publisher,publication_year,source_url,bibliographic_note,submitted_by) SELECT file_id,origin_revision_id,id,title,source_type,author,publisher,publication_year,source_url,bibliographic_note,submitted_by FROM source_documents WHERE id=?",id);
        // Copy successful text into a new batch with new segment IDs; historical evidence never changes.
        var runs=access.db.queryForList("SELECT id,parser_name FROM document_parse_runs WHERE document_id=? AND status='SUCCEEDED' ORDER BY parse_version DESC LIMIT 1",id);if(!runs.isEmpty()){var previous=runs.get(0);long run=access.insert("INSERT INTO document_parse_runs(document_id,parse_version,parser_name,status) VALUES(?,1,?,'SUCCEEDED')",next,previous.get("parser_name"));access.db.update("INSERT INTO document_segments(document_id,parse_run_id,segment_no,heading_path,page_label,body,content_hash) SELECT ?,?,segment_no,heading_path,page_label,body,content_hash FROM document_segments WHERE parse_run_id=?",next,run,previous.get("id"));}
        access.db.update("UPDATE source_documents n JOIN source_documents old ON old.id=? SET n.rights_status=old.rights_status,n.license_type=old.license_type,n.copyright_source=old.copyright_source,n.authorization_scope=old.authorization_scope,n.public_reading_allowed=old.public_reading_allowed,n.public_download_allowed=old.public_download_allowed,n.quote_display_allowed=old.quote_display_allowed,n.rag_use_allowed=old.rag_use_allowed,n.reader_required=old.reader_required WHERE n.id=?",id,next);
        if(SourceRights.flag(d.get("reader_required"))&&!runs.isEmpty())buildReader(user,next);
        access.audit(user,"SOURCE_REVISE","SOURCE",next);return detail(user,next);
    }
    @Transactional public Map<String,Object> suspend(long user,long id,String reason){access.require(user,"knowledge:review");if(reason==null||reason.isBlank()||reason.length()>1000)throw ApiException.invalid("请填写1000字以内停用原因");var d=access.document(user,id,true);if(!"APPROVED".equals(d.get("review_state")))throw ApiException.conflict("只有已审核来源可停用");
        var affected=access.db.queryForList("SELECT DISTINCT r.id,v.id AS revision_id,v.title FROM content_resources r JOIN content_revisions v ON v.id=r.published_revision_id JOIN revision_evidence e ON e.revision_id=v.id WHERE e.document_id=? AND r.visibility_status IN ('PUBLISHED','SUSPENDED') ORDER BY r.id FOR UPDATE",id);
        access.db.update("UPDATE source_documents SET availability='SUSPENDED' WHERE id=?",id);
        String resourceReason="来源停用："+reason.strip();if(resourceReason.length()>1000)resourceReason=resourceReason.substring(0,1000);
        for(var r:affected){long resource=((Number)r.get("id")).longValue();access.db.update("UPDATE content_resources SET visibility_status='SUSPENDED',suspended_reason=?,lock_version=lock_version+1 WHERE id=?",resourceReason,resource);access.db.update("INSERT INTO async_jobs(job_type,idempotency_key,revision_id,document_id,payload) VALUES('REMOVE_INDEX',?,?,?,JSON_OBJECT('reason','SOURCE_SUSPENDED')) ON DUPLICATE KEY UPDATE id=id","source-suspend:"+id+":"+resource,r.get("revision_id"),id);}
        access.audit(user,"SOURCE_SUSPEND","SOURCE",id);
        access.db.update("INSERT INTO user_notifications(user_id,event_key,kind,title,body,relative_path) VALUES(?,?,'SOURCE_SUSPENDED','来源资料已停用',?,?) ON DUPLICATE KEY UPDATE event_key=event_key",d.get("submitted_by"),"source-suspend:"+id,reason.strip(),"/contribute/sources/"+id);
        String trace=org.slf4j.MDC.get("traceId");access.db.update("INSERT INTO sys_audit_logs(actor_id,trace_id,action,object_type,object_key,summary) VALUES(?,?,'SOURCE_SUSPEND_REASON','SOURCE',?,JSON_OBJECT('reason',?))",user,trace==null?UUID.randomUUID().toString():trace,Long.toString(id),reason.strip());
        return map("affectedResources",affected.stream().map(r->map("id",id(r.get("id")),"title",r.get("title"))).toList());
    }
}
