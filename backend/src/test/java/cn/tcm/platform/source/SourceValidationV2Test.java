package cn.tcm.platform.source;

import cn.tcm.platform.common.ApiException;
import java.io.*;
import java.nio.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.*;
import org.apache.pdfbox.pdmodel.*;
import org.apache.poi.poifs.filesystem.POIFSFileSystem;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.multipart.MultipartFile;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class SourceValidationV2Test {
    @TempDir Path root;
    @Test void acceptsScanPdfWithoutInventingText()throws Exception{
        Path pdf=root.resolve("scan.pdf");try(var doc=new PDDocument()){doc.addPage(new PDPage());doc.save(pdf.toFile());}
        assertEquals("application/pdf",SourceFiles.validate("scan.pdf",pdf,SourceLimits.DEFAULTS));
    }
    private byte[] doc(int flags)throws Exception{
        var out=new ByteArrayOutputStream();try(var ole=new POIFSFileSystem()){
            byte[] fib=new byte[512];var header=ByteBuffer.wrap(fib).order(ByteOrder.LITTLE_ENDIAN);header.putShort(0,(short)0xA5EC);header.putShort(2,(short)0xC1);header.putShort(10,(short)flags);
            ole.getRoot().createDocument("WordDocument",new ByteArrayInputStream(fib));ole.getRoot().createDocument("0Table",new ByteArrayInputStream(new byte[512]));ole.writeFilesystem(out);
        }return out.toByteArray();
    }
    @Test void distinguishesBinaryWordAndRejectsEncryptedOrRenamedData()throws Exception{
        assertEquals("application/msword",SourceFiles.validate("book.doc",doc(0)));
        assertThrows(ApiException.class,()->SourceFiles.validate("book.doc",doc(0x100)));
        assertThrows(ApiException.class,()->SourceFiles.validate("book.doc","{\\rtf1 text}".getBytes(StandardCharsets.US_ASCII)));
        assertThrows(ApiException.class,()->SourceFiles.validate("book.docx",doc(0)));
    }
    @Test void zipBudgetAndTraversalRemainEnforced()throws Exception{
        Path zip=root.resolve("limits.zip");try(var out=new ZipOutputStream(Files.newOutputStream(zip))){entry(out,"one",new byte[64]);entry(out,"two",new byte[64]);}
        assertThrows(IOException.class,()->new SourceArchive(zip,new SourceLimits(1024,100,5,200)));
        assertThrows(IOException.class,()->new SourceArchive(zip,new SourceLimits(1024,1024,1,200)));
        Path traversal=root.resolve("traversal.zip");try(var out=new ZipOutputStream(Files.newOutputStream(traversal))){entry(out,"../escape",new byte[1]);}
        assertThrows(IOException.class,()->new SourceArchive(traversal,SourceLimits.DEFAULTS));
    }
    private void entry(ZipOutputStream out,String name,byte[] body)throws IOException{out.putNextEntry(new ZipEntry(name));out.write(body);out.closeEntry();}
    @Test void uploadStreamsAboveOldTenMiBLimitAndCleansStaging()throws Exception{
        Path epub=root.resolve("large.epub");try(var out=new ZipOutputStream(Files.newOutputStream(epub))){
            entry(out,"mimetype","application/epub+zip".getBytes(StandardCharsets.US_ASCII));
            entry(out,"META-INF/container.xml","<container xmlns='urn:oasis:names:tc:opendocument:xmlns:container'><rootfiles><rootfile full-path='book.opf'/></rootfiles></container>".getBytes(StandardCharsets.UTF_8));
            entry(out,"book.opf","<package xmlns='http://www.idpf.org/2007/opf'><manifest><item id='c' href='one.xhtml' media-type='application/xhtml+xml'/></manifest><spine><itemref idref='c'/></spine></package>".getBytes(StandardCharsets.UTF_8));
            entry(out,"one.xhtml","<html xmlns='http://www.w3.org/1999/xhtml'><body><p>真实正文</p></body></html>".getBytes(StandardCharsets.UTF_8));
            out.putNextEntry(new ZipEntry("images/large.bin"));byte[] chunk=new byte[8192];var random=new Random(1234);for(int i=0;i<1400;i++){random.nextBytes(chunk);out.write(chunk);}out.closeEntry();
        }
        long size=Files.size(epub);assertTrue(size>10*1024*1024);
        var db=mock(JdbcTemplate.class);var access=new SourceAccess(db);var store=mock(SourceObjectStore.class);
        // Vararg queries use concrete argument arities in JdbcTemplate.
        when(db.queryForObject(contains("sys_user_roles"),eq(Long.class),eq(7L),eq("contribution:submit"))).thenReturn(1L);
        when(db.queryForMap(anyString(),any())).thenReturn(Map.of("id",12L,"uploaded_by",7L,"mime_type","application/epub+zip","byte_size",size));
        when(db.queryForObject(contains("file_upload_grants"),eq(Long.class),eq(12L),eq(7L))).thenReturn(0L);
        when(db.queryForObject(contains("original_name"),eq(String.class),eq(12L),eq(7L))).thenReturn("large.epub");
        when(store.put(anyString(),any(InputStream.class),eq(size),eq("application/epub+zip"))).thenAnswer(inv->{try(var input=(InputStream)inv.getArgument(1)){assertEquals(size,input.transferTo(OutputStream.nullOutputStream()));}return new SourceObjectStore.Stored("MINIO","tcm-learning-sources","originals/test");});
        var file=new MultipartFile(){public String getName(){return "file";}public String getOriginalFilename(){return "large.epub";}public String getContentType(){return "application/epub+zip";}public boolean isEmpty(){return false;}public long getSize(){return size;}public byte[] getBytes(){throw new AssertionError("Original must stream");}public InputStream getInputStream()throws IOException{return Files.newInputStream(epub);}public void transferTo(File dest)throws IOException{Files.copy(epub,dest.toPath());}};
        Path staging=root.resolve("staging");var files=new SourceFiles(access,store,SourceLimits.DEFAULTS,staging.toString());assertEquals("12",files.upload(7,file).get("id"));
        try(var entries=Files.list(staging)){assertEquals(0,entries.count());}
        verify(store).put(anyString(),any(InputStream.class),eq(size),eq("application/epub+zip"));
    }
    @Test void originalIntegrityAndTemporaryFailureCloseStreams()throws Exception{
        byte[] body="original".getBytes(StandardCharsets.UTF_8);var db=mock(JdbcTemplate.class);var store=mock(SourceObjectStore.class);Path staging=root.resolve("original-temp");
        var files=new SourceFiles(new SourceAccess(db),store,SourceLimits.DEFAULTS,staging.toString());
        Map<String,Object> row=new HashMap<>(Map.of("storage_backend","MINIO","storage_bucket","tcm-learning-sources","storage_key","originals/key","original_name","book.txt","mime_type","text/plain","sha256",SourceFiles.hash(body),"byte_size",body.length));
        when(store.open(anyString(),anyString(),anyString())).thenAnswer(inv->new ByteArrayInputStream(body));
        var original=files.openOriginal(row);try(var input=original.stream()){assertArrayEquals(body,input.readAllBytes());}
        try(var entries=Files.list(staging)){assertEquals(0,entries.count());}
        row.put("sha256","a".repeat(64));assertThrows(ApiException.class,()->files.openOriginal(row));
        try(var entries=Files.list(staging)){assertEquals(0,entries.count());}
        Path blocked=root.resolve("not-a-directory");Files.writeString(blocked,"x");boolean[] closed={false};
        when(store.open(anyString(),anyString(),anyString())).thenReturn(new ByteArrayInputStream(body){public void close(){closed[0]=true;}});
        var broken=new SourceFiles(new SourceAccess(db),store,SourceLimits.DEFAULTS,blocked.toString());assertThrows(ApiException.class,()->broken.openOriginal(row));assertTrue(closed[0]);
    }
}
