import { isAxiosError } from "axios";
import { errorMessage } from "../../shared/api/client";
import type { Reference, RevisionLock, RevisionSource } from "./types";

export function characterCount(source: string): number {
  let count = 0;
  for (const _ of source) count++;
  return count;
}

export function bodyCharacterCount(source: string): number {
  return characterCount(source.replace(/\r\n?/g, "\n"));
}

export function idempotencyKeys() {
  const commands = new Map<string, { payload: string; key: string }>();
  return {
    for(operation: string, input: unknown) {
      const payload = JSON.stringify(input);
      let current = commands.get(operation);
      if (!current || current.payload !== payload) {
        current = { payload, key: crypto.randomUUID() };
        commands.set(operation, current);
      }
      return { headers: { "Idempotency-Key": current.key } };
    },
    forget(operation: string) {
      commands.delete(operation);
    },
    clear() {
      commands.clear();
    },
  };
}
export type CommandHeaders = ReturnType<ReturnType<typeof idempotencyKeys>["for"]>;

export function revisionLock(item: { latestRevision: RevisionSource; lockVersion: number }): RevisionLock {
  return { baseRevisionId: item.latestRevision.revisionId, lockVersion: item.lockVersion };
}

export function newReference(references: readonly Reference[], lastAssigned = 0): Reference {
  const number = references.reduce((max, reference) => Math.max(max, reference.displayNumber), lastAssigned);
  return {
    referenceKey: crypto.randomUUID(), displayNumber: number + 1, title: "", authors: null,
    publicationYear: null, sourceName: null, doi: null, url: null, locationLabel: null,
  };
}

export function communityError(error: unknown): string {
  const message = errorMessage(error);
  if (!isAxiosError(error)) return message;
  const status = error.response?.status;
  if (status === 429) {
    const waiting = Number(error.response?.headers?.["retry-after"]);
    return message + (Number.isFinite(waiting) && waiting > 0 ? "。请在 " + waiting + " 秒后重试。" : "。请稍后重试。");
  }
  if (status === 409) return message + "。请核对最新状态后重试。";
  if (status === 403) return message + "。当前操作权限已发生变化，请核对账号或分区状态。";
  return message;
}

export const quotaLabels = {
  PENDING: "待审批", APPROVED: "已批准", REJECTED: "已拒绝", WITHDRAWN: "已撤回",
};
