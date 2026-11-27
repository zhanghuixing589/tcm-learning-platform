"""Lease-authenticated streaming input and bounded binary result bundles."""
import hashlib
from pathlib import Path
import re
import tempfile
from urllib.parse import urlsplit
import anyio
import httpx
from fastapi import Depends, Request
from fastapi.responses import StreamingResponse
from pydantic import BaseModel, ConfigDict, Field
from starlette.background import BackgroundTask
from app.documents import DocumentInvalid
from app.processing import process_file, ProcessingLimits


class ProcessInput(BaseModel):
    model_config=ConfigDict(extra='forbid')
    jobId: str=Field(pattern=r'^[A-Za-z0-9_-]{1,64}$')
    runToken: str=Field(min_length=1,max_length=200,pattern=r'^[A-Za-z0-9_-]+$')
    fileName: str=Field(min_length=1,max_length=255)


async def download_input(config,job,token,path,transport=None):
    base=config.backend_url.rstrip('/')
    ref=urlsplit(base)
    if ref.scheme not in ('http','https') or not ref.hostname or ref.username or ref.password or ref.query or ref.fragment:
        raise DocumentInvalid('处理服务输入配置无效')
    url=base+'/internal/v1/source-inputs/'+job
    headers={'X-Internal-Token':config.internal_token.get_secret_value(),'X-Job-Run-Token':token}
    total=0; digest=hashlib.sha256()
    try:
        async with httpx.AsyncClient(follow_redirects=False,trust_env=False,timeout=httpx.Timeout(config.process_download_timeout_seconds),transport=transport) as client:
            async with client.stream('GET',url,headers=headers) as response:
                if response.status_code!=200: raise DocumentInvalid('处理输入不可用或任务租约已失效')
                declared=response.headers.get('content-length')
                if declared and (not declared.isdecimal() or int(declared)>config.process_max_bytes):
                    raise DocumentInvalid('处理输入超过大小限制')
                with Path(path).open('wb') as output:
                    async for chunk in response.aiter_bytes(64*1024):
                        total+=len(chunk)
                        if total>config.process_max_bytes: raise DocumentInvalid('处理输入超过大小限制')
                        output.write(chunk); digest.update(chunk)
                if not total or (declared and total!=int(declared)):
                    raise DocumentInvalid('处理输入不完整')
                expected=response.headers.get('x-content-sha256')
                if expected and (not re.fullmatch(r'[a-fA-F0-9]{64}',expected) or digest.hexdigest()!=expected.lower()):
                    raise DocumentInvalid('处理输入完整性校验失败')
    except httpx.HTTPError as error:
        raise DocumentInvalid('处理输入读取失败') from error
    return {'bytes':total,'sha256':digest.hexdigest()}


def install_processing(application,authorize,config):
    workers=anyio.CapacityLimiter(1)
    @application.post('/internal/v2/process',dependencies=[Depends(authorize)])
    async def process(input: ProcessInput,request: Request):
        # Admission precedes spooling, so concurrent 50MiB jobs cannot fill disk.
        borrower=object()
        await workers.acquire_on_behalf_of(borrower)
        task=None; released=False
        async def cleanup():
            nonlocal released
            if released:return
            released=True
            with anyio.CancelScope(shield=True):
                try:
                    if task is not None: await anyio.to_thread.run_sync(task.cleanup)
                finally: workers.release_on_behalf_of(borrower)
        try:
            task=tempfile.TemporaryDirectory(prefix='tcm-process-')
            root=Path(task.name)
            suffix=Path(input.fileName).suffix.lower()
            if suffix not in ('.epub','.pdf','.doc','.docx','.txt'):
                raise DocumentInvalid('不支持的资料格式')
            source=root/('input'+suffix)
            await download_input(config,input.jobId,input.runToken,source)
            limits=ProcessingLimits(raw_bytes=config.process_max_bytes,expanded_bytes=config.process_max_expanded,
                                    zip_items=config.process_max_zip_items,ratio=config.process_max_ratio,
                                    timeout=config.process_timeout_seconds)
            bundle=await anyio.to_thread.run_sync(lambda:process_file(source,input.fileName,root/'output',limits=limits))
            async def chunks():
                try:
                    async with await anyio.open_file(bundle,'rb') as stream:
                        while chunk:=await stream.read(64*1024): yield chunk
                finally: await cleanup()
            # Keep the admission slot until streaming and task cleanup finish.
            return StreamingResponse(chunks(),media_type='application/zip',
                                     headers={'Content-Disposition':'attachment; filename="result.zip"','Content-Length':str(bundle.stat().st_size)},
                                     background=BackgroundTask(cleanup))
        except BaseException:
            await cleanup(); raise
