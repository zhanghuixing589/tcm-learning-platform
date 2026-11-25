# 知识类型、目录与通用标签（2026-11-25）

本契约是本次新增/调整的实际接口，外部前缀 `/api/v1`。ID为正整数的字符串，统一响应沿用 ApiResponse，错误沿用400/401/403/404/409。原知识、来源、收藏与审核接口继续存在。

## 读取与筛选

| 方法/路径 | 输入 | 成功data |
|---|---|---|
| GET /knowledge/categories | 可选type，六个固定枚举 | 已启用且非历史归档的目录数组；type指定时仅该类型，未指定供编辑器读取全部；按type、sortOrder、id排序 |
| GET /knowledge/tags | 无类型限制 | 已启用通用标签数组，按name、id排序 |
| GET /knowledge | 原q/type/categoryId/page/pageSize，新增可选tagId | 原分页；q支持标题/规范名称/别名以及已启用标签名称；分类和tagId条件同时生效，正式可见性规则不变 |

知识类型固定 CONCEPT/HERB/FORMULA/EFFECT/SYMPTOM/MERIDIAN。使用categoryId必须同时提供其所属type；未知、停用、归档目录或类型不匹配400。tagId可不带type跨类型筛选；未知/停用标签400。关键词沿用LIKE字面转义及100字符限制，分页与最近更新排序不变。

目录返回 `{id,name,code,type,sortOrder,active,legacy,lockVersion}`；公开目录均active=true、legacy=false。标签返回 `{id,name,active,lockVersion}`。知识目录及详情新增categoryLegacy/categoryActive与tags数组，旧categoryId/categoryName保持原ID及原名，即使历史目录归档或管理员停用也不会导致正式知识消失。通用标签的停用不删除已发布版本的标签关联。

## 知识写入与版本

POST /knowledge、PATCH /knowledge/{id}/draft、批量导入的KnowledgeInput新增可选 `tagIds:string[]`，最多20个不同的已启用标签。创建省略表示无标签；更新省略表示保留已有标签，显式[]清空。原categoryId仍可为空；有值时必须已启用、非归档、属于当前type。非法输入400，批量导入整体原子拒绝，不部分写入。

目录与标签在草稿保存时通过共享行锁校验；提交、审核批准时重新检查有效性，停用后的待审稿不能直接发布，返回409并提示退回修订。管理员停用不使已发布知识、收藏或历史失效。修订复制原版本标签；目录仅继承仍启用的类型专属目录，旧归档/停用目录不再进入新稿，原版本不修改。审核回避、来源有效性、冻结与正式指针流程不变。

## 管理配置

新增权限 `knowledge:taxonomy:manage`，迁移仅分配给ADMIN，技术管理员不因此获得知识审核权限。所有管理路由均需登录并具有此权限，不以是否显示前端菜单代替鉴权。

| 方法/路径 | JSON输入 | 状态 |
|---|---|---|
| GET /admin/knowledge/categories | 无 | 200，含停用和历史归档目录 |
| POST /admin/knowledge/categories | {type,name,sortOrder,active} | 201 |
| PATCH /admin/knowledge/categories/{id} | {name,sortOrder,active,lockVersion} | 200，版本+1 |
| GET /admin/knowledge/tags | 无 | 200，含已停用标签 |
| POST /admin/knowledge/tags | {name,active} | 201 |
| PATCH /admin/knowledge/tags/{id} | {name,active,lockVersion} | 200，版本+1 |

目录名1–80字符，同类型名称唯一；标签名1–64字符，全局名称唯一；sortOrder为0–10000，越小越靠前；active为必填布尔；更新lockVersion为必填非负整数。目录code自动生成且固定，建立后所属类型不可改；历史归档目录只读，不能重新启用。无删除路由。

未授权403、记录不存在404、无效字段400、同名或旧版本冲突409。创建以数据库唯一约束防重复同名，客户端忙碌时禁止再次保存；没有新增Idempotency-Key必填要求。更新必须携带开始编辑时的版本，刷新列表不能静默重基未保存表单。写入均记录原审计表。

## 兼容与未开放能力

V10只追加列、约束、触发器、初始目录与权限。旧知识目录原ID/名称/排序/引用不变，标记为只读归档并停用，不自动重写既有修订的分类。其他scope分类和原content_tags/revision_tags及推荐相关外键保持不变。

旧链接仅categoryId无type不再是有效筛选；前端全部知识入口会清除此旧条件，直接调用API仍拒绝。旧未填写tagIds的客户端不会清空已关联标签。标签不绑定知识类型，未来推荐/RAG可继续复用原表与版本ID；当前推荐、索引执行、RAG检索/回答仍未实现，本次不宣称新增这些能力。
