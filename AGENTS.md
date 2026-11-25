# AGENTS.md

## Projeto

**Atlas Inventory** — gestão de estoque multiempresa e multidepósito, com foco em consistência de dados.

- `backend/` — Java 25 + Spring Boot 4.2 + Spring Modulith 2.2, Maven.
- `frontend/` — Next.js + TypeScript + shadcn/ui (a criar).
- `docs/` — decisões, ADRs e convenções.

## Comandos

Backend, em `backend/` (JDK 25 e Docker):

- `docker compose up -d --wait` — PostgreSQL 18 local com os papéis `atlas_owner` (dono, Flyway) e `atlas_app` (aplicação).
- `./mvnw spring-boot:run` — sobe a aplicação contra o Compose; exige `ATLAS_JWT_SECRET` (≥ 32 bytes; em dev, um valor fictício).
- `./mvnw test` — testes unitários e de fronteira (`ModularityTest`), sem Docker.
- `./mvnw verify` — tudo, incluindo os `*IT` com Testcontainers; é o que o CI roda.
- Migrações em `src/main/resources/db/migration/`, rodam como `atlas_owner`; a aplicação conecta como `atlas_app`.

## Regras de arquitetura

- Monólito modular ([ADR-001](docs/adr/ADR-001-monolito-modular.md)): módulos só se acessam pela API pública uns dos outros ou por eventos; fronteiras verificadas com Spring Modulith em teste.
- Auth (módulo `access`): JWT HS256 + refresh rotativo; endpoints públicos `/api/auth/register|login|refresh|logout`, o resto exige `Bearer`. O tenant vem do claim `org`, nunca de header/param/body.
- Multi-tenant ([ADR-002](docs/adr/ADR-002-multi-tenancy.md)): toda tabela de uma organização tem `tenant_id` com RLS forçada; o tenant vem só do contexto autenticado, nunca da requisição.

## Convenções

Siga `docs/CONVENCOES.md` (idioma, branches, commits, PRs, changelog). Resumo:

- Código, commits e branches em inglês; documentação em pt-BR.
- Commit: Conventional Commits — `<type>(<scope>): <description> (#<issue>)`.
- Branch: `<issue>-<tipo>-<descricao>`; PR para `develop` com o template.
- Specs em `.specs/features/<issue>-<slug>/`, seguindo o tlc-spec-driven ≥ 4.1 (skill instalada globalmente, não versionada): `spec.md`, `context.md`, `design.md` e `captures/` são versionados e entram no PR da feature; o resto da `.specs/` é local.
- Nunca commitar segredos nem usar `--no-verify`: os hooks em `.githooks/` barram segredos e commits fora do formato; corrija a causa.
