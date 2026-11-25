# 学习、测验与错题

状态：待实现；learning 目录为空包，无专属前端或专项测试。

## 既有方案与数据

详细业务和字段分别查 `docs/04-功能与数据库映射.md` 的学习测验项、`docs/03-数据库关系与业务规则.md`、`docs/02-数据库字段字典.md`；开发按 docs/05 第五阶段。只加载当前专题/试卷/错题相关章节，不复制69表字典。

专题/卡片：learning_topics、topic_profiles、learning_cards、topic_cards、card_knowledge_links；题库：question_profiles、question_options、question_knowledge_links；试卷：learning_assessments、assessment_questions；学习记录：user_enrollments、user_card_progress；作答和错题：quiz_attempts、quiz_attempt_items、quiz_attempt_answers、user_wrong_questions。

## 计划流程与约束

专题报名固定目录版本 → 卡片完成 → 测验尝试/作答 → 服务端提交评分 → 错题复习形成新作答。题目更新不能覆盖历史成绩；重复提交不重复累计，只有本人且处于允许状态可写答案；不能信任前端评分。

`/topics`、`/enrollments`、`/assessments/{id}/attempts`、`/attempts/{id}/answers/{itemId}` 及错题等仅为 contracts 设计。实际 API/DTO、页面和测试在实施时补齐，不创建虚假占位通过项。

## 后续验收

覆盖他人试卷、固定版本快照、过期/重复提交、服务端评分、错题再练不覆盖历史，联动知识可见性。当前 `test-module -Module learning` 仅回归既有基线并报告缺少专项测试；实施后按消费者关系更新策略。
