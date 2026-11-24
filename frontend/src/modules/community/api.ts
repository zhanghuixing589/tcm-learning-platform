import { api } from "../../shared/api/client";
import type { Envelope, Page } from "../../shared/api/types";
import type { CommandHeaders } from "./editor";
import type {
  Article, ArticleAction, ArticleInput, Comment, CommentCreate, CommentEdit,
  DraftIdentity, Membership, OwnPost, Pin, PolicyHead, PolicyVersion, Post, PostCreate,
  PostEdit, PostQuery, Quota, QuotaDecision, QuotaInput, QuotaRequest, QuotaStatus,
  RevisionDiff, RevisionHeader, RevisionLock, Section, SectionCreate, SectionProfile,
  SectionUpdate, TextPolicy, TextPolicyConfig,
} from "./types";

async function get<T>(path: string, params?: object): Promise<T> {
  return (await api.http.get<Envelope<T>>(path, params && { params })).data.data;
}
async function post<T>(path: string, input: object, command: CommandHeaders): Promise<T> {
  return (await api.http.post<Envelope<T>>(path, input, command)).data.data;
}
async function patch<T>(path: string, input: object): Promise<T> {
  return (await api.http.patch<Envelope<T>>(path, input)).data.data;
}
async function put<T>(path: string, input?: object, command?: CommandHeaders): Promise<T> {
  return (await api.http.put<Envelope<T>>(path, input, command)).data.data;
}
async function remove<T>(path: string, params?: object): Promise<T> {
  return (await api.http.delete<Envelope<T>>(path, params && { params })).data.data;
}

const sections = "/community/sections";
const admin = "/admin/community";
const articles = "/me/articles";
const posts = "/posts";

export const communityApi = {
  sections: (parentId?: string, joinedOnly = false) => get<Section[]>(sections, { parentId, joinedOnly }),
  section: (id: string) => get<Section>(sections + "/" + id),
  membership: (id: string, join: boolean) => {
    const path = sections + "/" + id + "/membership";
    return join ? put<Membership>(path) : remove<Membership>(path);
  },
  sectionProfile: (id: string, input: SectionProfile) => patch<Section>(sections + "/" + id + "/profile", input),
  createSection: (input: SectionCreate, command: CommandHeaders) => post<Section>(admin + "/sections", input, command),
  updateSection: (id: string, input: SectionUpdate) => patch<Section>(admin + "/sections/" + id, input),
  pin: (sectionId: string, id: string, input: { pinned: boolean; lockVersion: number }) =>
    put<Pin>(sections + "/" + sectionId + "/posts/" + id + "/pin", input),
  posts: (query: PostQuery) => get<Page<Post>>(posts, query),
  post: (id: string) => get<Post>(posts + "/" + id),
  ownPost: (id: string) => get<OwnPost>("/me/posts/" + id),
  createPost: (input: PostCreate, command: CommandHeaders) => post<Post>(posts, input, command),
  editPost: (id: string, input: PostEdit, command: CommandHeaders) => post<Post>(posts + "/" + id + "/revisions", input, command),
  revisions: (id: string, page: number) => get<Page<RevisionHeader>>("/me/posts/" + id + "/revisions", { page }),
  diff: (fromRevisionId: string, toRevisionId: string) =>
    get<RevisionDiff>("/me/community-revisions/diff", { fromRevisionId, toRevisionId }),
  comments: (id: string, page: number, rootId?: string) => get<Page<Comment>>(posts + "/" + id + "/comments", { page, rootId }),
  createComment: (id: string, input: CommentCreate, command: CommandHeaders) =>
    post<Comment>(posts + "/" + id + "/comments", input, command),
  editComment: (id: string, input: CommentEdit, command: CommandHeaders) =>
    post<Comment>("/comments/" + id + "/revisions", input, command),
  deleteComment: (id: string, lockVersion: number) => remove<Comment>("/comments/" + id, { lockVersion }),
  textPolicy: () => get<TextPolicy>("/community/text-policy"),
  policies: (page: number) => get<Page<PolicyVersion>>(admin + "/text-policies", { page }),
  createPolicy: (input: { config: TextPolicyConfig; reason: string }, command: CommandHeaders) =>
    post<PolicyVersion>(admin + "/text-policies", input, command),
  activatePolicy: (input: PolicyHead & { reason: string }, command: CommandHeaders) =>
    put<PolicyHead>(admin + "/text-policy-head", input, command),
  article: (id: string) => get<Article>(articles + "/" + id),
  createArticle: (input: ArticleInput, command: CommandHeaders) => post<Article>(articles, input, command),
  draftIdentity: (input: DraftIdentity, command: CommandHeaders) =>
    post<Article>(articles + "/draft-identities", input, command),
  saveArticle: (id: string, input: ArticleInput & RevisionLock) => patch<Article>(articles + "/" + id, input),
  articleAction: (id: string, action: ArticleAction, input: RevisionLock, command: CommandHeaders) =>
    post<Article>(articles + "/" + id + "/" + action, input, command),
  deleteArticle: (id: string, input: RevisionLock) => remove<null>(articles + "/" + id, input),
  reorderReferences: (id: string, input: RevisionLock & { referenceKeys: string[] }, command: CommandHeaders) =>
    post<Article>(articles + "/" + id + "/references/reorder", input, command),
  quota: (id: string) => get<Quota>(articles + "/" + id + "/quota"),
  quotaHistory: (id: string, page: number) => get<Page<QuotaRequest>>(articles + "/" + id + "/quota-requests", { page }),
  requestQuota: (id: string, input: QuotaInput, command: CommandHeaders) =>
    post<QuotaRequest>(articles + "/" + id + "/quota-requests", input, command),
  quotaQueue: (status: QuotaStatus | "ALL", page: number) => get<Page<QuotaRequest>>(admin + "/quota-requests", { status, page }),
  decideQuota: (id: string, input: QuotaDecision, command: CommandHeaders) =>
    post<QuotaRequest>(admin + "/quota-requests/" + id + "/decisions", input, command),
};

export interface SectionGroup {
  section: Section;
  children: Section[];
}
export async function sectionTree(): Promise<SectionGroup[]> {
  const roots = await communityApi.sections();
  return Promise.all(roots.map(async (section) => ({ section, children: await communityApi.sections(section.id) })));
}
export function flattenSections(groups: SectionGroup[]): Section[] {
  return groups.flatMap(({ section, children }) => [section, ...children]);
}
