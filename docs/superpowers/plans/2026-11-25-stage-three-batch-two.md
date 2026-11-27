# 第三阶段第二批：治理闭环分包计划

> 实施者按包串行推进，使用 executing-plans 核对检查点；不默认启动子代理。本文件是数据/API技术方案与实施拆分，不是已完成代码或验收报告。

**目标：** 在有限额度下逐包实现治理闭环，先举报、裁决和申诉，后开放普通用户自建分区。

**架构：** 复用 Java/Spring Boot、MySQL/Flyway、Vue/TypeScript、既有内容/评论版本与社区写入守卫。治理案件、决定和申诉独立记录，不把通用文章审核任务直接改成治理案件，不提前实现第三批专业授权及意见复用。

**基线：** 2026-11-25，main / 849710e2；本任务开始时工作区干净。业务依据为 [第三阶段设计](../specs/2026-11-20-stage-three-design.md) 第3、4、8节。用户已选择本轮只做规划，并确认上述顺序。

## 1. 额度与交付边界

- 本轮初次查询：5小时已用78%，周已用44%；本轮后续查询已用79%。均为账户共享快照，不是本聊天专属额度，不能换算成固定token或工时。
- 本轮预算以78%为起点，拟最多消耗10个百分点；到88%前完成交接，保留约12%。工具不能设置计费硬上限，单次响应和其他聊天仍会消耗共享额度，因此不得承诺绝对不触顶。
- 文档完成后结束本轮，不自动开始开发。未来每次实施前重新读取两窗口额度；不能以重置后的百分比计算前一窗口消耗，也不自动使用重置券或付费额度。
- 实施预算必须含上下文、实现、验证、失败修复和交接；每包开工、数据/接口定稿、验证前检查余量。余量不足就停在文档或代码检查点，不为省额度删掉权限/迁移测试，不留下已开放但未验收的写入口。
- 本文件中的表名、路由与类名为技术提案；执行该包时经契约审查定稿。当前不更改 contracts、业务代码、数据库、权限种子及运行服务。

## 2. 当前代码证据与复用边界

| 路径 / 符号 | 本次核对结论 |
|---|---|
| backend/src/main/resources/db/migration/V1__initial_schema.sql / community_reports | resource_id/comment_id二选一，只有处理状态，无被举报版本字段；旧记录不能用当前公开版猜填 |
| 同文件 / community_moderation_actions | 已有动作历史表，缺案件、决定链和申诉关联；不能据建表认定裁决服务已实现 |
| 同文件 / user_notifications | (user_id,event_key)唯一，可复用站内通知幂等；需要补读取、归属、已读接口和页面 |
| community/application/CommunityQuotaService.java | 已写入额度类通知；新通知中心须兼容此类记录 |
| community/application/CommunityWriteGuard.java / executeLocked、lockAccounts | 已有账号升序锁、事务、请求摘要及回执；重放仍需重新鉴权，新治理命令按既有方式接入 |
| community/infrastructure/CommunitySectionRepository.java / writable、owner_id | 已区分归档和无主状态；新建区资格、观察期及定时转态未据此视为完成 |
| community/api/CommunityPolicyController.java | 当前是文本长度/频率政策，不等于风险主题、例外和治理政策版本 |
| source/SourceService.java / Input、validate | 当前输入只有文件/链接路径，虽然source_documents已有origin_revision_id外键，文章转来源路径仍需实现 |
| content/ReviewService.java、docs/modules/content.md | 通用审核已区分knowledge:review/community:moderate并禁止自审；不赋予治理人医学资质 |
| backend/src/main/java/db/migration/V7__CommunityFoundation.java、V8__CommunityVersionSnapshots.java | V7/V8是Java迁移；加上SQL V9/V10才是当前迁移链，不能只看SQL文件序号 |

## 3. 开发包、依赖与开放门槛

2026-11-25用户追加授权：先实施来源模块EPUB格式扩展，再开展G1。该增量独立于第二批治理，不将其计作G1完成；范围/测试/部署边界见[EPUB任务记录](../../workflow/tasks/2026-11-25-epub.md)。本计划原“只规划”描述保留为该阶段历史，后续开发授权以本段及任务记录为准。

| 包 | 内容和可交付结果 | 依赖 / 开放限制 |
|---|---|---|
| G1 | 固定版本举报、我的举报、治理待办及案件底座 | 首个实施包；单独验收时仅作为举报受理，不称完整治理闭环 |
| G2a | 不可变决定、处置投影、站内通知读取及已读 | G1；申诉接口未就绪前不开放正式处罚写入口 |
| G2b | 正式申诉、逾期受理申请、回避、两轮链与纠错留痕 | G2a；合并验收G2a/G2b后开放裁决闭环 |
| G3 | 风险政策版本、例外快照、规则命中与提交分流 | G2b；明确风险冻结新版，模糊匹配只提示/进入队列 |
| G4 | 建区资格、敏感主题审核、观察期与延期/归档 | G3；先验收再开放普通二级分区自建 |
| G5 | 接管、交接、无主暂停、静默归档、恢复与人工合并 | G4；不得在G5缺失时把分区生命周期称作完整交付 |
| G6 | 固定文章版本转来源、独立来源审核与追溯 | G2b后可设计，默认排在G5后；沿用来源/知识独立准入 |

G1—G6均完成相关验收后才称“第二批完成”。专业授权、背对背专业意见、组合审核、块级复用属于第三批；第二批如遇所需专业能力缺失，案件保持待处理，不以普通治理裁决代替。

## 4. 数据与一致性方案

### 4.1 G1—G2：版本举报、案件、决定、申诉

- 扩展community_reports：resource_revision_id或comment_revision_id、case_id、lock_version、snapshot_status。与目标身份一起校验且版本二选一；新举报必须绑定用户实际所见且提交时仍有读取权限的公开版本，不接受任意历史版本ID。旧记录标记LEGACY_UNBOUND，保留原字段，不猜测版本；不得直接据其生成固定版本裁决。
- 新community_governance_cases：target_kind、目标ID、固定版本ID、状态、当前决定ID、lock_version、创建时间。举报通过case_id关联；同目标同版本举报归并，不同版本分别建案，通过内容身份关联展示，不另设主案件。案件是决定/申诉上限的稳定归属，同版本后续决定不新建案件重置次数；版本间重复事实人工核查，不自动重复处罚，资源级处置不因新版本解除。确认细节见第8节。
- 新community_governance_decisions：case_id、sequence_no、previous_decision_id、target_revision_id、actor_id、action、reason_code、reason_text、policy_snapshot、effective_at、notification_id。唯一(case_id,sequence_no)，决定不可原位改；撤销/调整追加记录。旧community_moderation_actions追加case_id/decision_id关联作为处置历史，不承担申诉计数。
- 新community_appeals：case_id、decision_id、round_no、applicant_id、original_handler_id、status、reason、submitted_at、reviewer_id、resolution、result_decision_id、lock_version；唯一decision_id和(case_id,round_no)，round_no仅1/2。逾期申请另存community_appeal_intake_requests，不占正式轮次；只有受理后才创建正式申诉。
- 新community_governance_events：案件/决定/申诉事件及操作者、幂等键、前后状态、原因；纠错重审显式事件链，不能通过新增决定暗中重置常规申诉轮次。
- 案件技术状态拟为OPEN、WAITING_PROFESSIONAL、DECIDED、APPEAL_PENDING、CLOSED；正式申诉状态拟为PENDING、PROCESSING、UPHELD、REVOKED、ADJUSTED。状态变化保存事件；提醒不自动裁决，无合格人员不恢复公开。
- 处置投影与案件审理状态分开。决定绑定固定版本；隐藏整条内容须显式作用域与理由，不因旧版被举报就自动处罚所有新版本，也不因作者编辑自动解除已生效下架。
- 决定、处置、审计、通知、幂等回执在同一事务提交；通知事件键可采用decision:{id}:author、decision:{id}:reporter:{userId}，仅写结果摘要与站内入口，不嵌入私密证据/候选正文。
- 复用user_notifications，不新建平行消息系统。通知只允许接收者读/标已读；幂等重复不改变通知时间或重算申诉窗口。关联链接点击时重新鉴权。

### 4.2 G3：风险规则独立于文本额度

- 新community_governance_policies（不可变规则JSON、版本、启用人/时点）、community_governance_policy_heads（唯一当前指针、lock_version）、community_rule_hits（目标版本、规则ID、规则/例外快照、动作、命中依据和处理状态）。
- 不覆写community_text_policies。统一发布检查调用独立风险服务，覆盖讨论、评论、文章提交/修订、分区申请；明确风险和模糊命中分流遵循原spec。
- 第一期采用可解释规则与人工判断，不新增模型服务、外部API调用或自动医学裁决。规则配置者与裁决权限按现有权限边界独立核验。
- 候选版只向作者及具任务权限的人开放。规则导致的暂存不写违规信誉；旧公开版本身有阻断风险时另起隐藏复核。

### 4.3 G4—G5：分区生命周期

- community_sections保留层级、owner_id、lifecycle、management_status，追加观察期起止、延期次数、暂停剩余时长、接管截止、政策版本/lock_version等字段。注册/公开帖子资格与每人一个观察期分区在账号锁下同事务核验，不能先查再插。
- 新community_section_applications：创建/改名/换大区申请、主题风险快照、审核任务与决定；高敏感申请不直接进入公开观察期。
- 新community_section_evaluations：统计截止时点、有效成员/注册时间/作者及帖子集合、规则版本、待处理举报关联、结果；保存明细或不可变JSON快照，不能只存当前计数。
- 新community_section_transfers：原/新负责人、接收同意、资格快照、管理员确认、状态、版本；接收人资格/成员身份/观察期上限在生效事务再次检查。
- 新community_section_redirects：旧/新分区ID、操作者、理由、迁移时间；拒绝循环跳转，保留帖子原归属与迁移记录，不按相似度自动合并。
- 时间事件复用可控Clock；定时处理按分区+事件类型+截止时点唯一键幂等。扫描后逐条短事务重读版本，竞争失败重算，不能旧快照覆盖新负责人或新帖子。静默计时使用讨论首次公开/文章首次审核公开的事件时间，编辑、评论、点赞不重置，后续删除或下架不倒退；刷帖保活另走治理。不得复用会随编辑变化的排序时间或查询当前有效帖最大时间代替此事件时间。
- 待处理举报暂停转正判断与活跃度不达标延期分开；无主暂停观察期并保存剩余时长。30天无新帖归档与14天接管截止分开计时，先通知后归档；归档仍允许举报、申诉、作者删除与治理。
- 禁用账号撤销成员/负责人关系与权限；解禁不自动恢复。须定位identity禁用调用链接入，不只在定时任务中处理。

### 4.4 G6：文章转来源

- 复用source_documents.origin_revision_id并新增转换幂等记录（转换人、文章资源及固定版本、来源ID、请求键）；保存被转换版本正文/文献快照，不能在读取时追随最新文章正文。
- 专用转换命令只接受可访问的已公开ARTICLE版本；来源对象进入既有独立提交/审核链，不自动可用、不写知识正式指针、不触发RAG准入。来源解析/片段路径须适配冻结社区正文，不能强制假造上传文件。
- 接入前核对SourceService/SourceAccess与ReviewService的来源→资源→审核锁序；若与账号→分区→资源的社区锁序冲突，拆成冻结转换请求与幂等消费，禁止在持有来源锁后倒序取账号锁。
- 已确认作者普通删除原文章不自动停用已转换来源：来源保留固定版本，继续独立审核与管理。治理下架按第8节已确认的分流执行，不能只凭origin外键就宣称来源失效传播已解决。

### 4.5 迁移与锁要求

- 只追加迁移，实施时以实际最新版本分配序号（当前SQL至V10，V7/V8为Java迁移）；本方案不提前占用V11及以后序号。
- 新外键/唯一约束/索引与回填分步：先保留旧行、回填可证实关联、核对孤儿及二选一约束，再收紧新写入限制。迁移测试覆盖空库和V10升级，旧账号/正文/收藏/历史指纹不变。
- 案件列表索引(status,created_at,id)、申诉(status,submitted_at,id)、举报(reporter_id,created_at,id)、定时分区(lifecycle,截止时间,id)；分页稳定ID兜底。
- 账号升序→分区升序→资源升序→评论→案件→申诉/决定的固定锁序须与现有审核调用者审查后定稿；预读目标关系后锁内重查，不在锁内倒序补取账号。写入同时校验lockVersion，过期409；相同幂等键不同请求409，重放重新鉴权。

## 5. API提案与页面

公共前缀/api/v1；ID字符串、UTC时间、既有ApiResponse/分页/错误格式。以下均未实现，正式合同在每包实施前同步contracts与前端类型。

| 包 | 提议接口 | 权限/输入边界 |
|---|---|---|
| G1 | POST /community/reports；GET /me/community/reports；GET /me/community/reports/{id} | community:post；targetKind/targetId/observedRevisionId/reasonCode/description；提交带Idempotency-Key，不能借举报读取候选版 |
| G1 | GET /governance/cases；GET /governance/cases/{id} | community:moderate及任务范围；固定目标版、证据、规则快照，仅授权字段 |
| G2a | POST /governance/cases/{id}/decisions | lockVersion、action、reasonCode/reason、policyVersion；幂等、回避、当前状态及权限重查 |
| G2a | GET /me/notifications；PUT /me/notifications/{id}/read | 仅本人；read幂等，列表不暴露证据正文 |
| G2b | GET /me/governance/decisions/{id}；POST /me/governance/decisions/{id}/appeals；POST /me/governance/decisions/{id}/late-requests | 仅决定相关作者；服务器计算窗口/轮次，客户端不能指定roundNo |
| G2b | GET /governance/appeals；POST /governance/appeals/{id}/decisions；POST /governance/late-requests/{id}/decisions | 原处理人及利益冲突者回避；缺合格人员保持待处理 |
| G3 | GET/POST /admin/community/governance-policies；PUT /admin/community/governance-policy-head | 政策管理权限；只追加版本、乐观锁启用；公开接口仅返回允许披露的提示 |
| G4 | POST /community/section-applications；GET /me/community/section-applications；POST /governance/section-applications/{id}/decisions | 服务端资格与风险分流；敏感主题不直建；管理员审核不代表医学资质 |
| G5 | POST /community/sections/{id}/transfer-requests；POST /community/transfers/{id}/consent；POST /admin/community/transfers/{id}/decisions | 负责人申请、接收人本人同意、管理员确认分离 |
| G5 | POST /community/sections/{id}/restore-requests；POST /admin/community/sections/{id}/merge | 恢复需审核；合并人工确认、版本校验、跳转防环 |
| G6 | POST /community/articles/{id}/source-conversions | 固定revisionId，复核来源提交资格；请求幂等；返回新来源ID及待审核状态 |

已有membership、profile、pin、文章编辑及来源提交接口保留；新增能力字段由后端推导，前端按钮隐藏不能替代鉴权。治理/系统管理/来源提交的精确权限映射按既有种子与当前角色核对后进入合同，不从ADMIN名称推导授权。

页面：G1在帖子/评论增加举报表单和“我的举报”，G2新增治理工作台、决定/申诉详情和通知列表；G3规则管理与冻结提示；G4建区申请及观察期指标；G5交接/接管/归档/合并界面；G6文章转来源及来源追溯。复用现有DESIGN，不另做全站重构；加载失败、过期409、无权限、待处理和空状态各有真实反馈，切换身份/目标不接收旧请求回写。

## 6. 文件职责与逐包执行方法

以下路径相对仓库根；“拟新增”不是当前已有符号。每包只定位直接调用者，不重读全仓。

| 包 | 后端拟新增/修改 | 前端与测试拟新增 |
|---|---|---|
| G1 | community/api/CommunityReportController.java、application/CommunityReportService.java、infrastructure/CommunityGovernanceRepository.java；复用CommunityWriteGuard | modules/community/ReportDialog.vue、MyReportsView.vue；CommunityReportIntegrationTest.java、对应组件测试 |
| G2a/b | community/api/CommunityGovernanceController.java、application/CommunityDecisionService.java、CommunityAppealService.java；公共通知组件按operations现有分层落位 | modules/community/governance/{CasesView,CaseView,AppealView}.vue、modules/account/NotificationsView.vue；CommunityGovernanceIntegrationTest、CommunityAppealIntegrationTest、NotificationIntegrationTest |
| G3 | application/CommunityRiskPolicyService.java、CommunityRiskEvaluator.java；修改CommunityPostService、CommunityCommentService、CommunityArticleService的提交路径 | GovernancePolicyView.vue、冻结提示组件；CommunityRiskPolicyIntegrationTest |
| G4/5 | 修改CommunitySectionService/Repository；拟增CommunitySectionLifecycleService、CommunitySectionScheduler、CommunityTransferService；接入identity禁用链 | SectionApplicationView.vue、SectionLifecyclePanel.vue、SectionTransferView.vue；CommunitySectionLifecycleIntegrationTest、CommunitySectionTransferIntegrationTest |
| G6 | source/SourceService、SourceAccess、SourceController及转换服务；核对SourceJobs/Parser/ReviewService消费者 | modules/knowledge/SourceView.vue、community文章详情；CommunitySourceConversionIntegrationTest及来源回归 |

所有community后端路径根为backend/src/main/java/cn/tcm/platform/community/，source同级；前端根frontend/src/；Java测试按backend/src/test/java/现有包布局落位。每包追加迁移并同步contracts、MODULE_INDEX、对应docs/modules和Test-Policy（若选择映射受影响）。不提前创建空类、空页面、空表。

每包执行检查点：

- [ ] 重新核对git状态、额度和本包入口；数据约束/状态/API审查完成后才写业务代码。
- [ ] 先补本包下节列出的行为用例，确认当前实现确实不满足；仅因编译失败不算复现业务问题。
- [ ] 实现最小纵向路径：迁移→服务事务/鉴权→DTO/API→消费者→界面；按检查点停，不跨包顺手扩展。
- [ ] 在根目录执行 `.\scripts\test-changed.ps1 -PlanOnly`，审查共享表与动态依赖后补齐覆盖，保留计划日志。
- [ ] 执行计划中必要测试；常规入口 `.\scripts\test-changed.ps1`。共享迁移/安全契约若选全套就保留，不按文件名减测；额度不足时延后实现或验收。
- [ ] 需真实链路的包按第7节做定向浏览器验收；服务复用scripts/dev.ps1，不默认开全量E2E或清库。
- [ ] 自审差异、文档和旧调用者；记录命令/退出码/失败修复/未验项；更新DEVELOPMENT_STATE。提交推送不由本规划自动触发。

## 7. 验收矩阵

| 包 | 必须通过的行为场景 |
|---|---|
| G1 | 帖子/评论版本二选一及归属；编辑后目标不漂移；伪造候选/他人私有版本拒绝；归档仍可举报；同键同请求重放、不同请求409；本人记录隔离；旧无版本举报保留且不伪装绑定 |
| G2a | 相反并发裁决仅一条生效；过期409；决定不可改；目标版本与处置范围一致；作者编辑不能自行恢复；处置/通知/事件同事务回滚；重复通知不重算时限；通知列表/已读越权拒绝 |
| G2b | 窗口前/边界/后；逾期申请单列；同决定一次、同案最多两轮；调整后窗口重开且轮次不清零；首轮维持/撤销终止常规申诉；原处理人/利益冲突回避；缺人待处理；超时提醒幂等且不自动恢复；纠错重审留独立链 |
| G3 | 明确风险暂存新版、保留安全旧版；旧版阻断另行隐藏；模糊命中不自动违规；规则/例外快照不随启用新规则改变；讨论/评论/文章/编辑/建区入口一致；撤回重提不绕过检查；候选正文/摘要/差异不公开 |
| G4 | 7天/3篇及一个观察期分区并发门槛；敏感主题审核；两级/同父重名；7天统计的5人/3老用户/3作者5帖精确快照；待处理举报暂缓与延期区分；首次延7天、再次失败归档；刷量另案处理，不追溯正常成员退出 |
| G5 | 禁用立即撤销成员及负责人、解禁不自动恢复；接收人同意/成员资格/管理员确认及上限重查；无主暂停剩余时长、接任恢复、14天接管归档；30天静默默认提前7天通知、配置3—7/14天及政策快照；仅讨论/文章首次公开重置，编辑/评论/点赞不重置，删除/下架不倒退；与观察期当前有效帖子统计分离；恢复审核；合并防环与旧入口；归档不阻断举报/申诉/删除 |
| G6 | 冻结原文章/文献版本；重复转换幂等；私有/候选/非文章目标拒绝；转换不自动通过来源/知识审核；来源不可用不能成为知识依据；原文章后续编辑不改已转换正文；原文治理联动按确认规则覆盖 |
| 全部 | 空库迁移与V10升级、旧账号/版本/收藏/历史保持；迁移失败可诊断；共享公开可见性/会话/审核/来源不退化；真实浏览器至少作者、举报人、两位治理处理人，覆盖权限与冲突提示；桌面/手机无溢出 |

时间场景用Clock推进，不真实等待7/14/30天。3工作日是内部目标，工作日历/人员配置就绪前不向用户承诺保证时限；提醒记录不等于专业判定。无须调用Python的纯治理包不主动启动AI服务，但自动计划选择的共享回归需评估而非随意删除。

## 8. 本轮逐题确认的业务细节

以下均已通过grill-me逐题确认，状态为已确定、待实施。既有已确认规则不重新提问；实施时发现新的业务歧义另行确认，例行技术细节沿项目约定处理。

1. **已确认（2026-11-25）：** “通知后7个自然日”按Asia/Shanghai计算，首次站内通知成功写入当天为第0天，第7天结束时截止；已读或重复通知不重算。例如11月25日通知，12月2日结束时截止。工程上存储第8天00:00对应的UTC排他截止点，提交时间严格小于该时刻才在窗口内，避免毫秒精度差异；不是固定168小时。
2. **同版本归并及跨版本分案均已确认（2026-11-25）：** 同目标同版本的处理中举报归入同案，各人的理由、材料和结果通知独立保留，不同理由作为案内审查项；结案后新证据走纠错重审，不通过新建案件重置申诉次数。不同版本各建正式案件，通过同一内容身份关联展示，不另建主案件；每案独立通知、决定和申诉计数，旧案申诉与纠错仍留旧案。V1申诉次数用完不影响V2新决定的申诉；新版本不解除旧案已生效的资源级处置。跨版本重复事实由治理人员关联核查并留痕，不能自动重复处罚或因新版本撤销原处置。
3. **已全部确认（2026-11-25）：** 作者普通删除不自动停用已转换来源，来源保存固定版本，继续受独立审核和来源管理约束。治理下架决定明确记录影响版本及对来源的动作：医学错误、隐私泄露等明确阻断风险涉及来源固定版本时，自动暂停该来源并创建复核任务，沿既有来源失效链处理相关知识；仅因分区/位置等原因下架且来源固定版本内容不受影响时，仅复核、暂不暂停。其他版本被下架不自动连带暂停。这里自动指执行治理人员明确记录的影响范围与处置决定，不是看到下架状态一律暂停；影响不确定时不推定安全，由治理人员明确范围与理由。恢复须复核并检查其他独立停用原因，原文恢复不自动恢复来源。
4. **归档预告及计时均已确认（2026-11-25）：** 连续30天无新帖归档，统一默认提前7天通知（满23天预告），普通成熟分区可配置3—7天，观察期分区和已有一次恢复记录的分区可配置14天（满16天预告）。参数与适用分类版本化，记录预计归档时点；归档前重查条件，有符合条件的新帖则取消本次归档、重新计时。此配置仅适用于30天无新帖归档，不改变观察期7天、首次延期7天及再不达标归档，也不延长无主14天接管期限；不同归档原因分开处理。用户对3天方案提出的申请延期/知晓操作为条件建议，本次采用7天默认，不增加延期申请状态机。计时仅由讨论首次公开、文章首次审核公开重置；编辑旧帖、评论、点赞不重置；帖子后来删除或下架不倒退计时，刷帖保活另走治理。该口径仅用于静默归档，观察期达标仍统计当前有效公开帖子。

## 9. 本轮证据与下一步

- 已完成：现状核对、包依赖、数据/API提案、兼容/安全/验收方案及第8节业务决策确认；仅文档规划。仅变更本计划、DECISIONS、DEVELOPMENT_STATE。
- 尚未执行：迁移、后端/前端/AI业务测试、真实业务浏览器验收、服务重启、提交和推送。不得据本文件声称任一包已实现。
- 本轮验证：test-changed.ps1 -PlanOnly退出0，文档变更未选择业务套件；git diff --check、计划相对链接及占位词检查通过。最终额度查询5小时已用85%（剩15%）、周已用45%（剩55%）；快照不能保证后续实时余量。
- 下一步：本轮只规划任务完成，结束交接。后续获明确开发指令并重新检查额度后，从G1固定版本举报开始，先定稿迁移/契约并补行为用例，不直接同时开展G2—G6。
