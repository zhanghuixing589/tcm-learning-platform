# 第三阶段第一批：数据库与接口详细设计

日期：2026-11-20。依据：[第三阶段业务设计](2026-11-20-stage-three-design.md)。状态：实施中的技术设计；V7—V9迁移已在隔离测试库验证并完成规格/质量审查，应用接口与界面仍待后续任务实现。其余字段与接口为拟实现契约，不能作为已实现能力对外展示；实际进度见 task_plan.md/progress.md。

## 1. 交付边界与当前代码证据

本批交付分区基础、成员、讨论、文章及文献/扩容、版本化评论、点赞收藏和兼容个人中心。治理规则配置、分区直建/申请、观察期自动转正、接管定时任务、举报申诉和专业复用按第二、三批实施。本批不开放缺少后续治理闭环的自建分区入口。涉及后续能力的高风险流程不模拟审核成功。

| 当前入口或对象 | 核对结果 | 本批接入方式 |
|---|---|---|
| `PersonalCenterDtos.ArticleInput` | 标题 `@Size(max=255)`、正文 `@Size(max=100000)` | 改为按策略/文章额度验证；旧入口同样调用统一服务 |
| `ArticleEditorView.vue` | 正文 `maxlength="100000"`，保存时对正文 `.trim()` | 动态额度；保留正文源文本空白和 Markdown 缩进，不用 trim 改写正文 |
| `PersonalCenterService` | 最新作者稿与正式指针分开；仅 DRAFT 可改；文章修订链可用 | 复用，不复制第二套文章正文；补版本锁、分区/文献及兼容代理 |
| `ReviewService.decide` | 文章按 `community:moderate` 审核，通过后设置 PUBLISHED 并清除停用原因 | 补文章发布前置检查；普通审核通过不能解除独立治理隐藏 |
| `community_comments` | 正文单列、同资源回复外键；只支持有限层级的设计约束 | 评论身份与版本拆开，补根评论和指定回复对象校验 |
| `SecurityConfiguration` | 业务默认登录；CORS 未放行幂等头，仅暴露 X-Trace-Id | 保留登录要求，新增 Idempotency-Key 放行及 Retry-After 暴露 |
| `IdentityService.status` | 锁目标账号、修改状态并撤销会话 | 同事务撤销成员加入关系、移除负责人权限并登记待接管状态 |
| `ResourceView.vue` 及通用资源详情 | 现有文章展示、收藏与浏览入口 | 社区内容统一通过同一公开正文读取服务；旧链接继续可用 |

本批选择“复用资源/版本＋社区扩展表”。另建完整内容系统会使个人中心、收藏、知识来源的资源身份分裂；把所有新增字段塞入共享版本表会影响知识中心。本方案仅在共享版本旁增加社区元数据，评论单独版本化。

## 2. 状态、权限和统一不变量

### 2.1 内容状态

1. 资源继续使用 `DRAFT/PUBLISHED/SUSPENDED/ARCHIVED`；内容版本继续使用 `DRAFT/IN_REVIEW/APPROVED/REJECTED/WITHDRAWN`，不修改共享 CHECK 枚举。
2. 社区版本的 `release_method` 区分 `NONE/DIRECT/MANUAL_REVIEW/LEGACY`。讨论直接公开使用 APPROVED 作为发布许可状态，同时记录 DIRECT；没有人工审核任务，不显示为专业审核通过。文章通过现有任务后标记 MANUAL_REVIEW。
3. 公开读取只能跟随 `published_revision_id`；作者的最新 DRAFT/IN_REVIEW 单独返回，公众响应没有 candidateRevisionId、候选摘要、待审标记或差异。
4. 分区归档表示只读，不隐去历史公开内容；资源 SUSPENDED 表示不可公开读取。两个状态不能混用。分区停用禁止新建内容，不批量改写历史资源状态。
5. 本批普通讨论和评论可直接公开。第二批接入风险分流后使用相同的公开/候选双版本机制；本批不声称已有风险自动判断或完整待审评论处置能力。

### 2.2 权限

| 操作 | 判定 |
|---|---|
| 浏览社区公开内容 | 登录且账号 ACTIVE，沿用现有全站鉴权 |
| 讨论、评论、加入退出 | `community:post`；写入时再次核验账号状态 |
| 文章草稿、提交及文献编辑 | `community:article:submit` 且本人；更新仅限 DRAFT |
| 普通文章审核 | `community:moderate`，禁止自审；不附带医学主题资质 |
| 分区全局配置、初始一级/二级建置 | 新增 `community:section:manage`，通过增量权限种子授予 ADMIN |
| 文章扩容审批 | 新增 `community:quota:manage`，增量授予 ADMIN；不授予治理或专业审核权 |
| 负责人维护简介、规则与置顶 | 当前负责人、账号正常且分区可管理；不授予全局角色或他人内容下架权 |

第一批管理员只通过明确能力管理基础分区，不绕过第二批才能开放的用户自建、主题改名/迁移审核。管理员已有账户管理权限不自动推导专业权限。

### 2.3 版本、计数与字段验证

- ID 以十进制字符串传输，时间为 UTC ISO8601，乐观锁为非负整数。分页沿用 `page>=1`、`pageSize=1..100`，默认 1/20。
- 标题允许去首尾空白；正文保存源文本，只将 CRLF/CR 统一为 LF，不去除有意义的空格、缩进或尾随空格。常规保存/提交的空正文按空白检查拒绝，但不以 trim 后结果覆盖源文；下述用于长文扩容的显式草稿身份命令可建立仅标题、空正文的私有 DRAFT，不能提交或公开。
- 字符额度以 Unicode code point 计数：Java `codePointCount`、前端按码点遍历。不是 UTF-16 length、UTF-8 字节数或可见字素数；接口明确提示“字符”。另校验请求体字节上限。
- 标题默认 120，讨论正文 20,000，文章正文 100,000，评论 2,000。Markdown 引用块、链接文本和语法计入正文；独立文献字段单独限额。
- 旧公开标题超过 120 时仍可读；修订允许原样保留该旧标题，修改标题后按新上限检查，不强制无关修订改短标题。历史长正文同理保留可读，新增/修改内容执行对应资源额度。
- 分区名规范化为 NFKC、去首尾空白、连续空白折叠、统一拉丁字母大小写；只对名称做该处理，不改写文章/代码正文。同义近似仅提示，不能自动当作同名合并。

## 3. 数据库设计

本批新增 11 张表，扩展 3 张既有社区表，不修改 V1—V6，也不改既有业务主键。均使用 InnoDB、utf8mb4；时间字段 DATETIME(3)、ID BIGINT，外键默认 RESTRICT。下文未重复列出的新表均有 `created_at/updated_at`；不可变记录的 updated_at 不作为允许修改依据。

### 3.1 扩展 community_sections

| 新增字段 | SQL 类型/默认 | 含义 |
|---|---|---|
| `parent_id` | BIGINT NULL | 一级为 NULL，二级指向一级 |
| `normalized_name` | VARCHAR(160) NOT NULL，二进制排序规则 | 名称规范化结果 |
| `sibling_scope` | BIGINT GENERATED ALWAYS AS (IFNULL(parent_id,0)) STORED | 解决 MySQL 唯一键对 NULL 的多值语义 |
| `created_by`、`owner_id` | BIGINT NULL | 历史创建者、当前负责人；系统一级不要求负责人 |
| `lifecycle` | VARCHAR(16) NOT NULL DEFAULT 'FORMAL' | OBSERVING/FORMAL/ARCHIVED；第一批仅建立 FORMAL，暂不启动观察期 |
| `management_status` | VARCHAR(16) NOT NULL DEFAULT 'NORMAL' | NORMAL/UNOWNED；无主不等于归档 |
| `rules_text` | TEXT NULL | 分区规则纯文本，不执行 HTML |
| `lock_version` | INT NOT NULL DEFAULT 0 | 简介/规则等并发控制 |

约束：`UNIQUE(sibling_scope,normalized_name)` 实现同父级去重，一级统一归入 scope=0；仅此唯一键不能代替两级校验。生命周期/管理状态枚举与非负锁版本做 CHECK，外键指向分区和用户。索引 `(parent_id,active,lifecycle,sort_order,id)`、`(owner_id,lifecycle,id)`。`parent_id<>id` 与两级跨行规则由服务在锁父项后验证：父项本身无父项，分区不能挂到自身，存在子项的一级不允许变为二级。实施探针已在隔离 MySQL 得到 ERROR 3818，不能对涉及 AUTO_INCREMENT id 的表达式建立该 CHECK，因此不宣称数据库 CHECK 已独立保证层级正确。

### 3.2 community_section_members（新增）

字段：`section_id BIGINT`、`user_id BIGINT`、`state VARCHAR(12)`（JOINED/LEFT/REMOVED）、`joined_at DATETIME(3)`、`left_at DATETIME(3) NULL`、`reason_code VARCHAR(40) NULL`、`lock_version INT DEFAULT 0`。主键 `(section_id,user_id)`，索引 `(user_id,state,section_id)` 和 `(section_id,state,user_id)`；两端外键与 state CHECK。

JOINED 重复加入/LEFT 重复退出为无副作用成功，不改最初加入时间、不重复加事件。LEFT/REMOVED 后主动重加入生成新 joined_at 并追加事件。禁用账号时 JOINED→REMOVED，解禁不恢复。有效人数实时联合账号 ACTIVE；计数不把已离开关系算入。

### 3.3 community_section_events（新增）

字段：`id`、`section_id`、`subject_user_id NULL`、`actor_id NULL`、`action VARCHAR(40)`、`reason_code VARCHAR(40) NULL`、`before_value JSON NULL`、`after_value JSON NULL`、`event_key VARCHAR(160)`、`trace_id VARCHAR(64)`、时间。`event_key` 唯一，索引 `(section_id,id)`、`(subject_user_id,id)`。不存密钥/资质原件。操作者为空只用于明确系统事件，不伪造为管理员操作。

用于第一批基础配置、成员变更和禁用接管事件；二批交接、归档和恢复沿用事件模型，业务申请另建关系，不把整个工作流塞进 JSON。

### 3.4 扩展 community_post_profiles

新增 `public_edited_at DATETIME(3) NULL`、`last_public_comment_at DATETIME(3) NULL`、`lock_version INT DEFAULT 0`。保留现有资源唯一关系、分区、置顶和评论开关。索引 `(section_id,is_pinned,public_edited_at,resource_id)`、`(section_id,last_public_comment_at,resource_id)`。

`section_id` 表示公开版归属；文章尚无公开版时表示首稿分区。候选修订的目标分区记录在版本元数据，不因保存草稿改变公开版分区。发布时统一变更。第一批作者不自行移动已归入正式分区的公开内容到其他分区；跨分区移动走后续治理流程。系统 LEGACY 历史容器仅表示未分类，不视为已经完成正式归属：迁入其中的历史公开文章可在候选修订中首次选择可写正式分区，并通过普通文章审核完成一次归类。保存和提交均不改变旧公开正文、指针或历史容器归属，批准时才原子切换；归类后再次从正式分区移往其他分区仍被禁止。

列表默认按首次公开时间＋资源 ID 排序，避免保存草稿或反复小改顶帖。最近编辑只用于展示，不作为默认排序；最新回复只使用有效公开评论时间。置顶在当前查询范围内优先，一级汇总按资源去重。

### 3.5 community_revision_profiles（新增）

字段：`revision_id BIGINT PK`、`resource_id BIGINT`、`section_id BIGINT`、`source_format VARCHAR(16)`（PLAIN_TEXT/MARKDOWN）、`format_policy_id BIGINT`、`release_method VARCHAR(20)`（NONE/DIRECT/MANUAL_REVIEW/LEGACY）、`source_sha256 CHAR(64)`、`body_char_count INT`、`lock_version INT DEFAULT 0`、时间。

外键 `(resource_id,revision_id)→content_revisions(resource_id,id)` 保证同资源版本，分区与格式策略外键；索引 `(resource_id,revision_id)`、`(section_id,revision_id)`。只为 POST/ARTICLE 写入，由服务验证类型。source_sha256 计算服务端规范化源文本＋按稳定键排序的文献快照＋格式/分区等影响审核的元数据，不含可变锁版本与时间。

DRAFT 的正文、文献和审核依据元数据可在同事务更新并递增锁；提交/公开后这些源字段不可改。发布结果字段 `release_method` 独立于冻结的审核依据：新投稿提交、撤回或退回时保持 NONE，仅在批准事务中变为 MANUAL_REVIEW；迁移前待审版本在本期获批时也记录 MANUAL_REVIEW，历史已公开的 LEGACY 版本保持不变。新增修订复制所需元数据、文献稳定键；不能更新旧 profiles 来让历史审核依据漂移。

### 3.6 community_comment_revisions（新增）及评论表扩展

新表字段：`id`、`comment_id BIGINT`、`version_no INT`、`base_revision_id BIGINT NULL`、`body TEXT`、`revision_state VARCHAR(20)`（沿用内容版本状态）、`release_method VARCHAR(20)`、`format_policy_id BIGINT`、`source_sha256 CHAR(64)`、`body_char_count INT`、`created_by BIGINT`、`submitted_at DATETIME(3) NULL`、时间。唯一 `(comment_id,version_no)`、`(comment_id,id)`；复合外键 `(comment_id,base_revision_id)` 指向本评论版本，版本号正数。

既有 `community_comments` 新增 `root_comment_id BIGINT NULL`、`published_revision_id BIGINT NULL`、`candidate_revision_id BIGINT NULL`、`lock_version INT DEFAULT 0`。原 `parent_comment_id` 明确为“指定回复对象”；根评论两个关系都 NULL，回复的 root 指向根，parent 指向同串实际回复对象。

- 复合外键 `(resource_id,root_comment_id)`、既有 `(resource_id,parent_comment_id)` 保证同帖子；服务锁定根和回复对象后核验根确为根、parent 属于该根，拒绝跨串回复。
- `(id,published_revision_id)` 和 `(id,candidate_revision_id)` 分别关联 `(comment_id,id)`，保证指针不串评论。新建先插评论身份、再插版本、再设公开指针，全在一个事务。
- 索引 `(resource_id,root_comment_id,status,created_at,id)` 支持根分页/串内分页。根与回复分别分页，不一次返回无限评论串。
- 迁移后删除旧 `body` 列，新正文只从 revisions 读取；第一批停旧写入口并一次切换应用，不承诺旧应用二进制与新 schema 同时可写。原 body 的完整值迁移后校验哈希/条数，不丢失 HIDDEN/DELETED 历史。
- 删除只改变身份状态 DELETED，公开序列返回占位，不返回历史正文；版本保留在权限保护下。HIDDEN 不因作者编辑恢复。被删/隐藏根下已有公开回复仍可分页；不能向已删或隐藏目标新增回复。

### 3.7 community_revision_references（新增）

字段：`revision_id BIGINT`、`reference_key CHAR(36)`（文章内稳定 UUID）、`display_number INT`、`title VARCHAR(500)`、`authors VARCHAR(1000) NULL`、`publication_year SMALLINT NULL`、`source_name VARCHAR(1000) NULL`、`doi VARCHAR(255) NULL`、`url VARCHAR(2048) NULL`、`location_label VARCHAR(500) NULL`、时间。主键 `(revision_id,reference_key)`，唯一 `(revision_id,display_number)`，版本外键；display_number>0。

只用于 ARTICLE；文献限额与字段长度服务端验证。第一批建议最多 300 条、所有文献文本合计 200,000 字符，均为可配置技术初值，不代表用户已逐项确认这些数值。DOI/URL 只作为手动文字，不发网络请求，不产生“已核验”标记。

正文以 `[1]` 等序号关联本版本条目。解析器仅在非代码/转义区识别独立引用，普通 Markdown 链接及代码中的 `[1]` 不改写；保存时允许无效引用警告，提交时必须解决悬空引用。稳定键是持久身份，编号是版本内显示值。

为避免“正文已手工编辑＋文献同时重排”无法判定旧编号含义，重排使用独立命令：客户端先保存私有草稿，再传该稿 baseRevisionId/lockVersion 与 referenceKeys 排列；服务端读取已保存的正文和旧映射，按稳定键计算新编号，在同一事务改写已识别引用及文献顺序，返回更新后的源文、文献和锁。不对整篇文章做无语境数字替换，不推测客户端当前正文采用哪套编号。普通保存不得悄悄改变已有条目编号；删除引用条目须在同次保存移除其正文引用，不能通过新键复占原编号让旧引用静默指向另一文献。删除后暂留编号间隙可保存，再由显式重排压紧编号；重排后重新检查正文额度。

### 3.8 community_text_policies、community_text_policy_heads（新增）

政策表字段：`id`、`version_no INT UNIQUE`、`config JSON`、`created_by BIGINT NULL`、`reason VARCHAR(1000)`、`created_at`；追加不可变。head 表单行 `id=1`、`policy_id BIGINT FK`、`lock_version INT`、`updated_by BIGINT NULL`、`updated_at`，更新需旧 lockVersion，保证同时只有一个活动政策而不是多条 active=true。

config 使用固定 JSON schema：语法白名单、协议、标题/正文/评论长度、文献数量/合计长度、请求体字节数和按操作限频参数。第一批以种子＋受控管理服务维护，不把任意 JSON 写入开放给客户端；原始 HTML、事件处理器、脚本和可执行嵌入永远不在可配置能力集合内。

建议技术初值：仅 http/https；文章扩容硬上限 500,000 字符；单次 JSON 在解压后、反序列化前的实际字节最多 8 MiB。前端不得按 JSON 文件字符数替代字节约束；服务端流式限量读取并返回 413，不能依赖仅适用于 multipart 的配置或 Content-Length；未实现有界解压时拒绝压缩请求体。当前图片/附件能力仍关闭。上述资源限额可在验证后下调，不能给任意无限额度。

### 3.9 community_article_quotas、community_quota_requests（新增）

quotas：`resource_id BIGINT PK/FK`、`body_char_limit INT`、`last_request_id BIGINT`、`lock_version INT DEFAULT 0`、时间。仅 ARTICLE 可建；无例外记录时用活动政策默认额度。例外不得超过系统硬上限；已有批准额度不因提高默认额度而反向降低实际可用额度。

requests：`id`、`resource_id`、`requested_by`、`requested_limit INT`、`reason VARCHAR(2000)`、`status VARCHAR(16)`（PENDING/APPROVED/REJECTED/WITHDRAWN）、`reviewer_id BIGINT NULL`、`approved_limit INT NULL`、`review_note VARCHAR(2000) NULL`、`reviewed_at NULL`、`lock_version INT`、`pending_slot BIGINT GENERATED AS (CASE WHEN status='PENDING' THEN resource_id ELSE NULL END) STORED`、时间。唯一 pending_slot，索引 `(requested_by,id)`、`(status,id)`，外键及状态 CHECK。

审批人不得自批；扩容请求只关联本人文章。申请须超过当前有效额度；批准额度介于当前有效额度和请求额度之间且不超硬上限。相同结果幂等，冲突结果 409；批准额度、申请状态、审计及通知一个事务提交。拒绝不改变已有额度或公开状态。无批准请求不允许直接写 quotas。

首次录入长文时可能尚无文章 ID。提供显式“创建草稿以申请扩容”命令，仅保存作者填写的有效标题、空正文和私有 DRAFT 身份，以取得申请所需的资源 ID；不截断全文、不伪造占位正文、不将批准额度前的超限正文偷偷保存。编辑器保留用户输入并显示尚未保存，离开仍须处理未保存内容。空正文身份不能提交审核或成为公开依据；取得额度后通过常规草稿保存全文，继续执行同一长度、权限、文献及版本检查。

last_request_id 在 requests 建表后补外键，且由服务核验与 resource_id 对应。文章已删除/归档或提交人失去投稿权限时，不审批为可写；不能通过扩容接口恢复权限或治理状态。

### 3.10 community_write_receipts（新增）

字段：`id`、`actor_id`、`operation VARCHAR(160)`（含目标 ID）、`request_key VARCHAR(128)`、`request_sha256 CHAR(64)`、`result_kind VARCHAR(32)`、`result_id BIGINT`、`http_status SMALLINT`、`created_at`。唯一 `(actor_id,operation,request_key)`；相同键不同规范化载荷返回 409，不重复创建版本、文献或通知。请求键区分大小写，仅允许非空格可见 ASCII（0x21—0x7E），8—128 字符；拒绝所有空白而非 trim 后接收。MySQL ascii_bin 的 PAD SPACE 语义因此不会让两个合法请求键发生尾空格折叠；标准 UUID 请求键不受影响。

receipt 与业务写入同事务提交，失败回滚不留假成功；不存整份长文或身份令牌。重放先检查当前身份和访问权限，再按 result_id 返回已产生的对象/版本；如对象后来不可读，返回当前安全状态，不重放旧公开正文。第一批不清理 receipt，避免短 TTL 到期后旧重试再次生效；后续维护另行确定保留规则。

写入门禁先按序锁参与账号，再原子插入或复用 receipt，然后锁定已存在的 receipt 行。不要先对不存在的键执行 SELECT FOR UPDATE 再插入，以免不同账号在空索引区间产生相互等待的 gap lock。可在同一未提交事务内使用 RESERVED/结果ID0/状态102作占位，成功前必须替换为实际结果；占位不得提交或对外返回。失败时占位、计数和业务变更一并回滚；需要不同参与账号锁的审批命令由门禁在最开始按ID升序一次获取。

### 3.11 community_rate_windows（新增）

主键 `(user_id,operation,window_seconds,window_start)`；字段 `attempt_count INT`、`policy_id`、`updated_at`。只保存操作类别/计数，不存正文/IP 或候选内容。使用服务端 Clock 计算 UTC 固定窗口，组合分钟/小时窗口，事务按主键顺序锁定增量；超限返回 429 和 Retry-After，不用浏览器计数作为安全依据。

建议可配置初值：讨论新建 3/分钟及20/小时；评论新建 10/分钟及120/小时；公开编辑 6/分钟及60/小时；私有草稿保存 30/分钟及300/小时；额度申请 3/天。同键成功重放不消耗新额度。限频是操作限制，不能直接计为违规或账号信誉扣分；以上为工程初值，可经真实交互验证后调整。

## 4. 迁移与旧数据兼容

候选迁移按当前 V6 基线排为 V7/V8/V9；实际创建前检查编号是否已被其他工作占用。第一批迁移不含后续工作流占位表。V7/V8 使用 Flyway Java migration 承担 NFKC 名称规范化、哈希和评论关系回填，拟放 `backend/src/main/java/db/migration/V7__CommunityFoundation.java` 与 `V8__CommunityVersionSnapshots.java`；V9 使用 `backend/src/main/resources/db/migration/V9__community_quotas_and_writes.sql`。Java migration 通过 Flyway Context 的连接执行受控 DDL/DML，不依赖尚未初始化的业务 Spring Bean，不假设纯 SQL 与 Java 的 Unicode 规范化算法相同。

### V7：分区与成员

1. 增量增加权限、分区 nullable 字段、成员和事件基础表，不产生用户账户。
2. 按实际 Java 名称规范化算法计算旧分区名称；若规范化碰撞，输出冲突 ID 并停止升级，不自动重命名或合并用户数据。最终 NOT NULL/唯一键在回填后建立。
3. 旧分区保留 ID、code、active 和帖子归属，回填为一级 FORMAL，创建者/负责人 NULL；一级系统分区不因无负责人进入接管状态。
4. 原未绑定 profile 的 ARTICLE/POST：创建隐藏导航的系统“历史内容”一级容器并补 profile，保留资源和版本 ID，不将所有旧文章强制塞到“学习交流”。该容器不接受新帖；旧草稿提交前提示选择可写分区。已公开历史文章的首次正式归类使用候选版本和普通审核，不能直接改写旧公开归属。

### V8：版本与文献

1. 创建文本政策、社区版本元数据、文献和评论版本表，写入初始政策/head 后回填元数据，再补指针外键。
2. 旧社区正文按 PLAIN_TEXT/LEGACY 回填，保留原样；不因升级突然把历史文本解释成 Markdown。审核中的旧版本不改判为通过。
3. 评论 body 全量回填为首版，身份 HIDDEN/DELETED 保持原样；可见首版设置公开指针。先校验条数、目标归属及正文哈希，再移除旧 body 列并启用新写入路径。
4. 历史根/回复按原有限层级关系回填 root；存在跨串/循环脏数据时停止并生成修复清单，不静默丢回复。旧举报没有版本证据时标记为历史未固定版本，不能伪称新生成的迁移版本就是当时被举报版本；二批显式兼容此类历史举报。

### V9：额度与写入控制

1. 创建额度、申请、幂等与限频表，补外键；默认政策引用与硬上限一致。
2. 回填公开编辑时间，首次公开时间保持原值；后续审批/编辑均不改首次发布时间。
3. 空库全迁移、V6 有数据升级、违规夹具阻断升级分别验证。升级前备份并确认恢复流程；MySQL DDL 不能当作可事务回滚的数据修改，失败后检查实际 schema，不直接盲重跑或先 repair。

发布时停旧应用写入再迁移和切换，新代码须在一次切换后处理所有新旧入口。代码回退必须与对应备份恢复策略配合，不声称降级旧二进制即可兼容已移除 body 的 schema。全程不重建本机已有开发数据库。

## 5. 写入事务、公开读取与竞态

### 5.1 锁序及幂等

社区命令固定：涉及账号 ID 升序→请求幂等记录→分区 ID 升序→资源 ID 升序→评论 ID 升序→审核/额度请求→文献键串行锁（有文献时）→限频窗口主键序→追加事件/通知。只读定位允许事务前读取，但获得锁后必须核验关联未变化。禁止锁资源后再补锁分区。

第一批跨文章文献稳定键的校验与落库，通过 community_text_policy_heads(id=1) 的 FOR UPDATE 串行锁完成；在全部账号、分区、资源及任务锁之后获取，取得串行锁后再采样活动文本政策，随后按 READ COMMITTED 查询已有归属并保存，持有至提交，不能再倒序补领域锁。政策创建/激活同样先账号后 head，取得 head 后不锁社区领域对象。此方案避免新建键在不同文章并发占用的竞态，代价是携带文献的写入串行；后续吞吐要求提高时可单独引入键归属表，不以进程内锁代替数据库保证。

现有文章审核分支需预定位提交版本的 section，先锁审核人/作者账号及分区，再进入资源/任务锁并重核版本。来源/知识维持来源→资源→任务的既有顺序，不反向进入社区命令；文章转来源在第二批单独设计两域交接，不在持有来源锁时倒序锁账号/分区。

账号禁用先锁账号，再按 ID 升序锁其管理分区及成员关系，撤销 JOINED、负责人置空并登记 UNOWNED；身份/会话和成员改变原子提交。与加入并发时同账号锁串行，解禁不会恢复旧 JOINED。分区负责人在交接功能尚未完成时，退出接口返回明确 409 引导交回管理员，不悄悄移除负责人。

### 5.2 草稿、直接发布和审核发布

1. 草稿 PATCH：校验所有权/资格、baseRevisionId 和 lockVersion；验证源文本/文献/额度，原子更新 DRAFT 与元数据并递增资源和元数据锁。正文和文献不分两次保存。 文献重排不仅校验数字，还需重新解析改写后的 Markdown，确认原引用对应的数量、顺序、位置与预期新编号一致；若编号撞到快捷引用链接定义等语法使引用含义变化，则拒绝并回滚，不静默把引用改为链接。
2. 讨论发布/评论发布：检查账号与分区、锁版本、限频和幂等；生成冻结的新版本，检查可见性状态，设置公开指针及 DIRECT，追加审计后返回 201/200。
3. 文章提交：重核额度、引用无悬空、分区可写及权限，冻结快照并建立唯一 review_tasks；不能用保留旧 DTO 的另一路提交绕过。
4. 文章批准：重核任务、版本、分区、内容资源治理状态及账户资格；因治理而 SUSPENDED 的资源不能被普通审核分支清除 suspended_reason 后恢复。第一批直接返回状态冲突并保留待处理任务，后续由治理流程协调。
5. 同版重试相同决定返回原结果；过期版本/不同决定 409。正文、正式指针、公开分区、public_edited_at、审核事件及通知同事务提交。
6. 删除评论：重复本人删除幂等；保留版本和回复占位，不物理级联删除。正文历史始终单独鉴权。

### 5.3 公开查询

共享读取条件：资源 PUBLISHED、当前指针属于该资源且版本 APPROVED、作者 ACTIVE；知识额外沿用来源证据有效性。社区专用服务不能放宽通用资源条件。分区只读不排除公开内容。

详情只调用一次有浏览历史副作用的资源读取；列表、评论、引用定位、差异、点赞状态及版本查询不增加浏览计数。根评论和回复从各自公开指针读取，不从 MAX(version_no) 读取。候选内容不得进入列表摘要、用户主页、收藏/历史、通知预览或后续索引。

## 6. API 契约

前缀 `/api/v1`；ApiResponse 沿用 `{code,message,data,traceId}`。POST 创建/修订/额度申请要求 `Idempotency-Key`；更新/决策传 `baseRevisionId` 与/或 `lockVersion`，缺少必要并发字段返回 400。幂等键在代理前后保持不变，登录刷新重试沿用同一键。

### 6.1 路由与返回

| Method / 路径 | 请求核心 | data / 行为 |
|---|---|---|
| GET `/community/sections` | `parentId?`、`joinedOnly=false` | 一级/直属二级列表；不递归无限树 |
| GET `/community/sections/{id}` | 无 | 分区详情、加入状态、能力标志 |
| PUT/DELETE `/community/sections/{id}/membership` | 无 | `{sectionId,joined,memberCount}`；幂等 |
| POST `/admin/community/sections` | `parentId?,name,code,description?,rulesText?,ownerId?,sortOrder` | 201 分区；第一批仅管理员初始配置，非用户直建 |
| PATCH `/admin/community/sections/{id}` | `lockVersion,description?,rulesText?,active?,sortOrder?` | 不允许在该入口任意改名、移动父级或强行转让 |
| PATCH `/community/sections/{id}/profile` | `lockVersion,description?,rulesText?` | 本区负责人维护；不接受 ownerId/parentId/name |
| GET `/posts` | `sectionId?,includeChildren=true,type=ALL,q?,sort=NEWEST,page=1,pageSize=20` | PageResult；type=ALL/POST/ARTICLE，sort=NEWEST/LATEST_REPLY，非法排序拒绝 |
| POST `/posts` | `sectionId,title,body,sourceFormat=MARKDOWN` | 201 直接发布讨论；不能传 resourceType=ARTICLE 绕过投稿 |
| GET `/posts/{id}` | 无 | 公开详情和本版本文献；保留通用资源读取规则 |
| POST `/posts/{id}/revisions` | `baseRevisionId,lockVersion,title,body,changeNote?` | 新的直接公开讨论版本；不接受作者变更 sectionId |
| GET `/me/posts/{id}` | 无 | 本人公开版和最新稿分离的编辑视图 |
| GET `/me/posts/{id}/revisions` | 分页 | 本人版本历史；正文不默认全量返回 |
| GET `/me/community-revisions/diff` | `fromRevisionId,toRevisionId` | 本人同资源版本源文本差异；治理另走带任务权限入口 |
| GET `/posts/{id}/comments` | `rootId?,page,pageSize` | 不带 rootId 返回根；带 rootId 返回其回复分页；占位无正文 |
| POST `/posts/{id}/comments` | `body,rootCommentId?,replyToCommentId?` | 201 评论公开视图；两个关系同时省略或合法组合 |
| POST `/comments/{id}/revisions` | `baseRevisionId,lockVersion,body` | 本人新版本，仍为纯文本 |
| DELETE `/comments/{id}` | `lockVersion` 查询参数 | 删除占位；重复本人删除成功 |
| PUT/DELETE `/resources/{id}/like`、`.../bookmark` | 既有接口 | 复用原幂等响应，不能另建第二套计数 |
| GET `/community/text-policy` | 无 | 当前可编辑格式、各额度、字符口径、policyId；不泄露二批内部风险匹配规则 |
| GET `/admin/community/text-policies` | 分页 | `community:section:manage` 可查格式/限额政策历史，不混入专业治理规则 |
| POST `/admin/community/text-policies` | `config,reason` | 新建经过 schema 和不可开放能力校验的不可变版本，默认不激活 |
| PUT `/admin/community/text-policy-head` | `lockVersion,policyId,reason` | 原子激活已有有效政策版本，记录审计；不能覆盖旧政策正文 |
| POST `/me/articles` | 扩展 ArticleInput，见下 | 201 草稿；不自动公开 |
| POST `/me/articles/draft-identities` | `title,sourceFormat?`＋幂等键 | 本人投稿资格；仅建立私有空正文DRAFT供首次长文申请额度，返回文章ID和版本锁；不能借此提交空正文 |
| PATCH `/me/articles/{id}` | 完整草稿载荷＋baseRevisionId/lockVersion | 200 草稿，正文/文献原子保存 |
| POST `/me/articles/{id}/references/reorder` | `baseRevisionId,lockVersion,referenceKeys`＋幂等键 | 仅本人DRAFT；keys必须为当前文献稳定键的完整排列，服务器原子更新源文引用和编号；返回新锁及草稿 |
| POST `/me/articles/{id}/submit`、`.../withdraw`、`.../revise` | 版本/锁及幂等键 | 复用既有生命周期，补并发字段；withdraw 不原位改稿 |
| GET `/me/articles/{id}/quota` | 无 | 当前正文额度、硬上限、最新申请状态 |
| POST `/me/articles/{id}/quota-requests` | `requestedLimit,reason` | 201 本文扩容申请；不含正文也不自动提交文章 |
| GET `/me/articles/{id}/quota-requests` | 分页 | 本人申请历史 |
| GET `/admin/community/quota-requests` | status/分页 | 额度管理员任务列表 |
| POST `/admin/community/quota-requests/{id}/decisions` | `lockVersion,decision,approvedLimit?,note` | 批准/拒绝与通知，不能自批 |
| PUT `/community/sections/{id}/posts/{resourceId}/pin` | `lockVersion,pinned` | 本区负责人或治理权限；验证帖子当前归属，不跨区置顶 |

`/me/articles/{id}`、`/me/publications/{id}` 保留原读取字段并增加版本/额度/文献信息。写接口原路径保留，但升级后客户端必须发送并发字段；旧客户端写入返回明确的刷新提示，不为了“兼容”绕过锁。原 `{title,body}` 草稿第一次创建可不带 references/sectionId：省略 references 视为空，省略 sectionId 进入仅本人可见的历史/待分类草稿容器；提交前必须选可写分区。PATCH 中省略 references 代表保留原文献，显式 `[]` 才清空并校验悬空引用。

新增通知读取接口属于第二批，不把额度审批消息写入当作第一批已交付完整通知中心；本批作者从申请页读取结果，已有通知表同步落库供后续入口使用。

### 6.2 完整草稿保存示例

```json
{
  "baseRevisionId": "901",
  "lockVersion": 4,
  "sectionId": "3",
  "title": "方剂文献阅读笔记",
  "body": "## 阅读说明\n相关论述见文献[1]。\n",
  "sourceFormat": "MARKDOWN",
  "references": [
    {
      "referenceKey": "a38d15a0-6092-4af1-a2cf-46ae84c4a886",
      "displayNumber": 1,
      "title": "示例文献条目（仅演示格式）",
      "authors": "示例作者",
      "publicationYear": 2020,
      "sourceName": "示例出处",
      "doi": null,
      "url": null,
      "locationLabel": "第 2 节"
    }
  ]
}
```

服务端不接受任意 revisionId 指定写入目标；目标始终由本人 resource 最新可写稿定位并与 baseRevisionId 校验。references 内稳定键只能是本文章已有条目或新 UUID，不能引用其他文章的内部文献记录。

### 6.3 公开与作者视图

公开 data 主要字段：`id,resourceType,section,revisionId,title,body,sourceFormat,formatPolicyId,references,owner,publishedAt,publicEditedAt,likeCount,bookmarkCount,liked,bookmarked,commentsEnabled,capabilities`。不返回锁定的候选稿；公开版本号并不表示专业认证级别。

作者编辑 data 主要字段：`id,resourceType,visibilityStatus,publishedRevisionId,latestRevision,lockVersion,quota,capabilities`。latestRevision 包含 revisionId/versionNo/state/body/references/formatPolicyId/sourceHash；不是把最新稿覆盖到公开 body 字段。审核详情读取冻结的目标版本和文献，不读取作者后来草稿。

文章版本命令的 receipt 记录 ARTICLE_REVISION 及实际产生/处理的 revision ID。若重放时已产生后续版本，则追加 resultRevision 并令它与兼容的平铺版本字段指向该原版本，latestRevision 仍如实返回当前最新稿，staleResult=true 并关闭编辑/提交/撤回/修订能力，客户端提示刷新，不能用过期命令响应覆盖未保存输入。原版本已删除时返回404；同一可变草稿版本在后续保存后的重放可返回该版本当前安全状态。

评论公开 data：`id,resourceId,rootCommentId,replyToCommentId,revisionId,author,body,displayState,createdAt,editedAt,replyCount,capabilities`；占位 body=null，displayState=DELETED/HIDDEN，无私有正文和旧版摘要。

### 6.4 错误与请求边界

| HTTP / code | 情况 |
|---|---|
| 400 / VALIDATION_ERROR | 缺字段、字符超限、文献映射/协议无效、非法层级或排序、同帖但跨串回复 |
| 401 / UNAUTHENTICATED | 未登录、禁用、旧凭据失效 |
| 403 / FORBIDDEN | 已鉴权但无明确操作权限；不得借错误消息暴露私有对象 |
| 404 / NOT_FOUND | 公开内容不可见或请求他人私有稿/版本/额度记录 |
| 409 / STATE_CONFLICT | 版本过期、并发任务决策、同键不同载荷、负责人尚未交接、分区只读、治理隐藏阻止发布 |
| 413 / PAYLOAD_TOO_LARGE | 实际请求体字节数超过上限，不返回堆栈 |
| 429 / RATE_LIMITED | 返回 Retry-After；前端保留输入并展示等待提示 |

错误 envelope 保持现有形状，字段定位错误先使用受控 message（不得含私有正文）；未来若增加结构化 errors，需独立兼容更新，不悄悄改变现有 data=null 契约。Spring 默认未知字段拒绝保留；浏览器不能提交 reviewerId、ownerId、publishedRevisionId、审核状态或任意额度。

## 7. 文件职责与验收矩阵

拟新增后端 `community/api`、`community/application`、`community/infrastructure`，分别容纳 DTO/控制器、分区/成员/讨论/评论/文献/额度服务和明确 SQL 仓储；不把全部写入继续堆入 PersonalCenterService。公共正文选择与互动规则抽为可复用服务，旧控制器仅代理兼容。格式政策、限频/幂等与字符计数分别有独立职责。

前端新增 `modules/community` 的列表、分区、详情/评论、讨论编辑和成员入口；既有文章编辑器扩展为动态额度、版本锁与文献表单。通用资源页识别社区资源并复用同一渲染组件，纯文本历史和 Markdown 新稿按 sourceFormat 区分。管理入口分别保护分区与额度权限。

| 验收组 | 必验行为与证据 |
|---|---|
| MIG-01 | 空库 V1→新版本、带 V6 旧账号/文章/评论/收藏升级；ID、正文哈希、隐藏/删除状态及引用关系保留；规范化冲突阻断而非静默修数据 |
| SEC-01 | 一级/二级及父级锁、同父重名、加入退出幂等、禁用后解禁不恢复加入；负责人不能跨区置顶/下架/改名 |
| POST-01 | 普通用户讨论和投稿资格隔离；直接发布有 DIRECT 无假专业认证；冻结版本不可原位改、两标签旧版本写入 409 |
| ART-01 | 旧公开版与新稿隔离；文献冻结、重排/删除不串引；标题/正文中文与表情计数一致，正文缩进不丢失 |
| QUOTA-01 | 默认/例外额度、新旧 API 一致、跨文章借用失败、禁止自批、并发同意/拒绝只有一个生效；审批不发布文章、不解除治理隐藏 |
| COM-01 | 同帖同串核验，跨帖/跨串拒绝；根/回复分页；删除根保留安全占位与已有回复；候选/隐藏正文不能从旧接口获取 |
| SAFE-01 | Markdown 白名单、协议及输出安全、代码仅文本、纯文本评论、无图片请求；源码不同但渲染相同仍有差异；文献 URL 不触发抓取 |
| CON-01 | 发布/归档、加入/禁用、编辑/提交、审批/下架的真实并发；版本、公开指针、文献、receipt、审计/通知原子；死锁重试不产生重复版本 |
| API-01 | 幂等键同载荷重试无重复，不同载荷 409；CORS 允许幂等头；真实超限 413、限频429且 Retry-After 可读；输入保留，无半份任务 |
| REG-01 | 原账号权限、个人中心、文章审核、知识来源有效性、收藏/历史、安全占位回归；详情只累计一次浏览 |
| UI-01 | 375/1440 宽度检查列表、两层评论、长文编辑、文献表单与额度申请；加载/空/错/只读状态和键盘操作可用 |

实现时使用真实 MySQL 进行约束/事务/迁移测试，前端组件测试与构建，浏览器走注册→讨论→评论→文章草稿/文献→扩容审批→文章审核→公开阅读→修订的实际接口。长文额度、限频和未来生命周期用可控 Clock/边界夹具验证，不生成虚假的医学正确性结论。

本文件仅作数据库和接口设计自查；执行计划应将上述组映射到具体迁移、服务、组件和测试任务，再进入应用实现。当前设计检查不等于 SQL 已执行、依赖已验证或功能已通过。
