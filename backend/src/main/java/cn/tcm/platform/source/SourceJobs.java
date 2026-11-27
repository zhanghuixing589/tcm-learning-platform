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
    public record Lease(long id,long documentId,long parseRunId,String runToken,String jobType){
        public Lease(long id,long documentId,long parseRunId,String runToken){this(id,documentId,parseRunId,runToken,"PARSE");}
    }
    public record ParsedSegment(int segmentNo,String pageLabel,String headingPath,String body){}
    private final SourceReadingData readingData;
    public SourceJobs(SourceAccess access,PlatformTransactionManager manager,ObjectMapper json,SourceReadingData readingData){this.access=access;this.tx=new TransactionTemplate(manager);this.json=json;this.readingData=readingData;}
    private long runId(Map<String,Object> job){try{long run=json.readTree(job.get("payload").toString()).path("parseRunId").asLong();if(run<=0)throw new IllegalArgumentException();return run;}catch(Exception e){throw ApiException.conflict("处理任务参数无效");}}
    private boolean same(Lease lease,Map<String,Object> job){return runId(job)==lease.parseRunId()&&((Number)job.get("document_id")).longValue()==lease.documentId()&&Objects.equals(job.get("job_type"),lease.jobType());}
    private boolean latestRun(long document,long run){return access.db.queryForObject("SELECT COUNT(*) FROM document_parse_runs r JOIN document_parse_runs newer ON newer.document_id=r.document_id AND newer.parse_version>r.parse_version WHERE r.id=? AND r.document_id=?",Long.class,run,document)==0;}
    public Lease activeLease(long id,String token){
        var rows=access.db.queryForList("SELECT j.* FROM async_jobs j JOIN source_documents d ON d.id=j.document_id WHERE j.id=? AND j.status='RUNNING' AND j.run_token=? AND j.lease_until>UTC_TIMESTAMP(3) AND ((j.job_type='PARSE' AND d.review_state='DRAFT') OR (j.job_type='BUILD_READER' AND d.availability='ACTIVE'))",id,token);
        if(rows.isEmpty())return null;var j=rows.get(0);long document=((Number)j.get("document_id")).longValue(),run=runId(j);if("PARSE".equals(j.get("job_type"))&&!latestRun(document,run))return null;return new Lease(id,document,run,token,(String)j.get("job_type"));
    }
    public boolean validLease(Lease lease){var current=activeLease(lease.id(),lease.runToken());return lease.equals(current);}
    public boolean heartbeat(Lease lease){if("PARSE".equals(lease.jobType())&&!latestRun(lease.documentId(),lease.parseRunId()))return false;return access.db.update("UPDATE async_jobs j JOIN source_documents d ON d.id=j.document_id SET j.lease_until=UTC_TIMESTAMP(3)+INTERVAL 90 SECOND WHERE j.id=? AND j.document_id=? AND j.job_type=? AND j.status='RUNNING' AND j.run_token=? AND j.lease_until>UTC_TIMESTAMP(3) AND ((j.job_type='PARSE' AND d.review_state='DRAFT') OR (j.job_type='BUILD_READER' AND d.availability='ACTIVE'))",lease.id(),lease.documentId(),lease.jobType(),lease.runToken())==1;}
    public Lease claim(){return tx.execute(status->{
        var rows=access.db.queryForList("SELECT * FROM async_jobs WHERE job_type IN ('PARSE','BUILD_READER') AND ((status IN ('QUEUED','RETRY_WAIT') AND available_at<=UTC_TIMESTAMP(3)) OR (status='RUNNING' AND lease_until<=UTC_TIMESTAMP(3))) ORDER BY id LIMIT 1 FOR UPDATE SKIP LOCKED");
        if(rows.isEmpty())return null;var job=rows.get(0);long id=((Number)job.get("id")).longValue(),document=((Number)job.get("document_id")).longValue(),run=runId(job);boolean build="BUILD_READER".equals(job.get("job_type"));
        if("RUNNING".equals(job.get("status")))access.db.update("UPDATE async_job_attempts SET status='EXPIRED',finished_at=UTC_TIMESTAMP(3),error_message='执行租约已过期' WHERE job_id=? AND run_token=? AND status='RUNNING'",id,job.get("run_token"));
        int attempt=((Number)job.get("attempt_count")).intValue()+1;
        if(attempt>((Number)job.get("max_attempts")).intValue()){
            access.db.update("UPDATE async_jobs SET status='DEAD',run_token=NULL,lease_until=NULL,last_error='已达到最大尝试次数' WHERE id=?",id);
            if(!build)access.db.update("UPDATE document_parse_runs SET status='FAILED',error_code='ATTEMPTS_EXHAUSTED',error_message='已达到最大尝试次数，请联系技术管理员重试' WHERE id=? AND status<>'SUCCEEDED'",run);return null;
        }
        var documents=access.db.queryForList("SELECT review_state,availability FROM source_documents WHERE id=? FOR UPDATE",document);
        boolean eligible=!documents.isEmpty()&&(build?"ACTIVE".equals(documents.get(0).get("availability")):"DRAFT".equals(documents.get(0).get("review_state")));
        var runs=access.db.queryForList("SELECT status FROM document_parse_runs WHERE id=? AND document_id=? FOR UPDATE",String.class,run,document);
        eligible=eligible&&!runs.isEmpty()&&(build?"SUCCEEDED".equals(runs.get(0)):!"SUCCEEDED".equals(runs.get(0))&&latestRun(document,run));
        if(!eligible){access.db.update("UPDATE async_jobs SET status='CANCELLED',run_token=NULL,lease_until=NULL,last_error='资料已冻结或处理批次无效' WHERE id=?",id);if(!build)access.db.update("UPDATE document_parse_runs SET status='FAILED',error_code='DOCUMENT_FROZEN',error_message='资料已提交或解析批次无效，旧解析已取消' WHERE id=? AND status<>'SUCCEEDED'",run);return null;}
        String token=UUID.randomUUID().toString();
        access.db.update("UPDATE async_jobs SET status='RUNNING',attempt_count=?,run_token=?,lease_until=UTC_TIMESTAMP(3)+INTERVAL 90 SECOND,last_error=NULL WHERE id=?",attempt,token,id);
        access.db.update("INSERT INTO async_job_attempts(job_id,attempt_no,run_token,started_at,status) VALUES(?,?,?,UTC_TIMESTAMP(3),'RUNNING')",id,attempt,token);
        if(!build)access.db.update("UPDATE document_parse_runs SET status='RUNNING',error_code=NULL,error_message=NULL,overall_outcome=NULL,stage_results=NULL,warnings=NULL WHERE id=?",run);
        return new Lease(id,document,run,token,(String)job.get("job_type"));
    });}
    public boolean complete(Lease lease,List<ParsedSegment> segments,String error){return finish(lease,segments,null,error);}
    public boolean completeDocument(Lease lease,SourceParser.DocumentResult result,String error){return finish(lease,result==null?null:result.segments(),result,error);}
    private boolean finish(Lease lease,List<ParsedSegment> segments,SourceParser.DocumentResult result,String error){return Boolean.TRUE.equals(tx.execute(status->{
        var jobs=access.db.queryForList("SELECT * FROM async_jobs WHERE id=? AND status='RUNNING' AND run_token=? AND lease_until>UTC_TIMESTAMP(3) FOR UPDATE",lease.id(),lease.runToken());if(jobs.isEmpty())return false;
        var job=jobs.get(0);if(!same(lease,job))return false;boolean build="BUILD_READER".equals(job.get("job_type"));
        var docs=access.db.queryForList("SELECT review_state,availability FROM source_documents WHERE id=? FOR UPDATE",lease.documentId());if(docs.isEmpty()||!(build?"ACTIVE".equals(docs.get(0).get("availability")):"DRAFT".equals(docs.get(0).get("review_state"))))return false;
        var runs=access.db.queryForList("SELECT status FROM document_parse_runs WHERE id=? AND document_id=? FOR UPDATE",String.class,lease.parseRunId(),lease.documentId());if(runs.isEmpty()||!build&&"SUCCEEDED".equals(runs.get(0))||build&&!"SUCCEEDED".equals(runs.get(0)))return false;
        if(!build&&!latestRun(lease.documentId(),lease.parseRunId()))return false;
        String failure=error;
        if(failure==null){
            if(build&&result==null)failure="阅读构建结果缺失";
            else if(segments==null||segments.size()>10000||(segments.isEmpty()&&(result==null||result.reading().path("capabilities").path("textSelectable").asBoolean())))failure="解析结果为空或数量超限";
            else{long total=0;int expected=1;for(var segment:segments){if(segment.segmentNo()!=expected++||segment.body()==null||segment.body().isBlank()||segment.pageLabel()!=null&&segment.pageLabel().length()>80||segment.headingPath()!=null&&segment.headingPath().length()>500){failure="解析结果格式无效";break;}total+=segment.body().length();if(total>2_000_000){failure="解析文本超限";break;}}}
        }
        if(failure==null){
            if(result!=null){SourceReadingData.validate(result.reading());if("FAILED".equals(result.reading().path("overallOutcome").asText()))throw ApiException.invalid("未生成可用阅读结果");readingData.publish(lease,result.reading(),result.assets());}
            if(!build){
                for(var segment:segments)access.db.update("INSERT INTO document_segments(document_id,parse_run_id,segment_no,page_label,heading_path,body,content_hash) VALUES(?,?,?,?,?,?,?)",lease.documentId(),lease.parseRunId(),segment.segmentNo(),segment.pageLabel(),segment.headingPath(),segment.body(),SourceFiles.hash(segment.body().getBytes(StandardCharsets.UTF_8)));
                if(result==null)access.db.update("UPDATE document_parse_runs SET status='SUCCEEDED',error_code=NULL,error_message=NULL,overall_outcome='SUCCEEDED' WHERE id=?",lease.parseRunId());
                else access.db.update("UPDATE document_parse_runs SET status='SUCCEEDED',parser_name='deterministic-v2',error_code=NULL,error_message=NULL,overall_outcome=?,stage_results=?,warnings=? WHERE id=?",result.reading().path("overallOutcome").asText(),readingData.encoded(result.reading().path("stages")),readingData.encoded(result.reading().path("warnings")),lease.parseRunId());
            }
            if(access.db.update("UPDATE async_jobs SET status='SUCCEEDED',lease_until=NULL,run_token=NULL,last_error=NULL WHERE id=? AND run_token=? AND status='RUNNING' AND lease_until>UTC_TIMESTAMP(3)",lease.id(),lease.runToken())!=1){status.setRollbackOnly();return false;}
            access.db.update("UPDATE async_job_attempts SET status='SUCCEEDED',finished_at=UTC_TIMESTAMP(3) WHERE job_id=? AND run_token=?",lease.id(),lease.runToken());
        }else{
            failure=failure.length()>1000?failure.substring(0,1000):failure;boolean exhausted=((Number)job.get("attempt_count")).intValue()>=((Number)job.get("max_attempts")).intValue();
            if(!build)access.db.update("UPDATE document_parse_runs SET status='FAILED',overall_outcome='FAILED',error_code='PARSE_FAILED',error_message=? WHERE id=?",failure,lease.parseRunId());
            if(access.db.update("UPDATE async_jobs SET status=?,lease_until=NULL,run_token=NULL,last_error=?,available_at=UTC_TIMESTAMP(3)+INTERVAL 30 SECOND WHERE id=? AND run_token=? AND status='RUNNING' AND lease_until>UTC_TIMESTAMP(3)",exhausted?"DEAD":"RETRY_WAIT",failure,lease.id(),lease.runToken())!=1){status.setRollbackOnly();return false;}
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
        return map("id",id(j.get("id")),"jobType",j.get("job_type"),"documentId",id(j.get("document_id")),"revisionId",id(j.get("revision_id")),"status",j.get("status"),"attemptCount",j.get("attempt_count"),"maxAttempts",j.get("max_attempts"),"lastError",j.get("last_error"),"availableAt",utc(j.get("available_at")),"leaseUntil",utc(j.get("lease_until")),"createdAt",utc(j.get("created_at")),"serviceMessage",Set.of("PARSE","BUILD_READER").contains(j.get("job_type"))?null:"等待第四阶段索引服务","attempts",attempts);
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
