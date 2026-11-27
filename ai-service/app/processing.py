"""V2 capability-driven processing with bounded input, units and binary assets.

Security/integrity errors abort the whole job. Local content/conversion errors
are retained as located failures; no missing text is invented or indexed.
"""
from dataclasses import dataclass
import hashlib
import base64
import io
import json
from pathlib import Path
import re
import shutil
import time
import zipfile
from defusedxml import ElementTree as ET
from defusedxml.common import DefusedXmlException
from pypdf import PdfReader, apply_configuration
from pypdf.errors import LimitReachedError
from app.conversion import IsolatedConverter, ConversionError
from app.documents import DocumentInvalid
from app.epub import archive_path, OPF, XHTML
from app.reader import Builder, DC, W, html_nodes, plain, text_node, walk, docx_reading


@dataclass(frozen=True)
class ProcessingLimits:
    raw_bytes: int=50*1024*1024
    expanded_bytes: int=200*1024*1024
    zip_items: int=5000
    ratio: int=200
    text_chars: int=2_000_000
    blocks: int=10000
    nodes: int=25000
    bundle_bytes: int=200*1024*1024
    unit_xml_bytes: int=8*1024*1024
    timeout: float=600


class SecurityFailure(DocumentInvalid):
    pass


def xml_member(z,name,limit=8*1024*1024):
    # Bound the member before allocating bytes or constructing an XML tree.
    if z.getinfo(name).file_size>limit:
        raise DocumentInvalid('XML阅读单元超过安全处理预算')
    with z.open(name) as incoming:
        body=incoming.read(limit+1)
    if len(body)>limit:
        raise DocumentInvalid('XML阅读单元超过安全处理预算')
    return body


def warning(code,message,unit=None,asset=None,severity='WARNING'):
    value={'code':code,'message':message,'severity':severity}
    if unit is not None: value['unitNo']=unit
    if asset is not None: value['assetRef']=asset
    return value


def capabilities(nodes=(),text=False,reliable=True,toc=False):
    types={n['type'] for n in walk(nodes)}
    return {'hasToc':toc,'hasImages':'image' in types,'hasTables':'table' in types,
            'hasFootnotes':'footnote' in types,'textSelectable':bool(text and reliable),
            'searchable':bool(text and reliable),'preciseCitationSupported':bool(text and reliable),
            'fontSizeAdjustable':bool(text and reliable)}


def validate_archive(path,limits):
    """Check both metadata and every actual uncompressed byte/CRC."""
    try:
        with zipfile.ZipFile(path) as z:
            infos=z.infolist()
            if len(infos)>limits.zip_items or len({x.filename for x in infos})!=len(infos):
                raise SecurityFailure('压缩包文件数量或重复项校验失败')
            declared=0; actual=0
            for info in infos:
                name=info.filename.rstrip('/')
                if not name or archive_path('',name)!=name or '\x00' in info.filename:
                    raise SecurityFailure('压缩包路径校验失败')
                declared+=info.file_size
                if info.flag_bits&1 or info.file_size>limits.expanded_bytes or info.file_size>max(info.compress_size,1)*limits.ratio or declared>limits.expanded_bytes:
                    raise SecurityFailure('压缩包加密或展开预算超限')
                with z.open(info) as stream:
                    member=0
                    while chunk:=stream.read(64*1024):
                        member+=len(chunk); actual+=len(chunk)
                        if member>info.file_size or actual>limits.expanded_bytes:
                            raise SecurityFailure('压缩包实际展开预算超限')
                if member!=info.file_size:
                    raise SecurityFailure('压缩包成员不完整')
    except SecurityFailure:
        raise
    except Exception as error:
        raise SecurityFailure('压缩包完整性或安全校验失败') from error


class Processor:
    def __init__(self,path,name,output,limits,converter):
        self.path=path; self.name=name; self.output=output; self.limits=limits
        self.converter=converter; self.started=time.monotonic()
        self.assets=[]; self.warnings=[]; self.units=[]; self.outline=[]
        self.metadata={'title':'','author':'','publisher':''}
        self.stages={stage:{'status':'SKIPPED','expected':0,'succeeded':0,'failed':0}
                     for stage in ('metadata','conversion','preview','structure','text','media')}
        self.media_cache={}; self.asset_bytes=0
        self.node_count=0
        self.layout_mode='REFLOWABLE'
        self.output.mkdir(parents=True,exist_ok=True)
        (self.output/'assets').mkdir(exist_ok=True)

    def check_time(self):
        if time.monotonic()-self.started>self.limits.timeout:
            raise ConversionError('PROCESSING_TIME_LIMIT')

    def stage(self,name,success):
        stage=self.stages[name]; stage['expected']+=1
        stage['succeeded' if success else 'failed']+=1
        stage['status']='PARTIAL_SUCCESS' if stage['succeeded'] and stage['failed'] else 'SUCCEEDED' if stage['succeeded'] else 'FAILED'

    def asset(self,path,mime,kind,unit=None,page=None,original=None):
        size=path.stat().st_size
        self.asset_bytes+=size
        if not size or self.asset_bytes>self.limits.bundle_bytes:
            raise ConversionError('ASSET_BUDGET_LIMIT')
        ref=f'asset-{len(self.assets)+1}'
        extension='.docx' if kind=='DERIVED_DOCX' else '.pdf' if kind=='DERIVED_PDF' else '.png'
        bundle='assets/'+ref+extension
        target=self.output/bundle
        if path.resolve()!=target.resolve(): shutil.copyfile(path,target)
        value={'ref':ref,'bundlePath':bundle,'mimeType':mime,'kind':kind}
        if unit is not None: value['unitNo']=unit
        if page is not None: value['pageNumber']=page
        if original: value['originalFormat']=original
        self.assets.append(value)
        return ref

    def convert(self,path,op,suffix='.png',page=None):
        self.check_time()
        target=self.output/('converted-'+str(len(self.assets))+'-'+op+suffix)
        data=self.converter.convert(path,op,target,page=page)
        return target,data

    def image(self,z,path,mime,unit):
        if path in self.media_cache:
            return self.media_cache[path]
        placeholder=[{'type':'image','text':'图片暂不可用'}]
        source=None; normalized=None
        try:
            self.check_time()
            if path not in z.namelist() or mime not in ('image/png','image/jpeg','image/gif','image/webp','image/svg+xml','image/bmp','image/tiff','image/x-ms-bmp'):
                raise ConversionError('MEDIA_UNSUPPORTED')
            info=z.getinfo(path)
            if info.file_size>20*1024*1024: raise ConversionError('MEDIA_INPUT_LIMIT')
            extension={'image/png':'.png','image/jpeg':'.jpg','image/gif':'.gif','image/webp':'.webp','image/svg+xml':'.svg','image/bmp':'.bmp','image/x-ms-bmp':'.bmp','image/tiff':'.tiff'}[mime]
            source=self.output/('media-input'+extension)
            with z.open(path) as incoming, source.open('wb') as outgoing:
                shutil.copyfileobj(incoming,outgoing,64*1024)
            normalized,data=self.convert(source,'image')
            ref=self.asset(normalized,'image/png','IMAGE',unit=unit,original=data.get('originalFormat',mime))
            result=[{'type':'image','assetRef':ref}]
            if data.get('frames',1)>1:
                self.warnings.append(warning('MEDIA_FIRST_FRAME','多帧图片只展示首帧，其他帧未覆盖',unit,ref))
            if data.get('sanitized'):
                self.warnings.append(warning('MEDIA_SANITIZED_VIEW','SVG已清理为静态安全图；不支持的主动或外部内容未展示',unit,ref))
            self.stage('media',True)
        except (ConversionError,OSError):
            self.warnings.append(warning('MEDIA_UNAVAILABLE','图片未成功生成，保留原位置及图注',unit))
            self.stage('media',False); result=placeholder
        finally:
            if source is not None: source.unlink(missing_ok=True)
            if normalized is not None: normalized.unlink(missing_ok=True)
        self.media_cache[path]=result
        return result

    def add_unit(self,title,path,nodes,kind='TEXT_SECTION',page=None,reliable=True,warnings=None):
        number=len(self.units)+1
        builder=Builder('')
        try:
            chapter=builder.chapter(title,path,nodes,page=page)
            blocks=chapter['blocks']
            count=sum(1 for _ in walk(nodes))
            if self.node_count+count>self.limits.nodes: raise ValueError('Global node budget')
            self.node_count+=count
            # Rename only edition-local prefixes, without altering visible text.
            ids={b['localId']:f'u{number}-b{i}' for i,b in enumerate(blocks,1)}
            for node in walk(nodes):
                if node.get('id') in ids: node['id']=ids[node['id']]
            for block in blocks: block['localId']=ids[block['localId']]
            text=sum(len(b['text']) for b in blocks)
            existing=sum(len(b['text']) for unit in self.units for b in unit['blocks'])
            existing_blocks=sum(len(unit['blocks']) for unit in self.units)
            if text+existing>self.limits.text_chars or len(blocks)+existing_blocks>self.limits.blocks:
                raise ValueError('Unit text budget')
        except (ValueError,RecursionError):
            nodes=[]; blocks=[]; reliable=False
            self.warnings.append(warning('UNIT_BUDGET_EXCEEDED','此阅读单元超过结构或文本预算，未截断为完整正文',number))
        has_text=bool(blocks)
        caps=capabilities(nodes,has_text,reliable)
        modes=['STRUCTURED','PLAIN_TEXT'] if has_text and reliable else []
        unit={'unitNo':number,'unitType':kind,'title':title,'sourcePath':path,
              'nodes':nodes if reliable else [],'blocks':blocks if reliable else [],
              'capabilities':caps,'availableReadingModes':modes,'warnings':warnings or []}
        if not reliable:
            unit['capabilities']=capabilities()
        if page is not None: unit['pageNumber']=page
        self.units.append(unit)
        return unit

    def preview(self,path,unit,page):
        generated=None
        try:
            generated,_=self.convert(path,'preview',page=page)
            ref=self.asset(generated,'image/png','PAGE_PREVIEW',unit['unitNo'],page)
            unit['previewRef']=ref
            unit['availableReadingModes'].append('ORIGINAL_PREVIEW')
            if 'FIXED_LAYOUT' not in unit['availableReadingModes']: unit['availableReadingModes'].append('FIXED_LAYOUT')
            self.stage('preview',True)
        except (ConversionError,OSError):
            self.stage('preview',False)
            self.warnings.append(warning('PREVIEW_UNAVAILABLE','此页安全预览未生成',unit['unitNo']))
        finally:
            if generated is not None: generated.unlink(missing_ok=True)

    def pdf(self,path,complex_word=False):
        self.layout_mode='FIXED'
        limits=dict(maximum_declared_stream_length=self.limits.expanded_bytes,
                    array_based_stream_maximum_output_length=self.limits.expanded_bytes,
                    zlib_maximum_output_length=self.limits.expanded_bytes,
                    lzw_maximum_output_length=self.limits.expanded_bytes,
                    run_length_maximum_output_length=self.limits.expanded_bytes,
                    stream_decoding_work_maximum_length=self.limits.expanded_bytes,
                    stream_parsing_work_maximum_count=500_000,
                    xform_maximum_invocations_per_extraction=500,page_tree_maximum_entries=2000)
        with apply_configuration(**limits):
            pdf=PdfReader(str(path),strict=True)
            if pdf.is_encrypted or len(pdf.pages)>500:
                raise SecurityFailure('不支持加密或超过500页的PDF')
            meta=pdf.metadata
            if meta:
                self.metadata.update(title=str(meta.title or ''),author=str(meta.author or ''))
            self.stage('metadata',True)
            stream_total=0
            for page_number,page in enumerate(pdf.pages,1):
                try:
                    self.check_time()
                except ConversionError:
                    unit=self.add_unit(f'第 {page_number} 页','',[],'PAGE',page_number,False)
                    self.stage('text',False); self.stage('structure',False); self.stage('preview',False)
                    self.warnings.append(warning('PROCESSING_TIME_LIMIT','此页尚未处理，保留位置；已完成页面仍可使用',unit['unitNo']))
                    continue
                text=''; reliable=not complex_word
                try:
                    stream=page.get_contents()
                    stream_total+=len(stream.get_data()) if stream else 0
                    if stream_total>self.limits.expanded_bytes: raise SecurityFailure('PDF展开预算超限')
                    text_size=0; positions=[]
                    def visitor(text,cm,tm,font,size):
                        nonlocal text_size
                        text_size+=len(text)
                        if text_size>self.limits.text_chars: raise ValueError('Page text budget')
                        if text.strip(): positions.append((round(tm[4]),round(tm[5]),bool(cm[1] or cm[2])))
                    text=page.extract_text(visitor_text=visitor) or ''
                    # Rotation and concurrent columns are conservatively not
                    # made citable merely because extract_text returned a str.
                    rows={}
                    for x,y,rotated in positions:
                        if rotated: reliable=False
                        rows.setdefault(round(y/10),[]).append(x)
                    if any(xs and max(xs)-min(xs)>200 for xs in rows.values()): reliable=False
                    if complex_word: reliable=False
                    self.stage('text',bool(text.strip() and reliable))
                except SecurityFailure: raise
                except LimitReachedError as error:
                    raise SecurityFailure('PDF解码或解析安全预算超限') from error
                except Exception:
                    reliable=False
                    self.stage('text',False)
                    self.warnings.append(warning('PAGE_TEXT_FAILED','此页文字提取失败，只开放已生成的安全预览',len(self.units)+1))
                nodes=[{'type':'paragraph','children':[text_node(p)]} for p in re.split(r'\n\s*\n',text) if p.strip()]
                unit=self.add_unit(f'第 {page_number} 页','',nodes,'PAGE',page_number,reliable)
                if not text.strip():
                    self.warnings.append(warning('PAGE_NO_TEXT','此页无可靠文字层，未执行OCR，禁用文字整理',unit['unitNo'],severity='INFO'))
                elif not reliable:
                    self.warnings.append(warning('LAYOUT_UNRELIABLE','此页排版或顺序无法可靠映射，禁用文字引用',unit['unitNo']))
                self.stage('structure',reliable and bool(nodes))
                self.preview(path,unit,page_number)

    def epub(self):
        validate_archive(self.path,self.limits)
        with zipfile.ZipFile(self.path) as z:
            if z.read('mimetype')!=b'application/epub+zip' or 'META-INF/encryption.xml' in z.namelist():
                raise SecurityFailure('不支持加密或非标准EPUB')
            container=ET.fromstring(xml_member(z,'META-INF/container.xml'))
            roots=container.findall('.//{urn:oasis:names:tc:opendocument:xmlns:container}rootfile')
            if not roots: raise DocumentInvalid('缺少EPUB包')
            package_path=archive_path('',roots[0].attrib['full-path'])
            package=ET.fromstring(xml_member(z,package_path))
            manifest={}; images={}
            for item in package.findall(OPF+'manifest/'+OPF+'item'):
                key=item.attrib['id']
                if key in manifest: raise SecurityFailure('重复EPUB manifest ID')
                # A manifest resource can never name a filesystem/network URL.
                try: resource_path=archive_path(package_path,item.attrib['href'])
                except ValueError as error: raise SecurityFailure('EPUB资源路径不安全') from error
                manifest[key]=item
                if item.attrib.get('media-type','').startswith('image/'):
                    images[resource_path]=item.attrib['media-type']
            for key,tag in [('title','title'),('author','creator'),('publisher','publisher'),('rightsText','rights')]:
                value=' / '.join((n.text or '').strip() for n in package.findall('.//'+DC+tag))
                if value or key!='rightsText': self.metadata[key]=value
            date=package.findtext('.//'+DC+'date','')
            year=re.match(r'\s*(\d{4})',date)
            if year: self.metadata['publicationYear']=int(year[1])
            self.stage('metadata',True)
            fixed=any(n.attrib.get('property')=='rendition:layout' and (n.text or '').strip()=='pre-paginated' for n in package.iter())
            if fixed:self.layout_mode='FIXED'
            paths={}; anchors={}
            processor=self
            class EpubBuilder(Builder):
                def __init__(self,format):
                    super().__init__(format); self.value['schemaVersion']=2
                def image(self,z,path,mime): return processor.image(z,path,mime,len(processor.units)+1)
                def warning(self,message): processor.warnings.append(warning('RESOURCE_OMITTED',message,len(processor.units)+1))
            for ref in package.findall(OPF+'spine/'+OPF+'itemref'):
                number=len(self.units)+1; nodes=[]; ids={}; path=''; title=f'阅读单元 {number}'; reliable=True
                try:
                    self.check_time()
                    item=manifest[ref.attrib['idref']]
                    path=archive_path(package_path,item.attrib['href'])
                    if item.attrib.get('media-type')!='application/xhtml+xml': raise ValueError('Unsupported spine item')
                    raw=xml_member(z,path,self.limits.unit_xml_bytes)
                    root=ET.fromstring(raw)
                    content=root.find(XHTML+'body')
                    if content is None: raise ValueError('Missing body')
                    complex_layout=fixed or any(n.tag in (XHTML+'ruby',XHTML+'rt',XHTML+'rp') for n in root.iter()) or bool(re.search(rb'writing-mode\s*:\s*(?:vertical|tb-)|position\s*:\s*absolute',raw,re.I))
                    for link in root.findall('.//'+XHTML+'link'):
                        if 'stylesheet' in link.attrib.get('rel','').split():
                            css_path=archive_path(path,link.attrib.get('href',''))
                            if css_path in z.namelist() and re.search(rb'writing-mode\s*:\s*(?:vertical|tb-)|position\s*:\s*absolute',z.read(css_path),re.I): complex_layout=True
                    nodes=html_nodes(content,EpubBuilder('EPUB'),z,path,images,ids)
                    title=next((plain(n).strip() for n in walk(nodes) if n['type']=='heading'),path.rsplit('/',1)[-1])
                    reliable=not complex_layout
                    unit=self.add_unit(title,path,nodes,'PAGE' if fixed else 'TEXT_SECTION',number if fixed else None,reliable)
                    self.stage('structure',reliable)
                    self.stage('text',reliable and bool(unit['blocks']))
                    if complex_layout:
                        if not fixed:self.layout_mode='MIXED'
                        self.warnings.append(warning('LAYOUT_UNRELIABLE','固定版式、竖排或复杂注释仅开放已生成的预览，禁止猜测文字顺序',number))
                        try:
                            # Static preview receives no executable tree or URLs.
                            fragment=self.output/'preview.xhtml'
                            def image_data(href):
                                try: image_path=archive_path(path,href)
                                except ValueError:return None
                                candidate=self.media_cache.get(image_path,[])
                                image_ref=candidate[0].get('assetRef') if candidate else None
                                asset=next((a for a in self.assets if a['ref']==image_ref),None)
                                if asset and asset['kind']=='IMAGE':
                                    return 'data:image/png;base64,'+base64.b64encode((self.output/asset['bundlePath']).read_bytes()).decode('ascii')
                                return None
                            fragment.write_text(safe_fragment(content,image_data),encoding='utf-8')
                            rendered,_=self.convert(fragment,'epub-preview')
                            asset=self.asset(rendered,'image/png','PAGE_PREVIEW',number,number if fixed else None)
                            unit['previewRef']=asset; unit['availableReadingModes']=['ORIGINAL_PREVIEW']
                            self.stage('preview',True)
                            self.warnings.append(warning('PREVIEW_LAYOUT_LIMITED','安全静态预览不保证复刻原书CSS、竖排或全部分页，请核对原件',number))
                        except (ConversionError,OSError):
                            self.stage('preview',False)
                            self.warnings.append(warning('PREVIEW_UNAVAILABLE','此阅读单元的安全预览未生成',number))
                    for original,node in ids.items():
                        local=node.get('id') or next((n.get('id') for n in walk([node]) if n.get('id')),None)
                        if local: anchors[(path,original)]=local
                    paths[path]=number
                except (SecurityFailure,DefusedXmlException): raise
                except Exception:
                    unit=self.add_unit(title,path,[], 'PAGE' if fixed else 'TEXT_SECTION',number if fixed else None,False)
                    self.stage('structure',False); self.stage('text',False)
                    self.warnings.append(warning('UNIT_STRUCTURE_FAILED','此阅读单元未成功解析，保留位置以便重试',number))
            from urllib.parse import unquote
            for unit in self.units:
                for node in walk(unit['nodes']):
                    if node['type']=='link' and node.get('href','').startswith('#'):
                        local=anchors.get((unit['sourcePath'],unquote(node['href'][1:])))
                        if local: node['href']='#'+local
                        else: node.pop('href',None)
            self.outline=read_outline(z,package_path,manifest,paths,anchors,self.warnings)
            if self.outline:
                targets={n['unitNo'] for n in outline_walk(self.outline)}
                for unit in self.units:
                    if unit['unitNo'] in targets and not fixed: unit['unitType']='CHAPTER'

    def word(self,is_doc):
        source=self.path
        if is_doc:
            with source.open('rb') as incoming: header=incoming.read(8)
            if header!=b'\xd0\xcf\x11\xe0\xa1\xb1\x1a\xe1':
                raise DocumentInvalid('旧版DOC必须为实际OLE二进制文档，不支持伪装的RTF或DOCX')
            try:
                derived,_=self.convert(source,'docx','.docx')
                validate_archive(derived,self.limits)
                self.asset(derived,'application/vnd.openxmlformats-officedocument.wordprocessingml.document','DERIVED_DOCX',original='DOC')
                source=derived; self.stage('conversion',True)
            except (ConversionError,OSError):
                self.stage('conversion',False)
                self.warnings.append(warning('DOC_CONVERSION_FAILED','旧版DOC隔离转换未成功，原件保留但无可用阅读模式'))
                return
        validate_archive(source,self.limits)
        with zipfile.ZipFile(source) as z:
            if any(info.filename.lower().endswith('.xml') and info.file_size>self.limits.unit_xml_bytes for info in z.infolist()):
                raise DocumentInvalid('Word XML单元超过安全处理预算')
            root=ET.fromstring(xml_member(z,'word/document.xml',self.limits.unit_xml_bytes))
            if root.tag!=W+'document': raise DocumentInvalid('DOCX正文格式错误')
            types=ET.fromstring(xml_member(z,'[Content_Types].xml'))
            if not any(x.attrib.get('PartName')=='/word/document.xml' and x.attrib.get('ContentType')=='application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml' for x in types):
                raise DocumentInvalid('非标准DOCX正文')
            complex_layout=any(n.tag in {W+'txbxContent',W+'vMerge',W+'footnoteReference',W+'endnoteReference'} for n in root.iter())
            # Active content does not become partial success.
            if any('vbaProject' in n or 'activeX/' in n for n in z.namelist()): raise SecurityFailure('不支持含宏或主动对象的Word文档')
        if is_doc or complex_layout:
            try:
                pdf,_=self.convert(source,'pdf','.pdf')
                self.asset(pdf,'application/pdf','DERIVED_PDF',original='DOC' if is_doc else 'DOCX')
                self.stage('conversion',True)
                if complex_layout:
                    self.pdf(pdf,complex_word=True)
                    self.warnings.append(warning('WORD_LAYOUT_UNRELIABLE','复杂表格、文本框或注释使用PDF衍生预览；不开放未经核对的文字引用'))
                    return
            except (ConversionError,OSError):
                self.stage('conversion',False)
                self.warnings.append(warning('WORD_PREVIEW_FAILED','Word固定版式衍生预览未成功'))
                if complex_layout: return
        try:
            processor=self
            class DocxBuilder(Builder):
                def __init__(self,format):
                    super().__init__(format); self.value['schemaVersion']=2
                def image(self,z,path,mime): return processor.image(z,path,mime,1)
                def warning(self,message): processor.warnings.append(warning('WORD_RESOURCE_OMITTED',message,1))
            result=docx_reading(source.read_bytes(),DocxBuilder('DOCX'))
            self.metadata=result['metadata']; self.stage('metadata',True)
            unit=self.add_unit(result['chapters'][0]['title'],'word/document.xml',result['chapters'][0]['nodes'])
            def map_outline(entries):
                return [{'title':entry['title'],'unitNo':1,'localId':entry['localId'].replace('c1-','u1-',1),'children':map_outline(entry.get('children',[]))} for entry in entries if entry.get('localId')]
            self.outline=map_outline(result['outline'])
            self.stage('structure',True); self.stage('text',bool(unit['blocks']))
        except (SecurityFailure,DefusedXmlException): raise
        except Exception:
            self.stage('structure',False)
            self.warnings.append(warning('WORD_STRUCTURE_FAILED','Word正文结构未成功生成'))

    def finish(self,format):
        for unit in self.units:
            unit['warnings']=[w for w in self.warnings if w.get('unitNo')==unit['unitNo']]
            unit['capabilities']['hasToc']=bool(self.outline)
        modes=list(dict.fromkeys(m for u in self.units for m in u['availableReadingModes']))
        caps={key:any(u['capabilities'][key] for u in self.units) for key in capabilities()}
        caps['hasToc']=bool(self.outline)
        usable=sum(bool(u['availableReadingModes']) for u in self.units)
        failures=any(s['failed'] for s in self.stages.values())
        warnings_present=any(w['severity']!='INFO' for w in self.warnings)
        outcome='FAILED' if not usable else 'PARTIAL_SUCCESS' if failures or warnings_present or usable<len(self.units) else 'SUCCEEDED'
        reading={'schemaVersion':2,'format':format,'layoutMode':self.layout_mode if self.units else 'UNKNOWN',
                 'availableReadingModes':modes,'capabilities':caps,'metadata':self.metadata,'outline':self.outline,
                 'units':self.units,'assets':self.assets,'warnings':self.warnings,'overallOutcome':outcome,'stages':self.stages}
        segments=[]
        for unit in self.units:
            if not unit['capabilities']['searchable']: continue
            for block in unit['blocks']:
                segments.append({'segmentNo':len(segments)+1,'pageLabel':f"第 {unit['pageNumber']} 页" if unit.get('pageNumber') else unit['title'],
                                 'headingPath':unit['title'] if unit['unitType']=='CHAPTER' else None,'body':block['text']})
        digest=hashlib.sha256()
        with self.path.open('rb') as stream:
            while chunk:=stream.read(64*1024): digest.update(chunk)
        result={'parserName':'deterministic-v2','inputSha256':digest.hexdigest(),'inputBytes':self.path.stat().st_size,'segments':segments,'reading':reading}
        manifest=json.dumps(result,ensure_ascii=False,separators=(',',':')).encode()
        if len(manifest)>32*1024*1024: raise DocumentInvalid('处理清单超过大小限制')
        bundle=self.output/'result.zip'
        with zipfile.ZipFile(bundle,'w',zipfile.ZIP_STORED) as z:
            z.writestr('result.json',manifest)
            for asset in self.assets: z.write(self.output/asset['bundlePath'],asset['bundlePath'])
        if bundle.stat().st_size>self.limits.bundle_bytes: raise DocumentInvalid('处理产物超过大小限制')
        return bundle


def outline_walk(nodes):
    for node in nodes:
        yield node
        yield from outline_walk(node.get('children',[]))


def read_outline(z,package_path,manifest,paths,anchors,warnings):
    from urllib.parse import urlsplit,unquote
    def target(base,href,title,children):
        try: path=archive_path(base,href)
        except ValueError: return None
        if path not in paths: return None
        result={'title':title,'unitNo':paths[path],'children':children}
        local=anchors.get((path,unquote(urlsplit(href).fragment)))
        if local: result['localId']=local
        return result
    try:
        nav=next((item for item in manifest.values() if 'nav' in item.attrib.get('properties','').split()),None)
        if nav is not None:
            base=archive_path(package_path,nav.attrib['href']); root=ET.fromstring(xml_member(z,base))
            toc=next((n for n in root.iter(XHTML+'nav') if 'toc' in n.attrib.get('{http://www.idpf.org/2007/ops}type','').split()),None)
            def recurse(ol,depth=0):
                if depth>32: raise ValueError('TOC depth')
                result=[]
                if ol is not None:
                    for li in ol.findall(XHTML+'li'):
                        a=li.find(XHTML+'a')
                        if a is not None:
                            item=target(base,a.attrib.get('href',''),''.join(a.itertext()).strip(),recurse(li.find(XHTML+'ol'),depth+1))
                            if item: result.append(item)
                return result
            result=recurse(toc.find(XHTML+'ol') if toc is not None else None)
            if result:return result
        ncx=next((item for item in manifest.values() if item.attrib.get('media-type')=='application/x-dtbncx+xml'),None)
        if ncx is not None:
            ns='{http://www.daisy.org/z3986/2005/ncx/}'; base=archive_path(package_path,ncx.attrib['href'])
            root=ET.fromstring(xml_member(z,base))
            def recurse(parent,depth=0):
                if depth>32:raise ValueError('TOC depth')
                result=[]
                for point in parent.findall(ns+'navPoint'):
                    content=point.find(ns+'content')
                    if content is not None:
                        item=target(base,content.attrib.get('src',''),point.findtext(ns+'navLabel/'+ns+'text',''),recurse(point,depth+1))
                        if item: result.append(item)
                return result
            navmap=root.find(ns+'navMap')
            if navmap is not None:return recurse(navmap)
    except DefusedXmlException: raise
    except Exception:
        warnings.append(warning('TOC_UNAVAILABLE','原目录未成功读取；不伪造章节目录'))
    return []


def safe_fragment(root,image_data=None):
    """Inert static preview input; never served as HTML to the browser."""
    from html import escape
    allowed={'body','div','section','article','p','h1','h2','h3','h4','h5','h6','ul','ol','li','table','tbody','thead','tr','td','th','strong','em','span','sup','sub','blockquote','pre','code','ruby','rt','rp','figure','figcaption','br','a'}
    def render(node,depth=0):
        if depth>64: raise ValueError('Preview depth')
        tag=node.tag.removeprefix(XHTML)
        if tag=='img':
            data=image_data(node.attrib.get('src','')) if image_data else None
            return '<img src="'+data+'"/>' if data else '<span>[图片暂不可用]</span>'
        if tag not in allowed:return ''
        if tag=='br': return '<br/>'
        text=escape(node.text or '')
        for child in node:text+=render(child,depth+1)+escape(child.tail or '')
        return '<'+tag+'>'+text+'</'+tag+'>'
    return '<!doctype html><html><head><meta charset="utf-8"/><style>@page{size:A4;margin:20mm}body{font-family:sans-serif}table{border-collapse:collapse}td,th{border:1px solid #777}pre{white-space:pre-wrap}</style></head>'+render(root)+'</html>'


def process_file(path,file_name,output,limits=None,converter=None):
    limits=limits or ProcessingLimits(); path=Path(path); output=Path(output)
    if not path.is_file() or not 0<path.stat().st_size<=limits.raw_bytes: raise DocumentInvalid('处理输入须非空且在大小限制内')
    suffix=Path(file_name).suffix.lower()
    processor=Processor(path,file_name,output,limits,converter or IsolatedConverter())
    try:
        if suffix=='.txt':
            with path.open('rb') as source: prefix=source.read(8)
            if prefix.startswith((b'PK',b'%PDF-',b'MZ',b'\xd0\xcf')): raise DocumentInvalid('文件实际格式与TXT不符')
            text=path.read_text(encoding='utf-8-sig').replace('\r\n','\n').replace('\r','\n')
            if any(ord(c)<32 and c not in '\n\r\t\f' for c in text): raise DocumentInvalid('TXT必须是UTF-8文本')
            # Internal sections control payload size without inventing chapters.
            paragraphs=[p for p in re.split(r'\n\s*\n',text) if p.strip()]
            batch=[]; size=0
            for paragraph in paragraphs:
                if batch and size+len(paragraph)>100000:
                    processor.add_unit('正文区段 '+str(len(processor.units)+1),'',batch); batch=[]; size=0
                batch.append({'type':'paragraph','children':[text_node(paragraph)]}); size+=len(paragraph)
            if batch:processor.add_unit('全文' if not processor.units else '正文区段 '+str(len(processor.units)+1),'',batch)
            processor.stage('metadata',True); processor.stage('structure',any(u['nodes'] for u in processor.units)); processor.stage('text',any(u['blocks'] for u in processor.units))
        elif suffix=='.epub':processor.epub()
        elif suffix=='.pdf':
            with path.open('rb') as stream:
                if stream.read(5)!=b'%PDF-': raise DocumentInvalid('文件实际格式与PDF不符')
            processor.pdf(path)
        elif suffix in ('.doc','.docx'):processor.word(suffix=='.doc')
        else:raise DocumentInvalid('仅支持EPUB、PDF、DOC、DOCX、TXT')
        return processor.finish(suffix[1:].upper())
    except DocumentInvalid: raise
    except DefusedXmlException as error: raise SecurityFailure('文档XML安全校验失败') from error
    except Exception as error: raise DocumentInvalid('资料损坏或处理失败') from error
