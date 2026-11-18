from pathlib import Path
import subprocess, socket, time, json, uuid, argparse, os
p=argparse.ArgumentParser();p.add_argument('--mysql-bin',required=True);p.add_argument('--work-dir',required=True);p.add_argument('--project',required=True);a=p.parse_args()
root=Path(a.project).resolve();work=Path(a.work_dir).resolve();work.mkdir(parents=True,exist_ok=True)
scratch=(work/('mysql-'+uuid.uuid4().hex[:10])).resolve()
assert work in scratch.parents
scratch.mkdir();data=scratch/'data';binary=Path(a.mysql_bin).resolve();mysql=binary/'mysql.exe';mysqld=binary/'mysqld.exe'
flags=subprocess.CREATE_NO_WINDOW if os.name=='nt' else 0
memory_name='TCM_'+uuid.uuid4().hex
pidfile=scratch/'server.pid'
report={'status':'RUNNING','tests':[],'scratch':str(scratch),'server_stopped':False}
proc=None;log=None
with socket.socket() as s:s.bind(('127.0.0.1',0));port=s.getsockname()[1]
def run(sql,db='tcm_schema_validation',ok=True):
    cmd=[str(mysql),'--no-defaults','--protocol=MEMORY','--host=localhost',f'--shared-memory-base-name={memory_name}','--user=root','--password=','--connect-timeout=2','--default-character-set=utf8mb4','--batch','--skip-column-names']
    if db:cmd.append(db)
    env=os.environ.copy();env.pop('MYSQL_PWD',None)
    r=subprocess.run(cmd,input=sql.encode('utf-8'),env=env,stdout=subprocess.PIPE,stderr=subprocess.PIPE,creationflags=flags,timeout=240)
    if ok and r.returncode:raise RuntimeError(r.stderr.decode('utf-8',errors='replace'))
    return r
def expect(name,sql,code):
    r=run(sql,ok=False);err=r.stderr.decode('utf-8',errors='replace')
    if r.returncode==0 or f'ERROR {code} ' not in err:raise AssertionError(name+': '+err)
    report['tests'].append({'name':name,'passed':True,'expected_mysql_error':code})
def positive(name,sql,expected):
    actual=run(sql).stdout.decode('utf-8').strip()
    if actual!=str(expected):raise AssertionError(name+': '+actual)
    report['tests'].append({'name':name,'passed':True})
try:
    r=subprocess.run([str(mysqld),'--no-defaults','--initialize-insecure',f'--basedir={binary.parent}',f'--datadir={data}','--console'],stdout=subprocess.PIPE,stderr=subprocess.PIPE,creationflags=flags,timeout=120)
    if r.returncode:raise RuntimeError(r.stderr.decode('utf-8',errors='replace'))
    log=(scratch/'server.log').open('wb')
    cmd=[str(mysqld),'--no-defaults',f'--basedir={binary.parent}',f'--datadir={data}','--skip-networking','--shared-memory',f'--shared-memory-base-name={memory_name}',f'--pid-file={pidfile}','--mysqlx=OFF','--max-connections=10','--innodb-buffer-pool-size=128M','--innodb-flush-log-at-trx-commit=2','--skip-log-bin','--console']
    proc=subprocess.Popen(cmd,stdout=log,stderr=log,creationflags=flags)
    deadline=time.time()+60
    while time.time()<deadline:
        if proc.poll() is not None:raise RuntimeError('Temporary MySQL exited: '+(scratch/'server.log').read_text(encoding='utf-8',errors='replace')[-3000:])
        if run('SELECT 1',db=None,ok=False).returncode==0:break
        time.sleep(.4)
    else:raise TimeoutError('Temporary MySQL startup timeout')
    report['mysql_version']=run('SELECT VERSION()',db=None).stdout.decode().strip()
    run('CREATE DATABASE tcm_schema_validation CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;',db=None)
    for filename in ['V1__initial_schema.sql','V2__seed_roles_and_categories.sql']:
        run((root/'backend/src/main/resources/db/migration'/filename).read_text(encoding='utf-8'))
        report['tests'].append({'name':'execute '+filename,'passed':True})
        print('migration passed: '+filename,flush=True)
    model=json.loads((root/'database/schema_model.json').read_text(encoding='utf-8'))
    positive('all designed tables created',"SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE()",len(model['tables']))
    positive('all foreign keys created',"SELECT COUNT(*) FROM information_schema.referential_constraints WHERE constraint_schema=DATABASE()",len(model['foreign_keys']))
    positive('seed creates no users','SELECT COUNT(*) FROM sys_users',0)
    positive('admin has no professional review permission',"SELECT COUNT(*) FROM sys_role_permissions rp JOIN sys_roles r ON r.id=rp.role_id JOIN sys_permissions p ON p.id=rp.permission_id WHERE r.code='ADMIN' AND p.code IN ('knowledge:review','learning:review','community:moderate')",0)
    run("INSERT INTO sys_users(id,username,password_hash,nickname) VALUES(1,'fixture_author','fixture-not-login-hash','author'),(2,'fixture_reviewer','fixture-not-login-hash','reviewer');")
    for i,kind in [(1,'KNOWLEDGE'),(2,'KNOWLEDGE'),(3,'QUESTION'),(4,'CARD'),(5,'TOPIC'),(6,'POST'),(7,'POST')]:
        run(f"INSERT INTO content_resources(id,resource_type,owner_id) VALUES({i},'{kind}',1); INSERT INTO content_revisions(id,resource_id,version_no,title,body,revision_state,created_by) VALUES({i*10+1},{i},1,'fixture','fixture','APPROVED',1); UPDATE content_resources SET published_revision_id={i*10+1},visibility_status='PUBLISHED' WHERE id={i};")
    run("INSERT INTO resource_bookmarks(user_id,resource_id) VALUES(1,1); INSERT INTO source_documents(id,title,source_type,source_url,submitted_by) VALUES(1,'fixture1','WEB','https://example.invalid/1',1),(2,'fixture2','WEB','https://example.invalid/2',1); INSERT INTO document_parse_runs(id,document_id,parse_version,parser_name) VALUES(1,1,1,'fixture'),(2,2,1,'fixture'); INSERT INTO document_segments(id,document_id,parse_run_id,segment_no,body,content_hash) VALUES(1,1,1,1,'fixture',REPEAT('a',64));")
    run("INSERT INTO question_profiles(revision_id,question_type,explanation) VALUES(31,'SINGLE','original explanation'); INSERT INTO learning_assessments(id,anchor_resource_id,assessment_type,version_no,title,status,created_by) VALUES(1,4,'CARD',1,'fixture','ACTIVE',1); INSERT INTO quiz_attempts(id,user_id,assessment_id,request_key,total_points) VALUES(1,1,1,'00000000-0000-0000-0000-000000000001',10); INSERT INTO quiz_attempt_items(id,attempt_id,question_resource_id,question_revision_id,position,question_snapshot,answer_key_snapshot,points) VALUES(1,1,3,31,1,JSON_OBJECT('title','original question'),JSON_OBJECT('keys',JSON_ARRAY('A'),'explanation','original explanation'),10);")
    run("INSERT INTO qa_sessions(id,user_id,title) VALUES(1,1,'fixture1'),(2,2,'fixture2'); INSERT INTO qa_messages(id,session_id,role,body,client_request_key) VALUES(1,1,'USER','fixture','00000000-0000-0000-0000-000000000002'); INSERT INTO community_post_profiles(resource_id,section_id) VALUES(6,1),(7,1); INSERT INTO community_comments(id,resource_id,user_id,body) VALUES(1,6,1,'fixture'); INSERT INTO recommendation_strategies(id,scene,version_no,name,config,status) VALUES(1,'DISCOVER',1,'fixture',JSON_OBJECT(),'ACTIVE'); INSERT INTO async_jobs(job_type,idempotency_key) VALUES('VECTOR_INDEX','fixture-unique'); INSERT INTO behavior_events(event_key,user_id,resource_id,behavior_type,occurred_at) VALUES('00000000-0000-0000-0000-000000000003',1,1,'VIEW',UTC_TIMESTAMP(3));")
    cases=[
    ('duplicate username',"INSERT INTO sys_users(username,password_hash,nickname) VALUES('fixture_author','x','x')",1062),
    ('duplicate bookmark',"INSERT INTO resource_bookmarks(user_id,resource_id) VALUES(1,1)",1062),
    ('orphan bookmark',"INSERT INTO resource_bookmarks(user_id,resource_id) VALUES(1,999999)",1452),
    ('published revision must belong to resource',"UPDATE content_resources SET published_revision_id=21 WHERE id=1",1452),
    ('invalid visibility state',"UPDATE content_resources SET visibility_status='INVALID' WHERE id=1",3819),
    ('duplicate revision number',"INSERT INTO content_revisions(resource_id,version_no,title,body,created_by) VALUES(1,1,'x','x',1)",1062),
    ('feedback revision must belong to resource',"INSERT INTO resource_feedback(user_id,resource_id,revision_id,kind,content) VALUES(1,1,21,'CORRECTION','x')",1452),
    ('cannot approve own submission',"INSERT INTO review_tasks(revision_id,submitted_by,reviewer_id) VALUES(41,1,1)",3819),
    ('review target exclusive',"INSERT INTO review_tasks(revision_id,document_id,submitted_by) VALUES(11,1,1)",3819),
    ('segment parse run must belong to document',"INSERT INTO document_segments(document_id,parse_run_id,segment_no,body,content_hash) VALUES(2,1,2,'x',REPEAT('b',64))",1452),
    ('same-session message parent',"INSERT INTO qa_messages(session_id,parent_message_id,role,body,client_request_key) VALUES(2,1,'ASSISTANT','x','00000000-0000-0000-0000-000000000004')",1452),
    ('same-post comment parent',"INSERT INTO community_comments(resource_id,user_id,parent_comment_id,body) VALUES(7,1,1,'x')",1452),
    ('report target exclusive',"INSERT INTO community_reports(reporter_id,resource_id,comment_id,reason_code) VALUES(1,6,1,'fixture')",3819),
    ('one active strategy per scene',"INSERT INTO recommendation_strategies(scene,version_no,name,config,status) VALUES('DISCOVER',2,'x',JSON_OBJECT(),'ACTIVE')",1062),
    ('event deduplication',"INSERT INTO behavior_events(event_key,user_id,resource_id,behavior_type,occurred_at) VALUES('00000000-0000-0000-0000-000000000003',1,1,'VIEW',UTC_TIMESTAMP(3))",1062),
    ('job idempotency',"INSERT INTO async_jobs(job_type,idempotency_key) VALUES('VECTOR_INDEX','fixture-unique')",1062),
    ('score cannot exceed total',"UPDATE quiz_attempts SET earned_points=11 WHERE id=1",3819),
    ('submission requires score and time',"UPDATE quiz_attempts SET status='SUBMITTED' WHERE id=1",3819),
    ('negative item points',"UPDATE quiz_attempt_items SET points=-1 WHERE id=1",3819),
    ('referenced revision cannot be deleted',"DELETE FROM content_revisions WHERE id=11",1451)]
    for name,sql,code in cases:expect(name,sql,code)
    run("UPDATE content_revisions SET body='changed fixture' WHERE id=31; UPDATE question_profiles SET explanation='changed fixture' WHERE revision_id=31;")
    positive('question snapshot independent from current content',"SELECT JSON_UNQUOTE(JSON_EXTRACT(question_snapshot,'$.title')) FROM quiz_attempt_items WHERE id=1",'original question')
    positive('answer snapshot independent from current explanation',"SELECT JSON_UNQUOTE(JSON_EXTRACT(answer_key_snapshot,'$.explanation')) FROM quiz_attempt_items WHERE id=1",'original explanation')
    report['status']='PASSED'
except Exception as e:
    report['status']='FAILED';report['error']=str(e)
finally:
    if proc is not None:
        try:
            if proc.poll() is None:run('SHUTDOWN',db=None,ok=False)
            proc.wait(timeout=15)
        except Exception:
            if proc.poll() is None:proc.terminate();proc.wait(timeout=10)
        report['server_stopped']=proc.poll() is not None and not pidfile.exists()
    if log:log.close()
    out=root/'docs/validation-report.json';out.write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8')
    print(json.dumps(report,ensure_ascii=True))
if report['status']!='PASSED':raise SystemExit(1)
