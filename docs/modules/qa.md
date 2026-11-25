# 检索与问答

状态：待实现；qa 包无业务实现、无专属页面。当前只有能力不可用声明和内部资料解析，不存在可验收的 RAG 问答链路。

## 既有设计与数据

按 `docs/05-开发顺序与验收.md` 第四阶段、`contracts/README.md` 问答/内部服务约定及 `database/检索投影设计.md` 分别加载所需章节。架构定义 Java 负责会话权限及证据有效性，Python 负责检索/生成；MySQL 为事实来源，Chroma/Neo4j 为可重建投影。

V1 预建 qa_sessions、qa_messages、qa_runs、qa_retrieval_hits、qa_citations、qa_feedback、qa_saved_answers。`/qa/sessions`、消息/stream/stop 及 Python retrieve/generate-stream 仍是计划，不能由契约推定已实现。

## 计划中的关键规则

私有会话校验归属、消息幂等键、引用绑定版本/原文片段；生成前重新验证公开版本和来源。SSE 事件及终态按契约执行，停止/断线保留明确状态；不编造来源 ID，也不混用嵌入维度/索引代次。

## 后续验证入口

没有问答专项测试；test_health.py 只验证当前内部健康/不可用能力，不证明检索质量。实施时需要权限、幂等/停止、旧版本/失效证据、无依据拒答、流终态及同输入实验，新增测试再写入索引/策略。`test-module -Module qa` 的现有基线回归会保留专项覆盖缺口。
