package cn.tcm.platform.source;

import cn.tcm.platform.common.ApiException;
import cn.tcm.platform.knowledge.api.KnowledgeDtos.Evidence;
import org.springframework.stereotype.Service;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import static cn.tcm.platform.source.SourceAccess.*;

/** Resolves trusted, immutable citation snapshots; clients cannot invent an anchor. */
@Service
public class SourceEvidence {
    private final SourceAccess access;private final SourceReader reader;private final SourceReadingData data;private final SourceRights rights;private final ObjectMapper json;
    public SourceEvidence(SourceAccess a,SourceReader r,SourceReadingData d,SourceRights rights,ObjectMapper j){access=a;reader=r;data=d;this.rights=rights;json=j;}
    public Evidence resolve(long actor,Evidence e){long document=cn.tcm.platform.content.ContentSupport.id(e.documentId());var d=access.document(actor,document,false);if(!"ACTIVE".equals(d.get("availability")))throw ApiException.invalid("来源已停用");
        if(e.excerptId()!=null){var x=reader.ownedExcerpt(actor,cn.tcm.platform.content.ContentSupport.id(e.excerptId()));if(!e.documentId().equals(x.get("documentId"))||!e.quoteText().equals(x.get("quoteText")))throw ApiException.invalid("摘录与依据不一致");return new Evidence(e.documentId(),null,Objects.toString(x.get("locationLabel"),null),e.quoteText(),e.excerptId(),Objects.toString(x.get("parseRunId"),null),Objects.toString(x.get("readingEditionId"),null),json.convertValue(x.get("locator"),new com.fasterxml.jackson.core.type.TypeReference<Map<String,Object>>(){}),Objects.toString(x.get("quoteHash"),null));}
        if(e.readingEditionId()!=null){
            long edition=cn.tcm.platform.content.ContentSupport.id(e.readingEditionId());var es=access.db.queryForList("SELECT * FROM source_reading_editions WHERE id=? AND document_id=? AND status='READY'",edition,document);if(es.isEmpty()||es.get(0).get("parse_run_id")==null||e.locator()==null)throw ApiException.invalid("阅读引用版本无效");
            // Quote-only reuse requires an identical existing visible citation before reading its anchors.
            boolean known=access.db.queryForObject("SELECT COUNT(*) FROM revision_evidence e JOIN content_revisions v ON v.id=e.revision_id JOIN content_resources r ON r.id=v.resource_id WHERE e.document_id=? AND e.reading_edition_id=? AND e.quote_hash=? AND e.quote_text=? AND e.locator=CAST(? AS JSON) AND "+cn.tcm.platform.content.ContentSupport.visible("r","v"),Long.class,document,edition,e.quoteHash(),e.quoteText(),data.encoded(e.locator()))>0;
            if(!rights.canRead(actor,d)&&!(known&&SourceRights.flag(d.get("quote_display_allowed"))))throw ApiException.missing();
            var s=reader.reconstruct(document,es.get(0),json.valueToTree(e.locator()).path("ranges"),e.quoteText());if(!Objects.equals(Long.toString(s.parseRunId()),e.parseRunId())||e.quoteHash()!=null&&!s.hash().equals(e.quoteHash()))throw ApiException.invalid("引用批次或摘要不一致");
            return new Evidence(e.documentId(),null,s.location(),s.quote(),null,e.parseRunId(),Long.toString(edition),s.locator(),s.hash());
        }
        // Legacy segment/manual evidence remains supported with explicit full-read permission.
        if(!rights.canRead(actor,d)){
            String hash=SourceFiles.hash(e.quoteText().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            var known=access.db.queryForList("SELECT e.* FROM revision_evidence e JOIN content_revisions v ON v.id=e.revision_id JOIN content_resources r ON r.id=v.resource_id WHERE e.document_id=? AND e.segment_id<=>? AND e.location_label<=>? AND e.reading_edition_id IS NULL AND e.quote_hash=? AND BINARY e.quote_text=BINARY ? AND "+cn.tcm.platform.content.ContentSupport.visible("r","v")+" LIMIT 1",document,e.segmentId()==null?null:cn.tcm.platform.content.ContentSupport.id(e.segmentId()),e.locationLabel(),hash,e.quoteText());
            if(known.isEmpty())throw ApiException.missing();var saved=known.get(0);if(e.parseRunId()!=null&&!Objects.equals(e.parseRunId(),Objects.toString(saved.get("parse_run_id"),null)))throw ApiException.invalid("历史引文的解析批次不一致");
            return new Evidence(e.documentId(),e.segmentId(),e.locationLabel(),e.quoteText(),null,Objects.toString(saved.get("parse_run_id"),null),null,null,hash);
        }return e;
    }
    public void publication(Map<String,Object> evidence){long document=((Number)evidence.get("document_id")).longValue();var d=access.db.queryForMap("SELECT * FROM source_documents WHERE id=?",document);rights.requirePublication(d);
        if(evidence.get("reading_edition_id")!=null){if(!Objects.equals(evidence.get("reading_edition_id"),d.get("reviewed_reading_edition_id")))throw ApiException.conflict("引用版本未审核，请补审来源或选择已审核版本");var coverage=data.tree(d.get("approved_coverage"));var locator=data.tree(evidence.get("locator"));var approved=new HashSet<String>();for(var u:coverage.path("unitIds"))approved.add(u.asText());for(var r:locator.path("ranges"))if(!approved.contains(r.path("unitId").asText()))throw ApiException.conflict("引用单元不在来源批准覆盖范围内");}
    }
}
