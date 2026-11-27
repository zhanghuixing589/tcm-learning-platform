export interface Envelope<T> {
  code: string;
  message: string;
  data: T;
  traceId: string;
}
export interface Page<T> {
  page: number;
  pageSize: number;
  total: number;
  items: T[];
}
export interface User {
  id: string;
  username: string;
  nickname: string;
  bio: string | null;
  status: "ACTIVE" | "DISABLED";
  roles: string[];
  permissions: string[];
  createdAt: string;
}
export interface AuthPayload {
  accessToken: string;
  expiresIn: number;
  user: User;
}
export interface RoleApplication {
  id: string;
  userId: string;
  username: string;
  roleCode: string;
  statement: string;
  status: string;
  reviewNote: string | null;
  reviewerId: string | null;
  reviewedAt: string | null;
  createdAt: string;
}
