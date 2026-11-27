"""Immutable reading payloads; archive-only resources and an explicit safe AST.

Canonical blocks partition visible text: structural ancestors never repeat the
text of their descendants. IDs are deterministic within this reading edition.
"""
import io
import json
import re
import zipfile
from urllib.parse import unquote, urlsplit
from defusedxml import ElementTree as ET
from app.epub import archive_path, OPF, XHTML

W = '{http://schemas.openxmlformats.org/wordprocessingml/2006/main}'
R = '{http://schemas.openxmlformats.org/officeDocument/2006/relationships}'
DC = '{http://purl.org/dc/elements/1.1/}'
MAX_NODES = 25000
MAX_BLOCKS = 10000
MAX_JSON = 8 * 1024 * 1024
STRUCTURAL = {'paragraph', 'heading', 'list', 'listItem', 'table', 'row', 'cell', 'blockquote', 'figure', 'figcaption', 'footnote'}


def plain(node):
    if node['type'] == 'text':
        return node['text']
    if node['type'] == 'break':
        return '\n'
    return ''.join(plain(c) for c in node.get('children', []))


def text_node(text):
    return {'type': 'text', 'text': text}


def safe_href(value):
    ref = urlsplit(value)
    if any(ord(c) < 32 for c in value):
        return None
    if value.startswith('#') or (ref.scheme in ('http', 'https') and ref.netloc):
        return value[:2000]
    return None


class Builder:
    def __init__(self, format):
        self.value = {'schemaVersion': 1, 'format': format,
                      'metadata': {'title': '', 'author': '', 'publisher': ''},
                      'outline': [], 'chapters': [], 'assets': [], 'warnings': []}
        self.count = 0
        self.block_count = 0
        self.text_count = 0
        self.anchors = {}

    def warning(self, message):
        if message not in self.value['warnings']:
            self.value['warnings'].append(message)

    def image(self, z, path, mime):
        if mime not in ('image/png', 'image/jpeg') or path not in z.namelist():
            self.warning('不支持或缺失的图片已省略')
            return []
        for asset in self.value['assets']:
            if asset['path'] == path:
                return [{'type': 'image', 'assetRef': asset['ref']}]
        ref = f"asset-{len(self.value['assets']) + 1}"
        self.value['assets'].append({'ref': ref, 'path': path, 'mimeType': mime})
        return [{'type': 'image', 'assetRef': ref}]

    def chapter(self, title, path, nodes, page=None, source_ids=None):
        number = len(self.value['chapters']) + 1
        blocks = []
        def finalize(node, depth=0):
            self.count += 1
            if depth > 64 or self.count > MAX_NODES:
                raise ValueError('Reading AST limit')
            children = node.get('children', [])
            # Text-bearing terminal blocks include all their inline descendants.
            has_blocks = any(c['type'] in STRUCTURAL for c in children)
            if node['type'] in STRUCTURAL and not has_blocks:
                text = plain(node)
                if text.strip():
                    self.block_count += 1
                    self.text_count += len(text)
                    if self.block_count > MAX_BLOCKS or self.text_count > 2_000_000:
                        raise ValueError('Reading text limit')
                    local = f'c{number}-b{len(blocks) + 1}'
                    node['id'] = local
                    blocks.append({'blockNo': len(blocks)+1, 'localId': local, 'text': text})
            for child in children:
                finalize(child, depth+1)
        for node in nodes:
            finalize(node)
        chapter = {'chapterNo': number, 'title': title, 'sourcePath': path,
                   'nodes': nodes, 'blocks': blocks}
        if page is not None:
            chapter['pageNumber'] = page
        self.value['chapters'].append(chapter)
        if source_ids:
            for original, node in source_ids.items():
                target = node.get('id')
                if not target:
                    target = next((n.get('id') for n in walk([node]) if n.get('id')), None)
                if target:
                    self.anchors[(path, original)] = target
        return chapter

    def finish(self):
        if not self.value['outline']:
            self.value['outline'] = [{'title': c['title'], 'chapterNo': c['chapterNo'], 'children': []}
                                     for c in self.value['chapters']]
        if len(json.dumps(self.value, ensure_ascii=False).encode()) > MAX_JSON:
            raise ValueError('Reading JSON limit')
        return self.value


def walk(nodes):
    for node in nodes:
        yield node
        yield from walk(node.get('children', []))


def grouped(nodes):
    """Wrap direct inline runs between nested blocks; keep each visible byte once."""
    result = []; run = []
    def flush():
        if run and (''.join(plain(n) for n in run).strip() or any(n['type']=='image' for n in walk(run))):
            result.append({'type':'paragraph', 'children':list(run)})
        run.clear()
    for node in nodes:
        if node['type'] in STRUCTURAL:
            flush(); result.append(node)
        else:
            run.append(node)
    flush()
    return result


def html_nodes(root, builder, z, path, images, ids, depth=0, preserve=False):
    if depth > 64:
        raise ValueError('XHTML depth limit')
    tag = root.tag.removeprefix(XHTML)
    local_tag=root.tag.split('}')[-1]
    if root.tag.startswith('{') and not root.tag.startswith(XHTML):
        if builder.value.get('schemaVersion')==2:
            builder.warning('不支持的内嵌命名空间内容未展示')
            return [{'type':'image','text':'内嵌内容暂不可用'}] if local_tag=='svg' else []
        return []
    preserve = preserve or tag == 'pre'
    if tag in {'script','style','iframe','object','embed','form','nav','svg','math','head','input','button','textarea'}:
        return []
    if tag == 'img':
        try:
            image_path = archive_path(path, root.attrib.get('src', ''))
        except ValueError:
            builder.warning('不支持或外部图片已省略')
            return [{'type':'image','text':'图片暂不可用'}] if builder.value.get('schemaVersion')==2 else []
        return builder.image(z, image_path, images.get(image_path))
    if tag == 'br':
        return [{'type':'break'}]
    children = []
    if root.text:
        children.append(text_node(root.text if preserve else re.sub(r'\s+', ' ', root.text)))
    for child in root:
        children.extend(html_nodes(child, builder, z, path, images, ids, depth+1, preserve))
        if child.tail:
            children.append(text_node(child.tail if preserve else re.sub(r'\s+', ' ', child.tail)))
    mapping = {'p':'paragraph','li':'listItem','ul':'list','ol':'list','table':'table','tr':'row',
               'td':'cell','th':'cell','strong':'strong','b':'strong','em':'em','i':'em',
               'a':'link','blockquote':'blockquote','code':'code','pre':'code','sup':'sup','sub':'sub'}
    kind = mapping.get(tag)
    if builder.value.get('schemaVersion') == 2:
        if tag in ('figure','figcaption'): kind=tag
        if 'footnote' in root.attrib.get('{http://www.idpf.org/2007/ops}type','').split(): kind='footnote'
    if re.fullmatch(r'h[1-6]', tag):
        kind = 'heading'
    if kind is None:
        result = grouped(children) if tag in ('body','div','section','article') else children
        if root.attrib.get('id') and result:
            ids[root.attrib['id']] = result[0]
        return result
    # Nonconforming XHTML can place blocks inside inline formatting. Flatten
    # that formatting so a canonical ancestor never repeats descendant text.
    if kind not in STRUCTURAL and any(n['type'] in STRUCTURAL for n in walk(children)):
        return children
    if kind in STRUCTURAL and any(c['type'] in STRUCTURAL for c in children):
        children = grouped(children)
    node = {'type':kind, 'children':children}
    if kind == 'heading':
        node['level'] = int(tag[1])
    if kind == 'list':
        node['ordered'] = tag == 'ol'
    if kind == 'link':
        href = safe_href(root.attrib.get('href',''))
        if href:
            node['href'] = href
    if kind == 'cell':
        for attr in ('colspan','rowspan'):
            value = root.attrib.get(attr,'1')
            if value.isdecimal() and 1 <= int(value) <= 100:
                node[attr] = int(value)
    if root.attrib.get('id'):
        ids[root.attrib['id']] = node
    if tag == 'pre':
        return [{'type':'paragraph','children':[node]}]
    return [node]


def epub_reading(body):
    builder = Builder('EPUB')
    with zipfile.ZipFile(io.BytesIO(body)) as z:
        container = ET.fromstring(z.read('META-INF/container.xml'))
        package_path = archive_path('', next(x for x in container.iter() if x.tag.endswith('}rootfile')).attrib['full-path'])
        package = ET.fromstring(z.read(package_path))
        metadata = builder.value['metadata']
        for key, tag in [('title','title'),('author','creator'),('publisher','publisher'),('rightsText','rights')]:
            value = ' / '.join((n.text or '').strip() for n in package.findall('.//' + DC + tag)).strip()
            if value or key != 'rightsText':
                metadata[key] = value
        date = package.findtext('.//' + DC + 'date', '')
        match = re.match(r'\s*(\d{4})', date)
        if match:
            metadata['publicationYear'] = int(match[1])
        manifest = {x.attrib['id']:x for x in package.findall(OPF+'manifest/'+OPF+'item')}
        images = {}
        for item in manifest.values():
            try:
                path = archive_path(package_path, item.attrib['href'])
            except ValueError:
                continue
            if item.attrib.get('media-type','').startswith('image/'):
                images[path] = item.attrib['media-type']
        paths = {}
        for ref in package.findall(OPF+'spine/'+OPF+'itemref'):
            item = manifest[ref.attrib['idref']]
            path = archive_path(package_path,item.attrib['href'])
            root = ET.fromstring(z.read(path))
            ids = {}
            nodes = html_nodes(root.find(XHTML+'body'), builder, z, path, images, ids)
            title = next((plain(n).strip() for n in walk(nodes) if n['type']=='heading'), path.rsplit('/',1)[-1])
            chapter = builder.chapter(title, path, nodes, source_ids=ids)
            paths[path] = chapter['chapterNo']
        def target(href, base, title, children):
            try:
                path = archive_path(base, href)
            except ValueError:
                return None
            if path not in paths:
                return None
            result = {'title':title, 'chapterNo':paths[path], 'children':children}
            local = builder.anchors.get((path, unquote(urlsplit(href).fragment)))
            if local:
                result['localId'] = local
            return result
        nav = next((x for x in manifest.values() if 'nav' in x.attrib.get('properties','').split()), None)
        if nav is not None:
            base = archive_path(package_path, nav.attrib['href'])
            root = ET.fromstring(z.read(base))
            toc = next((n for n in root.iter(XHTML+'nav') if 'toc' in n.attrib.get('{http://www.idpf.org/2007/ops}type','').split()), None)
            def nav_list(ol, depth=0):
                if depth > 32: raise ValueError('Outline depth limit')
                result = []
                if ol is None: return result
                for li in ol.findall(XHTML+'li'):
                    a = li.find(XHTML+'a')
                    if a is not None:
                        entry = target(a.attrib.get('href',''),base,''.join(a.itertext()).strip(),nav_list(li.find(XHTML+'ol'),depth+1))
                        if entry: result.append(entry)
                return result
            if toc is not None:
                builder.value['outline'] = nav_list(toc.find(XHTML+'ol'))
        if not builder.value['outline']:
            ncx = next((x for x in manifest.values() if x.attrib.get('media-type')=='application/x-dtbncx+xml'),None)
            if ncx is not None:
                base = archive_path(package_path,ncx.attrib['href'])
                root = ET.fromstring(z.read(base)); ns = '{http://www.daisy.org/z3986/2005/ncx/}'
                def ncx_list(parent, depth=0):
                    if depth > 32: raise ValueError('Outline depth limit')
                    result = []
                    for point in parent.findall(ns+'navPoint'):
                        content = point.find(ns+'content')
                        if content is not None:
                            entry = target(content.attrib.get('src',''),base,point.findtext(ns+'navLabel/'+ns+'text',''),ncx_list(point,depth+1))
                            if entry: result.append(entry)
                    return result
                navmap = root.find(ns+'navMap')
                if navmap is not None: builder.value['outline'] = ncx_list(navmap)
        if not builder.value['outline']:
            builder.warning('未找到可用目录，使用正文章节标题')
    return builder.finish()


def docx_reading(body, builder=None):
    builder = builder or Builder('DOCX')
    with zipfile.ZipFile(io.BytesIO(body)) as z:
        # parse_document has already checked size, ratio, duplicates and main XML.
        for info in z.infolist():
            if archive_path('',info.filename.rstrip('/')) != info.filename.rstrip('/'):
                raise ValueError('Invalid DOCX entry')
        if 'docProps/core.xml' in z.namelist():
            core = ET.fromstring(z.read('docProps/core.xml'))
            builder.value['metadata']['title'] = core.findtext(DC+'title','')
            builder.value['metadata']['author'] = core.findtext(DC+'creator','')
        rels = {}
        if 'word/_rels/document.xml.rels' in z.namelist():
            for rel in ET.fromstring(z.read('word/_rels/document.xml.rels')):
                if rel.attrib.get('TargetMode') != 'External':
                    try: rels[rel.attrib['Id']] = (archive_path('word/document.xml',rel.attrib['Target']),rel.attrib.get('Type',''))
                    except ValueError: builder.warning('不安全的文档关系已省略')
        numbering = {}
        if 'word/numbering.xml' in z.namelist():
            root = ET.fromstring(z.read('word/numbering.xml'))
            abstracts = {n.attrib.get(W+'abstractNumId'):n for n in root.findall(W+'abstractNum')}
            for num in root.findall(W+'num'):
                abstract = num.find(W+'abstractNumId')
                if abstract is not None:
                    numbering[num.attrib.get(W+'numId')] = abstracts.get(abstract.attrib.get(W+'val'))
        def convert(node, depth=0):
            if depth>64: raise ValueError('DOCX depth limit')
            tag=node.tag.removeprefix(W)
            if tag in ('pPr','rPr','tblPr','trPr','tcPr','sectPr','del'):
                return []
            if tag=='t': return [text_node(node.text or '')]
            if tag in ('br','cr'): return [{'type':'break'}]
            if tag=='tab': return [text_node('\t')]
            if node.tag=='{http://schemas.openxmlformats.org/drawingml/2006/main}blip':
                rel=rels.get(node.attrib.get(R+'embed'))
                if rel and rel[1].endswith('/image'):
                    path=rel[0]; suffix=path.rsplit('.',1)[-1].lower()
                    return builder.image(z,path,{'png':'image/png','jpg':'image/jpeg','jpeg':'image/jpeg','gif':'image/gif','webp':'image/webp','svg':'image/svg+xml','bmp':'image/bmp','tif':'image/tiff','tiff':'image/tiff'}.get(suffix))
                builder.warning('不支持或外部图片已省略')
                return [{'type':'image','text':'图片暂不可用'}] if builder.value.get('schemaVersion')==2 else []
            children=[]
            for child in node: children.extend(convert(child,depth+1))
            if tag=='r':
                props=node.find(W+'rPr')
                if props is not None:
                    for prop,kind in [('i','em'),('b','strong')]:
                        value=props.find(W+prop)
                        if value is not None and value.attrib.get(W+'val','1') not in ('0','false','off'):
                            children=[{'type':kind,'children':children}]
                    align=props.find(W+'vertAlign')
                    if align is not None and align.attrib.get(W+'val') in ('superscript','subscript'):
                        children=[{'type':'sup' if align.attrib[W+'val']=='superscript' else 'sub','children':children}]
                return children
            mapping={'p':'paragraph','tbl':'table','tr':'row','tc':'cell'}
            if tag not in mapping: return children
            result={'type':mapping[tag],'children':children}
            if tag=='tc':
                span=node.find(W+'tcPr/'+W+'gridSpan')
                if span is not None:
                    value=span.attrib.get(W+'val','1')
                    if not value.isdecimal() or not 1<=int(value)<=100:
                        raise ValueError('DOCX table span limit')
                    result['colspan']=int(value)
            if tag=='p':
                style=node.find(W+'pPr/'+W+'pStyle')
                match=re.fullmatch(r'Heading([1-9])',style.attrib.get(W+'val',''),re.I) if style is not None else None
                if match: result.update(type='heading',level=int(match[1]))
                num=node.find(W+'pPr/'+W+'numPr/'+W+'numId')
                if num is not None:
                    abstract=numbering.get(num.attrib.get(W+'val'))
                    level=node.find(W+'pPr/'+W+'numPr/'+W+'ilvl')
                    ilvl=level.attrib.get(W+'val','0') if level is not None else '0'
                    fmt=next((n.find(W+'numFmt') for n in abstract.findall(W+'lvl') if n.attrib.get(W+'ilvl')==ilvl),None) if abstract is not None else None
                    return [{'type':'list','ordered':fmt is not None and fmt.attrib.get(W+'val')!='bullet', 'children':[{'type':'listItem','children':[result]}]}]
            return [result]
        root=ET.fromstring(z.read('word/document.xml'))
        nodes=convert(root.find(W+'body'))
        builder.chapter(builder.value['metadata']['title'] or '全文','word/document.xml',nodes)
        stack=[]
        for node in walk(nodes):
            if node['type']=='heading' and node.get('id'):
                entry={'title':plain(node),'chapterNo':1,'localId':node['id'],'children':[]}
                while stack and stack[-1][0]>=node['level']: stack.pop()
                (stack[-1][1]['children'] if stack else builder.value['outline']).append(entry)
                stack.append((node['level'],entry))
    return builder.finish()


def build_reading(suffix, body, raw_segments, pdf_pages=None):
    if suffix=='.epub': return epub_reading(body)
    if suffix=='.docx': return docx_reading(body)
    builder=Builder(suffix[1:].upper())
    if suffix=='.txt':
        text=body.decode('utf-8-sig').replace('\r\n','\n').replace('\r','\n')
        nodes=[{'type':'paragraph','children':[text_node(p)]} for p in re.split(r'\n\s*\n',text) if p.strip()]
        builder.chapter('全文','',nodes)
    else:
        for number,text in enumerate(pdf_pages or [],1):
            nodes=[{'type':'paragraph','children':[text_node(p)]} for p in re.split(r'\n\s*\n',text) if p.strip()]
            builder.chapter(f'第 {number} 页','',nodes,page=number)
    return builder.finish()
