from pydantic import BaseModel, ConfigDict, Field
from fastapi.exceptions import RequestValidationError
from app.documents import DocumentInvalid, parse_document

class ParseInput(BaseModel):
    model_config = ConfigDict(extra='forbid')
    fileName: str = Field(min_length=1, max_length=255)
    contentBase64: str


def install_documents(application, authorize, result):
    import anyio
    parse_workers = anyio.CapacityLimiter(2)
    @application.exception_handler(RequestValidationError)
    async def validation_error(request, error):
        from fastapi.responses import JSONResponse
        return JSONResponse(status_code=400, content={'code':'VALIDATION_ERROR','message':'请求内容不符合要求','data':None,'traceId':request.state.trace_id})

    @application.exception_handler(DocumentInvalid)
    async def document_error(request, error):
        from fastapi.responses import JSONResponse
        return JSONResponse(status_code=400, content={'code':'DOCUMENT_INVALID','message':str(error),'data':None,'traceId':request.state.trace_id})

    from fastapi import Depends, Request
    @application.post('/internal/v1/parse',dependencies=[Depends(authorize)])
    async def parse(input: ParseInput, request: Request):
        # CPU parsing runs in a bounded request worker, leaving the event loop free.
        return result(request, await anyio.to_thread.run_sync(parse_document,input.fileName,input.contentBase64,limiter=parse_workers))
