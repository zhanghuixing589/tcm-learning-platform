package cn.tcm.platform.source;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.net.URI;
import java.util.*;
import java.util.zip.*;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.*;
import org.apache.pdfbox.Loader;

/** Suggestions only. Embedded rights text never becomes a permission grant. */
final class SourceFileMetadata {
    private static final String OPF="http://www.idpf.org/2007/opf",DC="http://purl.org/dc/elements/1.1/";
    static Map<String,Object> read(byte[] bytes,String mime,String name){
        Path temp=null;
        try{temp=Files.createTempFile("source-metadata-",".bin");Files.write(temp,bytes);return read(temp,mime,name,SourceLimits.DEFAULTS);}
        catch(IOException e){return SourceAccess.map("title",name,"author","","publisher","","publicationYear",null,"rightsText","","outline",List.of(),"warnings",List.of("元数据暂时不可读取，请手动核对。"));}
        finally{if(temp!=null)try{Files.deleteIfExists(temp);}catch(IOException ignored){}}
    }
    static Map<String,Object> read(Path file,String mime,String name,SourceLimits limits){
        var result=SourceAccess.map("title",name.replaceFirst("(?i)\\.[^.]+$",""),"author","","publisher","","publicationYear",null,"rightsText","","outline",List.of(),"warnings",new ArrayList<String>());
        @SuppressWarnings("unchecked") var warnings=(List<String>)result.get("warnings");
        try {
            if(Files.size(file)>limits.maxOriginalBytes())throw new IOException("Original limit");
            if("application/epub+zip".equals(mime)){try(var entries=new SourceArchive(file,limits)){epub(entries,result,warnings);}}
            else if("application/pdf".equals(mime)){
                try(var pdf=Loader.loadPDF(file.toFile(),org.apache.pdfbox.io.IOUtils.createTempFileOnlyStreamCache())){
                    var info=pdf.getDocumentInformation();if(info.getTitle()!=null&&!info.getTitle().isBlank())result.put("title",bounded(info.getTitle()));
                    if(info.getAuthor()!=null)result.put("author",bounded(info.getAuthor()));
                }
                warnings.add("PDF书目信息仅作建议，请核对原书。");
            }else if(mime.contains("wordprocessingml")){
                try(var entries=new SourceArchive(file,limits)){if(entries.containsKey("docProps/core.xml")){
                    var core=xml(entries.get("docProps/core.xml"));set(result,"title",text(core,DC,"title"));set(result,"author",text(core,DC,"creator"));
                }
                }
                warnings.add("文档书目信息仅作建议，请核对原书。");
            }else if("application/msword".equals(mime))warnings.add("DOC原件已保留；嵌入书目待隔离转换后补充，目前仅建议文件名标题。");
            else warnings.add("当前格式仅使用文件名建议标题，请补充书目信息。");
        }catch(Exception e){warnings.add("部分嵌入元数据不可读取，请手动核对。");}
        warnings.add("文件内的版权文字不代表平台已获得公开阅读、下载或RAG授权。");
        return result;
    }
    private static void epub(SourceArchive entries,Map<String,Object> result,List<String> warnings)throws Exception{
        var container=xml(entries.get("META-INF/container.xml"));
        var roots=container.getElementsByTagNameNS("urn:oasis:names:tc:opendocument:xmlns:container","rootfile");if(roots.getLength()==0)throw new IOException();
        String packagePath=resolve("",((Element)roots.item(0)).getAttribute("full-path"));var pkg=xml(entries.get(packagePath));
        set(result,"title",text(pkg,DC,"title"));set(result,"author",text(pkg,DC,"creator"));set(result,"publisher",text(pkg,DC,"publisher"));set(result,"rightsText",text(pkg,DC,"rights"));
        var year=java.util.regex.Pattern.compile("(?<![0-9])([12][0-9]{3})(?![0-9])").matcher(text(pkg,DC,"date"));if(year.find())result.put("publicationYear",Integer.valueOf(year.group(1)));
        Map<String,Element> manifest=new HashMap<>();Element nav=null,ncx=null;
        var items=pkg.getElementsByTagNameNS(OPF,"item");for(int i=0;i<items.getLength();i++){
            var item=(Element)items.item(i);manifest.put(item.getAttribute("id"),item);
            if(Arrays.asList(item.getAttribute("properties").split("\\s+")).contains("nav"))nav=item;
            if("application/x-dtbncx+xml".equals(item.getAttribute("media-type")))ncx=item;
        }
        var chapters=new LinkedHashMap<String,Integer>();var fallback=new ArrayList<Map<String,Object>>();
        var spine=pkg.getElementsByTagNameNS(OPF,"itemref");for(int i=0;i<spine.getLength();i++){
            var item=manifest.get(((Element)spine.item(i)).getAttribute("idref"));if(item==null)continue;
            String path=resolve(packagePath,item.getAttribute("href"));int no=i+1;chapters.put(path,no);
            var doc=xml(entries.get(path));String title=text(doc,"http://www.w3.org/1999/xhtml","h1");if(title.isBlank())title=text(doc,"http://www.w3.org/1999/xhtml","title");if(title.isBlank())title=path.substring(path.lastIndexOf('/')+1);
            fallback.add(SourceAccess.map("title",title,"unitNo",no,"chapterNo",no,"children",List.of()));
        }
        var outline=new ArrayList<Map<String,Object>>();
        if(nav!=null){String path=resolve(packagePath,nav.getAttribute("href"));var doc=xml(entries.get(path));var navs=doc.getElementsByTagNameNS("http://www.w3.org/1999/xhtml","nav");
            for(int i=0;i<navs.getLength();i++){var n=(Element)navs.item(i);if(n.getAttributeNS("http://www.idpf.org/2007/ops","type").contains("toc")){collectLinks(n,path,chapters,outline);break;}}
        }
        if(outline.isEmpty()&&ncx!=null){String path=resolve(packagePath,ncx.getAttribute("href"));var doc=xml(entries.get(path));var points=doc.getElementsByTagNameNS("http://www.daisy.org/z3986/2005/ncx/","navPoint");for(int i=0;i<points.getLength()&&outline.size()<2000;i++){
            var point=(Element)points.item(i);var contents=point.getElementsByTagNameNS("http://www.daisy.org/z3986/2005/ncx/","content");if(contents.getLength()>0)add(outline,text(point,"http://www.daisy.org/z3986/2005/ncx/","text"),path,((Element)contents.item(0)).getAttribute("src"),chapters);
        }}
        if(outline.isEmpty()){outline.addAll(fallback);warnings.add("未找到可用导航目录，使用spine顺序及章节标题。");}
        result.put("outline",outline);
    }
    private static void collectLinks(Element parent,String path,Map<String,Integer> chapters,List<Map<String,Object>> out)throws Exception{
        var links=parent.getElementsByTagNameNS("http://www.w3.org/1999/xhtml","a");for(int i=0;i<links.getLength()&&out.size()<2000;i++){var a=(Element)links.item(i);add(out,bounded(a.getTextContent()),path,a.getAttribute("href"),chapters);}
    }
    private static void add(List<Map<String,Object>> out,String title,String base,String href,Map<String,Integer> chapters)throws Exception{
        Integer no=chapters.get(resolve(base,href));if(no!=null&&!title.isBlank())out.add(SourceAccess.map("title",title,"unitNo",no,"chapterNo",no,"children",List.of()));
    }
    private static String resolve(String base,String href)throws Exception{
        var uri=new URI(href);if(uri.isAbsolute()||uri.getAuthority()!=null||uri.getPath()==null||uri.getPath().startsWith("/")||uri.getPath().contains("\\"))throw new IOException();
        String combined=(base.contains("/")?base.substring(0,base.lastIndexOf('/')+1):"")+uri.getPath();var parts=new ArrayDeque<String>();
        for(String p:combined.split("/")){if(p.isEmpty()||p.equals("."))continue;if(p.equals("..")){if(parts.isEmpty())throw new IOException();parts.removeLast();}else parts.add(p);}return String.join("/",parts);
    }
    private static Document xml(byte[] data)throws Exception{
        if(data==null||data.length>4*1024*1024)throw new IOException();var f=DocumentBuilderFactory.newInstance();f.setNamespaceAware(true);
        f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING,true);f.setFeature("http://xml.org/sax/features/external-general-entities",false);f.setFeature("http://xml.org/sax/features/external-parameter-entities",false);f.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd",false);f.setXIncludeAware(false);f.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD,"");f.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA,"");
        var doc=f.newDocumentBuilder().parse(new ByteArrayInputStream(data));if(doc.getDoctype()!=null&&doc.getDoctype().getEntities().getLength()>0)throw new IOException();return doc;
    }
    private static String text(Node doc,String namespace,String name){var nodes=doc instanceof Document d?d.getElementsByTagNameNS(namespace,name):((Element)doc).getElementsByTagNameNS(namespace,name);return nodes.getLength()==0?"":bounded(nodes.item(0).getTextContent());}
    private static String bounded(String value){String s=value.replaceAll("[\\p{Cntrl}&&[^\\n\\t]]","").strip();return s.substring(0,Math.min(4000,s.length()));}
    private static void set(Map<String,Object> target,String key,String value){if(!value.isBlank())target.put(key,value);}
}
