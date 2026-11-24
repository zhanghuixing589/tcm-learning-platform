package cn.tcm.platform.community.application;
import cn.tcm.platform.common.ApiException;
import org.commonmark.node.*;
import java.util.*;
import java.util.regex.Pattern;

/** Pure manual-reference validation and saved-source reorder. Never fetches external URLs. */
public final class CommunityReferences {
 private CommunityReferences(){}
 private static final Pattern CITATION=Pattern.compile("\\[([1-9][0-9]{0,8})\\]");
 public record Citation(int number,int start,int end){}
 public record Reordered(String body,List<CommunityReference> references){}
 public static List<Citation> citations(String body,String format){String source=CommunityTextValidator.lf(body);var result=new ArrayList<Citation>();
  if("PLAIN_TEXT".equals(format)){scan(source,0,source.length(),result);return List.copyOf(result);}
  if(!"MARKDOWN".equals(format))throw ApiException.invalid("正文格式无效");
  CommunityTextValidator.walk(CommunityTextValidator.MARKDOWN.parse(source),n->{if(!(n instanceof Text))return;for(Node p=n.getParent();p!=null;p=p.getParent())if(p instanceof Link||p instanceof Image||p instanceof Code)return;for(var span:n.getSourceSpans())scan(source,span.getInputIndex(),span.getInputIndex()+span.getLength(),result);});
  result.sort(Comparator.comparingInt(Citation::start));return List.copyOf(result);
 }
 private static void scan(String source,int start,int end,List<Citation> result){var matcher=CITATION.matcher(source);matcher.region(start,end);while(matcher.find()){int slash=0;for(int j=matcher.start()-1;j>=0&&source.charAt(j)=='\\';j--)slash++;if(slash%2==0)result.add(new Citation(Integer.parseInt(matcher.group(1)),matcher.start(),matcher.end()));}}
 public static Set<Integer> validate(String source,List<CommunityReference> references,CommunityTextPolicy policy,boolean submitting,String format){
  if(references==null)throw ApiException.invalid("文献列表不能为空");if(references.size()>policy.limit("maxReferences"))throw ApiException.invalid("文献条数超限");var keys=new HashSet<String>();var numbers=new HashSet<Integer>();int total=0;
  for(var r:references){if(r==null)throw ApiException.invalid("文献不能为空");try{if(!UUID.fromString(r.referenceKey()).toString().equals(r.referenceKey()))throw new IllegalArgumentException();}catch(Exception e){throw ApiException.invalid("文献稳定键必须为规范UUID");}if(!keys.add(r.referenceKey())||r.displayNumber()<1||!numbers.add(r.displayNumber()))throw ApiException.invalid("文献键或编号重复");
   total+=field(r.title(),500,true)+field(r.authors(),1000,false)+field(r.sourceName(),1000,false)+field(r.doi(),255,false)+field(r.url(),2048,false)+field(r.locationLabel(),500,false);
   if(r.publicationYear()!=null&&(r.publicationYear()<1||r.publicationYear()>9999))throw ApiException.invalid("文献年份无效");if(r.url()!=null&&!r.url().isBlank())CommunityTextValidator.safeUrl(r.url(),policy);
  }
  if(total>policy.limit("maxReferenceChars"))throw ApiException.invalid("文献字段字符数超限");var dangling=new TreeSet<Integer>();for(var c:citations(source,format))if(!numbers.contains(c.number()))dangling.add(c.number());if(submitting&&!dangling.isEmpty())throw ApiException.invalid("正文存在悬空文献引用");return Collections.unmodifiableSet(dangling);
 }
 private static int field(String text,int max,boolean required){if(text==null){if(required)throw ApiException.invalid("文献标题不能为空");return 0;}int chars=CommunityTextValidator.chars(text);if(chars>max||required&&text.codePoints().allMatch(CommunityTextValidator::whitespace))throw ApiException.invalid("文献字段为空或字符超限");return chars;}
 /** Ordinary-save invariant: retained keys keep their numbers; deleted cited keys cannot be silently replaced. */
 public static void preventCitationSubstitution(String savedBody,List<CommunityReference> saved,String nextBody,List<CommunityReference> next,String format){preventCitationSubstitution(savedBody,saved,format,nextBody,next,format);}
 public static void preventCitationSubstitution(String savedBody,List<CommunityReference> saved,String savedFormat,String nextBody,List<CommunityReference> next,String nextFormat){var nextKeys=new HashSet<String>();var nextNumbers=new HashMap<String,Integer>();next.forEach(r->{nextKeys.add(r.referenceKey());nextNumbers.put(r.referenceKey(),r.displayNumber());});for(var r:saved){Integer number=nextNumbers.get(r.referenceKey());if(number!=null&&number!=r.displayNumber())throw ApiException.invalid("普通保存不能修改已有文献编号，请使用文献重排命令");}var used=new HashSet<Integer>();citations(savedBody,savedFormat).forEach(c->used.add(c.number()));var nextUsed=new HashSet<Integer>();citations(nextBody,nextFormat).forEach(c->nextUsed.add(c.number()));for(var r:saved)if(used.contains(r.displayNumber())&&!nextKeys.contains(r.referenceKey())&&nextUsed.contains(r.displayNumber()))throw ApiException.invalid("删除已引用文献时必须显式移除正文引用，不能替换稳定键");}
 public static Reordered reorder(String savedBody, List<CommunityReference> saved, List<String> orderedKeys, String format) {
  var byKey = new HashMap<String, CommunityReference>();
  for (var reference : saved) {
   if (byKey.put(reference.referenceKey(), reference) != null) throw ApiException.invalid("文献稳定键重复");
  }
  if (orderedKeys == null || orderedKeys.size() != saved.size() || !byKey.keySet().equals(new HashSet<>(orderedKeys)))
   throw ApiException.invalid("重排必须是已保存文献稳定键的完整排列");

  var renumbered = new HashMap<Integer, Integer>();
  var reordered = new ArrayList<CommunityReference>();
  for (int i = 0; i < orderedKeys.size(); i++) {
   var reference = byKey.get(orderedKeys.get(i));
   int next = i + 1;
   if (renumbered.put(reference.displayNumber(), next) != null) throw ApiException.invalid("文献编号重复");
   reordered.add(reference.numbered(next));
  }

  String source = CommunityTextValidator.lf(savedBody);
  var result = new StringBuilder(source.length());
  var expected = new ArrayList<Citation>();
  int cursor = 0;
  for (var citation : citations(source, format)) {
   Integer next = renumbered.get(citation.number());
   if (next == null) throw ApiException.invalid("悬空引用不能重排");
   result.append(source, cursor, citation.start());
   int start = result.length();
   result.append('[').append(next).append(']');
   expected.add(new Citation(next, start, result.length()));
   cursor = citation.end();
  }
  String rewritten = result.append(source, cursor, source.length()).toString();
  // A newly numbered token may become a Markdown shortcut/reference link at the same position.
  if ("MARKDOWN".equals(format) && !citations(rewritten, format).equals(expected))
   throw ApiException.invalid("重排会改变 Markdown 正文中的文献引用，请调整正文后重试");
  return new Reordered(rewritten, List.copyOf(reordered));
 }
}
