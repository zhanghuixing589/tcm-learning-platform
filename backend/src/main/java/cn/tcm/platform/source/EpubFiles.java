package cn.tcm.platform.source;

import java.io.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.zip.*;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.*;

/** Bounded archive validation only; never extracts files or fetches XHTML resources. */
final class EpubFiles {
    private static final String OPF="http://www.idpf.org/2007/opf";
    private static final String XHTML="http://www.w3.org/1999/xhtml";
    private EpubFiles() {}

    static void validate(byte[] bytes) throws Exception {
        var archive=Files.createTempFile("tcm-epub-", ".zip");
        try {Files.write(archive,bytes);validate(archive,SourceLimits.DEFAULTS);}
        finally{Files.deleteIfExists(archive);}
    }
    static void validate(java.nio.file.Path archive,SourceLimits limits)throws Exception {
        try(var entries=new SourceArchive(archive,limits)){
        if(!Arrays.equals(entries.get("mimetype"),"application/epub+zip".getBytes(StandardCharsets.US_ASCII))
                || entries.containsKey("META-INF/encryption.xml")) throw new IOException("Not an unencrypted EPUB");
        var container=xml(entries.get("META-INF/container.xml"));
        var roots=container.getElementsByTagNameNS("urn:oasis:names:tc:opendocument:xmlns:container","rootfile");
        if(roots.getLength()==0) throw new IOException("Missing EPUB package");
        String packagePath=path("",((Element)roots.item(0)).getAttribute("full-path"));
        var pkg=xml(entries.get(packagePath));
        if(!OPF.equals(pkg.getDocumentElement().getNamespaceURI()) || !"package".equals(pkg.getDocumentElement().getLocalName()))
            throw new IOException("Invalid EPUB package");
        Map<String,Element> manifest=new HashMap<>();
        var items=pkg.getElementsByTagNameNS(OPF,"item");
        for(int i=0;i<items.getLength();i++) {
            var item=(Element)items.item(i); String id=item.getAttribute("id");
            if(id.isBlank() || manifest.putIfAbsent(id,item)!=null) throw new IOException("Duplicate EPUB item");
        }
        var spine=pkg.getElementsByTagNameNS(OPF,"itemref");
        if(spine.getLength()==0 || spine.getLength()>limits.maxArchiveEntries()) throw new IOException("Invalid EPUB spine");
        for(int i=0;i<spine.getLength();i++) {
            var item=manifest.get(((Element)spine.item(i)).getAttribute("idref"));
            if(item==null || !"application/xhtml+xml".equals(item.getAttribute("media-type"))) throw new IOException("Invalid EPUB chapter");
            var chapter=xml(entries.get(path(packagePath,item.getAttribute("href"))));
            if(!XHTML.equals(chapter.getDocumentElement().getNamespaceURI()) || !"html".equals(chapter.getDocumentElement().getLocalName())
                    || chapter.getElementsByTagNameNS(XHTML,"body").getLength()!=1) throw new IOException("Invalid XHTML");
        }
        }
    }

    private static String path(String base,String href) throws Exception {
        // URI accepts Unicode paths; resolve only within the archive, never through a URL handler.
        var uri=new URI(href);
        String raw=uri.getPath();
        if(uri.isAbsolute() || uri.getAuthority()!=null || raw==null || raw.isBlank() || raw.startsWith("/") || raw.contains("\\"))
            throw new IOException("Invalid EPUB path");
        var parts=new ArrayDeque<String>();
        String combined=(base.contains("/")?base.substring(0,base.lastIndexOf('/')+1):"")+raw;
        for(String part:combined.split("/")) {
            if(part.isEmpty() || part.equals(".")) continue;
            if(part.equals("..")) { if(parts.isEmpty()) throw new IOException("EPUB path escape"); parts.removeLast(); }
            else parts.add(part);
        }
        return String.join("/",parts);
    }

    private static Document xml(byte[] bytes) throws Exception {
        if(bytes==null) throw new IOException("Missing EPUB entry");
        var f=DocumentBuilderFactory.newInstance(); f.setNamespaceAware(true);
        f.setFeature("http://xml.org/sax/features/external-general-entities",false);
        f.setFeature("http://xml.org/sax/features/external-parameter-entities",false);
        f.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd",false);
        f.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD,""); f.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA,"");
        f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING,true); f.setXIncludeAware(false);
        var doc=f.newDocumentBuilder().parse(new ByteArrayInputStream(bytes));
        if(doc.getDoctype()!=null && doc.getDoctype().getEntities().getLength()>0) throw new IOException("EPUB entities forbidden");
        return doc;
    }
}
