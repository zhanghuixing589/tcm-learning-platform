package cn.tcm.platform.account.application;

import cn.tcm.platform.account.api.PersonalCenterDtos.*;
import cn.tcm.platform.common.*;
import cn.tcm.platform.identity.infrastructure.IdentityMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.sql.*;
import java.time.ZoneOffset;
import java.util.*;

/** Queries always resolve the approved publication pointer separately from the latest owner revision. */
@Service
public class PersonalCenterService {
    private final JdbcTemplate db;
    private final IdentityMapper identity;
    static final String AVAILABLE="(r.visibility_status='PUBLISHED' AND p.id IS NOT NULL AND p.revision_state='APPROVED' AND o.status='ACTIVE')";
    private static final String JOINS=" FROM content_resources r JOIN sys_users o ON o.id=r.owner_id LEFT JOIN content_revisions p ON p.id=r.published_revision_id AND p.resource_id=r.id LEFT JOIN content_revisions l ON l.resource_id=r.id AND l.version_no=(SELECT MAX(v.version_no) FROM content_revisions v WHERE v.resource_id=r.id) ";
    private static final String LIKES="(SELECT COUNT(*) FROM resource_likes lk JOIN sys_users lu ON lu.id=lk.user_id AND lu.status='ACTIVE' WHERE lk.resource_id=r.id AND lk.user_id<>r.owner_id)";
    private static final String BOOKMARKS="(SELECT COUNT(*) FROM resource_bookmarks bk JOIN sys_users bu ON bu.id=bk.user_id AND bu.status='ACTIVE' WHERE bk.resource_id=r.id)";
    private static final Set<String> TYPES=Set.of("KNOWLEDGE","TOPIC","CARD","QUESTION","POST","ARTICLE");
    private static final Set<String> STATUSES=Set.of("ALL","PUBLISHED","IN_REVIEW","DRAFT","NEEDS_CHANGES","SUSPENDED","ARCHIVED");
    public PersonalCenterService(JdbcTemplate db,IdentityMapper identity) {this.db=db;this.identity=identity;}
    private long count(String sql,Object...args) {return Objects.requireNonNull(db.queryForObject(sql,Long.class,args));}
    private static String time(ResultSet rs,String col) throws SQLException {var t=rs.getObject(col,java.time.LocalDateTime.class);return t==null?null:t.toInstant(ZoneOffset.UTC).toString();}
    private boolean follows(long from,long to) {return count("SELECT COUNT(*) FROM user_follows f JOIN sys_users a ON a.id=f.follower_id AND a.status='ACTIVE' JOIN sys_users b ON b.id=f.followed_id AND b.status='ACTIVE' WHERE f.follower_id=? AND f.followed_id=?",from,to)>0;}
    private void activeUser(long id) {if(count("SELECT COUNT(*) FROM sys_users WHERE id=? AND status='ACTIVE'",id)==0)throw ApiException.missing();}
    public Summary summary(long viewer,long owner) {
        activeUser(owner);
        Profile profile=db.queryForObject("SELECT id,nickname,bio,avatar_path,created_at FROM sys_users WHERE id=?",(rs,n)->new Profile(rs.getString("id"),rs.getString("nickname"),rs.getString("bio"),rs.getString("avatar_path")==null?null:"/api/v1/users/"+owner+"/avatar",time(rs,"created_at"),identity.roles(owner).stream().filter(role->Set.of("LEARNER","CONTRIBUTOR").contains(role)).toList()),owner);
        long received=count("SELECT COUNT(*)"+JOINS+" JOIN resource_likes likes ON likes.resource_id=r.id JOIN sys_users liker ON liker.id=likes.user_id WHERE r.owner_id=? AND r.resource_type IN ('ARTICLE','POST') AND "+AVAILABLE+" AND liker.status='ACTIVE' AND liker.id<>r.owner_id",owner);
        long following=count("SELECT COUNT(*) FROM user_follows f JOIN sys_users u ON u.id=f.followed_id AND u.status='ACTIVE' WHERE f.follower_id=?",owner);
        long followers=count("SELECT COUNT(*) FROM user_follows f JOIN sys_users u ON u.id=f.follower_id AND u.status='ACTIVE' WHERE f.followed_id=?",owner);
        return new Summary(profile,received,following,followers,follows(viewer,owner),follows(owner,viewer));
    }
    @Transactional public Map<String,Boolean> follow(long viewer,long owner,boolean add) {
        if(viewer==owner) throw ApiException.invalid("不能关注自己");
        // Same ordering as concurrent follows; account disabling also locks these account rows.
        db.queryForList("SELECT id FROM sys_users WHERE id IN (?,?) ORDER BY id FOR UPDATE",Math.min(viewer,owner),Math.max(viewer,owner));
        if(add) {activeUser(owner);db.update("INSERT INTO user_follows(follower_id,followed_id) VALUES(?,?) ON DUPLICATE KEY UPDATE id=id",viewer,owner);}
        else db.update("DELETE FROM user_follows WHERE follower_id=? AND followed_id=?",viewer,owner);
        return Map.of("following",add,"followsYou",follows(owner,viewer));
    }
    public PageResult<Relationship> relationships(long viewer,boolean followers,int page,int size,String q) {
        int offset=PageResult.offset(page,size);String search=search(q);
        String join=followers?"u.id=f.follower_id":"u.id=f.followed_id";
        String where=" FROM user_follows f JOIN sys_users u ON "+join+" AND u.status='ACTIVE' WHERE "+(followers?"f.followed_id":"f.follower_id")+"=?";
        List<Object> args=new ArrayList<>();args.add(viewer);
        if(!search.isEmpty()){where+=" AND u.nickname LIKE ? ESCAPE '!'";args.add(like(search));}
        long total=count("SELECT COUNT(*)"+where,args.toArray());
        args.add(size);args.add(offset);
        List<Relationship> items=db.query("SELECT u.id,u.nickname,u.bio,u.avatar_path"+where+" ORDER BY f.created_at DESC,f.id DESC LIMIT ? OFFSET ?",(rs,n)-> {
            long id=rs.getLong("id");return new Relationship(""+id,rs.getString("nickname"),rs.getString("bio"),rs.getString("avatar_path")==null?null:"/api/v1/users/"+id+"/avatar",follows(viewer,id),follows(id,viewer));
        },args.toArray());
        return new PageResult<>(page,size,total,items);
    }
    private static String search(String q) {if(q==null)return "";if(q.length()>100)throw ApiException.invalid("搜索词最多100字");return q.trim();}
    private static String like(String q) {return "%"+q.replace("!","!!").replace("%","!%").replace("_","!_")+"%";}
    private static String sourceName(String revision) {return "(SELECT d.title FROM revision_evidence e JOIN source_documents d ON d.id=e.document_id WHERE e.revision_id="+revision+".id AND d.review_state='APPROVED' AND d.availability='ACTIVE' ORDER BY e.id LIMIT 1)";}
    private static void filters(String type,String status) {
        if(type!=null && !type.isEmpty() && !TYPES.contains(type))throw ApiException.invalid("内容类型不正确");
        if(status!=null && !status.isEmpty() && !STATUSES.contains(status))throw ApiException.invalid("内容状态不正确");
    }
    public PageResult<ContentItem> contents(long viewer,long owner,String kind,int page,int size,String q,String type,String status) {
        int offset=PageResult.offset(page,size);String search=search(q);filters(type,status);
        boolean own=kind.equals("publications") && viewer==owner;
        if(kind.equals("publications") && !own)activeUser(owner);
        String from=JOINS,where;String occurred;
        List<Object> args=new ArrayList<>();args.add(owner);
        switch(kind) {
            case "bookmarks" -> {from+=" JOIN resource_bookmarks a ON a.resource_id=r.id ";where="a.user_id=?";occurred="a.created_at";}
            case "likes" -> {from+=" JOIN resource_likes a ON a.resource_id=r.id ";where="a.user_id=?";occurred="a.created_at";}
            case "history" -> {from+=" JOIN user_resource_history a ON a.resource_id=r.id ";where="a.user_id=?";occurred="a.last_viewed_at";}
            case "received-likes" -> {where="r.owner_id=? AND r.resource_type IN ('ARTICLE','POST') AND "+AVAILABLE+" AND "+LIKES+">0";occurred="(SELECT MAX(a.created_at) FROM resource_likes a JOIN sys_users u ON u.id=a.user_id AND u.status='ACTIVE' WHERE a.resource_id=r.id AND a.user_id<>r.owner_id)";}
            case "publications" -> {where="r.owner_id=? AND r.resource_type IN ('ARTICLE','POST')"+(own?" AND l.id IS NOT NULL":" AND "+AVAILABLE);occurred=own?"GREATEST(r.updated_at,l.updated_at)":"r.published_at";}
            default -> throw ApiException.missing();
        }
        String revision=own?"l":"p";
        String visible=own?"1=1":AVAILABLE;
        if(!search.isEmpty()){where+=" AND ("+visible+") AND "+revision+".title LIKE ? ESCAPE '!'";args.add(like(search));}
        if(type!=null&&!type.isEmpty()){where+=" AND r.resource_type=?";args.add(type);}
        if(status!=null&&!status.isEmpty()&&!status.equals("ALL")) {
            where+=switch(status) {
                case "IN_REVIEW" -> " AND "+(own?"l.revision_state='IN_REVIEW'":"1=0");
                case "NEEDS_CHANGES" -> " AND "+(own?"l.revision_state IN ('REJECTED','WITHDRAWN')":"1=0");
                case "DRAFT" -> " AND "+(own?"l.revision_state='DRAFT'":"1=0");
                default -> " AND r.visibility_status=?";
            };
            if(Set.of("PUBLISHED","SUSPENDED","ARCHIVED").contains(status))args.add(status);
        }
        from+=" WHERE "+where;
        long total=count("SELECT COUNT(*)"+from,args.toArray());args.add(size);args.add(offset);
        String source=sourceName(revision);
        String selection="SELECT r.id,r.resource_type,r.owner_id,o.nickname,r.visibility_status,"+AVAILABLE+" AS available,"+revision+".id AS revision_id,"+revision+".title,"+revision+".body,"+revision+".revision_state,"+source+" AS source_name,"+occurred+" AS occurred_at,"+LIKES+" AS like_count,"+BOOKMARKS+" AS bookmark_count";
        String order=kind.equals("received-likes")?"like_count DESC,occurred_at DESC,r.id DESC":"occurred_at DESC,r.id DESC";
        List<ContentItem> items=db.query(selection+from+" ORDER BY "+order+" LIMIT ? OFFSET ?",(rs,n)->item(rs,own),args.toArray());
        return new PageResult<>(page,size,total,items);
    }
    private ContentItem item(ResultSet rs,boolean own) throws SQLException {
        boolean available=rs.getBoolean("available"),show=available||own;
        String body=show?rs.getString("body"):null;
        return new ContentItem(rs.getString("id"),rs.getString("resource_type"),show?rs.getString("title"):"内容暂不可查看",body==null?null:body.substring(0,Math.min(body.length(),180)),available,time(rs,"occurred_at"),show?rs.getString("owner_id"):null,show?rs.getString("nickname"):null,show?rs.getString("source_name"):null,show?rs.getString("visibility_status"):"UNAVAILABLE",show?rs.getString("revision_state"):null,show?rs.getString("revision_id"):null,show?rs.getLong("like_count"):0,show?rs.getLong("bookmark_count"):0);
    }
    @Transactional public Map<String,Object> detail(long viewer,long id) {
        var rows=db.queryForList("SELECT r.id,p.title,p.body,p.id AS revision_id,r.resource_type,r.owner_id,o.nickname,r.visibility_status,p.revision_state,DATE_FORMAT(r.published_at,'%Y-%m-%dT%H:%i:%s.%fZ') AS occurred_at,"+sourceName("p")+" AS source_name"+JOINS+" WHERE r.id=? AND "+AVAILABLE,id);
        if(rows.isEmpty())throw ApiException.missing();
        var row=rows.get(0);var result=new LinkedHashMap<String,Object>();
        result.put("id",""+id);result.put("title",row.get("title"));result.put("body",row.get("body"));result.put("resourceType",row.get("resource_type"));result.put("ownerId",row.get("owner_id").toString());result.put("ownerNickname",row.get("nickname"));result.put("revisionId",row.get("revision_id").toString());result.put("revisionState",row.get("revision_state"));result.put("visibilityStatus",row.get("visibility_status"));result.put("available",true);
        String body=row.get("body").toString();result.put("summary",body.substring(0,Math.min(body.length(),180)));result.put("occurredAt",row.get("occurred_at"));result.put("sourceName",row.get("source_name"));
        result.put("liked",count("SELECT COUNT(*) FROM resource_likes WHERE user_id=? AND resource_id=?",viewer,id)>0);
        result.put("bookmarked",count("SELECT COUNT(*) FROM resource_bookmarks WHERE user_id=? AND resource_id=?",viewer,id)>0);
        result.put("likeCount",count("SELECT "+LIKES+JOINS+" WHERE r.id=?",id));
        result.put("bookmarkCount",count("SELECT "+BOOKMARKS+JOINS+" WHERE r.id=?",id));
        db.update("INSERT INTO user_resource_history(user_id,resource_id,last_viewed_at) VALUES(?,?,UTC_TIMESTAMP(3)) ON DUPLICATE KEY UPDATE last_viewed_at=UTC_TIMESTAMP(3),view_count=LEAST(view_count,2147483646)+1",viewer,id);
        return result;
    }
    @Transactional public Map<String,Boolean> interaction(long viewer,long id,boolean bookmark,boolean add) {
        String table=bookmark?"resource_bookmarks":"resource_likes";
        if(add) {
            db.queryForList("SELECT id FROM content_resources WHERE id=? FOR UPDATE",id);
            if(count("SELECT COUNT(*)"+JOINS+" WHERE r.id=? AND "+AVAILABLE,id)==0)throw ApiException.missing();
            db.update("INSERT INTO "+table+"(user_id,resource_id) VALUES(?,?) ON DUPLICATE KEY UPDATE id=id",viewer,id);
        } else db.update("DELETE FROM "+table+" WHERE user_id=? AND resource_id=?",viewer,id);
        return Map.of("active",add);
    }
    @Transactional public void deleteHistory(long viewer,Long resource) {
        if(resource==null) db.update("DELETE FROM user_resource_history WHERE user_id=?",viewer);
        else db.update("DELETE FROM user_resource_history WHERE user_id=? AND resource_id=?",viewer,resource);
    }
    private void canWrite(long viewer) {if(!identity.permissions(viewer).contains("community:article:submit"))throw new ApiException(403,"FORBIDDEN","需要文章投稿权限");}
    private long insert(String sql,Object...args) {
        var key=new GeneratedKeyHolder();db.update(c->{var s=c.prepareStatement(sql,Statement.RETURN_GENERATED_KEYS);for(int i=0;i<args.length;i++)s.setObject(i+1,args[i]);return s;},key);
        return Objects.requireNonNull(key.getKey()).longValue();
    }
    private Map<String,Object> articleRow(long owner,long id,boolean lock) {
        return publicationRow(owner,id,lock,false);
    }
    private Map<String,Object> publicationRow(long owner,long id,boolean lock,boolean includePost) {
        var resources=db.queryForList("SELECT id,resource_type,visibility_status,published_revision_id,suspended_reason FROM content_resources WHERE id=? AND owner_id=? AND "+(includePost?"resource_type IN ('ARTICLE','POST')":"resource_type='ARTICLE'")+(lock?" FOR UPDATE":""),id,owner);
        if(resources.isEmpty())throw ApiException.missing();
        var revisions=db.queryForList("SELECT * FROM content_revisions WHERE resource_id=? ORDER BY version_no DESC LIMIT 1",id);
        if(revisions.isEmpty())throw ApiException.missing();
        var row=new LinkedHashMap<>(revisions.get(0));row.putAll(resources.get(0));return row;
    }
    public Map<String,Object> article(long owner,long id) {
        return publicationView(id,articleRow(owner,id,false));
    }
    public Map<String,Object> publication(long owner,long id) {
        return publicationView(id,publicationRow(owner,id,false,true));
    }
    private Map<String,Object> publicationView(long id,Map<String,Object> row) {
        var result=new LinkedHashMap<String,Object>();result.put("id",""+id);result.put("title",row.get("title"));result.put("body",row.get("body"));result.put("revisionId",db.queryForObject("SELECT id FROM content_revisions WHERE resource_id=? ORDER BY version_no DESC LIMIT 1",String.class,id));result.put("revisionState",row.get("revision_state"));result.put("visibilityStatus",row.get("visibility_status"));
        result.put("resourceType",row.get("resource_type"));
        var notes=db.query("SELECT t.review_note FROM review_tasks t JOIN content_revisions v ON v.id=t.revision_id WHERE v.resource_id=? ORDER BY v.version_no DESC LIMIT 1",(rs,n)->rs.getString(1),id);
        result.put("reviewNote",notes.isEmpty()?null:notes.get(0));
        result.put("stateReason",row.get("suspended_reason"));
        return result;
    }
    @Transactional public Map<String,Object> createArticle(long owner,ArticleInput input) {
        canWrite(owner);long id=insert("INSERT INTO content_resources(resource_type,owner_id) VALUES('ARTICLE',?)",owner);
        db.update("INSERT INTO content_revisions(resource_id,version_no,title,body,created_by) VALUES(?,1,?,?,?)",id,input.title().trim(),input.body(),owner);return article(owner,id);
    }
    @Transactional public Map<String,Object> updateArticle(long owner,long id,ArticleInput input) {
        canWrite(owner);var row=articleRow(owner,id,true);requireState(row,"DRAFT");
        db.update("UPDATE content_revisions SET title=?,body=? WHERE resource_id=? AND version_no=?",input.title().trim(),input.body(),id,row.get("version_no"));return article(owner,id);
    }
    private static void requireState(Map<String,Object> row,String state) {if(!state.equals(row.get("revision_state")))throw ApiException.conflict("当前修订状态不允许此操作");}
    @Transactional public void deleteArticle(long owner,long id) {
        var row=articleRow(owner,id,true);requireState(row,"DRAFT");
        db.update("DELETE FROM content_revisions WHERE resource_id=? AND version_no=?",id,row.get("version_no"));
        if(count("SELECT COUNT(*) FROM content_revisions WHERE resource_id=?",id)==0)db.update("DELETE FROM content_resources WHERE id=?",id);
    }
    @Transactional public Map<String,Object> submitArticle(long owner,long id) {
        canWrite(owner);var row=articleRow(owner,id,true);requireState(row,"DRAFT");
        long revision=db.queryForObject("SELECT id FROM content_revisions WHERE resource_id=? AND version_no=?",Long.class,id,row.get("version_no"));
        db.update("UPDATE content_revisions SET revision_state='IN_REVIEW',submitted_at=UTC_TIMESTAMP(3) WHERE id=?",revision);
        db.update("INSERT INTO review_tasks(revision_id,submitted_by) VALUES(?,?)",revision,owner);return article(owner,id);
    }
    @Transactional public Map<String,Object> withdrawArticle(long owner,long id) {
        var row=articleRow(owner,id,true);requireState(row,"IN_REVIEW");
        long revision=db.queryForObject("SELECT id FROM content_revisions WHERE resource_id=? AND version_no=?",Long.class,id,row.get("version_no"));
        var tasks=db.queryForList("SELECT id,status FROM review_tasks WHERE revision_id=? FOR UPDATE",revision);
        if(tasks.isEmpty()||!"PENDING".equals(tasks.get(0).get("status")))throw ApiException.conflict("审核任务已处理");
        db.update("UPDATE review_tasks SET status='WITHDRAWN' WHERE revision_id=?",revision);
        db.update("INSERT INTO review_events(review_task_id,actor_id,action) VALUES(?,?,'WITHDRAW')",tasks.get(0).get("id"),owner);
        db.update("UPDATE content_revisions SET revision_state='WITHDRAWN' WHERE id=?",revision);return article(owner,id);
    }
    @Transactional public Map<String,Object> reviseArticle(long owner,long id) {
        canWrite(owner);var row=articleRow(owner,id,true);
        if(!Set.of("APPROVED","REJECTED","WITHDRAWN").contains(row.get("revision_state")))throw ApiException.conflict("请先完成当前修订");
        db.update("INSERT INTO content_revisions(resource_id,version_no,base_revision_id,title,body,created_by) SELECT resource_id,version_no+1,id,title,body,? FROM content_revisions WHERE resource_id=? AND version_no=?",owner,id,row.get("version_no"));return article(owner,id);
    }
}
