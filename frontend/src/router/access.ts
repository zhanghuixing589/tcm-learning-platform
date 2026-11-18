export function safeRedirect(value: unknown): string {
  if (
    typeof value !== "string" ||
    !value.startsWith("/") ||
    value.startsWith("//") ||
    value.includes("\\") ||
    /^\/(login|register)([/?#]|$)/.test(value)
  )
    return "/account";
  return value;
}
export function canEnter(
  meta: { requiresAuth?: boolean; permission?: string },
  user: { permissions: string[] } | null,
): boolean {
  if ((meta.requiresAuth || meta.permission) && !user) return false;
  return !meta.permission || !!user?.permissions.includes(meta.permission);
}
