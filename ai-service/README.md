# 当前阶段

独立 Python3.11 / FastAPI 服务，依赖锁定在 `requirements.lock`。已实现配置验证、内部凭据校验、traceId、健康、能力状态及第二阶段确定性文档解析。

```powershell
.\.venv\Scripts\python.exe -m pytest -q
.\.venv\Scripts\python.exe -m uvicorn app.main:create_app --factory --host 127.0.0.1 --port 8000
```

必须配置至少32字符的 `AI_INTERNAL_TOKEN`。内部请求带 `X-Internal-Token`；内部端点不对浏览器公开。未配置模型密钥时健康正常、生成能力明确不可用；即使配置密钥，也不声称生成能力已实现。检索、索引和生成在第四阶段实施。

`POST /internal/v1/parse` 接收 Java 受控读取后的 `{fileName,contentBase64}`，返回统一响应内 `{parserName,segments:[{segmentNo,pageLabel,headingPath,body}]}`。不接收文件路径，不抓取URL，不连接业务库，不执行OCR或模型抽取。TXT必须UTF-8；DOCX用安全XML读取正文和标题；PDF必须有文本层，页码来自实际页面。TXT/DOCX只有段落定位，绝不伪造页码。

限制：原文件非空且不超过10MB，流式HTTP请求最多14MB，DOCX最多2000项/32MB解压/200倍压缩比，PDF最多500页并限制解压及解析工作量。所有格式最多200万字符/1万片段，同时最多2个解析线程。坏文档返回脱敏的 `DOCUMENT_INVALID`，请求字段错误返回 `VALIDATION_ERROR`；内部鉴权优先于解析请求体。Java负责创建不可变解析批次、90秒任务租约、45秒完整HTTP响应期限和runToken条件回写。失败尝试保留在Java任务历史中。

开发验证使用真实生成的DOCX和有文本层PDF，覆盖尺寸、坏格式、解压炸弹、加密/空PDF、路径拒绝及鉴权。命令同上，无需模型密钥或付费服务。
