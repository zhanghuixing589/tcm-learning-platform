# 社区验收测试文章清理（2026-11-24）

- 用户要求：社区正常后仅保留两条测试数据，其余删除；使用grill-me，能由代码/数据核实的事项先自行核实。
- 基线：main 1cdbceb1；保留已有README/findings和未跟踪工作流文件，不提交、不推送，无业务源码/迁移/依赖变化。
- 核实：截图是“共144项”，不是144页。数据库全部144篇公开ARTICLE均匹配“仅用于浏览器验收”的正文标记；来源脚本为frontend/e2e/seed-personal-center.py（每次生成24篇分页夹具）。原库另有5篇文章草稿和知识资源，不属于本次144篇清理对象。
- 清理前再次执行runtime/probe-community-debug.cjs after，退出0，分区、列表、筛选、详情、发布编辑器及刷新加载正常，采集的社区请求200，无pageerror。此项不代表浏览器完整写入闭环。
- 保留原有资源78《如何核对一篇中医科普文章的来源》、79《阅读方剂资料时，先看哪些信息》，无需新增或改写正文。其他142篇均为上述标记夹具。
- 先核对information_schema外键与目标关联计数：142个文章版本/社区档案/版本档案/收藏，6条点赞、2条历史；没有命中知识、问答、审核或其他模块引用。预览runtime/community-cleanup-preview.tsv。
- 升级后当前原库已备份到ignored runtime/community-cleanup-before.sql，不输出、不提交备份内容。
- runtime/cleanup-community-fixtures.py在单个事务中执行精确目标清理；保留FK校验，锁定目标资源，用CHECK断言目标142、版本142、初始公开文章144及最终2。先清关联及档案，将待删除资源临时设DRAFT并断开公开指针，再删版本与资源；任何错误由客户端退出使未提交事务回滚。执行SQL存于runtime/community-cleanup-exact.sql。
- 执行退出0并提交：删除142篇资源、142条版本、142条收藏、6条点赞、2条历史及对应社区档案。结果runtime/community-cleanup-result.tsv；事务内核对账号计数/身份/密码/状态及非目标正文版本的聚合指纹保持一致。原有账号、文章草稿和知识数据保留。
- 浏览器验收：node runtime/probe-community-cleanup.cjs退出0，真实Edge登录demo_learner后读取社区，HTTP200、total=2、两条可见记录、“第1页，共2项”、上一页/下一页均禁用、无alert/pageerror。结果runtime/community-cleanup-browser.json，截图runtime/community-cleanup-after.png。
- 范围与审查：仅运行数据清理和恢复记录；外键未关闭，未修改分页总数逻辑或造假total，未删除账号和分区。未运行全量测试或全量E2E；原站点当前数据复验不能代替所有管理/文章写入功能验收。seed脚本仍用于独立验收，不在本任务改变测试套件。
