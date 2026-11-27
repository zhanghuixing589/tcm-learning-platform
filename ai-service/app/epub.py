"""EPUB text in spine order. Archive-only reads, no HTML rendering or network."""
import io
import posixpath
import zipfile
from urllib.parse import unquote, urlsplit
from defusedxml import ElementTree as ET

OPF = '{http://www.idpf.org/2007/opf}'
XHTML = '{http://www.w3.org/1999/xhtml}'


def archive_path(base, href):
    ref = urlsplit(href)
    path = unquote(ref.path)
    if ref.scheme or ref.netloc or not path or path.startswith('/') or '\\' in path:
        raise ValueError('Invalid EPUB path')
    result = posixpath.normpath(posixpath.join(posixpath.dirname(base), path))
    if result == '..' or result.startswith('../'):
        raise ValueError('EPUB path escapes archive')
    return result


def epub_segments(body):
    with zipfile.ZipFile(io.BytesIO(body)) as z:
        infos = z.infolist()
        if (len(infos) > 2000 or len({x.filename for x in infos}) != len(infos)
                or sum(x.file_size for x in infos) > 32 * 1024 * 1024
                or any(x.flag_bits & 1 or x.file_size > max(x.compress_size, 1) * 200 for x in infos)):
            raise ValueError('EPUB archive limit')
        for entry in infos:
            if archive_path('', entry.filename.rstrip('/')) != entry.filename.rstrip('/'):
                raise ValueError('Invalid EPUB entry')
        if z.read('mimetype') != b'application/epub+zip' or 'META-INF/encryption.xml' in z.namelist():
            raise ValueError('Not an unencrypted EPUB')
        container = ET.fromstring(z.read('META-INF/container.xml'))
        roots = container.findall('.//{urn:oasis:names:tc:opendocument:xmlns:container}rootfile')
        if not roots:
            raise ValueError('Missing EPUB package')
        package_path = archive_path('', roots[0].attrib['full-path'])
        package = ET.fromstring(z.read(package_path))
        if package.tag != OPF + 'package':
            raise ValueError('Invalid EPUB package')
        title = package.findtext('.//{http://purl.org/dc/elements/1.1/}title', '').strip()
        manifest = {}
        for item in package.findall(OPF + 'manifest/' + OPF + 'item'):
            key = item.attrib['id']
            if key in manifest:
                raise ValueError('Duplicate EPUB item')
            manifest[key] = item
        result = []; total = 0
        for chapter_no, ref in enumerate(package.findall(OPF + 'spine/' + OPF + 'itemref'), 1):
            item = manifest[ref.attrib['idref']]
            if item.attrib.get('media-type') != 'application/xhtml+xml':
                raise ValueError('Unsupported EPUB chapter')
            chapter = ET.fromstring(z.read(archive_path(package_path, item.attrib['href'])))
            content = chapter.find(XHTML + 'body')
            if chapter.tag != XHTML + 'html' or content is None:
                raise ValueError('Invalid EPUB XHTML')
            # Remove non-content subtrees, preserving their trailing visible text.
            for parent in list(content.iter()):
                for child in list(parent):
                    if child.tag in {XHTML + x for x in ('script', 'style', 'iframe', 'object', 'nav')}:
                        index = list(parent).index(child)
                        if index:
                            previous = list(parent)[index - 1]
                            previous.tail = (previous.tail or '') + (child.tail or '')
                        else:
                            parent.text = (parent.text or '') + (child.tail or '')
                        parent.remove(child)
            headings = {}; paragraph = 0
            blocks = {XHTML + x for x in ('body', 'div', 'p', 'li', 'blockquote', 'td', 'h1', 'h2', 'h3', 'h4', 'h5', 'h6')}
            def text_blocks(node):
                if not any(child.tag in blocks for child in list(node.iter())[1:]):
                    yield node, ''.join(node.itertext())
                    return
                if node.text and node.text.strip():
                    yield node, node.text
                for child in node:
                    yield from text_blocks(child)
                    if child.tail and child.tail.strip():
                        yield node, child.tail

            for node, raw_text in text_blocks(content):
                text = raw_text.strip()
                if not text:
                    continue
                total += len(text); paragraph += 1
                if total > 2_000_000 or len(result) >= 10_000:
                    raise ValueError('EPUB text limit')
                tag = node.tag.removeprefix(XHTML)
                if tag in ('h1', 'h2', 'h3', 'h4', 'h5', 'h6'):
                    level = int(tag[1]); headings = {k: v for k, v in headings.items() if k < level}
                    headings[level] = text
                heading = ' / '.join(([title] if title else []) + list(headings.values()))[:500] or None
                result.append((f'章节 {chapter_no} · 段落 {paragraph}', heading, text))
        return result
