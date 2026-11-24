# AGENTS.md

## Projeto

**Atlas Inventory** — gestão de estoque multiempresa e multidepósito, com foco em consistência de dados.

- `backend/` — Java 25 + Spring Boot + Spring Modulith (a criar).
- `frontend/` — Next.js + TypeScript + shadcn/ui (a criar).
- `docs/` — decisões, ADRs e convenções.

## Comandos

Ainda não há código. Documente aqui os comandos de ambiente, testes e lint quando o backend for criado.

## Regras de arquitetura

- Monólito modular: módulos só se acessam pela API pública uns dos outros ou por eventos; fronteiras verificadas com Spring Modulith/ArchUnit em teste.

## Convenções

Siga `docs/CONVENCOES.md` (idioma, branches, commits, PRs, changelog). Resumo:

- Código, commits e branches em inglês; documentação em pt-BR.
- Commit: Conventional Commits — `<type>(<scope>): <description> (#<issue>)`.
- Branch: `<issue>-<tipo>-<descricao>`; PR para `develop` com o template.
- Specs em `.specs/features/<issue>-<slug>/`, seguindo o tlc-spec-driven ≥ 4.1 (skill instalada globalmente, não versionada): `spec.md`, `context.md`, `design.md` e `captures/` são versionados e entram no PR da feature; o resto da `.specs/` é local.
- Nunca commitar segredos nem usar `--no-verify`: os hooks em `.githooks/` barram segredos e commits fora do formato; corrija a causa.
