package cn.tcm.platform.community.application;
import cn.tcm.platform.content.ContentSupport;
import com.fasterxml.jackson.databind.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
public final class CommunitySourceHash {
    private static final ObjectMapper JSON=new ObjectMapper().configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS,true);
    private CommunitySourceHash(){}
    public static String content(String title,String body,String sectionId,String sourceFormat,List<CommunityReference> references){var value=new TreeMap<String,Object>();value.put("body",CommunityTextValidator.lf(body));value.put("references",(references==null?List.<CommunityReference>of():references).stream().sorted(Comparator.comparing(CommunityReference::referenceKey)).map(r->JSON.convertValue(r,TreeMap.class)).toList());value.put("sectionId",Long.toString(ContentSupport.id(sectionId)));value.put("sourceFormat",sourceFormat);value.put("title",title);return canonical(value);}
    public static String json(Object value){try{return JSON.writeValueAsString(sort(JSON.valueToTree(value)));}catch(Exception e){throw new IllegalArgumentException("Cannot serialize command",e);}}
    private static Object sort(JsonNode node){if(node.isObject()){var result=new TreeMap<String,Object>();node.properties().forEach(e->result.put(e.getKey(),sort(e.getValue())));return result;}if(node.isArray()){var result=new ArrayList<Object>();node.forEach(n->result.add(sort(n)));return result;}return node;}
    public static String canonical(Object value){try{return sha256(json(value).getBytes(StandardCharsets.UTF_8));}catch(Exception e){throw new IllegalArgumentException("Cannot hash command",e);}}
    public static String comment(String source){return sha256(CommunityTextValidator.lf(source).getBytes(StandardCharsets.UTF_8));}
    private static String sha256(byte[] source){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(source));}catch(Exception e){throw new IllegalStateException(e);}}
}
