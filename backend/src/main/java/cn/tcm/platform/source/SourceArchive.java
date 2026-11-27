package cn.tcm.platform.source;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.zip.*;

/** File-backed ZIP validation: entry payloads are streamed, never accumulated. */
final class SourceArchive implements AutoCloseable {
    private final ZipFile zip;
    private final Map<String,ZipEntry> entries=new LinkedHashMap<>();
    SourceArchive(Path file,SourceLimits limits)throws IOException{
        zip=new ZipFile(file.toFile(),StandardCharsets.UTF_8);
        try{
            long total=0;var members=zip.entries();byte[] buffer=new byte[16384];
            while(members.hasMoreElements()){
                var entry=members.nextElement();String name=entry.getName();
                if(entries.size()>=limits.maxArchiveEntries()||entries.putIfAbsent(name,entry)!=null||!safe(name))throw new IOException("Archive members");
                long count=0;var crc=new CRC32();
                try(var input=zip.getInputStream(entry)){int n;while((n=input.read(buffer))!=-1){count+=n;total+=n;if(total>limits.maxExpandedBytes()||count>Math.max(1,entry.getCompressedSize())*limits.maxCompressionRatio())throw new IOException("Archive expansion limit");crc.update(buffer,0,n);}}
                if(count!=entry.getSize()||crc.getValue()!=entry.getCrc())throw new IOException("Archive checksum");
            }
        }catch(Exception e){zip.close();throw e;}
    }
    boolean containsKey(String name){return entries.containsKey(name);}
    byte[] get(String name)throws IOException{
        var entry=entries.get(name);if(entry==null)return null;
        // Metadata/XML is parsed one entry at a time; media is never read into this map.
        if(entry.getSize()>16L*1024*1024)throw new IOException("XML member limit");
        try(var input=zip.getInputStream(entry)){return input.readNBytes(16*1024*1024+1);}
    }
    static boolean safe(String name){
        if(name==null||name.isBlank()||name.startsWith("/")||name.contains("\\")||name.indexOf('\0')>=0||name.contains(":"))return false;
        String path=name.endsWith("/")?name.substring(0,name.length()-1):name;
        for(String part:path.split("/",-1))if(part.isEmpty()||part.equals(".")||part.equals(".."))return false;
        return true;
    }
    public void close()throws IOException{zip.close();}
}
