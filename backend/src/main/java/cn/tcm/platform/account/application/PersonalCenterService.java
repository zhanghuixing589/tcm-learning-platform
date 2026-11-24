package cn.tcm.platform.account.application;

import cn.tcm.platform.account.api.PersonalCenterDtos.*;
import cn.tcm.platform.common.*;
import cn.tcm.platform.community.application.CommunityArticleService;
import cn.tcm.platform.community.infrastructure.CommunityPostRepository;
import cn.tcm.platform.content.ContentSupport;
import cn.tcm.platform.content.application.PublishedResourceAccess;
import cn.tcm.platform.identity.infrastructure.IdentityMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import java.sql.*;
import java.time.ZoneOffset;
import java.util.*;
import static cn.tcm.platform.content.application.PublishedResourceAccess.*;
import static cn.tcm.platform.community.application.CommunitySectionService.POST;
import static cn.tcm.platform.community.application.CommunitySectionService.MODERATE;

/** Queries always resolve the approved publication pointer separately from the latest owner revision. */
@Service
public class PersonalCenterService {
    // JDBC 数据访问组件。
    private final JdbcTemplate db;
    private final IdentityMapper identity;
    private final PublishedResourceAccess publications;
    private final CommunityArticleService articles;
    private final CommunityPostRepository posts;
    private final ContentSupport sql;
    // 连接作者、公开修订及本人最新修订；最新草稿不覆盖正式指针。
    private static final String JOINS=PublishedResourceAccess.JOINS+" LEFT JOIN content_revisions l ON l.resource_id=r.id AND l.version_no=(SELECT MAX(v.version_no) FROM content_revisions v WHERE v.resource_id=r.id) ";
    // 内容类型白名单：知识、话题、卡片、问题、讨论和文章。
    private static final Set<String> TYPES=Set.of("KNOWLEDGE","TOPIC","CARD","QUESTION","POST","ARTICLE");
    // 个人内容列表支持的状态白名单。
    private static final Set<String> STATUSES=Set.of("ALL","PUBLISHED","IN_REVIEW","DRAFT","NEEDS_CHANGES","SUSPENDED","ARCHIVED");
    public PersonalCenterService(JdbcTemplate db, IdentityMapper identity, PublishedResourceAccess publications,
                                 CommunityArticleService articles, CommunityPostRepository posts, ContentSupport sql) {
        this.db = db;
        this.identity = identity;
        this.publications = publications;
        this.articles = articles;
        this.posts = posts;
        this.sql = sql;
    }
    private long count(String sql,Object...args) {return Objects.requireNonNull(db.queryForObject(sql,Long.class,args));}
    private static String time(ResultSet rs,String col) throws SQLException {var t=rs.getObject(col,java.time.LocalDateTime.class);return t==null?null:t.toInstant(ZoneOffset.UTC).toString();}
    // 关注关系查询要求关注双方账号都有效。
    private boolean follows(long from,long to) {return count("SELECT COUNT(*) FROM user_follows f JOIN sys_users a ON a.id=f.follower_id AND a.status='ACTIVE' JOIN sys_users b ON b.id=f.followed_id AND b.status='ACTIVE' WHERE f.follower_id=? AND f.followed_id=?",from,to)>0;}
    // 不存在或非活跃账号按不可访问处理。
    private void activeUser(long id) {if(count("SELECT COUNT(*) FROM sys_users WHERE id=? AND status='ACTIVE'",id)==0)throw ApiException.missing();}
    // 组合个人资料、有效获赞、关注粉丝数量和双向关注关系。
    public Summary summary(long viewer,long owner) {
        activeUser(owner);
        Profile profile=db.queryForObject("SELECT id,nickname,bio,avatar_path,created_at FROM sys_users WHERE id=?",(rs,n)->new Profile(rs.getString("id"),rs.getString("nickname"),rs.getString("bio"),rs.getString("avatar_path")==null?null:"/api/v1/users/"+owner+"/avatar",time(rs,"created_at"),identity.roles(owner).stream().filter(role->Set.of("LEARNER","CONTRIBUTOR").contains(role)).toList()),owner);
        long received=count("SELECT COUNT(*)"+JOINS+" JOIN resource_likes likes ON likes.resource_id=r.id JOIN sys_users liker ON liker.id=likes.user_id WHERE r.owner_id=? AND r.resource_type IN ('ARTICLE','POST') AND "+AVAILABLE+" AND liker.status='ACTIVE' AND liker.id<>r.owner_id",owner);
        long following=count("SELECT COUNT(*) FROM user_follows f JOIN sys_users u ON u.id=f.followed_id AND u.status='ACTIVE' WHERE f.follower_id=?",owner);
        long followers=count("SELECT COUNT(*) FROM user_follows f JOIN sys_users u ON u.id=f.follower_id AND u.status='ACTIVE' WHERE f.followed_id=?",owner);
        return new Summary(profile,received,following,followers,follows(viewer,owner),follows(owner,viewer));
    }
    // 关注或取消关注，并返回当前关系；不能关注自己。
    @Transactional public Map<String,Boolean> follow(long viewer,long owner,boolean add) {
        if(viewer==owner) throw ApiException.invalid("不能关注自己");
        // Same ordering as concurrent follows; account disabling also locks these account rows.
        // A 关注 B 与 B 关注 A 都按账号 ID 升序加锁，避免相反锁序形成循环等待。
        db.queryForList("SELECT id FROM sys_users WHERE id IN (?,?) ORDER BY id FOR UPDATE",Math.min(viewer,owner),Math.max(viewer,owner));
        if(add) {activeUser(owner);db.update("INSERT INTO user_follows(follower_id,followed_id) VALUES(?,?) ON DUPLICATE KEY UPDATE id=id",viewer,owner);}
        else db.update("DELETE FROM user_follows WHERE follower_id=? AND followed_id=?",viewer,owner);
        return Map.of("following",add,"followsYou",follows(owner,viewer));
    }
    // 按关系方向查询关注或粉丝，支持分页和昵称过滤。
    public PageResult<Relationship> relationships(long viewer,boolean followers,int page,int size,String q) {
        int offset=PageResult.offset(page,size);String search=search(q);
        String join=followers?"u.id=f.follower_id":"u.id=f.followed_id";
        String where=" FROM user_follows f JOIN sys_users u ON "+join+" AND u.status='ACTIVE' WHERE "+(followers?"f.followed_id":"f.follower_id")+"=?";
        List<Object> args=new ArrayList<>();args.add(viewer);
        // 模糊搜索参数先转义，再使用预编译参数绑定。
        if(!search.isEmpty()){where+=" AND u.nickname LIKE ? ESCAPE '!'";args.add(like(search));}
        long total=count("SELECT COUNT(*)"+where,args.toArray());
        args.add(size);args.add(offset);
        // 组装关系列表；当前每行仍查询双向关注状态，不能将这里表述为已消除 N+1 查询。
        List<Relationship> items=db.query("SELECT u.id,u.nickname,u.bio,u.avatar_path"+where+" ORDER BY f.created_at DESC,f.id DESC LIMIT ? OFFSET ?",(rs,n)-> {
            long id=rs.getLong("id");return new Relationship(""+id,rs.getString("nickname"),rs.getString("bio"),rs.getString("avatar_path")==null?null:"/api/v1/users/"+id+"/avatar",follows(viewer,id),follows(id,viewer));
        },args.toArray());
        return new PageResult<>(page,size,total,items);
    }
    // 搜索词限制长度后去除首尾空白。
    private static String search(String q) {if(q==null)return "";if(q.length()>100)throw ApiException.invalid("搜索词最多100字");return q.trim();}
    // 转义用户输入中的 !、% 和 _，再添加包含匹配通配符。
    private static String like(String q) {return "%"+q.replace("!","!!").replace("%","!%").replace("_","!_")+"%";}
    private static String sourceName(String revision) {return "(SELECT d.title FROM revision_evidence e JOIN source_documents d ON d.id=e.document_id WHERE e.revision_id="+revision+".id AND d.review_state='APPROVED' AND d.availability='ACTIVE' ORDER BY e.id LIMIT 1)";}
    // 只允许已声明的资源类型和状态参与筛选。
    private static void filters(String type,String status) {
        if(type!=null && !type.isEmpty() && !TYPES.contains(type))throw ApiException.invalid("内容类型不正确");
        if(status!=null && !status.isEmpty() && !STATUSES.contains(status))throw ApiException.invalid("内容状态不正确");
    }
    // 按 Tab 选择本人发表、收藏、获赞或浏览历史，公开读取始终跟随正式版本。
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
        // 模糊搜索参数先转义，再使用预编译参数绑定。
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
    // 不可访问内容使用安全占位；本人稿件与公众可见性分别判定。
    private ContentItem item(ResultSet rs,boolean own) throws SQLException {
        boolean available=rs.getBoolean("available"),show=available||own;
        String body=show?rs.getString("body"):null;
        return new ContentItem(rs.getString("id"),rs.getString("resource_type"),show?rs.getString("title"):"内容暂不可查看",excerpt(body),available,time(rs,"occurred_at"),show?rs.getString("owner_id"):null,show?rs.getString("nickname"):null,show?rs.getString("source_name"):null,show?rs.getString("visibility_status"):"UNAVAILABLE",show?rs.getString("revision_state"):null,show?rs.getString("revision_id"):null,show?rs.getLong("like_count"):0,show?rs.getLong("bookmark_count"):0);
    }
    // 读取公开详情及互动状态，并只记录一次浏览。
    @Transactional public Map<String,Object> detail(long viewer,long id) {
        // 查询正式版正文，附带来源和资源信息。
        var rows=db.queryForList("SELECT r.id,p.title,p.body,p.id AS revision_id,r.resource_type,r.owner_id,o.nickname,r.visibility_status,p.revision_state,DATE_FORMAT(r.published_at,'%Y-%m-%dT%H:%i:%s.%fZ') AS occurred_at,"+sourceName("p")+" AS source_name"+JOINS+" WHERE r.id=? AND "+AVAILABLE,id);
        // 未查询到可见资源时返回 404。
        if(rows.isEmpty())throw ApiException.missing();
        // 提取查询结果并组装返回字段。
        var row=rows.get(0);var result=new LinkedHashMap<String,Object>();
        // 映射正文、身份、版本与公开状态；社区元数据另行补齐。
        result.put("id",""+id);result.put("title",row.get("title"));result.put("body",row.get("body"));result.put("resourceType",row.get("resource_type"));result.put("ownerId",row.get("owner_id").toString());result.put("ownerNickname",row.get("nickname"));result.put("revisionId",row.get("revision_id").toString());result.put("revisionState",row.get("revision_state"));result.put("visibilityStatus",row.get("visibility_status"));result.put("available",true);
        result.put("summary",excerpt(row.get("body").toString()));result.put("occurredAt",row.get("occurred_at"));result.put("sourceName",row.get("source_name"));
        result.put("liked",count("SELECT COUNT(*) FROM resource_likes WHERE user_id=? AND resource_id=?",viewer,id)>0);
        result.put("bookmarked",count("SELECT COUNT(*) FROM resource_bookmarks WHERE user_id=? AND resource_id=?",viewer,id)>0);
        result.put("likeCount",count("SELECT "+LIKES+JOINS+" WHERE r.id=?",id));
        result.put("bookmarkCount",count("SELECT "+BOOKMARKS+JOINS+" WHERE r.id=?",id));
        String type = (String) row.get("resource_type");
        if (isCommunityResource(type)) {
            var detail = posts.published(new CommunityPostRepository.Grantee(viewer, sql.has(viewer, POST),
                    sql.has(viewer, MODERATE)), id, false);
            result.put("sourceFormat", detail == null ? "PLAIN_TEXT" : detail.sourceFormat());
            result.put("formatPolicyId", detail == null ? null : detail.formatPolicyId());
            result.put("section", detail == null ? null : detail.section());
            result.put("references", detail == null ? List.of() : detail.references());
            result.put("community", detail);
        }
        publications.recordView(viewer, id, type);
        return result;
    }
    // 统一处理点赞与收藏；新增关系前重新检查资源可见性和类型。
    @Transactional(isolation=Isolation.READ_COMMITTED)
    public Map<String,Boolean> interaction(long viewer,long id,boolean bookmark,boolean add) {
        var types=db.queryForList("SELECT resource_type FROM content_resources WHERE id=?",String.class,id);
        String type=types.isEmpty()?null:types.get(0);
        // Community writers lock the actor first. Knowledge publication locks its referenced resource first.
        if(isCommunityResource(type))publications.lockActiveViewer(viewer);
        String table=bookmark?"resource_bookmarks":"resource_likes";
        if(add) {
            if(type==null)throw ApiException.missing();
            var currentTypes=db.queryForList("SELECT resource_type FROM content_resources WHERE id=? FOR UPDATE",String.class,id);
            if(currentTypes.isEmpty())throw ApiException.missing();
            if(!type.equals(currentTypes.get(0)))throw ApiException.conflict("资源类型已变化，请刷新后重试");
            if(count("SELECT COUNT(*)"+JOINS+" WHERE r.id=? AND "+AVAILABLE,id)==0)throw ApiException.missing();
            db.update("INSERT INTO "+table+"(user_id,resource_id) VALUES(?,?) ON DUPLICATE KEY UPDATE id=id",viewer,id);
        } else db.update("DELETE FROM "+table+" WHERE user_id=? AND resource_id=?",viewer,id);
        return Map.of("active",add);
    }
    // 删除指定浏览记录，资源编号为空时清空本人历史。
    @Transactional public void deleteHistory(long viewer,Long resource) {
        if(resource==null) db.update("DELETE FROM user_resource_history WHERE user_id=?",viewer);
        else db.update("DELETE FROM user_resource_history WHERE user_id=? AND resource_id=?",viewer,resource);
    }
    // 本人内容兼容入口：文章转交统一文章服务，讨论返回独立的作者版本视图。
    @Transactional(readOnly = true)
    public Map<String, Object> publication(long owner, long id) {
        var row = sql.row("""
                SELECT * FROM content_resources WHERE id=? AND owner_id=? AND resource_type IN ('ARTICLE','POST')
                """, id, owner);
        if ("ARTICLE".equals(row.get("resource_type"))) return articles.own(owner, id);
        var latest = posts.source(posts.latest(id), false);
        var published = posts.published(new CommunityPostRepository.Grantee(owner, sql.has(owner, POST),
                sql.has(owner, MODERATE)), id);
        var notes = db.queryForList("""
                SELECT t.review_note FROM review_tasks t JOIN content_revisions v ON v.id=t.revision_id
                WHERE v.resource_id=? ORDER BY v.version_no DESC LIMIT 1
                """, id);
        return ContentSupport.map("id", Long.toString(id), "title", latest.title(), "body", latest.body(),
                "resourceType", row.get("resource_type"), "revisionId", latest.revisionId(), "revisionState", latest.state(),
                "visibilityStatus", row.get("visibility_status"), "stateReason", row.get("suspended_reason"),
                "reviewNote", notes.isEmpty() ? null : notes.get(0).get("review_note"), "latestRevision", latest,
                "publishedRevisionId", ContentSupport.sid(row.get("published_revision_id")), "published", published,
                "lockVersion", row.get("lock_version"));
    }
}
