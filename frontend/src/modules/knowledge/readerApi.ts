import { api } from "../../shared/api/client";
import type { Envelope, Page } from "../../shared/api/types";
import {
  get,
  post,
  patch,
  type SourceDocument,
  type SourceInput,
  type KnowledgeInput,
  type KnowledgeType,
  type Evidence,
} from "./api";
export type ReadingMode =
  | "STRUCTURED"
  | "FIXED_LAYOUT"
  | "PLAIN_TEXT"
  | "ORIGINAL_PREVIEW";
export interface ReaderCapabilities {
  hasToc: boolean;
  hasImages: boolean;
  hasTables: boolean;
  hasFootnotes: boolean;
  textSelectable: boolean;
  searchable: boolean;
  preciseCitationSupported: boolean;
  fontSizeAdjustable: boolean;
}
export type ReaderWarning =
  | string
  | {
      code: string;
      message: string;
      unitNo?: number;
      assetRef?: string;
      severity: string;
    };
export interface ReaderNode {
  type: string;
  id?: string;
  text?: string;
  level?: number;
  ordered?: boolean;
  children?: ReaderNode[];
  assetRef?: string;
  href?: string;
  colspan?: number;
  rowspan?: number;
  role?: string;
  targetId?: string;
}
export interface ReaderBlock {
  id: string;
  localId: string;
  blockNo: number;
  text: string;
}
export interface Outline {
  title: string;
  unitNo: number;
  localId?: string;
  children: Outline[];
}
export interface UnitSummary {
  id: string;
  unitNo: number;
  unitType: "CHAPTER" | "PAGE" | "TEXT_SECTION";
  title: string;
  pageNumber?: number;
  capabilities: ReaderCapabilities;
  availableReadingModes: ReadingMode[];
  previewRef?: string;
  warnings?: ReaderWarning[];
}
export interface ReadingUnit extends UnitSummary {
  editionId: string;
  nodes: ReaderNode[];
  blocks: ReaderBlock[];
}
export interface ReadingProgress {
  editionId: string;
  unitId: string;
  blockId?: string;
  offset?: number;
  pageNumber?: number;
  fontSize?: number;
  readingMode?: ReadingMode;
  lockVersion?: number;
}
export interface Reader {
  document: SourceDocument;
  edition: {
    id: string;
    readingVersionNo: number;
    parseRunId?: string;
    processingRunId: string;
    parseVersion: string;
    format: string;
    status: string;
    layoutMode: string;
    availableReadingModes: ReadingMode[];
    capabilities: ReaderCapabilities;
    metadata: Record<string, unknown>;
    warnings: ReaderWarning[];
    overallOutcome: string;
    stages: Record<string, unknown>;
  };
  outline: Outline[];
  units: UnitSummary[];
  assets: { id: string; ref: string; mimeType: string; kind: string }[];
  progress?: ReadingProgress;
  capabilities: { read: boolean; extract: boolean; download: boolean };
}
export interface Excerpt extends Evidence {
  id: string;
  parseRunId?: string;
  readingEditionId: string;
  quoteHash: string;
  locator: Record<string, unknown>;
}
export interface ExcerptRange {
  blockId: string;
  start: number;
  end: number;
}
export interface ReaderHit {
  unitId: string;
  unitNo: number;
  blockId: string;
  localId: string;
  offset: number;
  endOffset: number;
  text: string;
}
export interface Metadata {
  title?: string;
  author?: string;
  publisher?: string;
  publicationYear?: number;
  rightsText?: string;
  outline?: Outline[];
  warnings: string[];
}
export interface KnowledgeMatch {
  id: string;
  title: string;
  canonicalName: string;
  type: KnowledgeType;
  ownerId: string;
  revisionId: string;
  revisionState: string;
}
export interface KnowledgeProposal {
  id: string;
  resourceId: string;
  baseRevisionId: string;
  status: string;
  lockVersion: number;
  submittedBy: string;
  knowledge: KnowledgeInput;
}
const root = (id: string) => `/source-documents/${id}`;
export const readerApi = {
  metadata: (id: string) => get<Metadata>(`/files/${id}/metadata`),
  confirm: (input: SourceInput & { documentId?: string; requestKey: string }) =>
    post<SourceDocument>("/source-documents/confirm", input),
  reader: (id: string, editionId?: string) =>
    get<Reader>(`${root(id)}/reader`, { editionId }),
  unit: (id: string, unitId: string, editionId: string, mode?: ReadingMode) =>
    get<ReadingUnit>(`${root(id)}/reader/units/${unitId}`, { editionId, mode }),
  search: (id: string, editionId: string, q: string, page = 1) =>
    get<Page<ReaderHit>>(`${root(id)}/reader/search`, {
      editionId,
      q,
      page,
      pageSize: 20,
    }),
  progress: async (
    id: string,
    value: ReadingProgress & { expectedVersion?: number },
  ) =>
    (
      await api.http.put<Envelope<ReadingProgress>>(
        `${root(id)}/reading-progress`,
        value,
      )
    ).data.data,
  excerpt: (
    id: string,
    editionId: string,
    ranges: ExcerptRange[],
    quoteText: string,
  ) => post<Excerpt>(`${root(id)}/excerpts`, { editionId, ranges, quoteText }),
  personalExcerpts: () => get<Excerpt[]>("/me/source-excerpts"),
  appendEvidence: (id: string, excerptIds: string[], expectedVersion: number) =>
    post<import("./api").KnowledgeDetail>(
      `/contributions/knowledge/${id}/evidence`,
      { excerptIds, expectedVersion },
    ),
  evidencePreview: (
    resourceId: string,
    revisionId: string,
    evidenceId: string,
  ) =>
    get<{
      document: SourceDocument;
      evidence: Evidence;
      restricted: boolean;
      canOpenReader: boolean;
      reason: string | null;
    }>(
      `/knowledge/${resourceId}/versions/${revisionId}/evidence/${evidenceId}/preview`,
    ),
  excerpts: (id: string) => get<Excerpt[]>(`${root(id)}/excerpts`),
  blob: async (id: string, assetId?: string) =>
    (
      await api.http.get<Blob>(
        assetId ? `${root(id)}/reader/assets/${assetId}` : `${root(id)}/file`,
        { responseType: "blob" },
      )
    ).data,
  build: (id: string) => post<SourceDocument>(`${root(id)}/reader-build`),
  retry: (id: string) => post<SourceDocument>(`${root(id)}/parse/retry`),
  matches: (q: string, type: KnowledgeType) =>
    get<KnowledgeMatch[]>("/knowledge/matches", { q, type }),
  createProposal: (
    id: string,
    baseRevisionId: string,
    knowledge: KnowledgeInput,
  ) =>
    post<KnowledgeProposal>(`/knowledge/${id}/proposals`, {
      baseRevisionId,
      knowledge,
    }),
  proposal: (id: string) =>
    get<KnowledgeProposal>(`/knowledge-proposals/${id}`),
  saveProposal: (
    id: string,
    expectedVersion: number,
    knowledge: KnowledgeInput,
  ) =>
    patch<KnowledgeProposal>(`/knowledge-proposals/${id}`, {
      expectedVersion,
      knowledge,
    }),
  proposalAction: (id: string, action: "submit" | "withdraw") =>
    post<KnowledgeProposal>(`/knowledge-proposals/${id}/${action}`),
};
