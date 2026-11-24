package cn.tcm.platform.community.application;
import cn.tcm.platform.common.ApiException;
import org.commonmark.node.*;
import org.commonmark.parser.*;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.springframework.stereotype.Component;
import java.net.URI;
import java.text.Normalizer;
import java.util.*;
@Component
public class CommunityTextValidator {
    public static final Parser MARKDOWN=Parser.builder().extensions(List.of(TablesExtension.create())).includeSourceSpans(IncludeSourceSpans.BLOCKS_AND_INLINES).build();
    public static String lf(String source){if(source==null)throw ApiException.invalid("正文不能为空");validUnicode(source);return source.replace("\r\n","\n").replace('\r','\n');}
    public static int chars(String s){validUnicode(s);return s.codePointCount(0,s.length());}
    public static void validUnicode(String s){if(s==null)throw ApiException.invalid("文本不能为空");for(int i=0;i<s.length();i++){char c=s.charAt(i);if(c==0)throw ApiException.invalid("文本含无效字符");if(Character.isHighSurrogate(c)){if(i+1>=s.length()||!Character.isLowSurrogate(s.charAt(++i)))throw ApiException.invalid("文本含无效Unicode");}else if(Character.isLowSurrogate(c))throw ApiException.invalid("文本含无效Unicode");}}
    public static boolean whitespace(int cp){return Character.isWhitespace(cp)||Character.isSpaceChar(cp)||cp==0x85;}
    public static String normalizeName(String name){validUnicode(name);var out=new StringBuilder();boolean space=false;for(int cp:Normalizer.normalize(name,Normalizer.Form.NFKC).codePoints().toArray()){if(whitespace(cp)){space=out.length()>0;}else{if(space)out.append(' ');space=false;out.appendCodePoint(cp);}}return out.toString().toLowerCase(Locale.ROOT);}
    public String title(String title,String unchangedLegacy,CommunityTextPolicy policy){if(title==null)throw ApiException.invalid("标题不能为空");validUnicode(title);String value=title.equals(unchangedLegacy)?title:title.strip();if(value.codePoints().allMatch(CommunityTextValidator::whitespace)||chars(value)>255||(chars(value)>policy.maxTitleChars()&&!value.equals(unchangedLegacy)))throw ApiException.invalid("标题字符数超出额度");return value;}
    public String body(String body,String format,int max,CommunityTextPolicy policy){String source=lf(body);if(source.codePoints().allMatch(CommunityTextValidator::whitespace)||chars(source)>max)throw ApiException.invalid("正文为空或字符数超出额度");if("MARKDOWN".equals(format))validateMarkdown(source,policy);else if(!"PLAIN_TEXT".equals(format))throw ApiException.invalid("正文格式无效");return source;}
    public String comment(String body,CommunityTextPolicy policy){String source=body(body,"PLAIN_TEXT",policy.limit("maxCommentChars"),policy);if(source.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>65535)throw ApiException.invalid("评论存储字节数超出上限");return source;}
    public static void safeUrl(String url,CommunityTextPolicy policy){try{URI uri=URI.create(url);String scheme=uri.getScheme();if(scheme==null||!policy.protocol(scheme.toLowerCase(Locale.ROOT))||!uri.isAbsolute()||uri.getHost()==null||uri.getUserInfo()!=null)throw new IllegalArgumentException();}catch(Exception e){throw ApiException.invalid("链接仅支持明确的HTTP或HTTPS地址");}}
    private void validateMarkdown(String source,CommunityTextPolicy policy){walk(MARKDOWN.parse(source),node->{if(node instanceof HtmlBlock||node instanceof HtmlInline||node instanceof Image)throw ApiException.invalid("Markdown不允许HTML或图片");String syntax=null;
        if(node instanceof Paragraph)syntax="paragraph";else if(node instanceof Heading)syntax="heading";else if(node instanceof Emphasis)syntax="emphasis";else if(node instanceof StrongEmphasis)syntax="strong";else if(node instanceof BlockQuote)syntax="blockquote";else if(node instanceof Code)syntax="code";else if(node instanceof FencedCodeBlock||node instanceof IndentedCodeBlock)syntax="codeBlock";else if(node instanceof ListBlock)syntax="list";else if(node instanceof Link)syntax="link";else if(node instanceof ThematicBreak)syntax="hr";else if(node instanceof org.commonmark.ext.gfm.tables.TableBlock)syntax="table";
        if(syntax!=null&&!policy.syntax(syntax))throw ApiException.invalid("当前文本政策不支持此Markdown语法");if(node instanceof Link link)safeUrl(link.getDestination(),policy);
    });}
    public static void walk(Node n,java.util.function.Consumer<Node> visitor){var stack=new ArrayDeque<Node>();stack.push(n);while(!stack.isEmpty()){Node current=stack.pop();visitor.accept(current);for(Node child=current.getLastChild();child!=null;child=child.getPrevious())stack.push(child);}}
}
