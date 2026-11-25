# 开发依据与发现

> 历史快照说明（2026-11-24）：下文按早期设计/开发时间追加，其中“无工程”等描述仅对应当时状态，不代表当前仓库。当前已有 backend、frontend、ai-service 工程；当前任务与模块入口以 DEVELOPMENT_STATE.md、MODULE_INDEX.md 为准，历史记录保留用于追溯。

- 仓库当前无 pom.xml / package.json / Python 应用入口，69表、149外键、95项功能均为设计基线。
- `sys_users` 使用 BCrypt、ACTIVE/DISABLED、credential_version；`auth_refresh_tokens` 保存 token_hash / family_key / expires_at / revoked_at / replaced_by_id。
- 注册默认 LEARNER。CONTRIBUTOR 可申请，ADMIN 审批；REVIEWER 与 ADMIN 仅显式授权。ADMIN 不自动具备专业审核权限。
- 角色申请禁止本人审核。私有申请接口仅按当前用户查询。
- 本机有 JDK17、JDK21、Maven3.9.9、Node22.22.3、MySQL8.0。
- 官方兼容依据：Spring Boot3.5支持Java17（https://docs.spring.io/spring-boot/3.5/system-requirements.html）；Vite支持Node22.12+（https://vite.dev/guide/）；FastAPI配置参考 https://fastapi.tiangolo.com/advanced/settings/ 。
- Element Plus按需加载依据：https://element-plus.org/en-US/guide/quickstart.html；组件导入由unplugin-vue-components resolver处理，不全量注册。
- 新增身份管理接口写sys_audit_logs，记录操作者、traceId、动作和对象，不写密码、令牌或完整敏感请求。
- 业务数据库历史设计报告保留；当前应用状态由第一阶段验收文档维护，避免把历史DESIGN_ONLY_NOT_IMPLEMENTED误读成当前状态。
# 2026-11-19 第二阶段核对

- PDFBox 使用官方当前 3.0.8 依赖声明（https://pdfbox.apache.org/3.0/getting-started.html），通过 Maven 实际解析/构建验证；不采用未核验的旧版本建议。
- 全局文件 SHA256 去重仍须记录每个实际上传者：V5 增加 file_upload_grants，避免第二个上传相同文件的用户无法合法关联或读取。
- V5 已被 RED 测试正常迁移，后续不原位修改。V6 独立保存每位上传者的文件名，避免同内容去重时泄露首位私有上传者的文件名。

- main 基线 b87376bc 已整合昨日工作。既有 content_resources/content_revisions、source_documents、证据、审核、药材方剂、任务等关系表可复用。
- knowledge_entries 与 knowledge_aliases 为正式检索名，需要按版本暂存候选名称/别名，避免修订草稿覆盖正式版本。
- 来源状态与解析状态独立，审核冻结原稿；新解析批次不覆盖旧来源片段。
- 权限已具备 contribution:submit、knowledge:review、system:job:manage；管理员默认没有专业审核权。
- 不在本阶段接入实际 RAG/向量/图谱；发布产生可观察的待处理索引任务。
# 2026-11-20 第三阶段第一批

- 当前 main 基线为 9341620d；工作区隔离开发，不重置或清空原开发库。
- 第一批设计复用共享内容/版本、文章审核和互动，新增11表、扩展3社区表；迁移拟V7/V8 Java＋V9 SQL，NFKC/哈希/回复回填不依赖运行中的业务Bean。
- 已核对旧文章前后端均固定10万正文限制，旧ReviewService审核可清除停用原因；新实现须统一额度并重核治理状态，防止旧入口绕过和旧任务复公开。
- 原始设计中的工程限额初值与用户确认的业务阈值分开记录，医学判断不交由模型；第二三批能力仍明确不开放。

- 源快照哈希契约：文章/讨论为按键排序的JSON(body,references,sectionId,sourceFormat,title)，正文仅哈希时统一LF，文献按referenceKey及对象键排序且保留null；评论哈希为LF正文UTF-8。旧正文原文不改写。
- MySQL涉及AUTO_INCREMENT id的CHECK可能受限制，Task1将实际验证并以服务校验补足不可用约束；不把跨行层级校验假装成SQL CHECK。

- Markdown实现参考已查官方源：commonmark-java发布/CHANGELOG显示0.32.0（2026-11-01），https://github.com/commonmark/commonmark-java；客户端参考markdown-it官方docs/safety.md与DOMPurify README。实施时锁版本并实际解析依赖，不用过时记忆选版本。DOMPurify应显式白名单，避免钩子改写共享allowedTags状态。

- 已在新隔离MySQL的随机探针schema实际确认ERROR3818：CHECK不能引用AUTO_INCREMENT列。已修正详细设计，将parent_id<>id明确归服务锁后验证，FK/枚举CHECK仍保留；探针schema已按精确名称清理。

- 明确引用重排命令：先保存DRAFT，再以baseRevisionId/lockVersion和稳定键排列让服务器原子改正文与文献编号；避免同时传已编辑正文/新顺序时猜测编号语义。普通保存不暗改现有编号，删除被引键不得用新键复占原编号造成静默串引。对应Task2纯重排辅助、Task4服务及Task5编辑器需共同覆盖。

- 幂等键精确定义为非空格可见ASCII0x21—0x7E、长度8—128，禁止trim后接收。这样V9已有ascii_bin的PAD SPACE不会混淆合法键；无需修改已在新隔离库执行的SQL迁移。Task2需覆盖空格/控制字符/非ASCII拒绝和大小写不同键独立。

- 写入门禁的receipt需原子reserve/upsert后锁存在行，避免空索引SELECT FOR UPDATE造成跨账号gap-lock死锁；允许同事务未提交RESERVED/0/102占位，成功前更新实际结果、失败全回滚。审批需门禁最初一次按序锁所有参与账号，不能先锁actor后倒序补锁作者。

- Task3/4整合注意：公共资源读取/互动应抽为独立content服务，避免PersonalCenterService→CommunityArticleService→CommunityPostService→PersonalCenterService循环依赖。旧通用资源链接仍只读正式版并统一可见性。
- Task2恢复审查：guard须使用V9的request_sha256；配置体积不能把登录/政策维护锁死（维护路径保留系统硬限额）；任意深度Markdown需有界迭代遍历；nestedObjectNode canonicalhash需忽略对象键顺序。

- Task2集成API：guard.executeLocked(actor,participantIds,operation,key,canonicalHash,rate,authorize,Function<RateGate,Result>,safeRead)；callback按序锁领域后gate.policy()校验并gate.charge()一次，再修改/写事件。authorize和safeRead均须重核当前权限/可见性。Record Result(kind,id,status)。
- CommunityTextPolicy为注入读取门面，调用.current()取得不可变快照；快照.limit(name)、config()深拷贝。TextValidator的title/body/comment/normalizeName与SourceHash内容摘要已可复用。正常保存必须调用References.preventCitationSubstitution（包括保留键编号不变），重排单独用reorder(savedBody,savedRefs,orderedKeys,format)。

- Task4/5长文首次申请需注意：新文章尚无resourceId时不能申请文章级额度。优先采用显式标题级草稿身份创建（空正文仅DRAFT、提交仍需非空、正文仍受批准额度），供申请扩容，完整未保存正文不被自动截断/替换；可细化独立draft-identity命令。不要为了建立ID伪造或静默截断作者正文，也不默认放宽已有文章额度规则。

- Task4兼容整合细节：PostRepository.published(Grantee(viewerId,poster,moderator),resourceId)可无history读取正式社区详情，source(revision,true)供冻结文献投影；可装饰旧genericdetail返回sourceFormat/refs以避免前端跳转造成双浏览计数。V7空库可能无legacy-content，旧无section文章创建需安全惰性确保系统容器，不能接管冲突的用户分区。新增额度/文献/receipt后草稿删除须处理FK及审计，避免沿用物理删除导致409或重放串版本。

- Task3质量兼容修复：旧Identity.decide先锁申请人后经reviewerFK取管理员锁，与新status/roles升序双账号相反；改审批也升序双账号且READ_COMMITTED/锁后重核权限。通用点赞/收藏的viewer-first只适用POST/ARTICLE，KNOWLEDGE现有readyTarget会资源→ownerX，须按不可变resource_type保留旧知识域资源优先；预定位类型不可引入RR旧快照，interaction需READ_COMMITTED或事务外定位后锁内重核。论坛history账号优先，非社区history保持知识域兼容路径。

## Task5界面预检（Task4并行只读准备）
- 实际视觉变量位于frontend/src/app/styles.css，不存在styles/tokens.css；定位失败后改rg --files查证，未修改组件。沿用primary/surface/muted/line、Inter/PingFang SC/Microsoft YaHei，不引入新字体下载。
- 设计方向：社区列表以分区导航＋内容列表为主；阅读页正文为视觉中心，桌面约72ch，成员与规则为辅助栏，手机改纵向；编辑页先正文和文献，再展示额度与申请状态，管理页用表单/表格保持原后台习惯。沿用白底、深绿主色、浅灰绿分隔，不用新仪表盘装饰。
- 旧ArticleEditor会trim正文且写死100000；替换为Unicode码点计数和真实额度，保留contextVersion/latestRequest、离开确认。首次长文的私有ID建立后不能assign空正文覆盖输入，也不能把未存全文标为已保存。
- 旧ResourceView只纯文本，社区详情需使用服务器正式版元数据与白名单渲染；generic详情不得为补元数据额外请求community detail重复记history。ReviewView也需按冻结文章版本显示文献，勿读取作者当前草稿。
- Portal和Admin入口按真实权限增加，Home移除社区“尚未开放”；原account/posts路由需接讨论编辑/作者详情，不能继续误入只读文章页。
- Task4规格复核发现：版本命令receipt仅存资源ID导致旧键重试返回后来的新版本；新UUID文献归属的跨文章COUNT无锁存在并发双占用；空库管理员可占用顶层历史内容保留名导致首次长文身份建立失败。均要求先补实际回归再修复。技术方案采用实际revisionID回执＋resultRevision/staleResult，跨文章文献写在领域锁之后取政策head行串行锁，避免新增迁移并明确吞吐代价。
- Task4质量审查发现Markdown重排可撞快捷引用链接定义并改变引用语义，要求重解析比对预期引用位置/顺序/编号且异常回滚。另核对旧设计的公开分区移动禁令会封死历史未分类公开文章的修订投稿；将其收窄为系统LEGACY占位的一次正式归类例外，只在普通文章批准时切换公开正文/分区，正式分区之间仍禁止自行移动。已向实现者与规格复核者明确，保留迁移和首次发布时间。
- 2026-11-23 恢复时检查出两个预检问题：测试入口仅检查DB状态文件存在导致重启后连接拒绝，已改为调用幂等启动探活并保留原数据目录；预检将讨论详情路径写成/community/posts，实际控制器为/posts，前端实现者已按真实接口纠正，运行预检同步修订，不改服务器兼容路由。

- 最终发布标记只在文章批准事务变为MANUAL_REVIEW，提交/撤回/拒绝保持NONE；迁移前待审LEGACY获批后更新，历史公开LEGACY不变。真实InnoDB1213、并发暂停及浏览器跨串400/输入保留/纠正201已补实际证据。完整阶段三治理仍在后续批次。
