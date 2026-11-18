from pathlib import Path
import json, hashlib
ROOT=Path(__file__).resolve().parents[1]
ROOT.mkdir(parents=True,exist_ok=True)
TABLES=[];FKS=[]
def T(name,domain,desc,spec,u=(),ix=(),ck=()):
    cols=[dict(name='id',definition='BIGINT NOT NULL AUTO_INCREMENT',comment='主键；API按字符串返回')]
    for line in spec.strip().splitlines():
        n,d,c=line.strip().split('|',2)
        if '@' in d:
            d,target=d.split('@');rt,rc=(target.split('.')+['id'])[:2];FKS.append(dict(table=name,columns=[n],reference=rt,reference_columns=[rc],on_delete='RESTRICT'))
        cols.append(dict(name=n,definition=d.strip(),comment=c))
    cols.extend([dict(name='created_at',definition='DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)',comment='创建时间UTC'),dict(name='updated_at',definition='DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3)',comment='更新时间UTC')])
    TABLES.append(dict(name=name,domain=domain,description=desc,columns=cols,unique=[list(k) for k in u],indexes=[list(k) for k in ix],checks=list(ck)))
def F(t,c,r,rc='id'):FKS.append(dict(table=t,columns=c.split(','),reference=r,reference_columns=rc.split(','),on_delete='RESTRICT'))
def W(p,s):
    p=ROOT/p;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(s.rstrip()+'\n',encoding='utf-8')
T('sys_users','基础权限','用户账户；禁用保留历史','''
username|VARCHAR(64) NOT NULL|登录名
password_hash|VARCHAR(100) NOT NULL|BCrypt散列
nickname|VARCHAR(80) NOT NULL|昵称
avatar_path|VARCHAR(512) NULL|头像路径
bio|VARCHAR(500) NULL|简介
status|VARCHAR(20) NOT NULL DEFAULT 'ACTIVE'|状态
credential_version|INT NOT NULL DEFAULT 0|密码重置或禁用时递增
last_login_at|DATETIME(3) NULL|最近登录
''',u=[('username',)],ck=["status IN ('ACTIVE','DISABLED')",'credential_version >= 0'])
T('sys_roles','基础权限','角色，不含游客','''
code|VARCHAR(32) NOT NULL|LEARNER/CONTRIBUTOR/REVIEWER/ADMIN
name|VARCHAR(64) NOT NULL|名称
description|VARCHAR(255) NULL|职责
''',u=[('code',)])
T('sys_permissions','基础权限','接口权限','''
code|VARCHAR(80) NOT NULL|权限编码
name|VARCHAR(100) NOT NULL|名称
''',u=[('code',)])
T('sys_user_roles','基础权限','用户角色','''
user_id|BIGINT NOT NULL@sys_users|用户
role_id|BIGINT NOT NULL@sys_roles|角色
granted_by|BIGINT NULL@sys_users|授权人
''',u=[('user_id','role_id')])
T('sys_role_permissions','基础权限','角色权限','''
role_id|BIGINT NOT NULL@sys_roles|角色
permission_id|BIGINT NOT NULL@sys_permissions|权限
''',u=[('role_id','permission_id')])
T('sys_role_applications','基础权限','贡献资格申请','''
user_id|BIGINT NOT NULL@sys_users|申请人
role_id|BIGINT NOT NULL@sys_roles|接口仅允许CONTRIBUTOR
statement|TEXT NOT NULL|说明
status|VARCHAR(20) NOT NULL DEFAULT 'PENDING'|状态
reviewer_id|BIGINT NULL@sys_users|处理人
review_note|TEXT NULL|意见
reviewed_at|DATETIME(3) NULL|处理时间
''',ix=[('status','created_at'),('user_id','created_at')],ck=["status IN ('PENDING','APPROVED','REJECTED','WITHDRAWN')",'reviewer_id IS NULL OR reviewer_id <> user_id'])
T('auth_refresh_tokens','基础权限','刷新令牌轮换撤销','''
user_id|BIGINT NOT NULL@sys_users|用户
token_hash|CHAR(64) NOT NULL|令牌SHA256，不存原文
family_key|CHAR(36) NOT NULL|登录轮换链
expires_at|DATETIME(3) NOT NULL|到期
revoked_at|DATETIME(3) NULL|撤销
replaced_by_id|BIGINT NULL@auth_refresh_tokens|后继令牌
''',u=[('token_hash',)],ix=[('user_id','expires_at'),('family_key',)])
T('file_objects','公共资源','文件与完全去重','''
sha256|CHAR(64) NOT NULL|内容哈希
storage_key|VARCHAR(512) NOT NULL|根目录内相对键
original_name|VARCHAR(255) NOT NULL|原名
mime_type|VARCHAR(100) NOT NULL|类型
byte_size|BIGINT NOT NULL|字节数
uploaded_by|BIGINT NOT NULL@sys_users|上传人
''',u=[('sha256',)],ck=['byte_size > 0'])
T('content_categories','公共资源','分类','''
parent_id|BIGINT NULL@content_categories|父分类
code|VARCHAR(64) NOT NULL|编码
name|VARCHAR(80) NOT NULL|名称
scope|VARCHAR(24) NOT NULL|KNOWLEDGE/LEARNING/COMMUNITY
sort_order|INT NOT NULL DEFAULT 0|排序
active|TINYINT NOT NULL DEFAULT 1|启用
''',u=[('code',)],ix=[('scope','parent_id','sort_order')],ck=['active IN (0,1)',"scope IN ('KNOWLEDGE','LEARNING','COMMUNITY')"])
T('content_tags','公共资源','标签','''
name|VARCHAR(64) NOT NULL|名称
active|TINYINT NOT NULL DEFAULT 1|启用
''',u=[('name',)],ck=['active IN (0,1)'])
T('content_resources','公共资源','内容身份与可见性','''
resource_type|VARCHAR(24) NOT NULL|资源类型
owner_id|BIGINT NOT NULL@sys_users|负责用户
visibility_status|VARCHAR(20) NOT NULL DEFAULT 'DRAFT'|可见状态
published_revision_id|BIGINT NULL|当前正式版本
lock_version|INT NOT NULL DEFAULT 0|乐观锁
published_at|DATETIME(3) NULL|首次公开时间
suspended_reason|VARCHAR(1000) NULL|停用说明
''',ix=[('resource_type','visibility_status','published_at'),('owner_id','created_at')],ck=["resource_type IN ('KNOWLEDGE','TOPIC','CARD','QUESTION','POST','ARTICLE')","visibility_status IN ('DRAFT','PUBLISHED','SUSPENDED','ARCHIVED')","visibility_status <> 'PUBLISHED' OR published_revision_id IS NOT NULL",'lock_version >= 0'])
T('content_revisions','公共资源','提交后冻结的内容版本','''
resource_id|BIGINT NOT NULL@content_resources|内容身份
version_no|INT NOT NULL|版本号
base_revision_id|BIGINT NULL|修订依据版本
category_id|BIGINT NULL@content_categories|分类
title|VARCHAR(255) NOT NULL|标题
body|LONGTEXT NOT NULL|受控Markdown正文
revision_state|VARCHAR(20) NOT NULL DEFAULT 'DRAFT'|审核状态
change_note|VARCHAR(1000) NULL|修改说明
created_by|BIGINT NOT NULL@sys_users|编辑人
submitted_at|DATETIME(3) NULL|提交时间
approved_at|DATETIME(3) NULL|通过时间
''',u=[('resource_id','version_no'),('resource_id','id')],ix=[('revision_state','created_at')],ck=['version_no > 0',"revision_state IN ('DRAFT','IN_REVIEW','APPROVED','REJECTED','WITHDRAWN')"])
T('revision_tags','公共资源','版本标签','''
revision_id|BIGINT NOT NULL@content_revisions|版本
tag_id|BIGINT NOT NULL@content_tags|标签
''',u=[('revision_id','tag_id')])
for name,desc in [('resource_bookmarks','统一收藏'),('resource_likes','点赞关联')]:
    T(name,'公共资源',desc,'''user_id|BIGINT NOT NULL@sys_users|用户
resource_id|BIGINT NOT NULL@content_resources|内容
''',u=[('user_id','resource_id')],ix=[('user_id','created_at')])
T('user_resource_history','公共资源','最近浏览聚合，可重建','''
user_id|BIGINT NOT NULL@sys_users|用户
resource_id|BIGINT NOT NULL@content_resources|资源
last_viewed_at|DATETIME(3) NOT NULL|最近访问
view_count|INT NOT NULL DEFAULT 1|有效次数
''',u=[('user_id','resource_id')],ix=[('user_id','last_viewed_at')],ck=['view_count > 0'])
T('resource_feedback','公共资源','纠错与知识需求','''
user_id|BIGINT NOT NULL@sys_users|反馈人
resource_id|BIGINT NULL@content_resources|资源
revision_id|BIGINT NULL|当时版本
kind|VARCHAR(24) NOT NULL|反馈类型
content|TEXT NOT NULL|内容
status|VARCHAR(20) NOT NULL DEFAULT 'OPEN'|处理状态
handler_id|BIGINT NULL@sys_users|处理人
resolution|TEXT NULL|结果
resolved_at|DATETIME(3) NULL|处理时间
''',ix=[('status','created_at'),('user_id','created_at')],ck=["kind IN ('CORRECTION','KNOWLEDGE_REQUEST')","status IN ('OPEN','PROCESSING','RESOLVED','REJECTED')",'revision_id IS NULL OR resource_id IS NOT NULL'])
T('user_notifications','公共资源','站内通知','''
user_id|BIGINT NOT NULL@sys_users|接收者
event_key|VARCHAR(120) NOT NULL|幂等事件键
kind|VARCHAR(40) NOT NULL|通知类型
title|VARCHAR(160) NOT NULL|标题
body|TEXT NOT NULL|消息
resource_id|BIGINT NULL@content_resources|资源
relative_path|VARCHAR(512) NULL|站内路径
read_at|DATETIME(3) NULL|已读时间
''',u=[('user_id','event_key')],ix=[('user_id','read_at','created_at')])
T('sys_audit_logs','公共资源','追加审计，不存密码密钥','''
actor_id|BIGINT NULL@sys_users|操作者
trace_id|VARCHAR(64) NOT NULL|追踪号
action|VARCHAR(80) NOT NULL|动作
object_type|VARCHAR(64) NOT NULL|对象类型
object_key|VARCHAR(100) NOT NULL|历史对象键
summary|JSON NULL|脱敏摘要
''',ix=[('object_type','object_key','created_at'),('actor_id','created_at'),('trace_id',)])
T('source_documents','知识加工','来源资料，提交后冻结元信息，新修订另建记录','''
file_id|BIGINT NULL@file_objects|文件
origin_revision_id|BIGINT NULL@content_revisions|社区文章版本
parent_document_id|BIGINT NULL@source_documents|上一资料版本
title|VARCHAR(255) NOT NULL|标题
source_type|VARCHAR(24) NOT NULL|来源类型
author|VARCHAR(255) NULL|作者
publisher|VARCHAR(255) NULL|出版机构
publication_year|SMALLINT NULL|出版年
source_url|VARCHAR(1500) NULL|来源链接
bibliographic_note|TEXT NULL|书目信息
submitted_by|BIGINT NOT NULL@sys_users|提交人
review_state|VARCHAR(20) NOT NULL DEFAULT 'DRAFT'|审核状态
availability|VARCHAR(20) NOT NULL DEFAULT 'ACTIVE'|可用状态
''',ix=[('review_state','created_at'),('submitted_by','created_at')],ck=["source_type IN ('BOOK','PAPER','WEB','COMMUNITY','OTHER')","review_state IN ('DRAFT','IN_REVIEW','APPROVED','REJECTED','WITHDRAWN')","availability IN ('ACTIVE','SUSPENDED')",'file_id IS NOT NULL OR origin_revision_id IS NOT NULL OR source_url IS NOT NULL'])
T('document_parse_runs','知识加工','版本化解析批次','''
document_id|BIGINT NOT NULL@source_documents|资料
parse_version|INT NOT NULL|解析序号
parser_name|VARCHAR(80) NOT NULL|解析器版本
status|VARCHAR(20) NOT NULL DEFAULT 'QUEUED'|解析状态
error_code|VARCHAR(80) NULL|失败类型
error_message|VARCHAR(1000) NULL|脱敏摘要
''',u=[('document_id','parse_version'),('document_id','id')],ck=['parse_version > 0',"status IN ('QUEUED','RUNNING','SUCCEEDED','FAILED')"])
T('document_segments','知识加工','不可变原文段落','''
document_id|BIGINT NOT NULL|资料
parse_run_id|BIGINT NOT NULL|解析批次
segment_no|INT NOT NULL|段落号
heading_path|VARCHAR(500) NULL|章节
page_label|VARCHAR(80) NULL|页码位置
body|LONGTEXT NOT NULL|原文
content_hash|CHAR(64) NOT NULL|文本哈希
''',u=[('parse_run_id','segment_no'),('document_id','id')],ck=['segment_no > 0'])
T('revision_evidence','知识加工','内容版本来源依据','''
revision_id|BIGINT NOT NULL@content_revisions|被支撑版本
document_id|BIGINT NOT NULL@source_documents|资料
segment_id|BIGINT NULL|原文段落
location_label|VARCHAR(255) NULL|人工定位
quote_text|TEXT NOT NULL|支持片段
''',u=[('revision_id','id')],ck=['segment_id IS NOT NULL OR location_label IS NOT NULL'])
T('review_tasks','知识加工','统一审核，一份冻结提交一个任务','''
revision_id|BIGINT NULL@content_revisions|版本
document_id|BIGINT NULL@source_documents|资料
submitted_by|BIGINT NOT NULL@sys_users|提交人
reviewer_id|BIGINT NULL@sys_users|审核人
status|VARCHAR(20) NOT NULL DEFAULT 'PENDING'|任务状态
review_note|TEXT NULL|意见
reviewed_at|DATETIME(3) NULL|处理时间
lock_version|INT NOT NULL DEFAULT 0|乐观锁
''',u=[('revision_id',),('document_id',)],ix=[('status','created_at'),('reviewer_id','status')],ck=['(revision_id IS NOT NULL) + (document_id IS NOT NULL) = 1','reviewer_id IS NULL OR reviewer_id <> submitted_by',"status IN ('PENDING','APPROVED','REJECTED','WITHDRAWN')","status NOT IN ('APPROVED','REJECTED') OR (reviewer_id IS NOT NULL AND reviewed_at IS NOT NULL AND review_note IS NOT NULL)"])
T('review_events','知识加工','审核操作历史','''
review_task_id|BIGINT NOT NULL@review_tasks|任务
actor_id|BIGINT NOT NULL@sys_users|操作者
action|VARCHAR(24) NOT NULL|审核动作
note|TEXT NULL|意见
''',ix=[('review_task_id','created_at')],ck=["action IN ('CLAIM','APPROVE','REJECT','WITHDRAW')"])
T('async_jobs','公共资源','事务内outbox与异步任务','''
job_type|VARCHAR(40) NOT NULL|PARSE/EXTRACT/VECTOR_INDEX/GRAPH_SYNC/REMOVE_INDEX/PROFILE_REFRESH/BACKUP
idempotency_key|VARCHAR(191) NOT NULL|动作对象版本配置的幂等键
revision_id|BIGINT NULL@content_revisions|版本目标
document_id|BIGINT NULL@source_documents|资料目标
payload|JSON NULL|索引代次等执行参数
status|VARCHAR(20) NOT NULL DEFAULT 'QUEUED'|任务状态
attempt_count|INT NOT NULL DEFAULT 0|尝试次数
max_attempts|INT NOT NULL DEFAULT 3|最大次数
available_at|DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)|最早执行
lease_until|DATETIME(3) NULL|租约截止
run_token|CHAR(36) NULL|本次领取令牌
last_error|VARCHAR(1000) NULL|脱敏错误
''',u=[('idempotency_key',)],ix=[('status','available_at','id'),('lease_until',)],ck=["status IN ('QUEUED','RUNNING','RETRY_WAIT','SUCCEEDED','DEAD','CANCELLED')",'attempt_count >= 0 AND max_attempts > 0'])
T('async_job_attempts','公共资源','任务执行历史','''
job_id|BIGINT NOT NULL@async_jobs|任务
attempt_no|INT NOT NULL|尝试序号
run_token|CHAR(36) NOT NULL|领取令牌
started_at|DATETIME(3) NOT NULL|开始
finished_at|DATETIME(3) NULL|结束
status|VARCHAR(20) NOT NULL|执行状态
error_message|VARCHAR(1000) NULL|脱敏错误
''',u=[('job_id','attempt_no')],ck=['attempt_no > 0',"status IN ('RUNNING','SUCCEEDED','FAILED','EXPIRED')"])
T('knowledge_entries','知识中心','知识身份与规范名称','''
resource_id|BIGINT NOT NULL@content_resources|知识资源
knowledge_type|VARCHAR(24) NOT NULL|知识类别
canonical_name|VARCHAR(160) NOT NULL|规范名称
''',u=[('resource_id',),('knowledge_type','canonical_name')],ck=["knowledge_type IN ('CONCEPT','HERB','FORMULA','EFFECT','SYMPTOM','MERIDIAN')"])
T('knowledge_aliases','知识中心','名称别名索引','''
knowledge_resource_id|BIGINT NOT NULL@knowledge_entries.resource_id|知识
alias|VARCHAR(160) NOT NULL|别名
''',u=[('knowledge_resource_id','alias')],ix=[('alias',)])
T('herb_profiles','知识中心','药材版本属性','''
revision_id|BIGINT NOT NULL@content_revisions|版本
nature|VARCHAR(160) NULL|性
flavor|VARCHAR(160) NULL|味
origin_note|TEXT NULL|来源说明
precautions|TEXT NULL|注意事项
''',u=[('revision_id',)])
T('formula_profiles','知识中心','方剂版本属性','''
revision_id|BIGINT NOT NULL@content_revisions|版本
origin_text|VARCHAR(500) NOT NULL|出处
function_summary|TEXT NULL|功效说明
indication_summary|TEXT NULL|主治知识说明
precautions|TEXT NULL|注意事项
''',u=[('revision_id',)])
T('formula_ingredients','知识中心','有依据的方剂版本组成','''
formula_resource_id|BIGINT NOT NULL@knowledge_entries.resource_id|方剂
formula_revision_id|BIGINT NOT NULL|方剂版本
herb_resource_id|BIGINT NOT NULL@knowledge_entries.resource_id|药材
herb_revision_id|BIGINT NOT NULL|药材依据版本
role|VARCHAR(16) NOT NULL DEFAULT 'UNSPECIFIED'|组成角色
dose_value|DECIMAL(12,4) NULL|资料数值，仅学习辅助
dose_unit|VARCHAR(32) NULL|保留原单位
dose_text|VARCHAR(160) NULL|原文剂量
sort_order|INT NOT NULL DEFAULT 0|顺序
evidence_id|BIGINT NOT NULL|方剂版本依据
''',u=[('formula_revision_id','herb_resource_id')],ck=["role IN ('JUN','CHEN','ZUO','SHI','UNSPECIFIED')",'dose_value IS NULL OR dose_value > 0'])
T('knowledge_relations','知识中心','有依据的知识关系事实','''
source_resource_id|BIGINT NOT NULL@knowledge_entries.resource_id|起点
source_revision_id|BIGINT NOT NULL|起点版本
target_resource_id|BIGINT NOT NULL@knowledge_entries.resource_id|终点
target_revision_id|BIGINT NOT NULL|终点版本
relation_type|VARCHAR(32) NOT NULL|关系类型
explanation|TEXT NULL|说明
evidence_id|BIGINT NOT NULL|起点版本依据
''',u=[('source_revision_id','target_resource_id','relation_type')],ck=["relation_type IN ('HAS_EFFECT','INDICATED_FOR','ENTERS_MERIDIAN','COMPATIBLE_WITH','RELATED_TO')",'source_resource_id <> target_resource_id'])
T('learning_topics','学习测验','专题身份','''resource_id|BIGINT NOT NULL@content_resources|TOPIC资源
''',u=[('resource_id',)])
T('topic_profiles','学习测验','专题版本属性','''
revision_id|BIGINT NOT NULL@content_revisions|版本
objectives|TEXT NOT NULL|学习目标
prerequisites|TEXT NULL|所需基础
level|VARCHAR(20) NOT NULL DEFAULT 'BEGINNER'|基础级别
estimated_minutes|INT NULL|建议时长
''',u=[('revision_id',)],ck=["level IN ('BEGINNER','INTERMEDIATE')",'estimated_minutes IS NULL OR estimated_minutes > 0'])
T('learning_cards','学习测验','卡片身份','''resource_id|BIGINT NOT NULL@content_resources|CARD资源
''',u=[('resource_id',)])
T('topic_cards','学习测验','专题版本目录，固定卡片版本','''
topic_revision_id|BIGINT NOT NULL@content_revisions|专题版本
card_resource_id|BIGINT NOT NULL@learning_cards.resource_id|卡片
card_revision_id|BIGINT NOT NULL|卡片版本
position|INT NOT NULL|目录位置
''',u=[('topic_revision_id','position'),('topic_revision_id','card_resource_id')],ck=['position > 0'])
T('card_knowledge_links','学习测验','卡片版本关联知识','''
card_revision_id|BIGINT NOT NULL@content_revisions|卡片版本
knowledge_resource_id|BIGINT NOT NULL@knowledge_entries.resource_id|知识
knowledge_revision_id|BIGINT NOT NULL|依据版本
''',u=[('card_revision_id','knowledge_resource_id')])
T('question_profiles','学习测验','题目版本私有属性，禁止直接返回作答API','''
revision_id|BIGINT NOT NULL@content_revisions|题目版本，题干在正文
question_type|VARCHAR(20) NOT NULL|类型
difficulty|VARCHAR(16) NOT NULL DEFAULT 'EASY'|难度
explanation|TEXT NOT NULL|提交后展示的解析
''',u=[('revision_id',)],ck=["question_type IN ('SINGLE','MULTIPLE','TRUE_FALSE')","difficulty IN ('EASY','MEDIUM','HARD')"])
T('question_options','学习测验','题目版本选项与服务端答案','''
question_revision_id|BIGINT NOT NULL@question_profiles.revision_id|题目版本
option_key|VARCHAR(8) NOT NULL|稳定选项键
option_text|TEXT NOT NULL|内容
is_correct|TINYINT NOT NULL|标准答案
position|INT NOT NULL|顺序
''',u=[('question_revision_id','option_key'),('question_revision_id','position')],ck=['is_correct IN (0,1)','position > 0'])
T('question_knowledge_links','学习测验','题目考查知识','''
question_revision_id|BIGINT NOT NULL@question_profiles.revision_id|题目版本
knowledge_resource_id|BIGINT NOT NULL@knowledge_entries.resource_id|知识
knowledge_revision_id|BIGINT NOT NULL|知识版本
''',u=[('question_revision_id','knowledge_resource_id')])
T('learning_assessments','学习测验','测验模板版本，启用后固定题目','''
anchor_resource_id|BIGINT NOT NULL@content_resources|关联专题或卡片
assessment_type|VARCHAR(20) NOT NULL|测验类型
version_no|INT NOT NULL|版本号
title|VARCHAR(255) NOT NULL|名称
status|VARCHAR(20) NOT NULL DEFAULT 'DRAFT'|状态
pass_score|DECIMAL(8,2) NULL|达标分数
created_by|BIGINT NOT NULL@sys_users|创建人
''',u=[('anchor_resource_id','assessment_type','version_no')],ck=["assessment_type IN ('PLACEMENT','CARD','TOPIC')","status IN ('DRAFT','ACTIVE','RETIRED')",'version_no > 0','pass_score IS NULL OR pass_score >= 0'])
T('assessment_questions','学习测验','模板题目固定版本','''
assessment_id|BIGINT NOT NULL@learning_assessments|测验
question_resource_id|BIGINT NOT NULL|题目
question_revision_id|BIGINT NOT NULL@question_profiles.revision_id|题目版本
position|INT NOT NULL|顺序
points|DECIMAL(8,2) NOT NULL|分数
''',u=[('assessment_id','position'),('assessment_id','question_resource_id')],ck=['position > 0','points > 0'])
T('user_enrollments','学习测验','专题学习，固定目录版本','''
user_id|BIGINT NOT NULL@sys_users|用户
topic_resource_id|BIGINT NOT NULL@learning_topics.resource_id|专题
topic_revision_id|BIGINT NOT NULL|专题版本
last_card_resource_id|BIGINT NULL@learning_cards.resource_id|最近卡片
status|VARCHAR(20) NOT NULL DEFAULT 'LEARNING'|状态
completed_at|DATETIME(3) NULL|完成时间
''',u=[('user_id','topic_resource_id')],ix=[('user_id','updated_at')],ck=["status IN ('LEARNING','COMPLETED','ARCHIVED')"])
T('user_card_progress','学习测验','明确完成标记，与测验成绩独立','''
enrollment_id|BIGINT NOT NULL@user_enrollments|学习记录
card_resource_id|BIGINT NOT NULL@learning_cards.resource_id|卡片
card_revision_id|BIGINT NOT NULL|实际学习版本
status|VARCHAR(20) NOT NULL DEFAULT 'STARTED'|状态
completed_at|DATETIME(3) NULL|明确完成时间
''',u=[('enrollment_id','card_resource_id')],ck=["status IN ('STARTED','COMPLETED')","status <> 'COMPLETED' OR completed_at IS NOT NULL"])
T('quiz_attempts','学习测验','一次作答，交卷事务评分','''
user_id|BIGINT NOT NULL@sys_users|用户
assessment_id|BIGINT NOT NULL@learning_assessments|模板
enrollment_id|BIGINT NULL@user_enrollments|专题记录
request_key|CHAR(36) NOT NULL|开始作答幂等键
status|VARCHAR(20) NOT NULL DEFAULT 'IN_PROGRESS'|状态
total_points|DECIMAL(10,2) NOT NULL DEFAULT 0|总分
earned_points|DECIMAL(10,2) NULL|得分
submitted_at|DATETIME(3) NULL|交卷时间
lock_version|INT NOT NULL DEFAULT 0|并发控制
''',u=[('user_id','request_key')],ix=[('user_id','status','created_at'),('assessment_id','created_at')],ck=["status IN ('IN_PROGRESS','SUBMITTED','ABANDONED')",'total_points >= 0','earned_points IS NULL OR (earned_points >= 0 AND earned_points <= total_points)',"status <> 'SUBMITTED' OR (submitted_at IS NOT NULL AND earned_points IS NOT NULL)"])
T('quiz_attempt_items','学习测验','题干选项及私有答案快照','''
attempt_id|BIGINT NOT NULL@quiz_attempts|试卷
question_resource_id|BIGINT NOT NULL|题目
question_revision_id|BIGINT NOT NULL@question_profiles.revision_id|题目版本
position|INT NOT NULL|顺序
question_snapshot|JSON NOT NULL|公开题干选项快照
answer_key_snapshot|JSON NOT NULL|仅服务端可读的标准答案与解析快照
points|DECIMAL(8,2) NOT NULL|满分
earned_points|DECIMAL(8,2) NULL|得分
is_correct|TINYINT NULL|正误
''',u=[('attempt_id','position'),('attempt_id','question_resource_id')],ck=['position > 0','points > 0','earned_points IS NULL OR (earned_points >= 0 AND earned_points <= points)','is_correct IS NULL OR is_correct IN (0,1)'])
T('quiz_attempt_answers','学习测验','用户暂存答案，交卷后冻结','''
attempt_item_id|BIGINT NOT NULL@quiz_attempt_items|试卷题目
selected_keys|JSON NOT NULL|选择的选项键数组
lock_version|INT NOT NULL DEFAULT 0|防旧请求覆盖
''',u=[('attempt_item_id',)])
T('user_wrong_questions','学习测验','错题聚合，不覆盖作答历史','''
user_id|BIGINT NOT NULL@sys_users|用户
question_resource_id|BIGINT NOT NULL@content_resources|题目
last_wrong_item_id|BIGINT NOT NULL@quiz_attempt_items|最近错误
wrong_count|INT NOT NULL DEFAULT 1|错误次数
last_wrong_at|DATETIME(3) NOT NULL|最近错误
review_state|VARCHAR(20) NOT NULL DEFAULT 'PENDING'|复习状态
last_reviewed_at|DATETIME(3) NULL|最近复习
''',u=[('user_id','question_resource_id')],ix=[('user_id','review_state','last_wrong_at')],ck=['wrong_count > 0',"review_state IN ('PENDING','REVIEWED')"])
T('qa_sessions','智能问答','私有多轮会话','''
user_id|BIGINT NOT NULL@sys_users|用户
title|VARCHAR(160) NOT NULL|会话名
context_resource_id|BIGINT NULL@content_resources|初始场景
archived_at|DATETIME(3) NULL|删除归档时间
''',ix=[('user_id','updated_at')])
T('qa_messages','智能问答','问答消息，不存模型内部推理','''
session_id|BIGINT NOT NULL@qa_sessions|会话
parent_message_id|BIGINT NULL|回复消息
role|VARCHAR(16) NOT NULL|USER/ASSISTANT
body|LONGTEXT NOT NULL|消息内容
status|VARCHAR(20) NOT NULL DEFAULT 'COMPLETED'|生成状态
client_request_key|CHAR(36) NOT NULL|会话内消息幂等键
context_revision_id|BIGINT NULL@content_revisions|场景版本
refusal_reason|VARCHAR(40) NULL|拒答原因分类
''',u=[('session_id','client_request_key'),('session_id','id')],ix=[('session_id','created_at')],ck=["role IN ('USER','ASSISTANT')","status IN ('GENERATING','COMPLETED','STOPPED','FAILED','REFUSED')"])
T('qa_runs','智能问答','检索生成配置、耗时和用量','''
assistant_message_id|BIGINT NOT NULL@qa_messages|助手消息
route|VARCHAR(24) NOT NULL|检索路径
model_name|VARCHAR(100) NULL|实际生成模型
embedding_model|VARCHAR(100) NULL|实际嵌入模型
prompt_version|VARCHAR(40) NOT NULL|模板版本
retrieval_config|JSON NOT NULL|TopK、阈值、距离度量及索引代次
latency_ms|INT NULL|总耗时
first_token_ms|INT NULL|首内容token耗时
input_tokens|INT NULL|输入用量
output_tokens|INT NULL|输出用量
cost_estimate|DECIMAL(12,6) NULL|估算费用，非账单金额
status|VARCHAR(20) NOT NULL|运行状态
error_code|VARCHAR(80) NULL|失败分类
''',u=[('assistant_message_id',)],ck=["route IN ('VECTOR','STRUCTURED','GRAPH','HYBRID','REFUSE')","status IN ('RUNNING','SUCCEEDED','FAILED','STOPPED','REFUSED')",'latency_ms IS NULL OR latency_ms >= 0','first_token_ms IS NULL OR first_token_ms >= 0','input_tokens IS NULL OR input_tokens >= 0','output_tokens IS NULL OR output_tokens >= 0','cost_estimate IS NULL OR cost_estimate >= 0'])
T('qa_retrieval_hits','智能问答','检索证据快照，区分距离和相似度','''
run_id|BIGINT NOT NULL@qa_runs|运行
rank_no|INT NOT NULL|排名
channel|VARCHAR(20) NOT NULL|检索渠道
revision_id|BIGINT NULL@content_revisions|知识版本
segment_id|BIGINT NULL@document_segments|来源段落
raw_metric|DECIMAL(14,8) NULL|原始距离或相似度
metric_name|VARCHAR(40) NULL|度量名称
normalized_score|DECIMAL(10,8) NULL|标准化排序分，非正确概率
content_snapshot|TEXT NOT NULL|当时证据内容
''',u=[('run_id','rank_no')],ck=['rank_no > 0','revision_id IS NOT NULL OR segment_id IS NOT NULL',"channel IN ('VECTOR','STRUCTURED','GRAPH')",'normalized_score IS NULL OR (normalized_score >= 0 AND normalized_score <= 1)'])
T('qa_citations','智能问答','引用快照保留原始证据','''
message_id|BIGINT NOT NULL@qa_messages|回答
citation_no|INT NOT NULL|引用序号
retrieval_hit_id|BIGINT NOT NULL@qa_retrieval_hits|实际检索证据
revision_id|BIGINT NULL@content_revisions|知识版本
segment_id|BIGINT NULL@document_segments|来源段落
title_snapshot|VARCHAR(255) NOT NULL|当时标题
quote_snapshot|TEXT NOT NULL|当时引用片段
''',u=[('message_id','citation_no')],ck=['citation_no > 0','revision_id IS NOT NULL OR segment_id IS NOT NULL'])
T('qa_feedback','智能问答','质量反馈','''
user_id|BIGINT NOT NULL@sys_users|反馈者
message_id|BIGINT NOT NULL@qa_messages|回答
rating|VARCHAR(20) NOT NULL|评价
comment|TEXT NULL|说明
''',u=[('user_id','message_id')],ck=["rating IN ('HELPFUL','UNHELPFUL','INCORRECT')"])
T('qa_saved_answers','智能问答','私有回答收藏','''
user_id|BIGINT NOT NULL@sys_users|用户
message_id|BIGINT NOT NULL@qa_messages|自己的回答
''',u=[('user_id','message_id')])
T('user_tag_preferences','个性化推荐','用户主动选择的兴趣','''
user_id|BIGINT NOT NULL@sys_users|用户
tag_id|BIGINT NOT NULL@content_tags|标签
''',u=[('user_id','tag_id')])
T('user_interest_scores','个性化推荐','可重建的行为画像','''
user_id|BIGINT NOT NULL@sys_users|用户
tag_id|BIGINT NOT NULL@content_tags|标签
score|DECIMAL(14,6) NOT NULL|衰减分
calculated_at|DATETIME(3) NOT NULL|计算时间
algorithm_version|VARCHAR(40) NOT NULL|算法版本
''',u=[('user_id','tag_id')],ck=['score >= 0'])
T('recommendation_strategies','个性化推荐','每场景只允许一个启用策略','''
scene|VARCHAR(32) NOT NULL|场景
version_no|INT NOT NULL|配置版本
name|VARCHAR(100) NOT NULL|名称
config|JSON NOT NULL|权重、去重、多样性等参数
status|VARCHAR(16) NOT NULL DEFAULT 'DRAFT'|状态
active_scene|VARCHAR(32) GENERATED ALWAYS AS (CASE WHEN status = 'ACTIVE' THEN scene ELSE NULL END) STORED|有效配置唯一键
''',u=[('scene','version_no'),('active_scene',)],ck=['version_no > 0',"status IN ('DRAFT','ACTIVE','RETIRED')","scene IN ('DISCOVER','CONTINUE_LEARNING','REVIEW')"])
T('recommendation_requests','个性化推荐','返回不等同曝光','''
user_id|BIGINT NOT NULL@sys_users|用户
strategy_id|BIGINT NOT NULL@recommendation_strategies|策略版本
request_key|CHAR(36) NOT NULL|幂等键
scene|VARCHAR(32) NOT NULL|入口
context_resource_id|BIGINT NULL@content_resources|当前内容
experiment_group|VARCHAR(40) NULL|预分配实验组
''',u=[('user_id','request_key')],ix=[('user_id','created_at'),('strategy_id','created_at')])
T('recommendation_items','个性化推荐','推荐内容、理由和实际曝光','''
request_id|BIGINT NOT NULL@recommendation_requests|批次
resource_id|BIGINT NOT NULL@content_resources|内容
revision_id|BIGINT NOT NULL|当时正式版本
position|INT NOT NULL|位置
score|DECIMAL(14,6) NOT NULL|最终分
component_scores|JSON NOT NULL|各路分和有效权重
reason_code|VARCHAR(40) NOT NULL|理由编码
reason_text|VARCHAR(255) NOT NULL|理由文案
exposed_at|DATETIME(3) NULL|实际可见确认
first_clicked_at|DATETIME(3) NULL|首次点击
''',u=[('request_id','position'),('request_id','resource_id')],ck=['position > 0'])
T('recommendation_feedback','个性化推荐','显式负反馈','''
user_id|BIGINT NOT NULL@sys_users|用户
resource_id|BIGINT NOT NULL@content_resources|内容
feedback_type|VARCHAR(24) NOT NULL|反馈类型
''',u=[('user_id','resource_id')],ck=["feedback_type IN ('NOT_INTERESTED','HIDE')"])
T('behavior_events','个性化推荐','幂等事件；匿名浏览只计热度','''
event_key|CHAR(36) NOT NULL|事件幂等键
user_id|BIGINT NULL@sys_users|登录用户
anonymous_session|CHAR(36) NULL|短期匿名标识
resource_id|BIGINT NULL@content_resources|内容目标
quiz_attempt_id|BIGINT NULL@quiz_attempts|测验目标
recommendation_item_id|BIGINT NULL@recommendation_items|归因推荐项
behavior_type|VARCHAR(32) NOT NULL|行为类型
occurred_at|DATETIME(3) NOT NULL|服务端确认时间
metadata|JSON NULL|停留时间等补充属性
''',u=[('event_key',)],ix=[('user_id','occurred_at'),('resource_id','behavior_type','occurred_at')],ck=['user_id IS NOT NULL OR anonymous_session IS NOT NULL','resource_id IS NOT NULL OR quiz_attempt_id IS NOT NULL',"behavior_type IN ('VIEW','LIKE','UNLIKE','BOOKMARK','UNBOOKMARK','COMMENT','PUBLISH','COMPLETE_CARD','SUBMIT_QUIZ','RECOMMEND_CLICK')"])
T('community_sections','科普社区','分区','''
name|VARCHAR(80) NOT NULL|名称
code|VARCHAR(64) NOT NULL|编码
description|VARCHAR(500) NULL|介绍
sort_order|INT NOT NULL DEFAULT 0|顺序
active|TINYINT NOT NULL DEFAULT 1|启用
''',u=[('code',)],ck=['active IN (0,1)'])
T('community_post_profiles','科普社区','帖子文章身份，正文采用共用版本','''
resource_id|BIGINT NOT NULL@content_resources|POST或ARTICLE资源
section_id|BIGINT NOT NULL@community_sections|分区
is_pinned|TINYINT NOT NULL DEFAULT 0|置顶
comments_enabled|TINYINT NOT NULL DEFAULT 1|允许评论
''',u=[('resource_id',)],ck=['is_pinned IN (0,1)','comments_enabled IN (0,1)'])
T('community_knowledge_links','科普社区','内容版本关联知识','''
post_revision_id|BIGINT NOT NULL@content_revisions|文章或帖子版本
knowledge_resource_id|BIGINT NOT NULL@knowledge_entries.resource_id|知识
''',u=[('post_revision_id','knowledge_resource_id')])
T('community_comments','科普社区','有限层级回复，服务层限制仅回复根评论','''
resource_id|BIGINT NOT NULL@community_post_profiles.resource_id|帖子文章
user_id|BIGINT NOT NULL@sys_users|作者
parent_comment_id|BIGINT NULL|被回复评论
body|TEXT NOT NULL|内容
status|VARCHAR(16) NOT NULL DEFAULT 'VISIBLE'|状态
''',u=[('resource_id','id')],ix=[('resource_id','status','created_at')],ck=["status IN ('VISIBLE','HIDDEN','DELETED')"])
T('community_reports','科普社区','举报内容或评论，二选一','''
reporter_id|BIGINT NOT NULL@sys_users|举报人
resource_id|BIGINT NULL@community_post_profiles.resource_id|帖子文章
comment_id|BIGINT NULL@community_comments|评论
reason_code|VARCHAR(40) NOT NULL|原因
 description|TEXT NULL|补充说明
status|VARCHAR(20) NOT NULL DEFAULT 'PENDING'|状态
handler_id|BIGINT NULL@sys_users|处理人
resolution|TEXT NULL|结果
resolved_at|DATETIME(3) NULL|处理时间
''',ix=[('status','created_at'),('reporter_id','created_at')],ck=['(resource_id IS NOT NULL) + (comment_id IS NOT NULL) = 1',"status IN ('PENDING','PROCESSING','RESOLVED','REJECTED')"])
T('community_moderation_actions','科普社区','内容治理操作历史','''
report_id|BIGINT NULL@community_reports|举报
resource_id|BIGINT NULL@community_post_profiles.resource_id|帖子文章
comment_id|BIGINT NULL@community_comments|评论
actor_id|BIGINT NOT NULL@sys_users|操作者
action|VARCHAR(32) NOT NULL|动作
reason|TEXT NOT NULL|依据
''',ix=[('resource_id','created_at'),('comment_id','created_at')],ck=['(resource_id IS NOT NULL) + (comment_id IS NOT NULL) = 1',"action IN ('HIDE','RESTORE','SUSPEND','DISABLE_COMMENTS','DISMISS_REPORT')"])
F('content_resources','id,published_revision_id','content_revisions','resource_id,id')
F('content_revisions','resource_id,base_revision_id','content_revisions','resource_id,id')
F('resource_feedback','resource_id,revision_id','content_revisions','resource_id,id')
F('document_segments','document_id,parse_run_id','document_parse_runs','document_id,id')
F('revision_evidence','document_id,segment_id','document_segments','document_id,id')
for t,r,v in [('formula_ingredients','formula_resource_id','formula_revision_id'),('formula_ingredients','herb_resource_id','herb_revision_id'),('knowledge_relations','source_resource_id','source_revision_id'),('knowledge_relations','target_resource_id','target_revision_id'),('topic_cards','card_resource_id','card_revision_id'),('card_knowledge_links','knowledge_resource_id','knowledge_revision_id'),('question_knowledge_links','knowledge_resource_id','knowledge_revision_id'),('assessment_questions','question_resource_id','question_revision_id'),('user_enrollments','topic_resource_id','topic_revision_id'),('user_card_progress','card_resource_id','card_revision_id'),('quiz_attempt_items','question_resource_id','question_revision_id'),('recommendation_items','resource_id','revision_id')]:F(t,r+','+v,'content_revisions','resource_id,id')
F('formula_ingredients','formula_revision_id,evidence_id','revision_evidence','revision_id,id')
F('knowledge_relations','source_revision_id,evidence_id','revision_evidence','revision_id,id')
F('qa_messages','session_id,parent_message_id','qa_messages','session_id,id')
F('community_comments','resource_id,parent_comment_id','community_comments','resource_id,id')
byname={t['name']:t for t in TABLES}
for f in FKS:
    t=byname[f['table']];keys=[['id']]+t['unique']+t['indexes']
    if not any(k[:len(f['columns'])]==f['columns'] for k in keys):t['indexes'].append(f['columns'])
W('database/schema_model.json',json.dumps(dict(tables=TABLES,foreign_keys=FKS),ensure_ascii=False,indent=2))
def Q(s):return "'"+s.replace("'","''")+"'"
def I(s):return '`'+s+'`'
def K(prefix,t,i):return prefix+'_'+hashlib.sha1((t+str(i)).encode()).hexdigest()[:16]
sql=['-- MySQL8.0.16+；仅在指定空库执行；无DROP/USE/CREATE DATABASE。','-- DDL不整体回滚。已提交内容的不可变性由业务事务实现。',"SET NAMES utf8mb4;","SET time_zone = '+00:00';"]
for t in TABLES:
    rows=[f"  {I(c['name'])} {c['definition']} COMMENT {Q(c['comment'])}" for c in t['columns']]+['  PRIMARY KEY (`id`)']
    for kind,keys in [('UNIQUE KEY',t['unique']),('KEY',t['indexes'])]:
        for i,k in enumerate(keys):rows.append('  '+kind+' '+I(K('uk' if kind=='UNIQUE KEY' else 'ix',t['name'],i))+' ('+', '.join(map(I,k))+')')
    for i,c in enumerate(t['checks']):rows.append('  CONSTRAINT '+I(K('ck',t['name'],i))+' CHECK ('+c+')')
    sql.append('CREATE TABLE '+I(t['name'])+' (\n'+',\n'.join(rows)+'\n) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='+Q(t['description'])+';\n')
for i,f in enumerate(FKS):sql.append('ALTER TABLE '+I(f['table'])+' ADD CONSTRAINT '+I(K('fk',f['table'],i))+' FOREIGN KEY ('+', '.join(map(I,f['columns']))+') REFERENCES '+I(f['reference'])+' ('+', '.join(map(I,f['reference_columns']))+') ON DELETE RESTRICT ON UPDATE RESTRICT;')
W('backend/src/main/resources/db/migration/V1__initial_schema.sql','\n'.join(sql))
roles=[('LEARNER','学习者','默认注册角色'),('CONTRIBUTOR','贡献者','提交资料文章及修订'),('REVIEWER','审核者','知识学习内容和社区审核'),('ADMIN','管理员','账户权限任务与技术管理，不默认具备专业审核权限')]
perms=[('qa:ask','问答'),('learning:study','学习测验'),('content:bookmark','收藏记录'),('community:post','讨论评论'),('content:feedback','纠错反馈'),('contribution:submit','资料修订'),('community:article:submit','文章投稿'),('knowledge:review','知识审核'),('learning:review','题目卡片审核'),('community:moderate','社区治理'),('system:user:manage','账户及资格'),('system:role:manage','角色授权'),('system:job:manage','任务索引'),('system:audit:read','查看审计'),('system:backup:manage','备份恢复'),('recommendation:configure','推荐配置')]
seed=['-- 不创建账号、不包含密码及API密钥。',"SET NAMES utf8mb4;","SET time_zone = '+00:00';",'START TRANSACTION;']
for i,(c,n,d) in enumerate(roles,1):seed.append(f'INSERT INTO sys_roles(id,code,name,description) VALUES({i},{Q(c)},{Q(n)},{Q(d)});')
for i,(c,n) in enumerate(perms,1):seed.append(f'INSERT INTO sys_permissions(id,code,name) VALUES({i},{Q(c)},{Q(n)});')
for r,ids in [(1,range(1,6)),(2,[6,7]),(3,[8,9,10]),(4,[11,12,13,14,15,16])]:
    for p in ids:seed.append(f'INSERT INTO sys_role_permissions(role_id,permission_id) VALUES({r},{p});')
for c,n,s in [('tcm-concept','中医概念','KNOWLEDGE'),('tcm-herb','中药材','KNOWLEDGE'),('tcm-formula','方剂','KNOWLEDGE'),('beginner-topics','入门专题','LEARNING'),('learning-discussion','学习交流','COMMUNITY')]:seed.append(f'INSERT INTO content_categories(code,name,scope) VALUES({Q(c)},{Q(n)},{Q(s)});')
for c,n in [('basics','基础知识'),('formulas','方剂学习'),('study','学习交流')]:seed.append(f'INSERT INTO community_sections(code,name) VALUES({Q(c)},{Q(n)});')
seed.append('COMMIT;');W('backend/src/main/resources/db/migration/V2__seed_roles_and_categories.sql','\n'.join(seed))
d=['# 数据库字段字典',f'\n共{len(TABLES)}张表，{len(FKS)}条外键。完整设计、分阶段实现。','\n所有ID在API按字符串返回；时间UTC；外键RESTRICT。以下约束不能替代业务权限和状态转换校验。']
for t in TABLES:
    d+=['\n## '+t['name']+' · '+t['description'],'\n领域：'+t['domain'],'\n| 字段 | SQL定义 | 说明 |','|---|---|---|']
    d += ['| '+c['name']+' | '+c['definition']+' | '+c['comment']+' |' for c in t['columns']]
    d+=['\n唯一键：'+('；'.join(','.join(k) for k in t['unique']) or '仅主键'),'\n索引：'+('；'.join(','.join(k) for k in t['indexes']) or '无额外索引')]
    d+=['- CHECK：`'+c+'`' for c in t['checks']]
    d+=['- FK：`'+','.join(f['columns'])+'` → `'+f['reference']+'('+','.join(f['reference_columns'])+')`' for f in FKS if f['table']==t['name']]
W('docs/02-数据库字段字典.md','\n'.join(d))
print(json.dumps(dict(root=str(ROOT),tables=len(TABLES),foreign_keys=len(FKS),columns=sum(len(t['columns']) for t in TABLES)),ensure_ascii=False))
