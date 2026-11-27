package cn.tcm.platform.source;

import cn.tcm.platform.common.ApiException;
import org.junit.jupiter.api.Test;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.zip.*;
import static org.junit.jupiter.api.Assertions.*;

class EpubValidationTest {
    private byte[] book(String href, boolean encrypted) throws IOException {
        var out = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(out)) {
            entry(zip,"mimetype","application/epub+zip");
            entry(zip,"META-INF/container.xml","<container xmlns='urn:oasis:names:tc:opendocument:xmlns:container'><rootfiles><rootfile full-path='OPS/book.opf'/></rootfiles></container>");
            entry(zip,"OPS/book.opf","<package xmlns='http://www.idpf.org/2007/opf'><manifest><item id='c' href='"+href+"' media-type='application/xhtml+xml'/></manifest><spine><itemref idref='c'/></spine></package>");
            entry(zip,"OPS/a.xhtml","<html xmlns='http://www.w3.org/1999/xhtml'><body><p>中医书籍正文</p></body></html>");
            if (encrypted) entry(zip,"META-INF/encryption.xml","<encryption/>");
        }
        return out.toByteArray();
    }
    private void entry(ZipOutputStream zip,String name,String body) throws IOException {
        zip.putNextEntry(new ZipEntry(name)); zip.write(body.getBytes(StandardCharsets.UTF_8)); zip.closeEntry();
    }
    @Test void acceptsRealEpub() throws Exception {
        assertEquals("application/epub+zip",SourceFiles.validate("中医.EPUB",book("a.xhtml",false)));
    }
    // Some EPUB writers set bit 3 on STORED entries. ZipInputStream cannot read
    // them, although their sizes and checksums are available in the central directory.
    private byte[] storedDescriptorBook() throws IOException {
        record Raw(byte[] name, byte[] body, long crc, int offset) {}
        var entries=new ArrayList<Raw>();
        var bytes=ByteBuffer.allocate(32768).order(ByteOrder.LITTLE_ENDIAN);
        try(var input=new ZipInputStream(new ByteArrayInputStream(book("a.xhtml",false)))) {
            ZipEntry entry;
            while((entry=input.getNextEntry())!=null) {
                byte[] name=entry.getName().getBytes(StandardCharsets.UTF_8),body=input.readAllBytes();
                var crc=new CRC32();crc.update(body);
                entries.add(new Raw(name,body,crc.getValue(),bytes.position()));
                bytes.putInt(0x04034b50).putShort((short)20).putShort((short)8).putShort((short)0)
                    .putShort((short)0).putShort((short)0).putInt(0).putInt(0).putInt(0)
                    .putShort((short)name.length).putShort((short)0).put(name).put(body)
                    .putInt(0x08074b50).putInt((int)crc.getValue()).putInt(body.length).putInt(body.length);
            }
        }
        int central=bytes.position();
        for(var entry:entries) {
            bytes.putInt(0x02014b50).putShort((short)20).putShort((short)20).putShort((short)8)
                .putShort((short)0).putShort((short)0).putShort((short)0).putInt((int)entry.crc())
                .putInt(entry.body().length).putInt(entry.body().length).putShort((short)entry.name().length)
                .putShort((short)0).putShort((short)0).putShort((short)0).putShort((short)0)
                .putInt(0).putInt(entry.offset()).put(entry.name());
        }
        int centralSize=bytes.position()-central;
        bytes.putInt(0x06054b50).putShort((short)0).putShort((short)0)
            .putShort((short)entries.size()).putShort((short)entries.size())
            .putInt(centralSize).putInt(central).putShort((short)0);
        return java.util.Arrays.copyOf(bytes.array(),bytes.position());
    }
    @Test void acceptsStoredEntriesWithDataDescriptors() throws Exception {
        assertEquals("application/epub+zip",SourceFiles.validate("descriptor.epub",storedDescriptorBook()));
    }
    @Test void rejectsMissingCentralDirectoryAndCorruptChecksum() throws Exception {
        byte[] original=book("a.xhtml",false);
        assertThrows(ApiException.class,()->SourceFiles.validate("truncated.epub",java.util.Arrays.copyOf(original,original.length-22)));
        byte[] corrupt=storedDescriptorBook();
        var buffer=ByteBuffer.wrap(corrupt).order(ByteOrder.LITTLE_ENDIAN);
        for(int i=0;i<corrupt.length-20;i++) {
            if(buffer.getInt(i)==0x02014b50) {corrupt[i+16]^=1;break;}
        }
        assertThrows(ApiException.class,()->SourceFiles.validate("corrupt.epub",corrupt));
    }
    @Test void rejectsFakeEncryptedAndEscapingEpub() throws Exception {
        assertThrows(ApiException.class,()->SourceFiles.validate("fake.epub",new byte[]{1,2,3}));
        assertThrows(ApiException.class,()->SourceFiles.validate("private.epub",book("a.xhtml",true)));
        assertThrows(ApiException.class,()->SourceFiles.validate("escape.epub",book("../../outside.xhtml",false)));
        assertThrows(ApiException.class,()->SourceFiles.validate("remote.epub",book("https://example.com/a.xhtml",false)));
    }
    @Test void rejectsCompressionBomb() throws Exception {
        var out=new ByteArrayOutputStream();
        try(var zip=new ZipOutputStream(out)) {
            entry(zip,"mimetype","application/epub+zip");
            entry(zip,"bomb","a".repeat(1_000_000));
        }
        assertThrows(ApiException.class,()->SourceFiles.validate("bomb.epub",out.toByteArray()));
    }
}
