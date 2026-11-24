import type { Article, Reference, TextPolicy } from "./types";

export const testPolicy: TextPolicy = {
  policyId: "1",
  lockVersion: 0,
  characterUnit: "UNICODE_CODE_POINT",
  config: {
    allowedSyntax: ["paragraph", "heading", "emphasis", "strong", "blockquote", "code", "codeBlock", "list", "link", "table", "hr"],
    allowedProtocols: ["http", "https"],
    maxTitleChars: 120,
    maxPostChars: 20000,
    maxArticleChars: 100000,
    maxCommentChars: 2000,
    maxReferences: 300,
    maxReferenceChars: 200000,
    maxArticleHardChars: 500000,
    maxRequestBytes: 8388608,
    rates: { POST_CREATE: [], COMMENT_CREATE: [], PUBLIC_EDIT: [], DRAFT_SAVE: [], QUOTA_REQUEST: [] },
  },
};
export function testArticle(id: string, title: string, body = "已保存的正文"): Article {
  const references: Reference[] = [];
  return {
    id, title, body, resourceType: "ARTICLE", revisionId: id, revisionState: "DRAFT", visibilityStatus: "DRAFT",
    reviewNote: null, stateReason: null, sourceFormat: "MARKDOWN", sectionId: null, references,
    published: null, publishedRevisionId: null, lockVersion: 0, danglingReferenceNumbers: [],
    latestRevision: {
      revisionId: id, versionNo: 1, state: "DRAFT", title, body, sectionId: null, sourceFormat: "MARKDOWN",
      formatPolicyId: "1", releaseMethod: "NONE", sourceHash: "", bodyCharCount: Array.from(body).length, references,
    },
    quota: {
      bodyCharLimit: 100000, bodyHardLimit: 500000, maxReferences: 300, maxReferenceChars: 200000, latestRequest: null,
    },
    capabilities: { canEdit: true, canSubmit: false, canWithdraw: false, canRevise: false, canDelete: true, canRequestQuota: true },
  };
}
