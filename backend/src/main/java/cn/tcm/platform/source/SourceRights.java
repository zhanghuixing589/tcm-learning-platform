package cn.tcm.platform.source;

import cn.tcm.platform.common.ApiException;
import jakarta.validation.constraints.Size;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import static cn.tcm.platform.source.SourceAccess.*;

/** Effective rights are checked for every read; a frozen historical quote is not a public access grant. */
@Service
public class SourceRights {
    private final SourceAccess access;
    public SourceRights(SourceAccess access){this.access=access;}
    public record Input(String licenseType,@Size(max=4000) String copyrightSource,
                        @Size(max=4000) String authorizationScope,boolean publicReadingAllowed,
                        boolean publicDownloadAllowed,boolean quoteDisplayAllowed,boolean ragUseAllowed) {}
    public static boolean flag(Object value){return Boolean.TRUE.equals(value)||value instanceof Number n&&n.intValue()!=0;}
    public static Map<String,Object> view(Map<String,Object> d){return map("licenseType",d.getOrDefault("license_type","UNKNOWN"),
        "copyrightSource",d.get("copyright_source"),"authorizationScope",d.get("authorization_scope"),
        "publicReadingAllowed",flag(d.get("public_reading_allowed")),"publicDownloadAllowed",flag(d.get("public_download_allowed")),
        "quoteDisplayAllowed",flag(d.get("quote_display_allowed")),"ragUseAllowed",flag(d.get("rag_use_allowed")));}
    public void apply(long document,Input input){
        if(input==null)return;
        if(input.licenseType()==null||!Set.of("UNKNOWN","OWNED","AUTHORIZED","PUBLIC_DOMAIN","OPEN_LICENSE").contains(input.licenseType()))throw ApiException.invalid("请选择有效的版权授权类型");
        if((input.copyrightSource()!=null&&input.copyrightSource().length()>4000)||(input.authorizationScope()!=null&&input.authorizationScope().length()>4000))throw ApiException.invalid("版权声明过长");
        boolean declared=!input.licenseType().equals("UNKNOWN")&&input.copyrightSource()!=null&&!input.copyrightSource().isBlank()&&input.authorizationScope()!=null&&!input.authorizationScope().isBlank();
        if(!declared&&(input.publicReadingAllowed()||input.publicDownloadAllowed()||input.quoteDisplayAllowed()||input.ragUseAllowed()))throw ApiException.invalid("开放使用前须填写版权来源与授权范围");
        if(input.ragUseAllowed()&&!input.quoteDisplayAllowed())throw ApiException.invalid("RAG使用必须允许展示有据可查的引用");
        access.db.update("UPDATE source_documents SET license_type=?,copyright_source=?,authorization_scope=?,rights_status=?,public_reading_allowed=?,public_download_allowed=?,quote_display_allowed=?,rag_use_allowed=? WHERE id=?",
            input.licenseType(),input.copyrightSource(),input.authorizationScope(),declared?"DECLARED":"UNCONFIRMED",input.publicReadingAllowed(),input.publicDownloadAllowed(),input.quoteDisplayAllowed(),input.ragUseAllowed(),document);
    }
    public boolean privileged(long actor,Map<String,Object>d){return Objects.toString(d.get("submitted_by")).equals(Long.toString(actor))||access.permission(actor,"knowledge:review");}
    public boolean canRead(long actor,Map<String,Object>d){return "ACTIVE".equals(d.get("availability"))&&(privileged(actor,d)||("APPROVED".equals(d.get("review_state"))&&flag(d.get("public_reading_allowed"))));}
    public boolean canDeliver(long actor,Map<String,Object>d){return "ACTIVE".equals(d.get("availability"))&&(privileged(actor,d)||("APPROVED".equals(d.get("review_state"))&&flag(d.get("public_download_allowed"))));}
    public Map<String,Object> requireRead(long actor,long document){var d=access.document(actor,document,false);if(!canRead(actor,d))throw ApiException.missing();return d;}
    public Map<String,Object> requireDelivery(long actor,long document){var d=access.document(actor,document,false);if(!canDeliver(actor,d))throw ApiException.missing();return d;}
    public void requirePublication(Map<String,Object>d){
        if(!"APPROVED".equals(d.get("review_state"))||!"ACTIVE".equals(d.get("availability")))throw ApiException.conflict("来源必须审核通过且有效");
        if(!"DECLARED".equals(d.get("rights_status"))||!flag(d.get("quote_display_allowed")))throw ApiException.conflict("来源未取得公开引用授权，请完善版权并审核");
        if("REFERENCE_ONLY".equals(d.get("approved_use")))throw ApiException.conflict("此来源仅供阅读，不能作为精确文字整理依据");
    }
    public record Restriction(int expectedVersion,boolean publicReadingAllowed,boolean publicDownloadAllowed,boolean quoteDisplayAllowed,boolean ragUseAllowed,String reason){}
    @Transactional public Map<String,Object> restrict(long actor,long document,Restriction input){var d=access.document(actor,document,true);if(!privileged(actor,d))throw ApiException.missing();if(input.reason()==null||input.reason().isBlank()||input.reason().length()>1000)throw ApiException.invalid("请填写1000字以内的授权变更原因");if(((Number)d.get("lock_version")).intValue()!=input.expectedVersion())throw ApiException.conflict("来源已被更新，请刷新");
        if(input.publicReadingAllowed()&&!flag(d.get("public_reading_allowed"))||input.publicDownloadAllowed()&&!flag(d.get("public_download_allowed"))||input.quoteDisplayAllowed()&&!flag(d.get("quote_display_allowed"))||input.ragUseAllowed()&&!flag(d.get("rag_use_allowed")))throw ApiException.conflict("开放更多授权需复制修订并重新审核");
        if(input.ragUseAllowed()&&!input.quoteDisplayAllowed())throw ApiException.invalid("RAG引用展示授权必须同时有效");
        var before=view(d);access.db.update("UPDATE source_documents SET public_reading_allowed=?,public_download_allowed=?,quote_display_allowed=?,rag_use_allowed=?,rights_status=IF(rights_status='LEGACY_UNCONFIRMED','UNCONFIRMED',rights_status),lock_version=lock_version+1 WHERE id=?",input.publicReadingAllowed(),input.publicDownloadAllowed(),input.quoteDisplayAllowed(),input.ragUseAllowed(),document);
        var after=access.db.queryForMap("SELECT * FROM source_documents WHERE id=?",document);try{var json=new ObjectMapper();access.db.update("INSERT INTO source_rights_events(document_id,actor_id,event_type,reason,before_rights,after_rights) VALUES(?,?,'RESTRICT',?,?,?)",document,actor,input.reason().strip(),json.writeValueAsString(before),json.writeValueAsString(view(after)));}catch(com.fasterxml.jackson.core.JsonProcessingException e){throw new IllegalStateException(e);}
        if(!input.quoteDisplayAllowed()&&(flag(d.get("quote_display_allowed"))||"LEGACY_UNCONFIRMED".equals(d.get("rights_status")))){access.db.update("UPDATE content_resources r JOIN revision_evidence e ON e.revision_id=r.published_revision_id SET r.visibility_status='SUSPENDED',r.suspended_reason='来源公开引用授权已撤回，待复核',r.lock_version=r.lock_version+1 WHERE e.document_id=? AND r.visibility_status='PUBLISHED'",document);}
        if(!input.ragUseAllowed()||!input.quoteDisplayAllowed())for(var r:access.db.queryForList("SELECT DISTINCT revision_id FROM revision_evidence WHERE document_id=?",document))access.db.update("INSERT INTO async_jobs(job_type,idempotency_key,document_id,revision_id,payload) VALUES('REMOVE_INDEX',?,?,?,JSON_OBJECT('reason','RIGHTS_RESTRICTED')) ON DUPLICATE KEY UPDATE id=id","rights:"+document+":"+input.expectedVersion()+":"+r.get("revision_id"),document,r.get("revision_id"));
        access.audit(actor,"SOURCE_RIGHTS_RESTRICT","SOURCE",document);return map("id",Long.toString(document),"rights",view(after),"lockVersion",after.get("lock_version"));
    }
}
