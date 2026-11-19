import base64
import io
import zipfile
from fastapi.testclient import TestClient
import pytest
from app.main import create_app
from app.core.settings import Settings

TOKEN = 'test-internal-credential-12345678901234567890'

@pytest.fixture
def client():
    return TestClient(create_app(Settings(_env_file=None, internal_token=TOKEN)))

def parse(client, name, body, authenticated=True):
    return client.post('/internal/v1/parse', json={'fileName': name, 'contentBase64': base64.b64encode(body).decode()}, headers={'X-Internal-Token': TOKEN} if authenticated else {})

def docx(xml):
    out = io.BytesIO()
    with zipfile.ZipFile(out, 'w', zipfile.ZIP_DEFLATED) as z:
        z.writestr('[Content_Types].xml', '<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/></Types>')
        z.writestr('word/document.xml', xml)
    return out.getvalue()

def test_txt_returns_real_nonempty_paragraphs(client):
    r=parse(client,'source.txt','第一段\n\n第二段'.encode())
    assert r.status_code == 200
    assert [s['body'] for s in r.json()['data']['segments']] == ['第一段','第二段']
    assert r.json()['data']['segments'][1]['pageLabel'] == '段落 2'

def test_docx_returns_headings_and_paragraph_positions(client):
    xml='<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body><w:p><w:pPr><w:pStyle w:val="Heading1"/></w:pPr><w:r><w:t>药性</w:t></w:r></w:p><w:p><w:r><w:t>性味归经</w:t></w:r></w:p></w:body></w:document>'
    r=parse(client,'source.docx',docx(xml))
    assert r.status_code == 200
    assert r.json()['data']['segments'][1]['headingPath'] == '药性'
    assert r.json()['data']['segments'][1]['pageLabel'] == '段落 2'

@pytest.mark.parametrize('name,body', [('fake.pdf',b'hello'),('fake.docx',b'PK broken'),('fake.txt',b'%PDF-1.4\n'),('bad.txt',b'\xff\xfe'),('empty.txt',b' \n'),('source.exe',b'hello')])
def test_invalid_formats_have_unified_error(client,name,body):
    r=parse(client,name,body)
    assert r.status_code == 400
    assert r.json()['code'] == 'DOCUMENT_INVALID'
    assert r.json()['data'] is None
    assert r.json()['traceId']

def test_requires_auth_and_rejects_path_contract(client):
    assert parse(client,'a.txt',b'ok',False).status_code == 401
    r=client.post('/internal/v1/parse',json={'path':'C:/secret'},headers={'X-Internal-Token':TOKEN})
    assert r.status_code == 400
    assert r.json()['code'] == 'VALIDATION_ERROR'

def test_size_limit_and_zip_bomb(client):
    assert parse(client,'big.txt',b'a'*(10*1024*1024+1)).status_code == 400
    r=parse(client,'bomb.docx',docx('a'*(32*1024*1024+1)))
    assert r.status_code == 400

def test_malformed_xml_is_safe(client):
    r=parse(client,'bad.docx',docx('<!DOCTYPE a [<!ENTITY e SYSTEM "file:///etc/passwd">]><a>&e;</a>'))
    assert r.status_code == 400

def test_text_pdf_preserves_real_page_number(client):
    # A real minimal two-page PDF generated with pypdf, no remote fixtures.
    from pypdf import PdfWriter
    from pypdf.generic import DictionaryObject, NameObject, DecodedStreamObject
    writer=PdfWriter()
    for text in ('Page one','Page two'):
        page=writer.add_blank_page(width=300,height=300)
        font=DictionaryObject({NameObject('/Type'):NameObject('/Font'),NameObject('/Subtype'):NameObject('/Type1'),NameObject('/BaseFont'):NameObject('/Helvetica')})
        page[NameObject('/Resources')]=DictionaryObject({NameObject('/Font'):DictionaryObject({NameObject('/F1'):writer._add_object(font)})})
        stream=DecodedStreamObject(); stream.set_data(f'BT /F1 12 Tf 10 200 Td ({text}) Tj ET'.encode())
        page[NameObject('/Contents')]=writer._add_object(stream)
    out=io.BytesIO(); writer.write(out)
    r=parse(client,'source.pdf',out.getvalue())
    assert r.status_code == 200
    assert [(s['pageLabel'],s['body']) for s in r.json()['data']['segments']] == [('第 1 页','Page one'),('第 2 页','Page two')]


def test_invalid_base64_and_extra_fields_have_safe_errors(client):
    for payload in ({'fileName':'a.txt','contentBase64':'!!!!'}, {'fileName':'a.txt','contentBase64':'b2s=','path':'/private'}):
        r=client.post('/internal/v1/parse',json=payload,headers={'X-Internal-Token':TOKEN})
        assert r.status_code == 400
        assert r.json()['data'] is None
        assert '/private' not in r.text

def test_large_request_is_rejected_before_json_validation(client):
    r=client.post('/internal/v1/parse',content=b'x'*(14*1024*1024+1),headers={'X-Internal-Token':TOKEN,'Content-Type':'application/json'})
    assert r.status_code == 400
    assert r.json()['code'] == 'DOCUMENT_INVALID'

def test_large_untrusted_request_is_unauthenticated_before_body_validation(client):
    r=client.post('/internal/v1/parse',content=b'x'*1024,headers={'Content-Type':'application/json'})
    assert r.status_code == 401

def test_pdf_decompression_bomb_fails_safely(client):
    from pypdf import PdfWriter
    from pypdf.generic import NameObject, DecodedStreamObject
    writer=PdfWriter(); page=writer.add_blank_page(width=100,height=100)
    stream=DecodedStreamObject();stream.set_data(b' '*(33*1024*1024))
    page[NameObject('/Contents')]=writer._add_object(stream.flate_encode())
    out=io.BytesIO();writer.write(out)
    r=parse(client,'bomb.pdf',out.getvalue())
    assert r.status_code == 400
    assert r.json()['code'] == 'DOCUMENT_INVALID'

@pytest.mark.parametrize('encrypted',[False,True])
def test_empty_or_encrypted_pdf_is_not_claimed_parsed(client,encrypted):
    from pypdf import PdfWriter
    writer=PdfWriter();writer.add_blank_page(width=100,height=100)
    if encrypted:
        writer.encrypt('password')
    out=io.BytesIO();writer.write(out)
    r=parse(client,'source.pdf',out.getvalue())
    assert r.status_code == 400
    assert r.json()['code'] == 'DOCUMENT_INVALID'
