import { describe, expect, it } from "vitest";
import {
  historyGroup,
  parseLibraryQuery,
  publicationLabel,
  latestRequest,
} from "./personalCenter";

describe("personal content navigation", () => {
  it("normalizes untrusted URL filters and bounds pagination", () => {
    expect(
      parseLibraryQuery({
        section: "private",
        page: "-1",
        type: "bad",
        q: ["方剂", "ignored"],
      }),
    ).toEqual({
      section: "bookmarks",
      page: 1,
      type: "",
      status: "",
      q: "方剂",
    });
    expect(
      parseLibraryQuery({
        section: "publications",
        page: "2",
        status: "IN_REVIEW",
        type: "ARTICLE",
      }),
    ).toMatchObject({
      section: "publications",
      page: 2,
      status: "IN_REVIEW",
      type: "ARTICLE",
    });
    expect(parseLibraryQuery({ page: "1.2" }).page).toBe(1);
  });
  it("groups aggregated history by Shanghai calendar day across UTC midnight", () => {
    const now = new Date("2026-11-18T00:10:00+08:00");
    expect(historyGroup("2026-11-17T16:01:00Z", now)).toBe("今天");
    expect(historyGroup("2026-11-17T15:59:00Z", now)).toBe("昨天");
    expect(historyGroup("2026-11-15T10:00:00Z", now)).toBe("更早");
  });
  it("keeps published identity when the newest revision is in review", () => {
    expect(publicationLabel("PUBLISHED", "IN_REVIEW")).toBe(
      "已发布 · 修订审核中",
    );
    expect(publicationLabel("DRAFT", "REJECTED")).toBe("需修改");
    expect(publicationLabel("SUSPENDED", "APPROVED")).toBe("已下架");
  });
  it("rejects stale results when navigation or cleanup invalidates the request", () => {
    const requests = latestRequest();
    const first = requests.begin();
    expect(requests.isCurrent(first)).toBe(true);
    const second = requests.begin();
    expect(requests.isCurrent(first)).toBe(false);
    expect(requests.isCurrent(second)).toBe(true);
    requests.invalidate();
    expect(requests.isCurrent(second)).toBe(false);
  });
});
