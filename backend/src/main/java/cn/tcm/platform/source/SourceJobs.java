package cn.tcm.platform.source;

import cn.tcm.platform.common.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static cn.tcm.platform.source.SourceAccess.*;

@Service
public class SourceJobs {
    private final SourceAccess access;
    private final TransactionTemplate tx;
    private final ObjectMapper json;
    public record Lease(long id,long documentId,long parseRunId,String runToken){}
    public record ParsedSegment(int segmentNo,String pageLabel,String headingPath,String body){}
    public SourceJobs(SourceAccess access,PlatformTransactionManager manager,ObjectMapper json){this.access=access;this.tx=new TransactionTemplate(manager);this.json=json;}
    private long runId(Map<String,Object> job){try{return json.readTree(job.get("payload").toString()).path("parseRunId").asLong();}catch(Exception e){throw ApiException.conflict("解析任务参数无效");}}
    public Lease claim(){return tx.execute(status->{
        var rows=access.db.queryForList("SELECT * FROM async_jobs WHERE job_type='PARSE' AND ((status IN ('QUEUED','RETRY_WAIT') AND available_at<=UTC_TIMESTAMP(3)) OR (status='RUNNING' AND lease_until<=UTC_TIMESTAMP(3))) ORDER BY id LIMIT 1 FOR UPDATE SKIP LOCKED");
        if(rows.isEmpty())return null;var job=rows.get(0);long id=((Number)job.get("id")).longValue();long document=((Number)job.get("document_id")).longValue();long run=runId(job);
        if("RUNNING".equals(job.get("status")))access.db.update("UPDATE async_job_attempts SET status='EXPIRED',finished_at=UTC_TIMESTAMP(3),error_message='执行租约已过期' WHERE job_id=? AND run_token=? AND status='RUNNING'",id,job.get("run_token"));
        int attempt=((Number)job.get("attempt_count")).intValue()+1;
        if(attempt>((Number)job.get("max_attempts")).intValue()){
            access.db.update("UPDATE async_jobs SET status='DEAD',run_token=NULL,lease_until=NULL,last_error='已达到最大尝试次数' WHERE id=?",id);access.db.update("UPDATE document_parse_runs SET status='FAILED',error_code='ATTEMPTS_EXHAUSTED',error_message='已达到最大尝试次数，请联系技术管理员重试' WHERE id=? AND status<>'SUCCEEDED'",run);return null;
        }
        var documents=access.db.queryForList("SELECT review_state FROM source_documents WHERE id=? FOR UPDATE",String.class,document);
        if(documents.isEmpty()||!documents.get(0).equals("DRAFT")){access.db.update("UPDATE async_jobs SET status='CANCELLED',run_token=NULL,lease_until=NULL,last_error='资料已冻结' WHERE id=?",id);access.db.update("UPDATE document_parse_runs SET status='FAILED',error_code='DOCUMENT_FROZEN',error_message='资料已提交，旧解析已取消' WHERE id=? AND status<>'SUCCEEDED'",run);return null;}
        String token=UUID.randomUUID().toString();
        access.db.update("UPDATE async_jobs SET status='RUNNING',attempt_count=?,run_token=?,lease_until=UTC_TIMESTAMP(3)+INTERVAL 90 SECOND,last_error=NULL WHERE id=?",attempt,token,id);
        access.db.update("INSERT INTO async_job_attempts(job_id,attempt_no,run_token,started_at,status) VALUES(?,?,?,UTC_TIMESTAMP(3),'RUNNING')",id,attempt,token);
        access.db.update("UPDATE document_parse_runs SET status='RUNNING',error_code=NULL,error_message=NULL WHERE id=?",run);
        return new Lease(id,document,run,token);
    });}
    public boolean complete(Lease lease,List<ParsedSegment> segments,String error){return Boolean.TRUE.equals(tx.execute(status->{
        // Token AND unexpired lease guard every attempt result, including failures.
        var jobs=access.db.queryForList("SELECT * FROM async_jobs WHERE id=? AND status='RUNNING' AND run_token=? AND lease_until>UTC_TIMESTAMP(3) FOR UPDATE",lease.id(),lease.runToken());if(jobs.isEmpty())return false;
        var job=jobs.get(0);if(runId(job)!=lease.parseRunId()||((Number)job.get("document_id")).longValue()!=lease.documentId())return false;
        var docs=access.db.queryForList("SELECT review_state FROM source_documents WHERE id=? FOR UPDATE",String.class,lease.documentId());if(docs.isEmpty()||!docs.get(0).equals("DRAFT"))return false;
        String failure=error;
        if(failure==null){if(segments==null||segments.isEmpty()||segments.size()>10000)failure="解析结果为空或数量超限";else {long total=0;int expected=1;for(var s:segments){if(s.segmentNo()!=expected++||s.body()==null||s.body().isBlank()||(s.pageLabel()!=null&&s.pageLabel().length()>80)||(s.headingPath()!=null&&s.headingPath().length()>500)){failure="解析结果格式无效";break;}total+=s.body().length();if(total>2_000_000){failure="解析文本超限";break;}}}}
        if(failure==null){
            // Success appends only to this new batch. Retried jobs never replace successful historical segments.
            for(var s:segments)access.db.update("INSERT INTO document_segments(document_id,parse_run_id,segment_no,page_label,heading_path,body,content_hash) VALUES(?,?,?,?,?,?,?)",lease.documentId(),lease.parseRunId(),s.segmentNo(),s.pageLabel(),s.headingPath(),s.body(),SourceFiles.hash(s.body().getBytes(StandardCharsets.UTF_8)));
            access.db.update("UPDATE document_parse_runs SET status='SUCCEEDED',error_code=NULL,error_message=NULL WHERE id=?",lease.parseRunId());
            access.db.update("UPDATE async_jobs SET status='SUCCEEDED',lease_until=NULL,run_token=NULL,last_error=NULL WHERE id=? AND run_token=?",lease.id(),lease.runToken());
            access.db.update("UPDATE async_job_attempts SET status='SUCCEEDED',finished_at=UTC_TIMESTAMP(3) WHERE job_id=? AND run_token=?",lease.id(),lease.runToken());
        }else{
            failure=failure.length()>1000?failure.substring(0,1000):failure;
            boolean exhausted=((Number)job.get("attempt_count")).intValue()>=((Number)job.get("max_attempts")).intValue();
            access.db.update("UPDATE document_parse_runs SET status='FAILED',error_code='PARSE_FAILED',error_message=? WHERE id=?",failure,lease.parseRunId());
            access.db.update("UPDATE async_jobs SET status=?,lease_until=NULL,run_token=NULL,last_error=?,available_at=UTC_TIMESTAMP(3)+INTERVAL 30 SECOND WHERE id=? AND run_token=?",exhausted?"DEAD":"RETRY_WAIT",failure,lease.id(),lease.runToken());
            access.db.update("UPDATE async_job_attempts SET status='FAILED',finished_at=UTC_TIMESTAMP(3),error_message=? WHERE job_id=? AND run_token=?",failure,lease.id(),lease.runToken());
        }
        return true;
    }));}
    public PageResult<Map<String,Object>> list(long user,int page,int size,String status,String type){access.require(user,"system:job:manage");int offset=PageResult.offset(page,size);var args=new ArrayList<Object>();StringBuilder where=new StringBuilder(" WHERE 1=1");
        if(status!=null&&!status.isBlank()){if(!Set.of("QUEUED","RUNNING","RETRY_WAIT","SUCCEEDED","DEAD","CANCELLED").contains(status))throw ApiException.invalid("任务状态无效");where.append(" AND status=?");args.add(status);}
        if(type!=null&&!type.isBlank()){if(type.length()>40)throw ApiException.invalid("任务类型无效");where.append(" AND job_type=?");args.add(type);}
        long total=access.db.queryForObject("SELECT COUNT(*) FROM async_jobs"+where,Long.class,args.toArray());args.add(size);args.add(offset);var rows=access.db.queryForList("SELECT * FROM async_jobs"+where+" ORDER BY created_at DESC,id DESC LIMIT ? OFFSET ?",args.toArray());return new PageResult<>(page,size,total,rows.stream().map(this::view).toList());
    }
    private Map<String,Object> view(Map<String,Object> j){var attempts=access.db.queryForList("SELECT * FROM async_job_attempts WHERE job_id=? ORDER BY attempt_no",j.get("id")).stream().map(a->map("id",id(a.get("id")),"attemptNo",a.get("attempt_no"),"status",a.get("status"),"errorMessage",a.get("error_message"),"startedAt",utc(a.get("started_at")),"finishedAt",utc(a.get("finished_at")))).toList();
        return map("id",id(j.get("id")),"jobType",j.get("job_type"),"documentId",id(j.get("document_id")),"revisionId",id(j.get("revision_id")),"status",j.get("status"),"attemptCount",j.get("attempt_count"),"maxAttempts",j.get("max_attempts"),"lastError",j.get("last_error"),"availableAt",utc(j.get("available_at")),"leaseUntil",utc(j.get("lease_until")),"createdAt",utc(j.get("created_at")),"serviceMessage","PARSE".equals(j.get("job_type"))?null:"等待第四阶段索引服务","attempts",attempts);
    }
    public Map<String,Object> retry(long user,long id){access.require(user,"system:job:manage");return tx.execute(status->{
        var rows=access.db.queryForList("SELECT *,lease_until<=UTC_TIMESTAMP(3) AS expired FROM async_jobs WHERE id=? FOR UPDATE",id);if(rows.isEmpty())throw ApiException.missing();var job=rows.get(0);
        boolean expired="RUNNING".equals(job.get("status"))&&job.get("expired")!=null&&((Number)job.get("expired")).intValue()==1;
        if(!expired&&!Set.of("DEAD","RETRY_WAIT").contains(job.get("status")))throw ApiException.conflict("仅失败或租约到期的任务可重试");
        if("PARSE".equals(job.get("job_type"))){var docs=access.db.queryForList("SELECT review_state FROM source_documents WHERE id=? FOR UPDATE",String.class,job.get("document_id"));if(docs.isEmpty()||!docs.get(0).equals("DRAFT"))throw ApiException.conflict("资料已冻结，不能重试旧解析");long run=runId(job);if(access.db.queryForObject("SELECT COUNT(*) FROM document_parse_runs WHERE id=? AND status='SUCCEEDED'",Long.class,run)>0)throw ApiException.conflict("成功的解析不可重写");access.db.update("UPDATE document_parse_runs SET status='QUEUED',error_code=NULL,error_message=NULL WHERE id=?",run);}
        if(expired)access.db.update("UPDATE async_job_attempts SET status='EXPIRED',finished_at=UTC_TIMESTAMP(3),error_message='管理员重试已过期任务' WHERE job_id=? AND run_token=? AND status='RUNNING'",id,job.get("run_token"));
        access.db.update("UPDATE async_jobs SET status='QUEUED',available_at=UTC_TIMESTAMP(3),run_token=NULL,lease_until=NULL,last_error=NULL,max_attempts=GREATEST(max_attempts,attempt_count+1) WHERE id=?",id);access.audit(user,"JOB_RETRY","JOB",id);return view(access.db.queryForMap("SELECT * FROM async_jobs WHERE id=?",id));
    });}
}
