import re
import secrets
import uuid
from fastapi import Depends, FastAPI, Header, HTTPException, Request
from fastapi.responses import JSONResponse
from app.core.settings import Settings


def create_app(settings: Settings | None = None) -> FastAPI:
    config = settings or Settings()
    application = FastAPI(title="TCM internal AI service", version="0.1.0", docs_url=None, redoc_url=None, openapi_url=None)

    @application.middleware("http")
    async def trace(request: Request, call_next):
        candidate = request.headers.get("X-Trace-Id", "")
        request.state.trace_id = candidate if re.fullmatch(r"[A-Za-z0-9_-]{1,64}", candidate) else str(uuid.uuid4())
        response = await call_next(request)
        response.headers["X-Trace-Id"] = request.state.trace_id
        response.headers["Cache-Control"] = "no-store"
        return response

    @application.exception_handler(HTTPException)
    async def http_error(request: Request, error: HTTPException):
        return JSONResponse(status_code=error.status_code, content={"code": "UNAUTHENTICATED" if error.status_code == 401 else "HTTP_ERROR",
            "message": str(error.detail), "data": None, "traceId": request.state.trace_id})

    async def authorize(x_internal_token: str | None = Header(default=None)):
        if x_internal_token is None or not secrets.compare_digest(x_internal_token, config.internal_token.get_secret_value()):
            raise HTTPException(status_code=401, detail="内部凭据无效")

    def result(request: Request, data):
        return {"code": "OK", "message": "成功", "data": data, "traceId": request.state.trace_id}

    @application.get("/internal/v1/health/liveness", dependencies=[Depends(authorize)])
    @application.get("/internal/v1/health/readiness", dependencies=[Depends(authorize)])
    async def health(request: Request):
        return result(request, {"status": "UP", "service": "tcm-ai-service"})

    @application.get("/internal/v1/capabilities", dependencies=[Depends(authorize)])
    async def capabilities(request: Request):
        has_key = bool(config.model_api_key and config.model_api_key.get_secret_value())
        return result(request, {"generation": {"available": False, "code": "NOT_IMPLEMENTED" if has_key else "MODEL_NOT_CONFIGURED"}})

    return application
