package cn.tcm.platform.community.application;

import cn.tcm.platform.common.ApiException;
import cn.tcm.platform.community.infrastructure.CommunityPostRepository;
import cn.tcm.platform.content.ContentSupport;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

@Service
public class CommunityReferenceService {
    private final ContentSupport sql;
    private final CommunityPostRepository posts;

    public CommunityReferenceService(ContentSupport sql, CommunityPostRepository posts) {
        this.sql = sql;
        this.posts = posts;
    }

    public List<CommunityReference> read(long revision) {
        return posts.references(revision);
    }

    /**
     * V8 keys are unique within each revision. Serialize cross-article key assignment on the
     * singleton head after account/section/resource locks, through the reference write and commit.
     * Policy administration takes the same head lock and does not acquire section or resource locks.
     */
    public void lockAssignments(List<CommunityReference> references) {
        if (!references.isEmpty()) {
            sql.db.queryForObject("SELECT id FROM community_text_policy_heads WHERE id=1 FOR UPDATE", Integer.class);
        }
    }

    public Set<Integer> validate(long resource, String body, String format, List<CommunityReference> references,
                                 CommunityTextPolicy policy, boolean submitting) {
        var warnings = CommunityReferences.validate(body, references, policy, submitting, format);
        if (!references.isEmpty()) {
            var args = new ArrayList<Object>();
            references.forEach(ref -> args.add(ref.referenceKey()));
            args.add(resource);
            var marks = String.join(",", Collections.nCopies(references.size(), "?"));
            if (sql.count("""
                    SELECT COUNT(*) FROM community_revision_references ref
                    JOIN content_revisions v ON v.id=ref.revision_id
                    WHERE ref.reference_key IN (%s) AND v.resource_id<>?
                    """.formatted(marks), args.toArray()) > 0) {
                throw ApiException.invalid("文献稳定键已用于其他文章");
            }
        }
        return warnings;
    }

    public void replace(long revision, List<CommunityReference> references) {
        sql.db.update("DELETE FROM community_revision_references WHERE revision_id=?", revision);
        if (references.isEmpty()) return;
        sql.db.batchUpdate("""
                INSERT INTO community_revision_references(revision_id,reference_key,display_number,title,authors,
                    publication_year,source_name,doi,url,location_label) VALUES(?,?,?,?,?,?,?,?,?,?)
                """, references.stream().map(r -> new Object[]{revision, r.referenceKey(), r.displayNumber(), r.title(),
                r.authors(), r.publicationYear(), r.sourceName(), r.doi(), r.url(), r.locationLabel()}).toList());
    }
}
