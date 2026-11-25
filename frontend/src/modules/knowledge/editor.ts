import { typeLabels, type KnowledgeInput, type KnowledgeType } from "./api";

export function blankKnowledge(
  type: KnowledgeType = "CONCEPT",
): KnowledgeInput {
  return {
    type,
    canonicalName: "",
    title: "",
    body: "",
    aliases: [],
    tagIds: [],
    evidence: [],
    ingredients: [],
    relations: [],
    herb: { nature: "", flavor: "", originNote: "", precautions: "" },
    formula: {
      originText: "",
      functionSummary: "",
      indicationSummary: "",
      precautions: "",
    },
  };
}
export function knowledgePayload(value: KnowledgeInput): KnowledgeInput {
  return {
    type: value.type,
    canonicalName: value.canonicalName.trim(),
    title: value.title.trim(),
    body: value.body.trim(),
    categoryId: value.categoryId || undefined,
    tagIds: value.tagIds,
    changeNote: value.changeNote,
    aliases: value.aliases
      .map((x) => x.trim())
      .filter(
        (x, i, all) =>
          x &&
          all.findIndex((other) => other.toLowerCase() === x.toLowerCase()) ===
            i,
      ),
    herb: value.type === "HERB" ? value.herb : undefined,
    formula: value.type === "FORMULA" ? value.formula : undefined,
    ingredients:
      value.type === "FORMULA"
        ? value.ingredients.map(
            ({
              herbResourceId,
              herbRevisionId,
              role,
              doseValue,
              doseUnit,
              doseText,
              evidenceIndex,
            }) => ({
              herbResourceId,
              herbRevisionId,
              role,
              doseValue:
                typeof doseValue === "number" && Number.isFinite(doseValue)
                  ? doseValue
                  : undefined,
              doseUnit: doseUnit || undefined,
              doseText: doseText || undefined,
              evidenceIndex,
            }),
          )
        : [],
    evidence: value.evidence.map(
      ({ documentId, segmentId, quoteText, locationLabel }) => ({
        documentId,
        segmentId: segmentId || undefined,
        quoteText: quoteText.trim(),
        locationLabel: locationLabel || undefined,
      }),
    ),
    relations: value.relations.map(
      ({
        targetResourceId,
        targetRevisionId,
        relationType,
        explanation,
        evidenceIndex,
      }) => ({
        targetResourceId,
        targetRevisionId,
        relationType,
        explanation,
        evidenceIndex,
      }),
    ),
  };
}
export function validateKnowledge(value: KnowledgeInput): string[] {
  const errors: string[] = [];
  if ((value.tagIds?.length || 0) > 20) errors.push("最多选择20个通用标签");
  if (value.tagIds && new Set(value.tagIds).size !== value.tagIds.length) errors.push("通用标签不能重复");
  if (!value.canonicalName.trim()) errors.push("请填写规范名称");
  if (!value.title.trim()) errors.push("请填写标题");
  if (!value.body.trim()) errors.push("请填写正文");
  if (value.type === "FORMULA" && !value.formula?.originText?.trim())
    errors.push("请填写方剂出处");
  if (!value.evidence.length) errors.push("请添加至少一条来源依据");
  value.evidence.forEach((item, index) => {
    if (!item.documentId || !item.quoteText.trim())
      errors.push(`依据第 ${index + 1} 行需要来源和引文`);
    if (!item.segmentId && !item.locationLabel?.trim())
      errors.push(`依据第 ${index + 1} 行未关联片段，请填写位置说明`);
  });
  value.ingredients.forEach((item, index) => {
    if (
      !item.herbResourceId ||
      !item.herbRevisionId ||
      !item.role ||
      !value.evidence[item.evidenceIndex]
    )
      errors.push(`组成第 ${index + 1} 行需要已公开药材、角色和本稿依据`);
  });
  value.relations.forEach((item, index) => {
    if (
      !item.targetResourceId ||
      !item.targetRevisionId ||
      !item.relationType ||
      !value.evidence[item.evidenceIndex]
    )
      errors.push(`关系第 ${index + 1} 行需要已公开目标、关系类型和本稿依据`);
  });
  return errors;
}
export function removeEvidence(value: KnowledgeInput, index: number) {
  value.evidence.splice(index, 1);
  for (const row of [...value.ingredients, ...value.relations]) {
    if (row.evidenceIndex === index) row.evidenceIndex = -1;
    else if (row.evidenceIndex > index) row.evidenceIndex--;
  }
}
export function parseImportCsv(text: string): KnowledgeInput[] {
  const rows: string[][] = [];
  let row: string[] = [],
    cell = "",
    quoted = false;
  const source = text.replace(/^\uFEFF/, "");
  for (let i = 0; i < source.length; i++) {
    const c = source[i]!;
    if (c === '"') {
      if (quoted && source[i + 1] === '"') {
        cell += '"';
        i++;
      } else quoted = !quoted;
    } else if (c === "," && !quoted) {
      row.push(cell);
      cell = "";
    } else if ((c === "\n" || c === "\r") && !quoted) {
      if (c === "\r" && source[i + 1] === "\n") i++;
      row.push(cell);
      if (row.some((v) => v.trim())) rows.push(row);
      row = [];
      cell = "";
    } else cell += c;
  }
  if (quoted) throw new Error("CSV 引号未闭合，请检查正文单元格");
  row.push(cell);
  if (row.some((v) => v.trim())) rows.push(row);
  if (["类型", "type"].includes(rows[0]?.[0]?.trim() || "")) rows.shift();
  if (!rows.length || rows.length > 50) throw new Error("每次导入 1–50 项草稿");
  return rows.map((r, i) => {
    const type = r[0]?.trim() as KnowledgeType;
    if (!Object.hasOwn(typeLabels, type))
      throw new Error(
        `第 ${i + 1} 行类型无效，请使用 CONCEPT、HERB、FORMULA、EFFECT、SYMPTOM 或 MERIDIAN`,
      );
    if (!r[1]?.trim() || !r[2]?.trim() || !r[3]?.trim())
      throw new Error(`第 ${i + 1} 行需要规范名称、标题和正文`);
    return {
      ...blankKnowledge(type),
      canonicalName: r[1].trim(),
      title: r[2].trim(),
      body: r[3].trim(),
      aliases: (r[4] || "")
        .split("|")
        .map((x) => x.trim())
        .filter(Boolean),
    };
  });
}
