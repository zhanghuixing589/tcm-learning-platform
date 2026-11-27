"""Bounded deterministic parsers. No filesystem paths, network fetches, OCR or models."""
import base64
import binascii
import io
import re
import zipfile
from pathlib import PurePath
from defusedxml import ElementTree
from pypdf import PdfReader, apply_configuration
from app.epub import epub_segments
from app.reader import build_reading

MAX_BYTES = 10 * 1024 * 1024
MAX_EXPANDED = 32 * 1024 * 1024
W = '{http://schemas.openxmlformats.org/wordprocessingml/2006/main}'

class DocumentInvalid(ValueError):
    pass

def pdf_segments(body, reading_pages=None):
    limits = dict(maximum_declared_stream_length=MAX_EXPANDED,
                  array_based_stream_maximum_output_length=MAX_EXPANDED,
                  zlib_maximum_output_length=MAX_EXPANDED,
                  lzw_maximum_output_length=MAX_EXPANDED,
                  run_length_maximum_output_length=MAX_EXPANDED,
                  stream_decoding_work_maximum_length=MAX_EXPANDED,
                  stream_parsing_work_maximum_count=500_000,
                  xform_maximum_invocations_per_extraction=500,
                  page_tree_maximum_entries=2000)
    with apply_configuration(**limits):
        pdf=PdfReader(io.BytesIO(body),strict=True)
        if pdf.is_encrypted or len(pdf.pages)>500:
            raise DocumentInvalid('不支持加密或超过500页的PDF')
        total=0; text_total=0; result=[]
        def bound_text(text, *unused):
            nonlocal text_total
            text_total += len(text)
            if text_total > 2_000_000:
                raise DocumentInvalid('PDF提取文本超限')
        for i,page in enumerate(pdf.pages,1):
            streams=page.get_contents()
            data=streams.get_data() if streams else b''
            total+=len(data)
            if total>MAX_EXPANDED:
                raise DocumentInvalid('PDF文本流大小超限')
            text=page.extract_text(visitor_text=bound_text) or ''
            if reading_pages is not None:
                reading_pages.append(text)
            for p in re.split(r'\n\s*\n',text):
                result.append((f'第 {i} 页',None,p.strip()))
        return result

def parse_document(name: str, encoded: str) -> dict:
    try:
        if len(encoded) > ((MAX_BYTES + 2) // 3) * 4:
            raise DocumentInvalid('文件最大10MB')
        body = base64.b64decode(encoded, validate=True)
        if not body or len(body) > MAX_BYTES:
            raise DocumentInvalid('文件须非空且最大10MB')
        suffix = PurePath(name).suffix.lower()
        raw_segments = []
        reading_pages = []
        if suffix == '.txt':
            if body.startswith((b'PK', b'%PDF-', b'MZ')):
                raise DocumentInvalid('文件实际格式与TXT不符')
            text = body.decode('utf-8-sig')
            if any(ord(c) < 32 and c not in '\n\r\t\f' for c in text):
                raise DocumentInvalid('TXT必须是UTF-8文本')
            raw_segments = [(f'段落 {i}', None, p.strip()) for i,p in enumerate(re.split(r'\n\s*\n', text.replace('\r\n','\n')),1)]
        elif suffix == '.docx':
            with zipfile.ZipFile(io.BytesIO(body)) as z:
                infos = z.infolist()
                if len(infos)>2000 or sum(x.file_size for x in infos)>MAX_EXPANDED or any(x.flag_bits & 1 or x.file_size>MAX_EXPANDED or x.file_size>max(x.compress_size,1)*200 for x in infos):
                    raise DocumentInvalid('DOCX解压大小或压缩比超限')
                if len({x.filename for x in infos}) != len(infos):
                    raise DocumentInvalid('DOCX存在重复文件项')
                types = ElementTree.fromstring(z.read('[Content_Types].xml'))
                expected='application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml'
                if not any(x.attrib.get('PartName')=='/word/document.xml' and x.attrib.get('ContentType')==expected for x in types):
                    raise DocumentInvalid('非标准DOCX正文')
                root=ElementTree.fromstring(z.read('word/document.xml'))
                if root.tag != W+'document':
                    raise DocumentInvalid('DOCX正文格式错误')
                headings={}
                for i,p in enumerate(root.iter(W+'p'),1):
                    text=''.join(x.text or '' for x in p.iter(W+'t')).strip()
                    style=p.find(W+'pPr/'+W+'pStyle')
                    match=re.fullmatch(r'Heading([1-9])',style.attrib.get(W+'val',''),re.IGNORECASE) if style is not None else None
                    if match and text:
                        level=int(match.group(1)); headings={k:v for k,v in headings.items() if k<level}; headings[level]=text
                    raw_segments.append((f'段落 {i}', ' / '.join(headings.values())[:500] or None, text))
        elif suffix == '.epub':
            raw_segments=epub_segments(body)
        elif suffix == '.pdf':
            if not body.startswith(b'%PDF-'):
                raise DocumentInvalid('文件实际格式与PDF不符')
            raw_segments=pdf_segments(body, reading_pages)
        else:
            raise DocumentInvalid('仅支持UTF-8 TXT、DOCX、文本PDF和未加密文本EPUB')
        segments=[]; total=0
        for position,heading,text in raw_segments:
            if not text:
                continue
            total+=len(text)
            if total>2_000_000 or len(segments)>=10_000:
                raise DocumentInvalid('提取文本或段落数量超限')
            segments.append({'segmentNo':len(segments)+1,'pageLabel':position,'headingPath':heading,'body':text})
        if not segments:
            raise DocumentInvalid('未提取到文本；不支持扫描PDF或OCR')
        reading = build_reading(suffix, body, raw_segments, reading_pages)
        result = {'parserName':'deterministic-v1','segments':segments,'reading':reading}
        # Match the Java HTTP client's bounded response, including legacy segments.
        import json
        if len(json.dumps(result, ensure_ascii=False).encode()) > 11 * 1024 * 1024:
            raise DocumentInvalid('解析结果大小超限')
        return result
    except DocumentInvalid:
        raise
    except (Exception,) as error:
        # Never expose parser internals, paths or document contents.
        raise DocumentInvalid('文档损坏或格式不受支持') from error
