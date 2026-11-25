package cn.tcm.platform.knowledge.application;

import cn.tcm.platform.common.ApiException;
import cn.tcm.platform.content.ContentSupport;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;
import static cn.tcm.platform.content.ContentSupport.*;

@Service
public class KnowledgeTaxonomy {
    public static final String MANAGE="knowledge:taxonomy:manage";
    private static final Set<String> TYPES=Set.of("CONCEPT","HERB","FORMULA","EFFECT","SYMPTOM","MERIDIAN");
    private final ContentSupport c;
    public KnowledgeTaxonomy(ContentSupport c){this.c=c;}
    public static void type(String type){if(type==null||!TYPES.contains(type))throw ApiException.invalid("知识类型不正确");}
    public List<Map<String,Object>> directories(String type){
        if(type!=null&&!type.isBlank())type(type);
        return c.db.queryForList("SELECT * FROM content_categories WHERE scope='KNOWLEDGE' AND active=1 AND legacy_archive=0"+(type==null||type.isBlank()?"":" AND knowledge_type=?")+" ORDER BY knowledge_type,sort_order,id",type==null||type.isBlank()?new Object[]{}:new Object[]{type}).stream().map(this::directory).toList();
    }
    public List<Map<String,Object>> tags(){return c.db.queryForList("SELECT * FROM content_tags WHERE active=1 ORDER BY name,id").stream().map(this::tag).toList();}
    public List<Map<String,Object>> adminDirectories(long actor){c.require(actor,MANAGE);return c.db.queryForList("SELECT * FROM content_categories WHERE scope='KNOWLEDGE' ORDER BY legacy_archive,knowledge_type,sort_order,id").stream().map(this::directory).toList();}
    public List<Map<String,Object>> adminTags(long actor){c.require(actor,MANAGE);return c.db.queryForList("SELECT * FROM content_tags ORDER BY name,id").stream().map(this::tag).toList();}
    private Map<String,Object> directory(Map<String,Object> row){return map("id",sid(row.get("id")),"name",row.get("name"),"code",row.get("code"),"type",row.get("knowledge_type"),"active",number(row.get("active"))==1,"legacy",number(row.get("legacy_archive"))==1,"sortOrder",row.get("sort_order"),"lockVersion",row.get("lock_version"));}
    private Map<String,Object> tag(Map<String,Object> row){return map("id",sid(row.get("id")),"name",row.get("name"),"active",number(row.get("active"))==1,"lockVersion",row.get("lock_version"));}
    public void requireDirectory(String category,String type,boolean lock){
        type(type);if(category==null||category.isBlank())return;
        var rows=c.db.queryForList("SELECT knowledge_type,scope,active,legacy_archive FROM content_categories WHERE id=?"+(lock?" FOR SHARE":""),id(category));
        if(rows.isEmpty())throw ApiException.invalid("目录不存在");var row=rows.get(0);
        if(!"KNOWLEDGE".equals(row.get("scope"))||!type.equals(row.get("knowledge_type"))||number(row.get("active"))!=1||number(row.get("legacy_archive"))!=0)throw ApiException.invalid("目录必须属于当前知识类型且已启用");
    }
    public List<Long> requireTags(List<String> ids,boolean lock){
        if(ids==null)return List.of();if(ids.size()>20)throw ApiException.invalid("最多选择20个通用标签");
        var unique=new TreeSet<Long>();for(String value:ids)if(!unique.add(id(value)))throw ApiException.invalid("标签不能重复");
        for(long value:unique){var rows=c.db.queryForList("SELECT active FROM content_tags WHERE id=?"+(lock?" FOR SHARE":""),value);if(rows.isEmpty()||number(rows.get(0).get("active"))!=1)throw ApiException.invalid("标签不存在或已停用");}
        return new ArrayList<>(unique);
    }
    @Transactional public Map<String,Object> createDirectory(long actor,String type,String name,int sort,boolean active){
        c.require(actor,MANAGE);type(type);name=text(name,80,true);
        try{long id=c.insert("INSERT INTO content_categories(code,name,scope,knowledge_type,sort_order,active) VALUES(?,?,'KNOWLEDGE',?,?,?)","kdir_"+UUID.randomUUID(),name,type,sort,active?1:0);c.audit(actor,"DIRECTORY_CREATE","CATEGORY",id);return directory(c.row("SELECT * FROM content_categories WHERE id=?",id));}
        catch(DuplicateKeyException e){throw ApiException.conflict("该类型下已有同名目录");}
    }
    @Transactional public Map<String,Object> updateDirectory(long actor,long id,String name,int sort,boolean active,int version){
        c.require(actor,MANAGE);var old=c.row("SELECT * FROM content_categories WHERE id=? AND scope='KNOWLEDGE' FOR UPDATE",id);
        if(number(old.get("legacy_archive"))==1)throw ApiException.conflict("历史目录只读，不能重新启用或修改");
        if(number(old.get("lock_version"))!=version)throw ApiException.conflict("目录已被更新，请重新加载后核对");
        try{c.db.update("UPDATE content_categories SET name=?,sort_order=?,active=?,lock_version=lock_version+1 WHERE id=?",text(name,80,true),sort,active?1:0,id);c.audit(actor,"DIRECTORY_UPDATE","CATEGORY",id);return directory(c.row("SELECT * FROM content_categories WHERE id=?",id));}
        catch(DuplicateKeyException e){throw ApiException.conflict("该类型下已有同名目录");}
    }
    @Transactional public Map<String,Object> createTag(long actor,String name,boolean active){
        c.require(actor,MANAGE);try{long id=c.insert("INSERT INTO content_tags(name,active) VALUES(?,?)",text(name,64,true),active?1:0);c.audit(actor,"TAG_CREATE","TAG",id);return tag(c.row("SELECT * FROM content_tags WHERE id=?",id));}catch(DuplicateKeyException e){throw ApiException.conflict("已有同名标签");}
    }
    @Transactional public Map<String,Object> updateTag(long actor,long id,String name,boolean active,int version){
        c.require(actor,MANAGE);var old=c.row("SELECT * FROM content_tags WHERE id=? FOR UPDATE",id);if(number(old.get("lock_version"))!=version)throw ApiException.conflict("标签已被更新，请重新加载后核对");
        try{c.db.update("UPDATE content_tags SET name=?,active=?,lock_version=lock_version+1 WHERE id=?",text(name,64,true),active?1:0,id);c.audit(actor,"TAG_UPDATE","TAG",id);return tag(c.row("SELECT * FROM content_tags WHERE id=?",id));}catch(DuplicateKeyException e){throw ApiException.conflict("已有同名标签");}
    }
}
