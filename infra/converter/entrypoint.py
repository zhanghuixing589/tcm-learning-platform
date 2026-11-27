"""One input, no network, no shell. Run only inside the restricted job container."""
import hashlib
import base64
import io
import json
import os
from pathlib import Path
import re
import resource
import subprocess
import sys
import warnings
import zipfile
from defusedxml import ElementTree as ET

MAX_OUTPUT = 200 * 1024 * 1024
MAX_IMAGE = 20 * 1024 * 1024
MAX_PIXELS = 24_000_000
resource.setrlimit(resource.RLIMIT_FSIZE, (MAX_IMAGE, MAX_IMAGE))
resource.setrlimit(resource.RLIMIT_NOFILE, (256, 256))
resource.setrlimit(resource.RLIMIT_CORE, (0, 0))


def run(args, timeout=90):
    # Never emit stdout/stderr: conversion tools may echo private book text.
    subprocess.run(args, check=True, stdout=subprocess.DEVNULL,
                   stderr=subprocess.DEVNULL, timeout=timeout)


def image(source, output):
    from PIL import Image, ImageOps
    Image.MAX_IMAGE_PIXELS = MAX_PIXELS
    warnings.simplefilter('error', Image.DecompressionBombWarning)
    data = source.read_bytes()
    original_format=None
    if len(data) > MAX_IMAGE:
        raise ValueError('MEDIA_INPUT_LIMIT')
    if source.suffix.lower() == '.svg' or data.lstrip().startswith(b'<svg'):
        original_format='SVG'
        # Build an inert SVG tree; no style/font/image/use/filter/foreignObject,
        # event handlers, URL paint servers or external resource references.
        root = ET.fromstring(data)
        allowed = {'svg','g','path','rect','circle','ellipse','line','polyline','polygon','text','tspan','title','desc'}
        attrs = {'width','height','viewBox','x','y','x1','y1','x2','y2','cx','cy','r','rx','ry','d','points','fill','stroke','stroke-width','opacity','transform','font-size','text-anchor'}
        def sanitize(node, depth=0):
            if depth > 64: raise ValueError('MEDIA_SVG_DEPTH')
            tag = node.tag.split('}')[-1]
            if tag not in allowed: return False
            for key in list(node.attrib):
                value=node.attrib[key]
                if key not in attrs or re.search(r'url\s*\(|https?:|file:|data:|javascript:|@',value,re.I):
                    del node.attrib[key]
            for child in list(node):
                if not sanitize(child,depth+1): node.remove(child)
            return True
        if not sanitize(root): raise ValueError('MEDIA_SVG_INVALID')
        width=root.attrib.get('width','800'); height=root.attrib.get('height','800')
        def dimension(v):
            match=re.fullmatch(r'\s*([0-9.]+)(?:px)?\s*',v)
            return float(match[1]) if match else 800
        if dimension(width)*dimension(height)>MAX_PIXELS: raise ValueError('MEDIA_PIXEL_LIMIT')
        import cairosvg
        data=cairosvg.svg2png(bytestring=ET.tostring(root),output_width=min(int(dimension(width)),4096),output_height=min(int(dimension(height)),4096))
    with Image.open(io.BytesIO(data)) as original:
        if original.width*original.height>MAX_PIXELS: raise ValueError('MEDIA_PIXEL_LIMIT')
        frames=getattr(original,'n_frames',1)
        if frames>200: raise ValueError('MEDIA_FRAME_LIMIT')
        original.seek(0)
        normalized=ImageOps.exif_transpose(original).convert('RGBA')
        normalized.info.clear()
        normalized.save(output,'PNG',optimize=False)
        return {'frames':frames,'width':normalized.width,'height':normalized.height,'originalFormat':original_format or original.format,'sanitized':original_format=='SVG'}


def office(source, output, target):
    home=Path('/tmp/home'); home.mkdir(parents=True,exist_ok=True)
    profile=Path('/work/profile/user'); profile.mkdir(parents=True,exist_ok=True)
    (profile/'registrymodifications.xcu').write_text('''<?xml version="1.0"?><oor:items xmlns:oor="http://openoffice.org/2001/registry"><item oor:path="/org.openoffice.Office.Common/Security/Scripting"><prop oor:name="MacroSecurityLevel" oor:op="fuse"><value>3</value></prop></item><item oor:path="/org.openoffice.Office.Writer/Content/Update"><prop oor:name="Link" oor:op="fuse"><value>2</value></prop></item><item oor:path="/org.openoffice.Office.Common/Security/Scripting"><prop oor:name="DisableMacrosExecution" oor:op="fuse"><value>true</value></prop></item></oor:items>''')
    destination=Path('/work/converted'); destination.mkdir(exist_ok=True)
    filter_name='docx:Office Open XML Text' if target=='docx' else 'pdf:writer_pdf_Export'
    run(['libreoffice','-env:UserInstallation=file:///work/profile','--headless','--nologo','--nodefault','--nofirststartwizard','--nolockcheck','--convert-to',filter_name,'--outdir',str(destination),str(source)])
    result=destination/(source.stem+'.'+target)
    if not result.exists() or not result.stat().st_size: raise ValueError('CONVERSION_NO_OUTPUT')
    output.write_bytes(result.read_bytes())
    return {'format':target.upper()}


def preview(source, output, page):
    if not 1<=page<=500: raise ValueError('PAGE_LIMIT')
    prefix=Path('/work/page')
    run(['pdftoppm','-f',str(page),'-l',str(page),'-singlefile','-scale-to','1600','-png',str(source),str(prefix)],45)
    return image(prefix.with_suffix('.png'),output)


def epub_preview(source, output):
    from weasyprint import HTML
    # The caller supplies a sanitized, archive-only fragment. This second
    # boundary refuses every fetch, including file/data URLs and CSS imports.
    def no_fetch(url, *args, **kwargs):
        if url.startswith('data:image/png;base64,') and len(url)<28*1024*1024:
            data=base64.b64decode(url.split(',',1)[1],validate=True)
            if data.startswith(b'\x89PNG\r\n\x1a\n') and len(data)<=MAX_IMAGE:
                return {'string':data,'mime_type':'image/png'}
        raise ValueError('EXTERNAL_RESOURCE_BLOCKED')
    pdf=Path('/work/fragment.pdf')
    HTML(filename=str(source),url_fetcher=no_fetch).write_pdf(str(pdf))
    return preview(pdf,output,1)


def main():
    operation=sys.argv[1]
    suffix=sys.argv[2]
    if not re.fullmatch(r'\.[a-z0-9]{1,5}',suffix): raise ValueError('INVALID_SUFFIX')
    source=Path('/input/input'+suffix)
    out=Path('/output'); out.mkdir(exist_ok=True)
    target=out/('result.docx' if operation=='docx' else 'result.pdf' if operation=='pdf' else 'result.png')
    if operation in ('docx','pdf'): result=office(source,target,operation)
    elif operation=='preview': result=preview(source,target,int(sys.argv[3]))
    elif operation=='epub-preview': result=epub_preview(source,target)
    elif operation=='image': result=image(source,target)
    else: raise ValueError('UNKNOWN_OPERATION')
    size=target.stat().st_size
    if size>MAX_OUTPUT: raise ValueError('OUTPUT_LIMIT')
    result.update(bytes=size,sha256=hashlib.sha256(target.read_bytes()).hexdigest())
    (out/'result.json').write_text(json.dumps(result))
    # stdout is a bounded binary ZIP protocol, never tool logs. All writable
    # conversion files remain in quota-limited container tmpfs until exit.
    with zipfile.ZipFile(sys.stdout.buffer,'w',zipfile.ZIP_STORED) as bundle:
        bundle.write(out/'result.json','result.json')
        bundle.write(target,target.name)


if __name__=='__main__':
    try:
        main()
    except Exception:
        # Typed stage errors are reported by the host without leaking paths.
        sys.exit(2)
