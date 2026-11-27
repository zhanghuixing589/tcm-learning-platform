package cn.tcm.platform.source;

import cn.tcm.platform.common.ApiException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.nio.charset.StandardCharsets;
import java.util.*;

@Service
public class SourceImportService {
    private final SourceAccess access;private final SourceService sources;private final ObjectMapper json;private final jakarta.validation.Validator validator;
    public SourceImportService(SourceAccess access,SourceService sources,ObjectMapper json,jakarta.validation.Validator validator){this.access=access;this.sources=sources;this.json=json;this.validator=validator;}
    private com.fasterxml.jackson.databind.JsonNode canonical(com.fasterxml.jackson.databind.JsonNode n){if(n.isObject()){var out=json.createObjectNode();var keys=new java.util.TreeSet<String>();n.fieldNames().forEachRemaining(keys::add);keys.forEach(k->out.set(k,canonical(n.get(k))));return out;}if(n.isArray()){var out=json.createArrayNode();n.forEach(v->out.add(canonical(v)));return out;}return n;}
    @Transactional public Map<String,Object> confirm(long actor,ObjectNode request){
        access.require(actor,"contribution:submit");
        String key=request.path("requestKey").asText();if(!key.matches("[A-Za-z0-9_-]{8,80}"))throw ApiException.invalid("确认请求标识无效，请重新确认");
        // Serialise confirmations for this actor so concurrent equal keys cannot create orphan sources.
        access.db.queryForObject("SELECT id FROM sys_users WHERE id=? FOR UPDATE",Long.class,actor);
        String fingerprint=SourceFiles.hash(canonical(request).toString().getBytes(StandardCharsets.UTF_8));
        var previous=access.db.queryForList("SELECT * FROM source_confirmation_requests WHERE user_id=? AND request_key=?",actor,key);
        if(!previous.isEmpty()){
            var row=previous.get(0);if(!fingerprint.equals(row.get("request_hash")))throw ApiException.conflict("同一确认标识不能用于不同内容");
            return sources.detail(actor,((Number)row.get("document_id")).longValue());
        }
        SourceService.Input input;try{var fields=request.deepCopy();fields.remove(List.of("requestKey","documentId"));input=json.treeToValue(fields,SourceService.Input.class);}catch(Exception e){throw ApiException.invalid("资料确认内容无效");}
        if(!validator.validate(input).isEmpty())throw ApiException.invalid("资料书目信息或授权内容无效");
        if(input.rights()==null||input.fileId()==null)throw ApiException.invalid("请上传原文件并声明授权范围");
        long document;
        if(request.hasNonNull("documentId")){
            try{document=Long.parseLong(request.path("documentId").asText());}catch(Exception e){throw ApiException.invalid("资料编号无效");}
            sources.update(actor,document,input);
        }else document=Long.parseLong(sources.create(actor,input).get("id").toString());
        var d=access.document(actor,document,true);if(!"DECLARED".equals(d.get("rights_status")))throw ApiException.invalid("确认处理前请完善版权来源和授权范围");
        access.db.update("UPDATE source_documents SET reader_required=1 WHERE id=?",document);
        var run=sources.parse(actor,document);
        access.db.update("INSERT INTO source_confirmation_requests(user_id,request_key,request_hash,document_id,parse_run_id) VALUES(?,?,?,?,?)",actor,key,fingerprint,document,Long.parseLong(run.get("id").toString()));
        return sources.detail(actor,document);
    }
}
