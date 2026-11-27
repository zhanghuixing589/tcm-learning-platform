import base64
import io
import zipfile
import pytest
from app.documents import parse_document, DocumentInvalid


def book(mode='valid'):
    out = io.BytesIO()
    with zipfile.ZipFile(out, 'w', zipfile.ZIP_STORED) as z:
        z.writestr('mimetype', 'application/epub+zip')
        z.writestr('META-INF/container.xml', '<container xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="OPS/book.opf" media-type="application/oebps-package+xml"/></rootfiles></container>')
        href = '../../outside.xhtml' if mode == 'traversal' else 'a.xhtml'
        z.writestr('OPS/book.opf', f'<package xmlns="http://www.idpf.org/2007/opf" version="3.0"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>本草测试</dc:title></metadata><manifest><item id="a" href="{href}" media-type="application/xhtml+xml"/><item id="b" href="b.xhtml" media-type="application/xhtml+xml"/></manifest><spine><itemref idref="b"/><itemref idref="a"/></spine></package>')
        z.writestr('OPS/a.xhtml', '<html xmlns="http://www.w3.org/1999/xhtml"><body><h1>药性</h1><p>性味<em>归经</em>。</p><script>secret()</script><style>hidden</style></body></html>')
        z.writestr('OPS/b.xhtml', '<html xmlns="http://www.w3.org/1999/xhtml"><body><h1>总论</h1><p>正文第一章</p></body></html>' if mode != 'empty' else '<html xmlns="http://www.w3.org/1999/xhtml"><body><img src="image.png"/></body></html>')
        if mode == 'encrypted':
            z.writestr('META-INF/encryption.xml', '<encryption/>')
        if mode == 'missing':
            # The package references a chapter that does not exist.
            return book_missing()
    return out.getvalue()


def book_missing():
    out = io.BytesIO()
    with zipfile.ZipFile(io.BytesIO(book())) as src, zipfile.ZipFile(out, 'w') as dst:
        for name in src.namelist():
            if name != 'OPS/a.xhtml':
                dst.writestr(name, src.read(name))
    return out.getvalue()


def parse(body):
    return parse_document('中医.EPUB', base64.b64encode(body).decode())


def test_epub_spine_order_book_title_and_plain_text():
    result = parse(book())
    assert [s['body'] for s in result['segments']] == ['总论', '正文第一章', '药性', '性味归经。']
    assert result['segments'][1]['headingPath'] == '本草测试 / 总论'
    assert result['segments'][1]['pageLabel'] == '章节 1 · 段落 2'
    assert result['parserName'] == 'deterministic-v1'


def test_epub_accepts_stored_entries_with_data_descriptors():
    class Stream(io.BytesIO):
        def seek(self, *args):
            raise io.UnsupportedOperation('non-seekable EPUB writer')
    out = Stream()
    with zipfile.ZipFile(io.BytesIO(book())) as src, zipfile.ZipFile(out, 'w', zipfile.ZIP_STORED) as dst:
        for name in src.namelist():
            dst.writestr(name, src.read(name))
    with zipfile.ZipFile(io.BytesIO(out.getvalue())) as check:
        assert all(x.flag_bits & 8 and x.compress_type == zipfile.ZIP_STORED for x in check.infolist())
    assert parse(out.getvalue()) == parse(book())


@pytest.mark.parametrize('mode', ['encrypted', 'traversal', 'missing'])
def test_epub_rejects_invalid_package(mode):
    with pytest.raises(DocumentInvalid):
        parse(book(mode))


def test_epub_rejects_fake_archive_and_decompression_bomb():
    for body in (b'not an epub',):
        with pytest.raises(DocumentInvalid):
            parse(body)
    out = io.BytesIO()
    with zipfile.ZipFile(out, 'w', zipfile.ZIP_DEFLATED) as z:
        z.writestr('mimetype', 'application/epub+zip')
        z.writestr('bomb', b'a' * 1_000_000)
    with pytest.raises(DocumentInvalid):
        parse(out.getvalue())


def replace_chapter(xml):
    out = io.BytesIO()
    with zipfile.ZipFile(io.BytesIO(book())) as src, zipfile.ZipFile(out, 'w') as dst:
        for name in src.namelist():
            dst.writestr(name, xml if name.endswith('.xhtml') else src.read(name))
    return out.getvalue()


def test_epub_accepts_standard_doctype_and_div_text():
    body = replace_chapter('<!DOCTYPE html PUBLIC "-//W3C//DTD XHTML 1.1//EN" "http://www.w3.org/TR/xhtml11/DTD/xhtml11.dtd"><html xmlns="http://www.w3.org/1999/xhtml"><body><div>正文<span>文字</span></div></body></html>')
    assert [s['body'] for s in parse(body)['segments']] == ['正文文字', '正文文字']


def test_epub_preserves_text_around_nested_paragraphs():
    body = replace_chapter('<html xmlns="http://www.w3.org/1999/xhtml"><body><div>前文<p>正文</p>后文</div></body></html>')
    assert [s['body'] for s in parse(body)['segments']] == ['前文', '正文', '后文'] * 2


@pytest.mark.parametrize('xml', [
    '<!DOCTYPE html [<!ENTITY leak SYSTEM "file:///secret">]><html xmlns="http://www.w3.org/1999/xhtml"><body><p>&leak;</p></body></html>',
    '<html xmlns="http://www.w3.org/1999/xhtml"><body><img src="scan.png"/></body></html>',
])
def test_epub_rejects_entities_and_image_only_books(xml):
    with pytest.raises(DocumentInvalid):
        parse(replace_chapter(xml))
