import base64
import io
import zipfile
import pytest
from app.documents import parse_document, DocumentInvalid
from test_epub import book
from test_documents import docx


def parse(name, body):
    return parse_document(name, base64.b64encode(body).decode())['reading']


def replace_entries(body, entries):
    out = io.BytesIO()
    with zipfile.ZipFile(io.BytesIO(body)) as src, zipfile.ZipFile(out, 'w') as dst:
        for name in src.namelist():
            dst.writestr(name, entries.pop(name, src.read(name)))
        for name, value in entries.items():
            dst.writestr(name, value)
    return out.getvalue()


def walk(nodes):
    for node in nodes:
        yield node
        yield from walk(node.get('children', []))


def test_txt_continuous_unicode_canonical_blocks_and_stable_ids():
    body = '第一段😀e\u0301\n下一行\n\n第二段'.encode()
    reading = parse('资料.txt', body)
    assert reading['schemaVersion'] == 1
    assert reading['format'] == 'TXT'
    assert len(reading['chapters']) == 1
    chapter = reading['chapters'][0]
    assert [b['text'] for b in chapter['blocks']] == ['第一段😀e\u0301\n下一行', '第二段']
    assert all(b['localId'] in {n.get('id') for n in walk(chapter['nodes'])} for b in chapter['blocks'])
    assert reading == parse('资料.txt', body)


def test_epub_structure_metadata_nav_and_sanitization():
    package = '''<package xmlns="http://www.idpf.org/2007/opf"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>书</dc:title><dc:creator>作者</dc:creator><dc:publisher>出版社</dc:publisher><dc:date>2020-01</dc:date><dc:rights>保留版权</dc:rights></metadata><manifest><item id="a" href="a.xhtml" media-type="application/xhtml+xml"/><item id="b" href="b.xhtml" media-type="application/xhtml+xml"/><item id="nav" href="nav.xhtml" properties="nav" media-type="application/xhtml+xml"/><item id="img" href="img.png" media-type="image/png"/></manifest><spine><itemref idref="b"/><itemref idref="a"/></spine></package>'''
    xhtml = '<html xmlns="http://www.w3.org/1999/xhtml"><body><h1 id="start">总论</h1><p onclick="evil()">前<strong>重点</strong><br/>后<a href="javascript:evil()">链接</a></p><ul><li>甲<ul><li>乙</li></ul>尾</li></ul><table><tr><td>表格</td></tr></table><img src="img.png"/><img src="https://evil/a.png"/><script>secret()</script><form>hidden</form></body></html>'
    nav = '<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops"><body><nav epub:type="toc"><ol><li><a href="b.xhtml#start">目录总论</a></li></ol></nav></body></html>'
    # Header is sufficient here: Java independently validates actual image bytes before storing.
    body = replace_entries(book(), {'OPS/book.opf': package, 'OPS/b.xhtml': xhtml, 'OPS/nav.xhtml': nav, 'OPS/img.png': b'\x89PNG\r\n\x1a\n'})
    reading = parse('a.epub', body)
    assert reading['metadata'] == {'title':'书','author':'作者','publisher':'出版社','publicationYear':2020,'rightsText':'保留版权'}
    assert reading['chapters'][0]['sourcePath'] == 'OPS/b.xhtml'
    assert reading['outline'][0]['title'] == '目录总论'
    assert reading['outline'][0]['localId'] == reading['chapters'][0]['blocks'][0]['localId']
    chapter = reading['chapters'][0]
    assert [b['text'] for b in chapter['blocks']] == ['总论', '前重点\n后链接', '甲', '乙', '尾', '表格']
    types = {n['type'] for n in walk(chapter['nodes'])}
    assert {'heading','strong','list','listItem','table','row','cell','image'} <= types
    assert reading['assets'] == [{'ref':'asset-1','path':'OPS/img.png','mimeType':'image/png'}]
    assert 'secret' not in str(reading) and 'javascript' not in str(reading) and 'https://evil' not in str(reading)


def test_docx_preserves_heading_list_table_inline_and_image_relationship():
    xml = '''<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main" xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><w:body><w:p><w:pPr><w:pStyle w:val="Heading1"/></w:pPr><w:r><w:t>标题</w:t></w:r></w:p><w:p><w:pPr><w:numPr><w:numId w:val="1"/></w:numPr></w:pPr><w:r><w:rPr><w:b/></w:rPr><w:t>列表</w:t></w:r><w:r><w:br/><w:t>换行</w:t></w:r></w:p><w:tbl><w:tr><w:tc><w:p><w:r><w:t>格子</w:t></w:r></w:p></w:tc></w:tr></w:tbl><w:p><w:r><w:drawing><a:blip r:embed="r1"/></w:drawing></w:r></w:p></w:body></w:document>'''
    body = replace_entries(docx(xml), {'word/_rels/document.xml.rels':'<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="r1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/image" Target="media/a.jpg"/></Relationships>', 'word/media/a.jpg':b'\xff\xd8\xfftest'})
    reading = parse('a.docx', body)
    chapter = reading['chapters'][0]
    assert [b['text'] for b in chapter['blocks']] == ['标题','列表\n换行','格子']
    assert {'heading','list','table','strong','image'} <= {n['type'] for n in walk(chapter['nodes'])}
    assert reading['assets'][0]['path'] == 'word/media/a.jpg'
    assert reading['outline'][0]['title'] == '标题'


def test_reader_rejects_excessive_node_expansion():
    xml = '<html xmlns="http://www.w3.org/1999/xhtml"><body><p>' + '<em>x</em>' * 30000 + '</p></body></html>'
    with pytest.raises(DocumentInvalid):
        parse('a.epub', replace_entries(book(), {'OPS/b.xhtml':xml}))


def test_epub_ncx_nested_outline_maps_original_ids_to_canonical_blocks():
    package = '<package xmlns="http://www.idpf.org/2007/opf"><manifest><item id="a" href="a.xhtml" media-type="application/xhtml+xml"/><item id="n" href="toc.ncx" media-type="application/x-dtbncx+xml"/></manifest><spine toc="n"><itemref idref="a"/></spine></package>'
    ncx = '<ncx xmlns="http://www.daisy.org/z3986/2005/ncx/"><navMap><navPoint><navLabel><text>章</text></navLabel><content src="a.xhtml#one"/><navPoint><navLabel><text>节</text></navLabel><content src="a.xhtml#two"/></navPoint></navPoint></navMap></ncx>'
    chapter = '<html xmlns="http://www.w3.org/1999/xhtml"><body><h1 id="one">一</h1><h2 id="two">二</h2></body></html>'
    reading = parse('a.epub',replace_entries(book(), {'OPS/book.opf':package,'OPS/toc.ncx':ncx,'OPS/a.xhtml':chapter}))
    assert reading['outline'][0]['localId']=='c1-b1'
    assert reading['outline'][0]['children'][0]['localId']=='c1-b2'


def test_pdf_preserves_blank_physical_pages_and_server_text():
    from pypdf import PdfWriter
    from pypdf.generic import DictionaryObject, NameObject, DecodedStreamObject
    writer=PdfWriter()
    writer.add_blank_page(width=300,height=300)
    page=writer.add_blank_page(width=300,height=300)
    font=DictionaryObject({NameObject('/Type'):NameObject('/Font'),NameObject('/Subtype'):NameObject('/Type1'),NameObject('/BaseFont'):NameObject('/Helvetica')})
    page[NameObject('/Resources')]=DictionaryObject({NameObject('/Font'):DictionaryObject({NameObject('/F1'):writer._add_object(font)})})
    stream=DecodedStreamObject(); stream.set_data(b'BT /F1 12 Tf 10 200 Td (Actual page two) Tj ET')
    page[NameObject('/Contents')]=writer._add_object(stream)
    out=io.BytesIO(); writer.write(out)
    reading=parse('a.pdf',out.getvalue())
    assert [c['pageNumber'] for c in reading['chapters']]==[1,2]
    assert reading['chapters'][0]['blocks']==[]
    assert reading['chapters'][1]['blocks'][0]['text']=='Actual page two'


def test_epub_preformatted_text_is_canonical_and_links_do_not_load_resources():
    chapter='<html xmlns="http://www.w3.org/1999/xhtml"><body><pre>  line one\n  line two</pre><p><a href="https://example.org/info">link</a><code>x</code><sup>2</sup></p></body></html>'
    reading=parse('a.epub', replace_entries(book(), {'OPS/b.xhtml':chapter}))
    assert reading['chapters'][0]['blocks'][0]['text']=='  line one\n  line two'
    assert reading['chapters'][0]['blocks'][1]['text']=='linkx2'


def test_malformed_inline_block_nesting_never_duplicates_canonical_text():
    chapter='<html xmlns="http://www.w3.org/1999/xhtml"><body><div>前<strong><p>中</p></strong>后</div></body></html>'
    reading=parse('a.epub',replace_entries(book(), {'OPS/b.xhtml':chapter}))
    assert [b['text'] for b in reading['chapters'][0]['blocks']]==['前','中','后']


def test_docx_numbering_and_superscript_are_visible_ast_not_duplicate_text():
    xml='''<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body><w:p><w:pPr><w:numPr><w:numId w:val="7"/></w:numPr></w:pPr><w:r><w:t>项目</w:t></w:r><w:r><w:rPr><w:vertAlign w:val="superscript"/></w:rPr><w:t>2</w:t></w:r></w:p></w:body></w:document>'''
    numbering='<w:numbering xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:abstractNum w:abstractNumId="0"><w:lvl w:ilvl="0"><w:numFmt w:val="decimal"/></w:lvl></w:abstractNum><w:num w:numId="7"><w:abstractNumId w:val="0"/></w:num></w:numbering>'
    reading=parse('a.docx',replace_entries(docx(xml),{'word/numbering.xml':numbering}))
    nodes=reading['chapters'][0]['nodes']
    assert nodes[0]['ordered'] is True
    assert 'sup' in {n['type'] for n in walk(nodes)}
    assert [b['text'] for b in reading['chapters'][0]['blocks']]==['项目2']


def test_epub_adjacent_preformatted_blocks_keep_separate_locations():
    chapter='<html xmlns="http://www.w3.org/1999/xhtml"><body><pre>one</pre><pre>two</pre></body></html>'
    reading=parse('a.epub',replace_entries(book(),{'OPS/b.xhtml':chapter}))
    assert [b['text'] for b in reading['chapters'][0]['blocks']]==['one','two']


def test_docx_horizontal_merged_cell_keeps_explicit_span():
    xml='<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body><w:tbl><w:tr><w:tc><w:tcPr><w:gridSpan w:val="2"/></w:tcPr><w:p><w:r><w:t>合并内容</w:t></w:r></w:p></w:tc></w:tr></w:tbl></w:body></w:document>'
    reading=parse('a.docx',docx(xml))
    cell=next(n for n in walk(reading['chapters'][0]['nodes']) if n['type']=='cell')
    assert cell['colspan']==2
    assert [b['text'] for b in reading['chapters'][0]['blocks']]==['合并内容']


def test_namespaced_svg_and_script_never_leak_active_subtrees_into_ast():
    chapter='<html xmlns="http://www.w3.org/1999/xhtml" xmlns:svg="http://www.w3.org/2000/svg"><body><p>正文</p><svg:svg><svg:script>secret()</svg:script><svg:text>hidden</svg:text></svg:svg></body></html>'
    reading=parse('a.epub',replace_entries(book(),{'OPS/b.xhtml':chapter}))
    assert 'secret' not in str(reading['chapters'][0]['nodes'])
    assert [b['text'] for b in reading['chapters'][0]['blocks']]==['正文']
