import { UNITS } from "./format";

// Mirrors the backend rules (AuthService.java, Organizations.java); lengths count code points.

export type RegisterField =
  | "name"
  | "email"
  | "password"
  | "organizationName"
  | "organizationSlug";
export type LoginField = "email" | "password";

export type RegisterInput = Record<RegisterField, string>;
export type LoginInput = Record<LoginField, string>;

// Java's \s is ASCII only: [ \t\n\x0B\f\r].
const EMAIL = /^[^@ \t\n\v\f\r]+@[^@ \t\n\v\f\r]+\.[^@ \t\n\v\f\r]+$/;

// Java Character.isWhitespace: Unicode separators except the no-break ones (U+00A0, U+2007, U+202F).
const JAVA_SPACE = "\\t\\n\\u000B\\f\\r\\u001C-\\u001F \\u1680\\u2000-\\u2006\\u2008-\\u200A\\u2028\\u2029\\u205F\\u3000";
const EDGE_SPACE = new RegExp(`^[${JAVA_SPACE}]+|[${JAVA_SPACE}]+$`, "g");

/** Java String.strip(). */
export const strip = (value: string) => value.replace(EDGE_SPACE, "");

/** The e-mail as the backend stores it: strip().toLowerCase(Locale.ROOT). */
export const normalizeEmail = (value: string) => strip(value).toLowerCase();
const SLUG = /^[a-z0-9]+(-[a-z0-9]+)*$/;

const NAME_MAX = 120;
const EMAIL_MAX = 254;
const PASSWORD_MIN = 12;
const PASSWORD_MAX = 128;
const SLUG_MIN = 3;
const SLUG_MAX = 63;

export const MESSAGES = {
  name: "Informe seu nome, com até 120 caracteres.",
  email: "Informe um e-mail válido.",
  pwd: "A senha deve ter de 12 a 128 caracteres.",
  organizationName: "Informe o nome da organização, com até 120 caracteres.",
  organizationSlug:
    "O endereço deve ter de 3 a 63 caracteres: letras minúsculas, números e hífens entre eles.",
  loginEmail: "Informe seu e-mail.",
  loginPwd: "Informe sua senha.",
} as const;

const length = (value: string) => [...value].length;

function validName(value: string) {
  const trimmed = strip(value);
  return trimmed !== "" && length(trimmed) <= NAME_MAX;
}

export function slugify(name: string): string {
  const cut = name
    .normalize("NFD")
    .replace(/[\u0300-\u036f]/g, "")
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, "-")
    .replace(/^-+|-+$/g, "")
    .slice(0, SLUG_MAX);
  return cut.replace(/-+$/, "");
}

export function validateRegister(
  input: RegisterInput,
): Partial<Record<RegisterField, string>> {
  const errors: Partial<Record<RegisterField, string>> = {};
  if (!validName(input.name)) errors.name = MESSAGES.name;
  const email = normalizeEmail(input.email);
  // UTF-16 units, like Java String.length().
  if (email.length > EMAIL_MAX || !EMAIL.test(email)) errors.email = MESSAGES.email;
  const passwordLength = length(input.password);
  if (passwordLength < PASSWORD_MIN || passwordLength > PASSWORD_MAX) {
    errors.password = MESSAGES.pwd;
  }
  if (!validName(input.organizationName)) {
    errors.organizationName = MESSAGES.organizationName;
  }
  const slug = input.organizationSlug;
  if (slug.length < SLUG_MIN || slug.length > SLUG_MAX || !SLUG.test(slug)) {
    errors.organizationSlug = MESSAGES.organizationSlug;
  }
  return errors;
}

export function validateLogin(
  input: LoginInput,
): Partial<Record<LoginField, string>> {
  const errors: Partial<Record<LoginField, string>> = {};
  if (strip(input.email) === "") errors.email = MESSAGES.loginEmail;
  if (input.password === "") errors.password = MESSAGES.loginPwd;
  return errors;
}

const WAREHOUSE_CODE = /^[A-Za-z0-9-]{1,20}$/;

export const COMPANY_MESSAGES = {
  name: "Informe um nome de até 120 caracteres.",
  code: "Use até 20 letras, números ou hífen.",
} as const;

export function validateCompany(input: { name: string }): Partial<Record<"name", string>> {
  return validName(input.name) ? {} : { name: COMPANY_MESSAGES.name };
}

export function validateWarehouse(input: {
  code: string;
  name: string;
}): Partial<Record<"code" | "name", string>> {
  const errors: Partial<Record<"code" | "name", string>> = {};
  if (!WAREHOUSE_CODE.test(strip(input.code))) errors.code = COMPANY_MESSAGES.code;
  if (!validName(input.name)) errors.name = COMPANY_MESSAGES.name;
  return errors;
}

const SKU = /^[A-Za-z0-9._-]{1,40}$/;

export const CATALOG_MESSAGES = {
  categoryName: "Informe um nome de até 80 caracteres.",
  sku: "Use até 40 letras, números, ponto, hífen ou sublinhado.",
  productName: "Informe um nome de até 200 caracteres.",
  unit: "Escolha uma unidade.",
} as const;

export function validateCategory(input: { name: string }): Partial<Record<"name", string>> {
  const name = strip(input.name);
  return name !== "" && length(name) <= 80 ? {} : { name: CATALOG_MESSAGES.categoryName };
}

export function validateProductEdit(input: { name: string }): Partial<Record<"name", string>> {
  const name = strip(input.name);
  return name !== "" && length(name) <= 200 ? {} : { name: CATALOG_MESSAGES.productName };
}

export function validateProduct(input: {
  sku: string;
  name: string;
  unit: string;
}): Partial<Record<"sku" | "name" | "unit", string>> {
  const errors: Partial<Record<"sku" | "name" | "unit", string>> = validateProductEdit(input);
  if (!SKU.test(strip(input.sku))) errors.sku = CATALOG_MESSAGES.sku;
  if (!(UNITS as string[]).includes(input.unit)) errors.unit = CATALOG_MESSAGES.unit;
  return errors;
}
