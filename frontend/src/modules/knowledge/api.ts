import { api } from "../../shared/api/client";
import type { Envelope, Page } from "../../shared/api/types";
import type { Reference, SectionRef, SourceFormat } from "../community/types";

export const typeLabels = {
  CONCEPT: "中医概念",
  HERB: "中药材",
  FORMULA: "方剂",
  EFFECT: "功效",
  SYMPTOM: "症状",
  MERIDIAN: "经络",
} as const;
export type KnowledgeType = keyof typeof typeLabels;
export const stateLabels: Record<string, string> = {
  DRAFT: "草稿",
  IN_REVIEW: "审核中",
  PENDING: "待审核",
  CLAIMED: "已领取",
  APPROVED: "已通过",
  REJECTED: "已退回",
  WITHDRAWN: "已撤回",
  ACTIVE: "有效",
  SUSPENDED: "已停用",
  QUEUED: "等待处理",
  RUNNING: "处理中",
  RETRY_WAIT: "等待重试",
  SUCCEEDED: "已完成",
  FAILED: "处理失败",
  DEAD: "处理失败",
  CANCELLED: "已取消",
  PUBLISHED: "已发布",
  OPEN: "待处理",
  RESOLVED: "已解决",
  DISMISSED: "不采纳",
};
export const sourceTypeLabels: Record<string, string> = {
  BOOK: "图书",
  PAPER: "论文",
  WEB: "网页",
  COMMUNITY: "社区资料",
  OTHER: "其他",
};
export interface Category {
  id: string;
  name: string;
  code: string;
}
export interface ParseRun {
  id: string;
  status: string;
  errorMessage: string | null;
  parseVersion: string;
}
export interface SourceDocument {
  id: string;
  title: string;
  sourceType: string;
  author: string | null;
  publisher: string | null;
  publicationYear: number | null;
  sourceUrl: string | null;
  bibliographicNote: string | null;
  fileId: string | null;
  fileName: string | null;
  reviewState: string;
  availability: string;
  submittedBy: string;
  parentDocumentId: string | null;
  createdAt: string;
  parseRun: ParseRun | null;
}
export interface SourceInput {
  fileId?: string;
  title: string;
  sourceType: string;
  author?: string;
  publisher?: string;
  publicationYear?: number;
  sourceUrl?: string;
  bibliographicNote?: string;
}
export interface Segment {
  id: string;
  documentId: string;
  parseRunId: string;
  segmentNo: number;
  pageLabel: string | null;
  headingPath: string | null;
  body: string;
}
export interface UploadedFile {
  id: string;
  originalName: string;
  mimeType: string;
  byteSize: number;
  sha256: string;
  deduplicated: boolean;
}
export interface Evidence {
  id?: string;
  documentId: string;
  segmentId?: string;
  locationLabel?: string;
  quoteText: string;
  documentTitle?: string;
}
export interface Ingredient {
  id?: string;
  herbResourceId: string;
  herbRevisionId: string;
  herbName?: string;
  role: string;
  doseValue?: number;
  doseUnit?: string;
  doseText?: string;
  evidenceIndex: number;
}
export interface Relation {
  id?: string;
  targetResourceId: string;
  targetRevisionId: string;
  targetName?: string;
  relationType: string;
  explanation?: string;
  evidenceIndex: number;
}
export interface HerbFacts {
  nature: string;
  flavor: string;
  originNote: string;
  precautions: string;
}
export interface FormulaFacts {
  originText: string;
  functionSummary: string;
  indicationSummary: string;
  precautions: string;
}
export interface KnowledgeInput {
  type: KnowledgeType;
  canonicalName: string;
  title: string;
  body: string;
  categoryId?: string;
  changeNote?: string;
  aliases: string[];
  herb?: HerbFacts;
  formula?: FormulaFacts;
  evidence: Evidence[];
  ingredients: Ingredient[];
  relations: Relation[];
}
export interface KnowledgeSummary {
  id: string;
  revisionId: string;
  type: KnowledgeType;
  canonicalName: string;
  title: string;
  summary: string;
  categoryId: string | null;
  categoryName: string | null;
  visibilityStatus: string;
  revisionState: string;
  versionNo: number;
  createdAt: string;
  updatedAt: string;
}
export interface KnowledgeDetail extends KnowledgeSummary {
  bookmarked?: boolean;
  body: string;
  ownerId: string;
  lockVersion: number;
  aliases: string[];
  herb?: HerbFacts;
  formula?: FormulaFacts;
  evidence: Evidence[];
  ingredients: Ingredient[];
  relations: Relation[];
  reviewNote: string | null;
  changeNote?: string;
}
export interface Review {
  id: string;
  revisionId: string | null;
  documentId: string | null;
  title: string;
  kind: "SOURCE" | "KNOWLEDGE" | "ARTICLE";
  submittedBy: string;
  submitterName: string;
  reviewerId: string | null;
  status: string;
  reviewNote: string | null;
  lockVersion: number;
  createdAt: string;
}
export interface ReviewEvent {
  id?: string;
  eventType?: string;
  decision?: string;
  note?: string;
  createdAt?: string;
  actorName?: string;
  action?: string;
}
export interface ReviewDetail extends Review {
  document?: SourceDocument;
  knowledge?: KnowledgeDetail;
  article?: {
    id: string;
    revisionId: string;
    title: string;
    body: string;
    revisionState: string;
    section: SectionRef | null;
    sectionId: string | null;
    sourceFormat: SourceFormat;
    formatPolicyId: string | null;
    sourceHash: string;
    references: Reference[];
    versionNo: number;
  };
  events: ReviewEvent[];
}
export interface Job {
  id: string;
  jobType: string;
  status: string;
  documentId?: string;
  revisionId?: string;
  attemptCount: number;
  maxAttempts?: number;
  attempts?: {
    id: string;
    attemptNo: number;
    status: string;
    errorMessage?: string;
    startedAt: string;
    finishedAt?: string;
  }[];
  lastError?: string;
  errorMessage?: string;
  createdAt: string;
  updatedAt?: string;
  availableAt?: string;
  leaseUntil?: string;
  serviceMessage?: string;
}
export interface Feedback {
  id: string;
  resourceId: string;
  revisionId?: string;
  title?: string;
  content: string;
  status: string;
  resolution: string | null;
  createdAt: string;
}
type Query = Record<string, string | number | boolean | undefined>;
export async function get<T>(url: string, params?: Query): Promise<T> {
  return (await api.http.get<Envelope<T>>(url, { params })).data.data;
}
export async function post<T>(url: string, body?: unknown): Promise<T> {
  return (await api.http.post<Envelope<T>>(url, body)).data.data;
}
export async function patch<T>(url: string, body: unknown): Promise<T> {
  return (await api.http.patch<Envelope<T>>(url, body)).data.data;
}
export const stageApi = {
  categories: () => get<Category[]>("/knowledge/categories"),
  knowledge: (params: Query = {}) =>
    get<Page<KnowledgeSummary>>("/knowledge", params),
  detail: (id: string) => get<KnowledgeDetail>(`/knowledge/${id}`),
  relations: (id: string) => get<Relation[]>(`/knowledge/${id}/relations`),
  drafts: (params: Query = {}) =>
    get<Page<KnowledgeDetail>>("/contributions/knowledge", params),
  draft: (id: string) => get<KnowledgeDetail>(`/knowledge/${id}/draft`),
  saveDraft: (id: string | null, body: KnowledgeInput) =>
    id
      ? patch<KnowledgeDetail>(`/knowledge/${id}/draft`, body)
      : post<KnowledgeDetail>("/knowledge", body),
  revise: (id: string) => post<KnowledgeDetail>(`/knowledge/${id}/revise`),
  revisionAction: (id: string, action: "submit" | "withdraw") =>
    post<KnowledgeDetail>(`/revisions/${id}/${action}`),
  versions: (id: string) => get<KnowledgeDetail[]>(`/knowledge/${id}/versions`),
  version: (id: string, revision: string) =>
    get<KnowledgeDetail>(`/knowledge/${id}/versions/${revision}`),
  compare: (ids: string[]) =>
    post<{ formulas: KnowledgeDetail[] }>("/formulas/compare", { ids }),
  sources: (params: Query = {}) =>
    get<Page<SourceDocument>>("/source-documents", params),
  source: (id: string) => get<SourceDocument>(`/source-documents/${id}`),
  saveSource: (id: string | null, body: SourceInput) =>
    id
      ? patch<SourceDocument>(`/source-documents/${id}`, body)
      : post<SourceDocument>("/source-documents", body),
  sourceAction: (id: string, action: "submit" | "withdraw" | "revise") =>
    post<SourceDocument>(`/source-documents/${id}/${action}`),
  parse: (id: string) => post<ParseRun>(`/source-documents/${id}/parse`),
  segments: (id: string, params: Query = {}) =>
    get<Page<Segment>>(`/source-documents/${id}/segments`, params),
  upload: (file: File) => {
    const body = new FormData();
    body.append("file", file);
    return post<UploadedFile>("/files", body);
  },
  reviews: (params: Query = {}) => get<Page<Review>>("/reviews", params),
  review: (id: string) => get<ReviewDetail>(`/reviews/${id}`),
  claim: (id: string, lockVersion: number) =>
    post<ReviewDetail>(`/reviews/${id}/claim`, { lockVersion }),
  decide: (
    id: string,
    lockVersion: number,
    decision: "APPROVED" | "REJECTED",
    note: string,
  ) =>
    post<ReviewDetail>(`/reviews/${id}/decisions`, {
      lockVersion,
      decision,
      note,
    }),
  feedback: (params: Query = {}) =>
    get<Page<Feedback>>("/contributions/feedback", params),
  jobs: (params: Query = {}) => get<Page<Job>>("/admin/jobs", params),
};
