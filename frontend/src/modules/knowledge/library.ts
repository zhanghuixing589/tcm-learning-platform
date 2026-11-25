import type {Ingredient,KnowledgeCatalogEntry,KnowledgeDetail} from './api';
export const roleLabels:Record<string,string>={JUN:'君',CHEN:'臣',ZUO:'佐',SHI:'使',UNSPECIFIED:'未指定'};
export const relationLabels:Record<string,string>={HAS_EFFECT:'关联功效',INDICATED_FOR:'主治关联',ENTERS_MERIDIAN:'经络关联',COMPATIBLE_WITH:'配伍关联',RELATED_TO:'相关知识'};
export function doseLabel(row:Ingredient){return row.doseText || [row.doseValue,row.doseUnit].filter(x=>x!==null && x!==undefined && x!=='').join(' ') || '暂无用量记载';}
export function ingredientPresence(formulas:KnowledgeDetail[]){const result=new Map<string,Set<string>>();for(const formula of formulas)for(const row of formula.ingredients||[]){if(!row.herbResourceId)continue;const ids=result.get(row.herbResourceId)||new Set<string>();ids.add(formula.id);result.set(row.herbResourceId,ids);}return result;}
export function catalogDescription(item:KnowledgeCatalogEntry){return item.type==='FORMULA' ? item.formula?.functionSummary || item.summary : item.summary;}
