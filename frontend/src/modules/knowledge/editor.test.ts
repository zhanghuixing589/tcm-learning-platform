import { describe, expect, it } from "vitest";
import {
  blankKnowledge,
  knowledgePayload,
  parseImportCsv,
  removeEvidence,
  validateKnowledge,
} from "./editor";

describe("knowledge authoring", () => {
  it("keeps aliases, structured facts and evidence indexes in a draft payload", () => {
    const value = blankKnowledge("FORMULA");
    value.title = "桂枝汤";
    value.canonicalName = "桂枝汤";
    value.body = "仅作学习资料";
    value.evidence = [
      {
        documentId: "9007199254740993",
        quoteText: "原文",
        locationLabel: "第1页",
      },
    ];
    value.formula!.originText = "验收书目";
    value.ingredients = [
      {
        herbResourceId: "18",
        herbRevisionId: "19",
        role: "君",
        evidenceIndex: 0,
      },
    ];
    value.aliases = [" 桂枝汤 ", "桂枝汤", ""];
    expect(validateKnowledge(value)).toEqual([]);
    expect(knowledgePayload(value).aliases).toEqual(["桂枝汤"]);
    expect(knowledgePayload(value).evidence[0]!.documentId).toBe(
      "9007199254740993",
    );
    value.ingredients[0]!.evidenceIndex = 1;
    expect(validateKnowledge(value).join()).toContain("组成第 1 行");
  });
  it("rejects empty evidence, missing names and targets before submission", () => {
    const value = blankKnowledge("HERB");
    expect(validateKnowledge(value).join()).toContain("规范名称");
    value.canonicalName = value.title = "桂枝";
    value.body = "药材说明";
    value.evidence = [{ documentId: "3", quoteText: "" }];
    expect(validateKnowledge(value).join()).toContain("引文");
  });
  it("parses quoted multiline CSV and rejects unsupported types and oversized imports", () => {
    const rows = parseImportCsv(
      '类型,规范名称,标题,正文,别名\nCONCEPT,阴阳,阴阳,"第一行\n第二行，含逗号",两仪|二气',
    );
    expect(rows[0]!.body).toBe("第一行\n第二行，含逗号");
    expect(rows[0]!.aliases).toEqual(["两仪", "二气"]);
    expect(() =>
      parseImportCsv("类型,规范名称,标题,正文\nWRONG,a,b,c"),
    ).toThrow("类型");
    expect(() =>
      parseImportCsv(
        "类型,规范名称,标题,正文\n" + "CONCEPT,a,b,c\n".repeat(51),
      ),
    ).toThrow("50");
  });
  it("invalidates removed evidence references and preserves indexes of later rows", () => {
    const value = blankKnowledge("FORMULA");
    value.evidence = [
      { documentId: "1", quoteText: "a" },
      { documentId: "2", quoteText: "b" },
      { documentId: "3", quoteText: "c" },
    ];
    value.ingredients = [
      {
        herbResourceId: "4",
        herbRevisionId: "5",
        role: "JUN",
        evidenceIndex: 1,
      },
    ];
    value.relations = [
      {
        targetResourceId: "6",
        targetRevisionId: "7",
        relationType: "RELATED_TO",
        evidenceIndex: 2,
      },
    ];
    removeEvidence(value, 1);
    expect(value.ingredients[0]!.evidenceIndex).toBe(-1);
    expect(value.relations[0]!.evidenceIndex).toBe(1);
  });
  it("preserves exact whitespace when editing an immutable reading citation", () => {
    const value = blankKnowledge();
    value.evidence = [{ documentId: "1", readingEditionId: "2", quoteText: " 原文\n" }];
    expect(knowledgePayload(value).evidence[0]!.quoteText).toBe(" 原文\n");
    value.evidence[0]!.readingEditionId=undefined;value.evidence[0]!.quoteHash='trusted-hash';
    expect(knowledgePayload(value).evidence[0]!.quoteText).toBe(" 原文\n");
  });
  it("requires human location for an unlinked quote and a formula origin", () => {
    const value = blankKnowledge("FORMULA");
    value.canonicalName = value.title = "桂枝汤";
    value.body = "正文";
    value.evidence = [{ documentId: "1", quoteText: "引文" }];
    expect(validateKnowledge(value).join()).toContain("位置");
    expect(validateKnowledge(value).join()).toContain("方剂出处");
    value.evidence[0]!.segmentId = "2";
    value.formula!.originText = "伤寒论";
    expect(validateKnowledge(value)).toEqual([]);
  });
});
