import { expect, it, vi, afterEach } from "vitest";
import { mount, flushPromises } from "@vue/test-utils";
import { createRouter, createMemoryHistory } from "vue-router";
import KnowledgeProposalView from "./KnowledgeProposalView.vue";
import { readerApi } from "./readerApi";
import { stageApi } from "./api";
import { blankKnowledge } from "./editor";
afterEach(() => vi.restoreAllMocks());
it("allows submitting a newly saved proposal after navigating to its persisted route", async () => {
  vi.spyOn(stageApi, "categories").mockResolvedValue([]);
  vi.spyOn(stageApi, "tags").mockResolvedValue([]);
  const knowledge = { ...blankKnowledge(), canonicalName: "阴阳", title: "独立修订", body: "人工核对正文",
    evidence: [{ documentId: "s", locationLabel: "第一单元", quoteText: "原文" }] };
  vi.spyOn(stageApi, "detail").mockResolvedValue({ ...knowledge, id: "r", revisionId: "base" } as never);
  const proposal = { id: "p", resourceId: "r", baseRevisionId: "base", status: "DRAFT" as const,
    lockVersion: 0, submittedBy: "u", knowledge };
  vi.spyOn(readerApi, "createProposal").mockResolvedValue(proposal);
  vi.spyOn(readerApi, "proposal").mockResolvedValue(proposal);
  const submit = vi.spyOn(readerApi, "proposalAction").mockResolvedValue({ ...proposal, status: "IN_REVIEW" });
  const router = createRouter({ history: createMemoryHistory(), routes: [
    { path: "/contribute/proposals/:id", component: KnowledgeProposalView },
  ] });
  await router.push("/contribute/proposals/new?resourceId=r");
  const wrapper = mount(KnowledgeProposalView, { global: { plugins: [router], stubs: { EvidenceEditor: true, FactsEditor: true } } });
  await flushPromises();
  await wrapper.get("form").trigger("submit");
  await flushPromises();
  expect(router.currentRoute.value.path).toBe("/contribute/proposals/p");
  const button = wrapper.findAll("button").find(b => b.text() === "提交修订建议审核")!;
  expect(button.attributes("disabled")).toBeUndefined();
  await button.trigger("click");
  await flushPromises();
  expect(submit).toHaveBeenCalledWith("p", "submit");
  expect(wrapper.text()).toContain("修订建议已提交审核");
  wrapper.unmount();
});
it("preserves local edits and proposal identity when optimistic save conflicts", async () => {
  vi.spyOn(stageApi, "categories").mockResolvedValue([]);
  vi.spyOn(stageApi, "tags").mockResolvedValue([]);
  const knowledge = {
    ...blankKnowledge(),
    canonicalName: "阴阳",
    title: "原建议",
    body: "正文",
    evidence: [
      { documentId: "s", locationLabel: "第一单元", quoteText: "原文" },
    ],
  };
  vi.spyOn(readerApi, "proposal").mockResolvedValue({
    id: "p",
    resourceId: "r",
    baseRevisionId: "base",
    status: "DRAFT",
    lockVersion: 7,
    submittedBy: "u",
    knowledge,
  });
  const save = vi
    .spyOn(readerApi, "saveProposal")
    .mockRejectedValue(new Error("409 基准已变化"));
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: "/contribute/proposals/:id", component: KnowledgeProposalView },
    ],
  });
  await router.push("/contribute/proposals/p");
  const wrapper = mount(KnowledgeProposalView, {
    global: {
      plugins: [router],
      stubs: { EvidenceEditor: true, FactsEditor: true },
    },
  });
  await flushPromises();
  await wrapper.get('input[maxlength="255"]').setValue("保留我的修订");
  await wrapper.get("form").trigger("submit");
  await flushPromises();
  expect(save).toHaveBeenCalledWith(
    "p",
    7,
    expect.objectContaining({ title: "保留我的修订" }),
  );
  expect(
    (wrapper.get('input[maxlength="255"]').element as HTMLInputElement).value,
  ).toBe("保留我的修订");
  expect(wrapper.text()).toContain("基准正式版本 base");
  expect(wrapper.get("[role=alert]").text()).toContain("409");
  wrapper.unmount();
});
