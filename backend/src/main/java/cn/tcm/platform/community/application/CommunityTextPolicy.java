package cn.tcm.platform.community.application;

import cn.tcm.platform.common.ApiException;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import java.util.*;

/** Immutable validated policy snapshot. Configuration cannot expand the supported safe language. */
@Component
public class CommunityTextPolicy {
    public static final int MAX_REQUEST_BYTES=8*1024*1024;
    private static final ObjectMapper JSON=new ObjectMapper();
    private static final Set<String> SYNTAX=Set.of("paragraph","heading","emphasis","strong","blockquote","code","codeBlock","list","link","table","hr");
    private static final Set<String> RATE_NAMES=Set.of("POST_CREATE","COMMENT_CREATE","PUBLIC_EDIT","DRAFT_SAVE","QUOTA_REQUEST");
    private final JdbcTemplate db;
    private final long id;
    private final ObjectNode config;
    @org.springframework.beans.factory.annotation.Autowired
    public CommunityTextPolicy(JdbcTemplate db){this.db=db;this.id=0;this.config=null;}
    private CommunityTextPolicy(long id,ObjectNode config){this.db=null;this.id=id;this.config=config.deepCopy();}
    public long id(){return id;}
    public ObjectNode config(){return config.deepCopy();}
    public int limit(String name){return config.path(name).intValue();}
    public int maxTitleChars(){return limit("maxTitleChars");}
    public boolean syntax(String name){for(var v:config.path("allowedSyntax"))if(v.asText().equals(name))return true;return false;}
    public boolean protocol(String name){for(var v:config.path("allowedProtocols"))if(v.asText().equals(name))return true;return false;}
    public record Rate(int seconds,int limit){}
    public List<Rate> rates(String name){if(!RATE_NAMES.contains(name))throw ApiException.invalid("限频类别无效");var rates=new ArrayList<Rate>();for(var n:config.path("rates").path(name))rates.add(new Rate(n.path("seconds").intValue(),n.path("limit").intValue()));rates.sort(Comparator.comparingInt(Rate::seconds));return List.copyOf(rates);}
    public CommunityTextPolicy current(){return db.queryForObject("SELECT p.id,p.config FROM community_text_policy_heads h JOIN community_text_policies p ON p.id=h.policy_id WHERE h.id=1",(rs,n)->parse(rs.getLong(1),read(rs.getString(2))));}
    public static JsonNode read(String value){try{return JSON.readTree(value);}catch(Exception e){throw ApiException.invalid("文本政策配置无效");}}
    public static CommunityTextPolicy parse(JsonNode node){return parse(0,node);}
    public static CommunityTextPolicy parse(long id,JsonNode n){
        Set<String> fields=Set.of("allowedSyntax","allowedProtocols","maxTitleChars","maxPostChars","maxArticleChars","maxCommentChars","maxReferences","maxReferenceChars","maxArticleHardChars","maxRequestBytes","rates");
        if(n==null||!n.isObject()||n.size()!=fields.size())throw invalid();
        n.fieldNames().forEachRemaining(k->{if(!fields.contains(k))throw invalid();});
        subset(n.path("allowedSyntax"),SYNTAX,Set.of("paragraph"));subset(n.path("allowedProtocols"),Set.of("http","https"),Set.of("https"));
        bound(n,"maxTitleChars",255);bound(n,"maxPostChars",500000);bound(n,"maxArticleChars",500000);bound(n,"maxCommentChars",65535);bound(n,"maxReferences",300);bound(n,"maxReferenceChars",200000);bound(n,"maxArticleHardChars",500000);bound(n,"maxRequestBytes",MAX_REQUEST_BYTES);
        if(n.path("maxArticleChars").intValue()>n.path("maxArticleHardChars").intValue())throw invalid();
        var rates=n.path("rates");if(!rates.isObject()||rates.size()!=RATE_NAMES.size())throw invalid();
        for(String key:RATE_NAMES){var windows=rates.path(key);if(!windows.isArray()||windows.isEmpty()||windows.size()>8)throw invalid();var seconds=new HashSet<Integer>();for(var w:windows){if(!w.isObject()||w.size()!=2)throw invalid();bound(w,"seconds",86400);bound(w,"limit",1000000);if(!seconds.add(w.path("seconds").intValue()))throw invalid();}}
        return new CommunityTextPolicy(id,(ObjectNode)n);
    }
    private static void subset(JsonNode n,Set<String> supported,Set<String> mandatory){if(!n.isArray()||n.isEmpty())throw invalid();var values=new HashSet<String>();for(var v:n)if(!v.isTextual()||!supported.contains(v.asText())||!values.add(v.asText()))throw invalid();if(!values.containsAll(mandatory))throw invalid();}
    private static void bound(JsonNode n,String key,int max){var v=n.path(key);if(!v.isIntegralNumber()||!v.canConvertToInt()||v.intValue()<1||v.intValue()>max)throw invalid();}
    private static ApiException invalid(){return ApiException.invalid("文本政策字段、额度或支持能力无效");}
    public static CommunityTextPolicy defaults(){return parse(1,read("""
      {"allowedSyntax":["paragraph","heading","emphasis","strong","blockquote","code","codeBlock","list","link","table","hr"],"allowedProtocols":["http","https"],"maxTitleChars":120,"maxPostChars":20000,"maxArticleChars":100000,"maxCommentChars":2000,"maxReferences":300,"maxReferenceChars":200000,"maxArticleHardChars":500000,"maxRequestBytes":8388608,"rates":{"POST_CREATE":[{"seconds":60,"limit":3},{"seconds":3600,"limit":20}],"COMMENT_CREATE":[{"seconds":60,"limit":10},{"seconds":3600,"limit":120}],"PUBLIC_EDIT":[{"seconds":60,"limit":6},{"seconds":3600,"limit":60}],"DRAFT_SAVE":[{"seconds":60,"limit":30},{"seconds":3600,"limit":300}],"QUOTA_REQUEST":[{"seconds":86400,"limit":3}]}}
      """));}
}
