import type { LocationQuery } from "vue-router";

export const sections = [
  "bookmarks",
  "history",
  "publications",
  "likes",
  "received-likes",
  "following",
  "followers",
] as const;
export type LibrarySection = (typeof sections)[number];
export interface LibraryQuery {
  section: LibrarySection;
  page: number;
  type: string;
  status: string;
  q: string;
}
export interface PublicUser {
  id: string;
  nickname: string;
  bio: string | null;
  avatarUrl: string | null;
  createdAt?: string;
  roles?: string[];
  following?: boolean;
  followsYou?: boolean;
}
export interface ProfileSummary {
  profile: PublicUser;
  receivedLikes: number;
  followingCount: number;
  followerCount: number;
  following: boolean;
  followsYou: boolean;
}
export interface ContentItem {
  id: string;
  resourceType: string;
  title: string;
  summary: string | null;
  sourceName?: string | null;
  available: boolean;
  occurredAt: string;
  ownerId: string;
  ownerNickname: string;
  visibilityStatus: string;
  revisionState: string;
  revisionId: string;
  likeCount: number;
  bookmarkCount: number;
}
export interface ResourceDetail extends ContentItem {
  body: string;
  liked: boolean;
  bookmarked: boolean;
}
export const typeLabels: Record<string, string> = {
  KNOWLEDGE: "知识条目",
  TOPIC: "学习专题",
  CARD: "学习卡片",
  QUESTION: "练习题",
  POST: "社区帖子",
  ARTICLE: "科普文章",
};
export const sectionLabels: Record<LibrarySection, string> = {
  bookmarks: "我的收藏",
  history: "浏览历史",
  publications: "我的发表",
  likes: "我的点赞",
  "received-likes": "收到的赞",
  following: "我的关注",
  followers: "我的粉丝",
};
export function parseLibraryQuery(query: LocationQuery): LibraryQuery {
  const first = (key: string) => {
    const value = query[key];
    return (Array.isArray(value) ? value[0] : value) || "";
  };
  const rawPage = Number(first("page"));
  return {
    section: sections.includes(first("section") as LibrarySection)
      ? (first("section") as LibrarySection)
      : "bookmarks",
    page:
      Number.isInteger(rawPage) && rawPage > 0 && rawPage <= 100000
        ? rawPage
        : 1,
    type: Object.hasOwn(typeLabels, first("type")) ? first("type") : "",
    status: [
      "PUBLISHED",
      "IN_REVIEW",
      "DRAFT",
      "NEEDS_CHANGES",
      "SUSPENDED",
      "ARCHIVED",
    ].includes(first("status"))
      ? first("status")
      : "",
    q: first("q").trim().slice(0, 100),
  };
}
const day = (date: Date) =>
  new Intl.DateTimeFormat("en-CA", {
    timeZone: "Asia/Shanghai",
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
  }).format(date);
export function historyGroup(value: string, now = new Date()) {
  if (!value || Number.isNaN(Date.parse(value))) return "更早";
  const target = day(new Date(value));
  if (target === day(now)) return "今天";
  return target === day(new Date(now.getTime() - 86400000)) ? "昨天" : "更早";
}
export function publicationLabel(visibility: string, revision: string) {
  if (visibility === "SUSPENDED") return "已下架";
  if (visibility === "ARCHIVED") return "已归档";
  const revisions: Record<string, string> = {
    DRAFT: "草稿",
    IN_REVIEW: "审核中",
    REJECTED: "需修改",
    WITHDRAWN: "已撤回",
    APPROVED: "已通过",
  };
  if (visibility === "PUBLISHED")
    return revision === "APPROVED"
      ? "已发布"
      : `已发布 · 修订${revisions[revision] || "处理中"}`;
  return revisions[revision] || "草稿";
}
export function latestRequest() {
  let version = 0;
  return {
    begin: () => ++version,
    isCurrent: (ticket: number) => ticket === version,
    invalidate: () => {
      version++;
    },
  };
}
export function formatDate(value?: string, withTime = false) {
  if (!value || Number.isNaN(Date.parse(value))) return "";
  return new Intl.DateTimeFormat("zh-CN", {
    timeZone: "Asia/Shanghai",
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
    ...(withTime ? ({ hour: "2-digit", minute: "2-digit" } as const) : {}),
  }).format(new Date(value));
}
export function formatCount(value: number) {
  return value >= 10000
    ? `${(value / 10000).toFixed(1).replace(/\.0$/, "")}万`
    : value.toLocaleString("zh-CN");
}
