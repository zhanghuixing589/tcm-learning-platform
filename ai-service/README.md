# 当前阶段

独立 Python3.11 / FastAPI 服务，依赖锁定在 `requirements.lock`。已实现配置验证、内部凭据校验、traceId、健康与能力状态。

```powershell
.\.venv\Scripts\python.exe -m pytest -q
.\.venv\Scripts\python.exe -m uvicorn app.main:create_app --factory --host 127.0.0.1 --port 8000
```

必须配置至少32字符的 `AI_INTERNAL_TOKEN`。内部请求带 `X-Internal-Token`；内部端点不对浏览器公开。未配置模型密钥时健康正常、生成能力明确不可用；即使配置密钥，第一阶段也不声称生成能力已实现。解析、检索、索引和生成按后续阶段实施。
