"""Opt-in real Linux converter tests, not a mock of preview/conversion success.

Run after docker compose --profile converter build converter with
TCM_CONVERTER_TESTS=1. Generated public fixtures contain no user book content.
"""
import json
import os
from pathlib import Path
import struct
import subprocess
import zipfile
import pytest
from app.conversion import IsolatedConverter
from app.processing import process_file

pytestmark=pytest.mark.skipif(os.environ.get('TCM_CONVERTER_TESTS')!='1',reason='Explicit real-container validation only')
COMPOSE=Path(__file__).resolve().parents[2]/'infra'/'compose.infra.yml'


@pytest.fixture(scope='module')
def samples(tmp_path_factory):
    root=tmp_path_factory.mktemp('converter-real')
    root.chmod(0o777)
    script='''
from pathlib import Path
from PIL import Image
import subprocess,zipfile
out=Path('/fixtures'); work=Path('/work')
im=Image.new('RGB',(24,16),(40,130,90))
for ext in ['png','gif','webp','bmp','tiff']:
    im.save(out/('sample.'+ext))
im.save(out/'scan.pdf','PDF')
(out/'sample.svg').write_text('<svg xmlns="http://www.w3.org/2000/svg" width="24" height="16"><rect width="24" height="16" fill="green"/><script>throw Error(1)</script><image href="https://example.invalid/blocked"/></svg>')
(out/'sample.xhtml').write_text('<!doctype html><html><body><h1>Static preview</h1><p>Visible paragraph</p></body></html>')
with zipfile.ZipFile(work/'sample.odt','w') as z:
    z.writestr('mimetype','application/vnd.oasis.opendocument.text')
    z.writestr('META-INF/manifest.xml','<manifest:manifest xmlns:manifest="urn:oasis:names:tc:opendocument:xmlns:manifest:1.0"><manifest:file-entry manifest:full-path="/" manifest:media-type="application/vnd.oasis.opendocument.text"/><manifest:file-entry manifest:full-path="content.xml" manifest:media-type="text/xml"/></manifest:manifest>')
    z.writestr('content.xml','<office:document-content xmlns:office="urn:oasis:names:tc:opendocument:xmlns:office:1.0" xmlns:text="urn:oasis:names:tc:opendocument:xmlns:text:1.0" office:version="1.2"><office:body><office:text><text:p>Real binary DOC fixture</text:p></office:text></office:body></office:document-content>')
subprocess.run(['libreoffice','-env:UserInstallation=file:///work/fixture-profile','--headless','--convert-to','doc:MS Word 97','--outdir','/fixtures','/work/sample.odt'],check=True,timeout=60)
'''
    result=subprocess.run(['docker','compose','-f',str(COMPOSE),'--profile','converter','run','--rm','--no-deps','-T',
                           '--volume',f'{root}:/fixtures:rw','--entrypoint','/usr/bin/python3','converter','-c',script],
                          stdout=subprocess.PIPE,stderr=subprocess.PIPE,text=True,timeout=120)
    assert result.returncode==0, result.stderr[-2000:]
    assert (root/'sample.doc').read_bytes()[:8]==b'\xd0\xcf\x11\xe0\xa1\xb1\x1a\xe1'
    return root


@pytest.mark.parametrize('extension',['png','gif','webp','svg','bmp','tiff'])
def test_actual_image_conversion_returns_safe_png(samples,tmp_path,extension):
    target=tmp_path/'result.png'
    metadata=IsolatedConverter().convert(samples/('sample.'+extension),'image',target)
    body=target.read_bytes()
    assert body[:8]==b'\x89PNG\r\n\x1a\n'
    assert struct.unpack('>II',body[16:24])==(24,16)
    assert metadata['bytes']==len(body)


def test_actual_doc_binary_conversion_and_bundle(samples,tmp_path):
    bundle=process_file(samples/'sample.doc','sample.doc',tmp_path/'processed')
    with zipfile.ZipFile(bundle) as z:
        result=json.loads(z.read('result.json')); reading=result['reading']
        assert reading['format']=='DOC'
        assert reading['overallOutcome']=='SUCCEEDED'
        assert {a['kind'] for a in reading['assets']}=={'DERIVED_DOCX','DERIVED_PDF'}
        assert 'Real binary DOC fixture' in str(reading['units'][0]['blocks'])
        assert all(z.read(a['bundlePath']) for a in reading['assets'])


def test_actual_scanned_pdf_preview_bundle(samples,tmp_path):
    bundle=process_file(samples/'scan.pdf','scan.pdf',tmp_path/'processed')
    with zipfile.ZipFile(bundle) as z:
        result=json.loads(z.read('result.json')); reading=result['reading']
        assert result['segments']==[]
        assert reading['units'][0]['previewRef']
        assert not reading['units'][0]['capabilities']['searchable']
        assert z.read(reading['assets'][0]['bundlePath']).startswith(b'\x89PNG')


def test_actual_static_xhtml_preview(samples,tmp_path):
    target=tmp_path/'preview.png'
    IsolatedConverter().convert(samples/'sample.xhtml','epub-preview',target)
    assert target.read_bytes().startswith(b'\x89PNG')


def test_container_has_nonroot_readonly_no_network_and_budgets():
    script='''
import os,socket
from pathlib import Path
assert os.getuid()==10001
assert not any(os.environ.get(name) for name in ['HTTP_PROXY','HTTPS_PROXY','ALL_PROXY','http_proxy','https_proxy','all_proxy'])
assert Path('/sys/fs/cgroup/memory.max').read_text().strip()=='805306368'
assert 'NoNewPrivs:\\t1' in Path('/proc/self/status').read_text()
assert Path('/sys/fs/cgroup/pids.max').read_text().strip()=='96'
assert Path('/sys/fs/cgroup/cpu.max').read_text().split()[0]=='100000'
try:
    Path('/opt/probe').write_text('denied')
    raise AssertionError('writable root')
except OSError: pass
connection=socket.socket(); connection.settimeout(1)
try:
    connection.connect(('1.1.1.1',443))
    raise AssertionError('network allowed')
except OSError: pass
'''
    result=subprocess.run(['docker','compose','-f',str(COMPOSE),'--profile','converter','run','--rm','--no-deps','-T',
                           '--entrypoint','/usr/bin/python3','converter','-c',script],
                          stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL,timeout=30)
    assert result.returncode==0
