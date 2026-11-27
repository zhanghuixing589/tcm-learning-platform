"""Host-side adapter for per-job, network-disabled Compose containers."""
import json
import hashlib
from pathlib import Path
import shutil
import subprocess
import tempfile
import threading
import uuid
import zipfile


class ConversionError(ValueError):
    pass


class IsolatedConverter:
    def __init__(self, timeout=120, compose=None):
        self.timeout=timeout
        self.compose=Path(compose) if compose else Path(__file__).resolve().parents[2]/'infra'/'compose.infra.yml'

    def convert(self, source, operation, output, page=None):
        source=Path(source).resolve(); output=Path(output).resolve()
        suffix=source.suffix.lower()
        if operation not in ('image','docx','pdf','preview','epub-preview'):
            raise ConversionError('ADAPTER_OPERATION_INVALID')
        if source.stat().st_size>(20 if operation=='image' else 50)*1024*1024:
            raise ConversionError('ADAPTER_INPUT_LIMIT')
        with tempfile.TemporaryDirectory(prefix='tcm-convert-') as task:
            task=Path(task); incoming=task/'input'; outgoing=task/'output'
            incoming.mkdir(); outgoing.mkdir()
            shutil.copyfile(source,incoming/('input'+suffix))
            # Dedicated bind dirs contain only this job, never storage roots.
            name='tcm-source-converter-'+uuid.uuid4().hex
            command=['docker','compose','-f',str(self.compose),'--profile','converter',
                     'run','--rm','--no-deps','-T','--name',name,
                     '--volume',f'{incoming}:/input:ro',
                     'converter',operation,suffix]
            if page is not None: command.append(str(int(page)))
            try:
                # Drain binary stdout in chunks. The worker cannot write to the
                # host filesystem: its output is quota-limited tmpfs, then a
                # bounded stream into this single host-owned spool file.
                process=subprocess.Popen(command,stdout=subprocess.PIPE,stderr=subprocess.DEVNULL)
                expired=threading.Event()
                def terminate():
                    expired.set()
                    try: process.kill()
                    except OSError: pass
                    try:
                        subprocess.run(['docker','rm','-f',name],stdout=subprocess.DEVNULL,
                                       stderr=subprocess.DEVNULL,timeout=15,check=False)
                    except (OSError,subprocess.TimeoutExpired): pass
                timer=threading.Timer(self.timeout,terminate); timer.daemon=True; timer.start()
                try:
                    size=0; spool=outgoing/'bundle.zip'
                    with spool.open('wb') as stream:
                        while chunk:=process.stdout.read(64*1024):
                            size+=len(chunk)
                            if size>200*1024*1024:
                                terminate(); raise ConversionError('ADAPTER_OUTPUT_LIMIT')
                            stream.write(chunk)
                    process.wait(timeout=15)
                    if expired.is_set(): raise ConversionError('ADAPTER_TIMEOUT')
                    if process.returncode: raise ConversionError('ADAPTER_FAILED')
                finally:
                    timer.cancel()
                    if process.poll() is None: terminate()
                    process.stdout.close()
                extension='docx' if operation=='docx' else 'pdf' if operation=='pdf' else 'png'
                with zipfile.ZipFile(spool) as bundle:
                    if set(bundle.namelist())!={'result.json','result.'+extension} or len(bundle.infolist())!=2 or bundle.getinfo('result.json').file_size>16000:
                        raise ConversionError('ADAPTER_PROTOCOL_INVALID')
                    if any(x.compress_type!=zipfile.ZIP_STORED or x.flag_bits&1 for x in bundle.infolist()) or sum(x.file_size for x in bundle.infolist())>200*1024*1024:
                        raise ConversionError('ADAPTER_PROTOCOL_LIMIT')
                    metadata=json.loads(bundle.read('result.json'))
                    output.parent.mkdir(parents=True,exist_ok=True)
                    digest=hashlib.sha256(); size=0
                    with bundle.open('result.'+extension) as incoming_result, output.open('wb') as target:
                        while chunk:=incoming_result.read(64*1024):
                            size+=len(chunk); digest.update(chunk); target.write(chunk)
                    if metadata.get('sha256')!=digest.hexdigest() or metadata.get('bytes')!=size:
                        raise ConversionError('ADAPTER_INTEGRITY_FAILED')
                return metadata
            except (subprocess.TimeoutExpired,OSError,ValueError,zipfile.BadZipFile) as error:
                if isinstance(error,ConversionError): raise
                raise ConversionError('ADAPTER_UNAVAILABLE') from error
            finally:
                # Timeout ends the whole container/process tree, not just CLI.
                try:
                    subprocess.run(['docker','rm','-f',name],stdout=subprocess.DEVNULL,
                                   stderr=subprocess.DEVNULL,timeout=15,check=False)
                except (OSError,subprocess.TimeoutExpired): pass
