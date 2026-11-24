export type SourceFormat = "MARKDOWN" | "PLAIN_TEXT";
export type ReleaseMethod = "NONE" | "DIRECT" | "MANUAL_REVIEW" | "LEGACY";
export type PostType = "POST" | "ARTICLE";
export type PostSort = "NEWEST" | "LATEST_REPLY";
export type PostFilter = "ALL" | PostType;

export interface SectionRef {
  id: string;
  parentId: string | null;
  code: string;
  name: string;
}

export interface Section extends SectionRef {
  description: string | null;
  rulesText: string | null;
  ownerId: string | null;
  lifecycle: string;
  managementStatus: string;
  active: boolean;
  writable: boolean;
  sortOrder: number;
  lockVersion: number;
  joined: boolean;
  memberCount: number;
  capabilities: {
    canJoin: boolean;
    canLeave: boolean;
    canEditProfile: boolean;
    canManage: boolean;
    canPin: boolean;
    canPost: boolean;
    canSubmitArticle: boolean;
  };
}
export interface Membership {
  sectionId: string;
  joined: boolean;
  memberCount: number;
}
export interface SectionProfile {
  lockVersion: number;
  description: string;
  rulesText: string;
}
export interface SectionCreate extends Omit<SectionProfile, "lockVersion"> {
  parentId: string | null;
  name: string;
  code: string;
  ownerId: string | null;
  sortOrder: number;
}
export interface SectionUpdate extends SectionProfile {
  active: boolean;
  sortOrder: number;
}
export interface Pin {
  resourceId: string;
  sectionId: string;
  pinned: boolean;
  lockVersion: number;
}

export interface Reference {
  referenceKey: string;
  displayNumber: number;
  title: string;
  authors: string | null;
  publicationYear: number | null;
  sourceName: string | null;
  doi: string | null;
  url: string | null;
  locationLabel: string | null;
}
export interface Person {
  id: string;
  nickname: string;
  avatarUrl: string | null;
}
export interface PostCapabilities {
  canEdit: boolean;
  canComment: boolean;
  canPin: boolean;
}
export interface Post {
  id: string;
  resourceType: PostType;
  section: SectionRef;
  revisionId: string;
  title: string;
  body: string | null;
  summary: string | null;
  sourceFormat: SourceFormat;
  formatPolicyId: string | null;
  releaseMethod: ReleaseMethod;
  references: Reference[];
  owner: Person;
  publishedAt: string;
  publicEditedAt: string | null;
  lastPublicCommentAt: string | null;
  commentCount: number;
  likeCount: number;
  bookmarkCount: number;
  liked: boolean;
  bookmarked: boolean;
  commentsEnabled: boolean;
  pinned: boolean;
  pinLockVersion: number;
  capabilities: PostCapabilities;
}
export interface PostQuery {
  sectionId?: string;
  includeChildren?: boolean;
  type?: PostFilter;
  q?: string;
  sort?: PostSort;
  page?: number;
  pageSize?: number;
}
export interface PostCreate {
  title: string;
  body: string;
  sourceFormat: SourceFormat;
  sectionId: string;
}
export interface RevisionLock {
  baseRevisionId: string;
  lockVersion: number;
}
export interface PostEdit extends RevisionLock {
  title: string;
  body: string;
  changeNote: string | null;
}
export interface RevisionHeader {
  revisionId: string;
  versionNo: number;
  baseRevisionId: string | null;
  state: string;
  title: string;
  changeNote: string | null;
  releaseMethod: ReleaseMethod;
  createdAt: string;
}
export interface RevisionSource {
  revisionId: string;
  versionNo: number;
  state: string;
  title: string;
  body: string;
  sectionId: string | null;
  sourceFormat: SourceFormat;
  formatPolicyId: string | null;
  releaseMethod: ReleaseMethod;
  sourceHash: string;
  bodyCharCount: number;
  references: Reference[];
}
export interface OwnPost {
  id: string;
  resourceType: "POST";
  visibilityStatus: string;
  publishedRevisionId: string | null;
  published: Post | null;
  latestRevision: RevisionSource;
  lockVersion: number;
  capabilities: PostCapabilities;
}
export interface SourceChange {
  commonPrefixChars: number;
  commonSuffixChars: number;
  fromChangedChars: number;
  toChangedChars: number;
}
export interface RevisionDiff {
  resourceId: string;
  before: RevisionSource;
  after: RevisionSource;
  titleChange: SourceChange;
  bodyChange: SourceChange;
}

export interface Comment {
  id: string;
  resourceId: string;
  rootCommentId: string | null;
  replyToCommentId: string | null;
  revisionId: string | null;
  author: Person | null;
  body: string | null;
  displayState: "VISIBLE" | "HIDDEN" | "DELETED";
  sourceFormat: "PLAIN_TEXT";
  formatPolicyId: string | null;
  createdAt: string | null;
  editedAt: string | null;
  replyCount: number;
  lockVersion: number;
  capabilities: { canReply: boolean; canEdit: boolean; canDelete: boolean };
}
export interface CommentCreate {
  body: string;
  rootCommentId?: string;
  replyToCommentId?: string;
}
export interface CommentEdit extends RevisionLock {
  body: string;
}

export type QuotaStatus = "PENDING" | "APPROVED" | "REJECTED" | "WITHDRAWN";
export interface QuotaRequest {
  id: string;
  resourceId: string;
  requestedBy: string;
  requestedLimit: number;
  reason: string;
  status: QuotaStatus;
  reviewerId: string | null;
  approvedLimit: number | null;
  reviewNote: string | null;
  lockVersion: number;
  createdAt: string;
  reviewedAt: string | null;
}
export interface Quota {
  bodyCharLimit: number;
  bodyHardLimit: number;
  maxReferences: number;
  maxReferenceChars: number;
  latestRequest: QuotaRequest | null;
}
export interface QuotaInput {
  requestedLimit: number;
  reason: string;
}
export interface QuotaDecision {
  lockVersion: number;
  decision: "APPROVED" | "REJECTED";
  approvedLimit: number | null;
  note: string;
}
export interface Article {
  id: string;
  resourceType: "ARTICLE";
  title: string;
  body: string;
  revisionId: string;
  revisionState: string;
  visibilityStatus: string;
  reviewNote: string | null;
  stateReason: string | null;
  sectionId: string | null;
  sourceFormat: SourceFormat;
  references: Reference[];
  publishedRevisionId: string | null;
  published: Post | RevisionSource | null;
  latestRevision: RevisionSource;
  resultRevision?: RevisionSource;
  staleResult?: boolean;
  lockVersion: number;
  quota: Quota;
  danglingReferenceNumbers: number[];
  capabilities: {
    canEdit: boolean;
    canSubmit: boolean;
    canWithdraw: boolean;
    canRevise: boolean;
    canDelete: boolean;
    canRequestQuota: boolean;
  };
}
export interface ArticleInput {
  title: string;
  body: string;
  sectionId?: string;
  sourceFormat: SourceFormat;
  references?: Reference[];
}
export type ArticleAction = "submit" | "withdraw" | "revise";
export type DraftIdentity = Pick<ArticleInput, "title" | "sourceFormat">;

export const syntaxOptions = [
  "paragraph", "heading", "emphasis", "strong", "blockquote", "code", "codeBlock", "list", "link", "table", "hr",
] as const;
export type Syntax = typeof syntaxOptions[number];
export type Protocol = "http" | "https";
export const rateOperations = ["POST_CREATE", "COMMENT_CREATE", "PUBLIC_EDIT", "DRAFT_SAVE", "QUOTA_REQUEST"] as const;
export type RateOperation = typeof rateOperations[number];
export interface RateWindow {
  seconds: number;
  limit: number;
}
export interface TextPolicyConfig {
  allowedSyntax: readonly Syntax[];
  allowedProtocols: readonly Protocol[];
  maxTitleChars: number;
  maxPostChars: number;
  maxArticleChars: number;
  maxCommentChars: number;
  maxReferences: number;
  maxReferenceChars: number;
  maxArticleHardChars: number;
  maxRequestBytes: number;
  rates: Record<RateOperation, RateWindow[]>;
}
export interface PolicyHead {
  policyId: string;
  lockVersion: number;
}
export interface TextPolicy extends PolicyHead {
  config: TextPolicyConfig;
  characterUnit: "UNICODE_CODE_POINT";
}
export interface PolicyVersion {
  policyId: string;
  versionNo: number;
  config: TextPolicyConfig;
  reason: string;
  createdBy: string | null;
  createdAt: string;
}
