export type Role = "OWNER" | "ADMIN" | "MEMBER";

const LABELS: Record<Role, string> = {
  OWNER: "Proprietário",
  ADMIN: "Administrador",
  MEMBER: "Membro",
};

export const roleLabel = (role: Role): string => LABELS[role];
