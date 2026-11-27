package cn.tcm.platform.source;

import cn.tcm.platform.common.ApiException;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import org.springframework.stereotype.Service;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Immutable reading snapshots. Publication is invoked only inside the valid lease transaction. */
@Service
public class SourceReadingData {
    private final SourceAccess access;private final ObjectMapper json;
    private static final Set<String> TYPES=Set.of("paragraph","heading","list","listItem","table","row","cell","image","text","strong","em","link","break","blockquote","code","sup","sub","figure","figcaption","footnote");
    private static final Set<String> MODES=Set.of("STRUCTURED","FIXED_LAYOUT","PLAIN_TEXT","ORIGINAL_PREVIEW");
    public record Asset(String ref,String kind,String originalFormat,Integer unitNo,Integer pageNumber,String mimeType,long size,String sha256,int width,int height,SourceObjectStore.Stored stored){}
    public SourceReadingData(SourceAccess access,ObjectMapper json){this.access=access;this.json=json;}
    public Object decoded(Object value,Object fallback){if(value==null)return fallback;try{return json.readValue(value.toString(),Object.class);}catch(Exception e){throw ApiException.conflict("阅读版本数据损坏，请联系维护人员");}}
    public JsonNode tree(Object value){return json.valueToTree(decoded(value,Map.of()));}
    public String encoded(Object value){try{return json.writeValueAsString(value);}catch(Exception e){throw ApiException.invalid("阅读结果格式无效");}}
    private static String plain(JsonNode n){if("text".equals(n.path("type").asText()))return n.path("text").asText();if("break".equals(n.path("type").asText()))return "\n";var out=new StringBuilder();for(var c:n.path("children"))out.append(plain(c));return out.toString();}
    private static void node(JsonNode value,int depth,int[] totals,Map<String,String> texts){
        if(depth>64||++totals[0]>25000||!value.isObject()||!TYPES.contains(value.path("type").asText()))throw ApiException.invalid("阅读节点超限或类型无效");
        if(value.has("style")||value.has("html")||value.has("src")||value.has("onload"))throw ApiException.invalid("阅读节点含不安全属性");
        if(value.has("href")){String href=value.path("href").asText();if(!href.startsWith("#")&&!href.matches("(?i)^https?://[^\\s]+$"))throw ApiException.invalid("阅读链接协议无效");if(href.chars().anyMatch(c->c<32))throw ApiException.invalid("阅读链接无效");}
        if(value.has("id")){String id=value.path("id").asText();if(!id.matches("[A-Za-z0-9_-]{1,80}")||texts.putIfAbsent(id,plain(value))!=null)throw ApiException.invalid("阅读块标识重复或无效");}
        for(var c:value.path("children"))node(c,depth+1,totals,texts);
    }
    public static void validate(JsonNode reading){
        if(reading==null||reading.path("schemaVersion").asInt()!=2||!Set.of("EPUB","PDF","DOC","DOCX","TXT").contains(reading.path("format").asText()))throw ApiException.invalid("阅读结果协议无效");
        if(!reading.path("units").isArray()||reading.path("units").isEmpty()||reading.path("units").size()>5000)throw ApiException.invalid("没有可用阅读单元或数量超限");
        int expected=1;int[] totals={0,0,0};boolean available=false;
        for(var unit:reading.path("units")){
            if(unit.path("unitNo").asInt()!=expected++||!Set.of("CHAPTER","PAGE","TEXT_SECTION").contains(unit.path("unitType").asText())||unit.path("title").asText().length()>500)throw ApiException.invalid("阅读单元顺序或类型无效");
            var texts=new HashMap<String,String>();for(var n:unit.path("nodes"))node(n,0,totals,texts);
            int blockNo=1;var ids=new HashSet<String>();
            for(var block:unit.path("blocks")){
                String id=block.path("localId").asText(),body=block.path("text").asText();
                if(block.path("blockNo").asInt()!=blockNo++||!ids.add(id)||!body.equals(texts.get(id)))throw ApiException.invalid("规范文本与阅读结构不一致");
                totals[1]++;totals[2]+=body.length();if(totals[1]>10000||totals[2]>2_000_000)throw ApiException.invalid("规范原文超出处理预算");
            }
            for(var mode:unit.path("availableReadingModes")){if(!MODES.contains(mode.asText()))throw ApiException.invalid("阅读模式无效");available=true;}
            if(unit.path("capabilities").path("preciseCitationSupported").asBoolean()&&unit.path("blocks").isEmpty())throw ApiException.invalid("无文字单元不能声明精确引用");
        }
        if(!available)throw ApiException.invalid("未生成安全可用的阅读模式");
    }
    public long publish(SourceJobs.Lease lease,JsonNode reading,List<Asset> assets){
        var existing=access.db.queryForList("SELECT id FROM source_reading_editions WHERE build_job_id=?",Long.class,lease.id());if(!existing.isEmpty())return existing.get(0);
        var source=access.db.queryForMap("SELECT d.active_reading_edition_id,f.sha256 FROM source_documents d JOIN file_objects f ON f.id=d.file_id WHERE d.id=?",lease.documentId());
        int version=access.db.queryForObject("SELECT COALESCE(MAX(reading_version_no),0)+1 FROM source_reading_editions WHERE document_id=?",Integer.class,lease.documentId());
        Long textRun=reading.path("capabilities").path("textSelectable").asBoolean()?lease.parseRunId():null;
        long edition=access.insert("INSERT INTO source_reading_editions(document_id,parse_run_id,processing_run_id,reading_version_no,supersedes_edition_id,schema_version,parser_name,format,file_sha256,layout_mode,overall_outcome,capabilities,reading_modes,stage_results,metadata,outline,warnings,build_job_id) VALUES(?,?,?,?,?,2,?,?,?,?,?,?,?,?,?,?,?,?)",
            lease.documentId(),textRun,lease.parseRunId(),version,source.get("active_reading_edition_id"),"deterministic-v2",reading.path("format").asText(),source.get("sha256"),reading.path("layoutMode").asText("UNKNOWN"),reading.path("overallOutcome").asText("SUCCEEDED"),encoded(reading.path("capabilities")),encoded(reading.path("availableReadingModes")),encoded(reading.path("stages")),encoded(reading.path("metadata")),encoded(reading.path("outline")),encoded(reading.path("warnings")),lease.id());
        var unitIds=new HashMap<Integer,Long>();
        for(var u:reading.path("units")){
            long unit=access.insert("INSERT INTO source_reading_units(edition_id,unit_no,unit_type,title,source_path,page_number,nodes,capabilities,reading_modes,warnings,preview_ref) VALUES(?,?,?,?,?,?,?,?,?,?,?)",
                edition,u.path("unitNo").asInt(),u.path("unitType").asText(),u.path("title").asText(),u.path("sourcePath").asText(""),u.hasNonNull("pageNumber")?u.path("pageNumber").asInt():null,encoded(u.path("nodes")),encoded(u.path("capabilities")),encoded(u.path("availableReadingModes")),encoded(u.path("warnings")),u.hasNonNull("previewRef")?u.path("previewRef").asText():null);
            unitIds.put(u.path("unitNo").asInt(),unit);
            for(var b:u.path("blocks")){String body=b.path("text").asText();access.db.update("INSERT INTO source_reading_blocks(edition_id,unit_id,block_no,local_id,body,content_hash) VALUES(?,?,?,?,?,?)",edition,unit,b.path("blockNo").asInt(),b.path("localId").asText(),body,SourceFiles.hash(body.getBytes(StandardCharsets.UTF_8)));}
        }
        toc(edition,null,reading.path("outline"),unitIds,0);
        for(var a:assets)access.db.update("INSERT INTO source_reading_assets(edition_id,asset_ref,asset_ref_hash,asset_kind,original_format,unit_no,page_number,generator_version,parent_sha256,storage_backend,storage_bucket,storage_key,mime_type,sha256,byte_size,pixel_width,pixel_height) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
            edition,a.ref(),SourceFiles.hash(a.ref().getBytes(StandardCharsets.UTF_8)),a.kind(),a.originalFormat(),a.unitNo(),a.pageNumber(),"isolated-v2",source.get("sha256"),a.stored().backend(),a.stored().bucket(),a.stored().key(),a.mimeType(),a.sha256(),a.size(),a.width(),a.height());
        access.db.update("UPDATE source_documents SET active_reading_edition_id=? WHERE id=?",edition,lease.documentId());return edition;
    }
    private void toc(long edition,Long parent,JsonNode nodes,Map<Integer,Long> units,int depth){
        if(depth>32||nodes.size()>5000)throw ApiException.invalid("目录过深或过多");int order=0;
        for(var n:nodes){String title=n.path("title").asText();if(title.length()>500)title=title.substring(0,500);
            long id=access.insert("INSERT INTO source_reading_toc_nodes(edition_id,parent_id,order_no,title,unit_id,local_id) VALUES(?,?,?,?,?,?)",edition,parent,++order,title,units.get(n.path("unitNo").asInt()),n.hasNonNull("localId")?n.path("localId").asText():null);
            toc(edition,id,n.path("children"),units,depth+1);
        }
    }
}
