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
  meta: {
    requiresAuth?: boolean;
    permission?: string;
    anyPermissions?: string[];
  },
  user: { permissions: string[] } | null,
): boolean {
  if ((meta.requiresAuth || meta.permission || meta.anyPermissions) && !user)
    return false;
  if (meta.permission && !user?.permissions.includes(meta.permission))
    return false;
  return (
    !meta.anyPermissions ||
    meta.anyPermissions.some((permission) =>
      user?.permissions.includes(permission),
    )
  );
}
