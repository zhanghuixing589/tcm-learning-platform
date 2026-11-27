package cn.tcm.platform.source;

import org.junit.jupiter.api.Test;
import java.io.*;
import java.util.zip.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class SourceFileMetadataTest {
    private byte[] book(String metadata)throws IOException{
        var output=new ByteArrayOutputStream();try(var zip=new ZipOutputStream(output)){
            entry(zip,"META-INF/container.xml","<container xmlns='urn:oasis:names:tc:opendocument:xmlns:container'><rootfiles><rootfile full-path='OPS/book.opf'/></rootfiles></container>");
            entry(zip,"OPS/book.opf","<package xmlns='http://www.idpf.org/2007/opf' xmlns:dc='http://purl.org/dc/elements/1.1/'><metadata>"+metadata+"</metadata><manifest><item id='c' href='one.xhtml' media-type='application/xhtml+xml'/><item id='nav' properties='nav' href='nav.xhtml' media-type='application/xhtml+xml'/></manifest><spine><itemref idref='c'/></spine></package>");
            entry(zip,"OPS/one.xhtml","<html xmlns='http://www.w3.org/1999/xhtml'><body><h1>第一章</h1></body></html>");
            entry(zip,"OPS/nav.xhtml","<html xmlns='http://www.w3.org/1999/xhtml' xmlns:epub='http://www.idpf.org/2007/ops'><body><nav epub:type='toc'><ol><li><a href='one.xhtml#start'>原目录</a></li></ol></nav></body></html>");
        }return output.toByteArray();
    }
    private void entry(ZipOutputStream zip,String name,String body)throws IOException{zip.putNextEntry(new ZipEntry(name));zip.write(body.getBytes(StandardCharsets.UTF_8));zip.closeEntry();}
    @Test void suggestsEmbeddedBibliographyAndNavigationWithoutGrantingRights()throws Exception{
        var data=SourceFileMetadata.read(book("<dc:title>中医原书</dc:title><dc:creator>作者</dc:creator><dc:publisher>出版社</dc:publisher><dc:date>2024-01-01</dc:date><dc:rights>保留所有权利</dc:rights>"),"application/epub+zip","fallback.epub");
        assertEquals("中医原书",data.get("title"));assertEquals("作者",data.get("author"));assertEquals(2024,data.get("publicationYear"));assertEquals("保留所有权利",data.get("rightsText"));
        @SuppressWarnings("unchecked") var outline=(List<Map<String,Object>>)data.get("outline");assertEquals("原目录",outline.get(0).get("title"));assertEquals(1,outline.get(0).get("chapterNo"));assertFalse(data.containsKey("publicReadingAllowed"));
    }
    @Test void malformedXmlRemainsSuggestionFailure()throws Exception{
        var data=SourceFileMetadata.read(book("<dc:title>&secret;</dc:title>"),"application/epub+zip","fallback.epub");assertEquals("fallback",data.get("title"));assertTrue(((List<?>)data.get("warnings")).size()>=2);
    }
}
