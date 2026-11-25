# 个性化推荐

状态：待实现；recommendation 目录为空包，无专属前端和专项测试。依据：docs/05 第六阶段、docs/04 对应功能、contracts 推荐路由及 docs/03 相关规则。

## 既有数据和计划流程

user_tag_preferences、user_interest_scores 保存偏好/画像；recommendation_strategies、recommendation_requests、recommendation_items 保存策略与推荐结果；recommendation_feedback、behavior_events 保存反馈和事件。

计划为行为收集 → 画像更新 → 推荐请求及结果快照 → 真实可见曝光 → 点击/反馈归因。`/recommendations`、`/recommendation-items/{id}/exposures` 和 clicks 当前仅为设计契约。推荐过滤需尊重公共内容与来源有效性，不因存在资源 ID 就推荐不可见内容。

## 约束与后续验证

曝光必须实际可见，点击校验归属并幂等；策略/结果记录应支持解释和复核，不能只输出无依据分值。新用户/缺少行为时的具体策略需按既有方案实施并注明确认状态，不在本工作流中新增算法承诺。

实施后补接口、页面、权限/归属、重复事件、无效资源过滤及冷启动测试，并同步 MODULE_INDEX 和 Test-Policy。当前 `test-module -Module recommendation` 只回归基线并提示专项缺口，不代表推荐质量或完整性已验证。
