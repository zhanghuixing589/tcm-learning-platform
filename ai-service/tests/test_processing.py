"""V2 contract and security fixtures; real container checks are separate."""
import io
import json
import zipfile
from pathlib import Path
import pytest
from fastapi.testclient import TestClient
from app.main import create_app
from app.core.settings import Settings
from test_epub import book
from test_reader import replace_entries

TOKEN='test-internal-credential-12345678901234567890'


def test_v2_txt_bundle_uses_units_and_real_capabilities(tmp_path):
    from app.processing import process_file
    path=tmp_path/'input.txt'; path.write_text('甲😀\n\n乙',encoding='utf-8')
    bundle=process_file(path,'input.txt',tmp_path/'output')
    with zipfile.ZipFile(bundle) as z:
        result=json.loads(z.read('result.json'))
    reading=result['reading']
    assert reading['schemaVersion']==2 and reading['format']=='TXT'
    assert 'chapters' not in reading
    assert reading['outline']==[]
    assert reading['units'][0]['unitType']=='TEXT_SECTION'
    assert reading['units'][0]['capabilities']['preciseCitationSupported']
    assert reading['overallOutcome']=='SUCCEEDED'
    assert [b['text'] for b in reading['units'][0]['blocks']]==['甲😀','乙']
    assert result['inputSha256'] and result['inputBytes']==len(path.read_bytes())


def test_v2_epub_local_failure_keeps_reliable_units(tmp_path):
    from app.processing import process_file
    path=tmp_path/'input.epub'; path.write_bytes(replace_entries(book(),{'OPS/b.xhtml':'<broken'}))
    bundle=process_file(path,'input.epub',tmp_path/'output')
    with zipfile.ZipFile(bundle) as z: reading=json.loads(z.read('result.json'))['reading']
    assert reading['overallOutcome']=='PARTIAL_SUCCESS'
    assert len(reading['units'])==2
    assert reading['units'][0]['availableReadingModes']==[]
    assert not reading['units'][0]['capabilities']['searchable']
    assert reading['units'][1]['capabilities']['searchable']
    assert reading['stages']['structure']['failed']==1


def test_v2_zip_traversal_is_hard_failure_not_partial(tmp_path):
    from app.processing import process_file
    from app.documents import DocumentInvalid
    path=tmp_path/'input.epub'; path.write_bytes(replace_entries(book(),{'../escape':'evil'}))
    with pytest.raises(DocumentInvalid): process_file(path,'input.epub',tmp_path/'output')


def test_v2_external_svg_stays_placeholder_when_adapter_fails(tmp_path):
    from app.processing import process_file
    from app.conversion import ConversionError
    class BrokenConverter:
        def convert(self,*args,**kwargs): raise ConversionError('ADAPTER_UNAVAILABLE')
    package='<package xmlns="http://www.idpf.org/2007/opf"><manifest><item id="a" href="a.xhtml" media-type="application/xhtml+xml"/><item id="i" href="a.svg" media-type="image/svg+xml"/></manifest><spine><itemref idref="a"/></spine></package>'
    chapter='<html xmlns="http://www.w3.org/1999/xhtml"><body><p>可靠文字</p><figure><img src="a.svg"/><figcaption>图注</figcaption></figure></body></html>'
    path=tmp_path/'input.epub'; path.write_bytes(replace_entries(book(),{'OPS/book.opf':package,'OPS/a.xhtml':chapter,'OPS/a.svg':'<svg xmlns="http://www.w3.org/2000/svg"><image href="https://evil/image"/></svg>'}))
    bundle=process_file(path,'input.epub',tmp_path/'output',converter=BrokenConverter())
    with zipfile.ZipFile(bundle) as z: reading=json.loads(z.read('result.json'))['reading']
    assert reading['overallOutcome']=='PARTIAL_SUCCESS'
    assert reading['assets']==[]
    assert reading['units'][0]['capabilities']['preciseCitationSupported']
    assert '图注' in str(reading['units'][0]['nodes'])
    assert any(w['code']=='MEDIA_UNAVAILABLE' for w in reading['warnings'])


def test_v2_endpoint_auth_path_validation_and_bundle(tmp_path,monkeypatch):
    import app.process_api as api
    async def download(config,job,token,path):
        path.write_text('文字',encoding='utf-8')
        return {'bytes':6}
    monkeypatch.setattr(api,'download_input',download)
    client=TestClient(create_app(Settings(_env_file=None,internal_token=TOKEN)))
    assert client.post('/internal/v2/process',json={}).status_code==401
    headers={'X-Internal-Token':TOKEN}
    assert client.post('/internal/v2/process',json={'jobId':'../escape','runToken':'token','fileName':'a.txt'},headers=headers).status_code==400
    response=client.post('/internal/v2/process',json={'jobId':'1','runToken':'valid-run-token','fileName':'a.txt'},headers=headers)
    assert response.status_code==200
    assert response.headers['content-type']=='application/zip'
    with zipfile.ZipFile(io.BytesIO(response.content)) as z:
        assert json.loads(z.read('result.json'))['reading']['format']=='TXT'
    # The admission slot is released after streaming and on processing failure.
    assert client.post('/internal/v2/process',json={'jobId':'1','runToken':'valid-run-token','fileName':'a.docx'},headers=headers).status_code==400
    assert client.post('/internal/v2/process',json={'jobId':'1','runToken':'valid-run-token','fileName':'a.txt'},headers=headers).status_code==200


def test_downloader_rejects_redirect_and_size_without_following(tmp_path):
    import asyncio
    import httpx
    from app.process_api import download_input
    from app.documents import DocumentInvalid
    def redirect(request): return httpx.Response(302,headers={'Location':'http://evil/private'})
    config=Settings(_env_file=None,internal_token=TOKEN)
    with pytest.raises(DocumentInvalid):
        asyncio.run(download_input(config,'1','run',tmp_path/'input',transport=httpx.MockTransport(redirect)))


def blank_pdf():
    from pypdf import PdfWriter
    output=io.BytesIO(); writer=PdfWriter(); writer.add_blank_page(width=200,height=300); writer.write(output)
    return output.getvalue()


def test_v2_scan_preview_mode_requires_actual_generated_asset(tmp_path):
    from app.processing import process_file
    from app.conversion import ConversionError
    class PreviewConverter:
        def convert(self,source,operation,output,**kwargs):
            assert operation=='preview'
            # The real renderer is independently exercised in container tests.
            output.write_bytes(b'\x89PNG\r\n\x1a\nfixture')
            return {}
    path=tmp_path/'input.pdf'; path.write_bytes(blank_pdf())
    bundle=process_file(path,'scan.pdf',tmp_path/'output',converter=PreviewConverter())
    with zipfile.ZipFile(bundle) as z:
        result=json.loads(z.read('result.json')); reading=result['reading']
        assert z.read(reading['assets'][0]['bundlePath']).startswith(b'\x89PNG')
    assert result['segments']==[]
    unit=reading['units'][0]
    assert unit['previewRef'] and unit['blocks']==[]
    assert unit['availableReadingModes']==['ORIGINAL_PREVIEW','FIXED_LAYOUT']
    assert not unit['capabilities']['preciseCitationSupported']
    class Broken:
        def convert(self,*args,**kwargs): raise ConversionError('unavailable')
    bundle=process_file(path,'scan.pdf',tmp_path/'failed',converter=Broken())
    with zipfile.ZipFile(bundle) as z: reading=json.loads(z.read('result.json'))['reading']
    assert reading['overallOutcome']=='FAILED'
    assert reading['units'][0]['availableReadingModes']==[]


def test_v2_doc_adapter_retains_original_type_and_binary_derivatives(tmp_path):
    from app.processing import process_file
    from test_documents import docx
    class OfficeConverter:
        def convert(self,source,operation,output,**kwargs):
            if operation=='docx':
                output.write_bytes(docx('<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body><w:p><w:r><w:t>文档正文</w:t></w:r></w:p></w:body></w:document>'))
            elif operation=='pdf': output.write_bytes(blank_pdf())
            else: raise AssertionError(operation)
            return {}
    path=tmp_path/'input.doc'; path.write_bytes(b'\xd0\xcf\x11\xe0\xa1\xb1\x1a\xe1fixture')
    bundle=process_file(path,'old.doc',tmp_path/'output',converter=OfficeConverter())
    with zipfile.ZipFile(bundle) as z:
        reading=json.loads(z.read('result.json'))['reading']
        assert {a['kind'] for a in reading['assets']}=={'DERIVED_DOCX','DERIVED_PDF'}
        assert all(z.read(a['bundlePath']) for a in reading['assets'])
    assert reading['format']=='DOC'
    assert reading['units'][0]['blocks'][0]['text']=='文档正文'


def test_v2_entity_in_bad_local_chapter_remains_security_hard_failure(tmp_path):
    from app.processing import process_file
    from app.documents import DocumentInvalid
    path=tmp_path/'input.epub'
    path.write_bytes(replace_entries(book(),{'OPS/b.xhtml':'<!DOCTYPE html [<!ENTITY e SYSTEM "file:///private">]><html xmlns="http://www.w3.org/1999/xhtml"><body><p>&e;</p></body></html>'}))
    with pytest.raises(DocumentInvalid):process_file(path,'a.epub',tmp_path/'output')


def test_pdf_processing_timeout_preserves_failed_page_positions(tmp_path):
    from app.processing import process_file, ProcessingLimits
    path=tmp_path/'input.pdf'; path.write_bytes(blank_pdf())
    bundle=process_file(path,'scan.pdf',tmp_path/'output',limits=ProcessingLimits(timeout=-1))
    with zipfile.ZipFile(bundle) as z: reading=json.loads(z.read('result.json'))['reading']
    assert reading['units'][0]['pageNumber']==1
    assert reading['units'][0]['availableReadingModes']==[]
    assert reading['overallOutcome']=='FAILED'
    assert any(w['code']=='PROCESSING_TIME_LIMIT' for w in reading['warnings'])


def test_v2_pdf_decompression_limit_is_security_failure(tmp_path):
    from pypdf import PdfWriter
    from pypdf.generic import NameObject, DecodedStreamObject
    from app.processing import process_file, ProcessingLimits
    from app.documents import DocumentInvalid
    writer=PdfWriter(); page=writer.add_blank_page(width=100,height=100)
    stream=DecodedStreamObject(); stream.set_data(b' '*(1024*1024+1))
    page[NameObject('/Contents')]=writer._add_object(stream.flate_encode())
    data=io.BytesIO(); writer.write(data)
    path=tmp_path/'input.pdf';path.write_bytes(data.getvalue())
    class Renderer:
        def convert(self,source,operation,output,**kwargs):
            output.write_bytes(b'\x89PNG\r\n\x1a\nfixture')
            return {}
    with pytest.raises(DocumentInvalid):
        process_file(path,'a.pdf',tmp_path/'output',limits=ProcessingLimits(expanded_bytes=1024*1024),converter=Renderer())


def test_downloader_verifies_hash_and_actual_bytes(tmp_path):
    import asyncio
    import httpx
    from app.process_api import download_input
    from app.documents import DocumentInvalid
    config=Settings(_env_file=None,internal_token=TOKEN,process_max_bytes=10)
    for headers,body in [({'X-Content-SHA256':'0'*64},b'content'),({},b'12345678901')]:
        transport=httpx.MockTransport(lambda request:httpx.Response(200,headers=headers,content=body))
        with pytest.raises(DocumentInvalid):
            asyncio.run(download_input(config,'1','run',tmp_path/'input',transport=transport))


def test_v2_budget_failure_does_not_claim_unit_text_success(tmp_path):
    from app.processing import process_file,ProcessingLimits
    path=tmp_path/'input.txt'; path.write_text('text beyond the budget',encoding='utf-8')
    bundle=process_file(path,'a.txt',tmp_path/'output',limits=ProcessingLimits(text_chars=3))
    with zipfile.ZipFile(bundle) as z: reading=json.loads(z.read('result.json'))['reading']
    assert reading['overallOutcome']=='FAILED'
    assert reading['stages']['structure']['status']=='FAILED'
    assert reading['units'][0]['blocks']==[]


def test_v2_epub_footnote_role_and_internal_anchor_use_canonical_id(tmp_path):
    from app.processing import process_file
    from test_reader import walk
    content='<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops"><body><p>正文<a href="#note">注</a></p><aside epub:type="footnote" id="note"><p>注释正文</p></aside></body></html>'
    path=tmp_path/'input.epub'; path.write_bytes(replace_entries(book(),{'OPS/b.xhtml':content}))
    bundle=process_file(path,'a.epub',tmp_path/'output')
    with zipfile.ZipFile(bundle) as z: reading=json.loads(z.read('result.json'))['reading']
    unit=reading['units'][0]
    assert unit['capabilities']['hasFootnotes']
    link=next(n for n in walk(unit['nodes']) if n['type']=='link')
    assert link['href']=='#'+unit['blocks'][1]['localId']


def test_v2_large_unit_xml_is_bounded_before_tree_build_and_keeps_remainder(tmp_path):
    from app.processing import process_file,ProcessingLimits
    chapter='<html xmlns="http://www.w3.org/1999/xhtml"><body><p>'+'x'*1000+'</p></body></html>'
    path=tmp_path/'input.epub'; path.write_bytes(replace_entries(book(),{'OPS/b.xhtml':chapter}))
    bundle=process_file(path,'a.epub',tmp_path/'output',limits=ProcessingLimits(unit_xml_bytes=300))
    with zipfile.ZipFile(bundle) as z: reading=json.loads(z.read('result.json'))['reading']
    assert reading['overallOutcome']=='PARTIAL_SUCCESS'
    assert reading['units'][0]['nodes']==[]
    assert reading['units'][1]['capabilities']['searchable']


def test_internal_original_stream_does_not_send_job_credentials_to_environment_proxy(tmp_path,monkeypatch):
    import asyncio,hashlib,threading
    from http.server import BaseHTTPRequestHandler,ThreadingHTTPServer
    from app.process_api import download_input
    body=b'private input'; received=[]
    class Handler(BaseHTTPRequestHandler):
        def log_message(self,*args): pass
        def do_GET(self):
            received.append((self.path,self.headers.get('X-Internal-Token')==TOKEN,self.headers.get('X-Job-Run-Token')=='unit-run'))
            self.send_response(200);self.send_header('Content-Length',str(len(body)));self.send_header('X-Content-SHA256',hashlib.sha256(body).hexdigest());self.end_headers();self.wfile.write(body)
    server=ThreadingHTTPServer(('127.0.0.1',0),Handler);worker=threading.Thread(target=server.serve_forever,daemon=True);worker.start()
    try:
        for name in ('HTTP_PROXY','HTTPS_PROXY','ALL_PROXY','http_proxy','https_proxy','all_proxy'):monkeypatch.setenv(name,'http://127.0.0.1:9')
        for name in ('NO_PROXY','no_proxy'):monkeypatch.setenv(name,'')
        config=Settings(internal_token=TOKEN,backend_url=f'http://127.0.0.1:{server.server_port}',process_download_timeout_seconds=1)
        result=asyncio.run(download_input(config,'123','unit-run',tmp_path/'input'))
        assert result['bytes']==len(body) and (tmp_path/'input').read_bytes()==body
        assert received==[('/internal/v1/source-inputs/123',True,True)]
    finally:
        server.shutdown();server.server_close();worker.join(5)
