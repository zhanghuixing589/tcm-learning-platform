import { execFileSync } from "node:child_process";
/** Generated safety fixtures only. They are never reported as real textbook acceptance. */
export function epubFixture(encrypted = false): Buffer {
  return execFileSync(
    "python",
    [
      "-c",
      `
import io,sys,zipfile
out=io.BytesIO()
with zipfile.ZipFile(out,'w') as z:
 z.writestr('mimetype','application/epub+zip')
 z.writestr('META-INF/container.xml','<container xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="OPS/book.opf"/></rootfiles></container>')
 z.writestr('OPS/book.opf','<package xmlns="http://www.idpf.org/2007/opf" version="3.0"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>Generated reader security fixture</dc:title><dc:creator>Test fixture</dc:creator></metadata><manifest><item id="a" href="a.xhtml" media-type="application/xhtml+xml"/><item id="b" href="b.xhtml" media-type="application/xhtml+xml"/><item id="toc" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/></manifest><spine><itemref idref="b"/><itemref idref="a"/></spine></package>')
 z.writestr('OPS/nav.xhtml','<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops"><body><nav epub:type="toc"><ol><li><a href="b.xhtml#first">First unit</a></li><li><a href="a.xhtml#second">Second unit</a></li></ol></nav></body></html>')
 z.writestr('OPS/b.xhtml','<html xmlns="http://www.w3.org/1999/xhtml"><body><h1 id="first">First unit</h1><p>Canonical first paragraph &#x1F600; with repeated evidence.</p><p>Canonical second paragraph for cross-block selection.</p><script>window.sourceReaderAttack=true;alert(1)</script><img src="https://source-reader.invalid/beacon.png" onerror="window.sourceReaderAttack=true"/><style>body{background-image:url(https://source-reader.invalid/css.png)}</style><iframe src="https://source-reader.invalid/frame"/></body></html>')
 z.writestr('OPS/a.xhtml','<html xmlns="http://www.w3.org/1999/xhtml"><body><h1 id="second">Second unit</h1><p>Reliable second unit text.</p><figure><figcaption>Image placeholder caption</figcaption></figure><aside id="note">Safe footnote.</aside><p>&lt;img src=x onerror=alert(1)&gt;</p></body></html>')
 ${encrypted ? "z.writestr('META-INF/encryption.xml','<encryption/>')" : "pass"}
sys.stdout.buffer.write(out.getvalue())
`,
    ],
    { windowsHide: true },
  );
}
export function docxFixture(): Buffer {
  return execFileSync(
    "python",
    [
      "-c",
      `
import io,sys,zipfile
out=io.BytesIO()
with zipfile.ZipFile(out,'w') as z:
 z.writestr('[Content_Types].xml','<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/></Types>')
 z.writestr('_rels/.rels','<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/></Relationships>')
 z.writestr('word/document.xml','<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body><w:p><w:pPr><w:pStyle w:val="Heading1"/></w:pPr><w:r><w:t>DOCX fixture heading</w:t></w:r></w:p><w:p><w:r><w:t>Reliable DOCX paragraph for actual parser acceptance.</w:t></w:r></w:p><w:sectPr/></w:body></w:document>')
sys.stdout.buffer.write(out.getvalue())
`,
    ],
    { windowsHide: true },
  );
}
/** Two physical pages, the second intentionally contains no text operators. */
export function mixedPdfFixture(): Buffer {
  const streams = [
    "BT /F1 16 Tf 48 740 Td (Reliable PDF first page.) Tj ET",
    "0.8 g 48 500 300 180 re f",
  ];
  const objects = [
    "<< /Type /Catalog /Pages 2 0 R >>",
    "<< /Type /Pages /Kids [3 0 R 4 0 R] /Count 2 >>",
    "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Resources << /Font << /F1 5 0 R >> >> /Contents 6 0 R >>",
    "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Resources << /Font << /F1 5 0 R >> >> /Contents 7 0 R >>",
    "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>",
    ...streams.map(
      (s) => `<< /Length ${Buffer.byteLength(s)} >>\nstream\n${s}\nendstream`,
    ),
  ];
  let text = "%PDF-1.4\n";
  const offsets = [0];
  for (let i = 0; i < objects.length; i++) {
    offsets.push(Buffer.byteLength(text));
    text += `${i + 1} 0 obj\n${objects[i]}\nendobj\n`;
  }
  const xref = Buffer.byteLength(text);
  text += `xref\n0 ${objects.length + 1}\n0000000000 65535 f \n${offsets
    .slice(1)
    .map((n) => String(n).padStart(10, "0") + " 00000 n \n")
    .join(
      "",
    )}trailer\n<< /Size ${objects.length + 1} /Root 1 0 R >>\nstartxref\n${xref}\n%%EOF\n`;
  return Buffer.from(text);
}
