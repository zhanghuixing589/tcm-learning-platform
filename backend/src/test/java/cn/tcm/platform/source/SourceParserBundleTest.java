package cn.tcm.platform.source;

import cn.tcm.platform.common.ApiException;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import java.io.*;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class SourceParserBundleTest {
    @TempDir Path temporary;
    ObjectMapper json=new ObjectMapper();SourceObjectStore store=mock(SourceObjectStore.class);SourceJobs jobs=mock(SourceJobs.class);
    SourceJobs.Lease lease=new SourceJobs.Lease(1,2,3,"test-token");
    Map<String,Object> original=Map.of("sha256","a".repeat(64),"byte_size",7L);
    SourceParser parser(){when(jobs.validLease(lease)).thenReturn(true);return new SourceParser(mock(SourceAccess.class),json,store,jobs,SourceLimits.DEFAULTS,"http://127.0.0.1:1","private",temporary.toString());}
    ObjectNode result()throws Exception{
        return (ObjectNode)json.readTree("""
            {"parserName":"deterministic-v2","inputSha256":"%s","inputBytes":7,"segments":[{"segmentNo":1,"body":"正文"}],"reading":{"schemaVersion":2,"format":"TXT","layoutMode":"REFLOWABLE","overallOutcome":"SUCCEEDED","availableReadingModes":["PLAIN_TEXT"],"capabilities":{"textSelectable":true},"metadata":{},"outline":[],"warnings":[],"stages":{},"units":[{"unitNo":1,"unitType":"TEXT_SECTION","title":"全文","nodes":[{"type":"paragraph","id":"u1-b1","children":[{"type":"text","text":"正文"}]}],"blocks":[{"blockNo":1,"localId":"u1-b1","text":"正文"}],"capabilities":{"preciseCitationSupported":true},"availableReadingModes":["PLAIN_TEXT"]}],"assets":[]}}
            """.formatted("a".repeat(64)));
    }
    Path bundle(ObjectNode value,Map<String,byte[]> extras)throws Exception{
        Path path=Files.createTempFile(temporary,"test-",".zip");try(var zip=new ZipOutputStream(Files.newOutputStream(path))){entry(zip,"result.json",json.writeValueAsBytes(value));for(var extra:extras.entrySet())entry(zip,extra.getKey(),extra.getValue());}return path;
    }
    void entry(ZipOutputStream zip,String path,byte[] body)throws IOException{zip.putNextEntry(new ZipEntry(path));zip.write(body);zip.closeEntry();}
    @Test void acceptsBoundedManifestAndRejectsWrongOriginalOrUndeclaredPaths()throws Exception{
        var parser=parser();var good=result();assertEquals("正文",parser.ingestBundle(lease,bundle(good,Map.of()),original,temporary).segments().get(0).body());
        var wrong=result();wrong.put("inputSha256","b".repeat(64));assertThrows(IOException.class,()->parser.ingestBundle(lease,bundle(wrong,Map.of()),original,temporary));
        assertThrows(IOException.class,()->parser.ingestBundle(lease,bundle(result(),Map.of("../escape",new byte[]{1})),original,temporary));
        assertThrows(IOException.class,()->parser.ingestBundle(lease,bundle(result(),Map.of("extra.txt",new byte[]{1})),original,temporary));verifyNoInteractions(store);
    }
    @Test void validatesActualImageThenStreamsPrivateAssetAndRejectsSpoofedMime()throws Exception{
        var parser=parser();var image=new BufferedImage(4,3,BufferedImage.TYPE_INT_RGB);var output=new ByteArrayOutputStream();ImageIO.write(image,"png",output);byte[] png=output.toByteArray();
        var value=result();var reading=(ObjectNode)value.path("reading");var asset=reading.withArray("assets").addObject();asset.put("ref","asset-1").put("bundlePath","assets/a.png").put("mimeType","image/png").put("kind","IMAGE").put("unitNo",1);
        ((ArrayNode)reading.path("units").get(0).path("nodes")).addObject().put("type","image").put("assetRef","asset-1");
        when(store.put(anyString(),any(InputStream.class),eq((long)png.length),eq("image/png"))).thenReturn(new SourceObjectStore.Stored("MINIO","private","reading/key"));
        var parsed=parser.ingestBundle(lease,bundle(value,Map.of("assets/a.png",png)),original,temporary);assertEquals(4,parsed.assets().get(0).width());assertEquals(3,parsed.assets().get(0).height());
        asset.put("mimeType","image/jpeg");assertThrows(IOException.class,()->parser.ingestBundle(lease,bundle(value,Map.of("assets/a.png",png)),original,temporary));
    }
    @Test void staleLeaseRejectsBeforeAssetPersistence()throws Exception{
        var parser=parser();when(jobs.validLease(lease)).thenReturn(false);assertThrows(ApiException.class,()->parser.ingestBundle(lease,bundle(result(),Map.of()),original,temporary));verifyNoInteractions(store);
    }
}
