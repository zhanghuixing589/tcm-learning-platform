package cn.tcm.platform.common;

/** Shared visibility guard for knowledge read through its own API or generic resource APIs. */
public final class PublishedKnowledge {
    private PublishedKnowledge() {}
    public static String evidenceIsActive(String revisionAlias) {
        if(!revisionAlias.matches("[a-zA-Z][a-zA-Z0-9_]*")) throw new IllegalArgumentException("Invalid SQL alias");
        return "(EXISTS (SELECT 1 FROM revision_evidence ke WHERE ke.revision_id="+revisionAlias+".id)"
                +" AND NOT EXISTS (SELECT 1 FROM revision_evidence ke JOIN source_documents kd ON kd.id=ke.document_id"
                +" WHERE ke.revision_id="+revisionAlias+".id AND (kd.review_state<>'APPROVED' OR kd.availability<>'ACTIVE')))";
    }
}
