# Backend Skeleton Specification

**Approved**: 2026-11-24 - user: "pode seguir"

## Problem Statement

O repositório não tem código. As features de domínio precisam de um backend que compile, teste contra um PostgreSQL real e já respeite as fronteiras de módulo (ADR-001) e a separação de papéis de banco que a RLS exige (ADR-002). Sem isso, a primeira feature carregaria a fundação junto e as decisões ficariam só no papel.

## Goals

- [ ] `./mvnw verify` em `backend/` passa com o teste de fronteiras e o teste de integração com Postgres.
- [ ] O papel da aplicação não consegue contornar a RLS nem alterar o schema desde o primeiro commit.

## Out of Scope

| Feature | Reason |
| ------- | ------ |
| Módulos de domínio (organização, empresas, estoque...) | D03: cada módulo nasce com a sua feature |
| Políticas RLS, contexto `app.tenant_id`, `@TenantId` | Não há tabela com `tenant_id`; entram com a issue de organizações |
| Teste "toda tabela com `tenant_id` tem RLS forçada" | Vazio sem tabelas de tenant; entra com a primeira |
| Registro de eventos do Spring Modulith (`event_publication`) | Entra com o primeiro evento entre módulos |
| Autenticação e segurança HTTP | Feature própria |
| Frontend | Issue própria |
| Imagem Docker da aplicação e deploy | Sem ambiente de deploy definido |
| Auditoria de dependências (OWASP) no CI | Issue própria de segurança |

---

## Assumptions & Open Questions

| Assumption / decision | Chosen default | Rationale | Confirmed? |
| --------------------- | -------------- | --------- | ---------- |
| Versões | Spring Boot 4.2.0, Java 25, PostgreSQL 18 | Versões estáveis atuais do Initializr e do Postgres | n |
| Onde fica o Compose | `backend/compose.yaml` e script de papéis em `backend/docker/postgres/init/` | Backend autocontido; o teste usa o mesmo script | n |
| Banco local | Banco `atlas` no schema `public`, dono `atlas_owner` | Um schema (ADR-002) | n |
| Senhas locais | Valores fictícios de desenvolvimento no Compose e como padrão em `application.yaml`, sobrescrevíveis por variáveis de ambiente | Rodar sem configurar nada; produção injeta as suas | n |
| Onde os papéis nascem | Script de init do container (infra); grants da aplicação na migração `V1` (rodam como `atlas_owner`) | Criar papel é tarefa do ambiente; os grants viajam com o schema para qualquer ambiente | n |
| CI | Workflow `backend.yml` rodando `./mvnw verify` em PRs e pushes em `main`/`develop` que tocam `backend/` | Mesmo padrão dos workflows atuais | n |
| Dependabot | Entrada `maven` em `/backend` com prefixo `build` | Já previsto no `dependabot.yml` | n |

**Open questions:** none - all resolved or logged above (required before the spec is confirmed).

---

## User Stories

### P1: Build verificável ⭐ MVP

**User Story**: As a developer, I want `./mvnw verify` to build and test the backend so that every change is checked the same way locally and in CI.

**Why P1**: Toda feature seguinte depende do build e do teste de fronteiras.

**Acceptance Criteria**:

1. WHEN `./mvnw verify` runs in `backend/` THEN the build SHALL compile with Java 25 and exit 0 with all tests passing.
2. WHEN the build runs THEN the Spring Modulith test SHALL verify the application modules of `io.github.abreulucass.atlas` with `ApplicationModules.verify()`.
3. WHEN a pull request changes `backend/` THEN CI SHALL run `./mvnw verify` and report the result as a check on the PR.

**Independent Test**: rodar `./mvnw verify` em `backend/` e ver os testes passarem; abrir o PR e ver o check do backend verde.

---

### P1: Banco local com papéis separados ⭐ MVP

**User Story**: As a developer, I want a local PostgreSQL with the owner and application roles so that I run the app and the tests under the same privileges as production.

**Why P1**: A RLS do ADR-002 só protege se a aplicação nunca conectar com papel privilegiado.

**Acceptance Criteria**:

1. WHEN `docker compose up -d --wait` runs in `backend/` THEN PostgreSQL 18 SHALL accept logins from `atlas_owner` and `atlas_app` on database `atlas`.
2. WHEN the application starts THEN Flyway SHALL apply the migrations connected as `atlas_owner`.
3. The application datasource SHALL connect as `atlas_app`, a role with `rolsuper = false` and `rolbypassrls = false`.
4. IF `atlas_app` issues `CREATE TABLE` THEN PostgreSQL SHALL reject it with a permission error.
5. WHEN `atlas_owner` creates a table in schema `public` THEN `atlas_app` SHALL be able to `INSERT` and `SELECT` rows in it without any extra grant.

**Independent Test**: subir o Compose e conectar como `atlas_app`; rodar o teste de integração, que prova os critérios 2 a 5 com Testcontainers.

---

### P1: Health check

**User Story**: As an operator, I want a health endpoint so that I know the app is up and reaching the database.

**Why P1**: Primeiro sinal de que a aplicação subiu de verdade, usado pelo Compose e pelo deploy futuro.

**Acceptance Criteria**:

1. WHEN a client sends `GET /actuator/health` while the database is reachable THEN the system SHALL respond `200` with body field `status` equal to `UP`.

**Independent Test**: com a aplicação rodando, `curl localhost:8080/actuator/health` devolve `{"status":"UP"}`.

---

## Edge Cases

- IF `atlas_app` issues `CREATE TABLE` THEN PostgreSQL SHALL reject it (DB-04).

Remaining implicit-requirement dimensions N/A for this scope: no business input, no idempotent operation, no concurrency, no state transitions, no external dependency besides the database.

---

## Requirement Traceability

| Requirement ID | Story | Phase | Status |
| -------------- | ----- | ----- | ------ |
| BUILD-01 | P1: Build verificável, AC 1 | Execute | Pending |
| BUILD-02 | P1: Build verificável, AC 2 | Execute | Pending |
| BUILD-03 | P1: Build verificável, AC 3 | Execute | Pending |
| DB-01 | P1: Banco local, AC 1 | Execute | Pending |
| DB-02 | P1: Banco local, AC 2 | Execute | Pending |
| DB-03 | P1: Banco local, AC 3 | Execute | Pending |
| DB-04 | P1: Banco local, AC 4 | Execute | Pending |
| DB-05 | P1: Banco local, AC 5 | Execute | Pending |
| HEALTH-01 | P1: Health check, AC 1 | Execute | Pending |

**Coverage:** 9 total, 9 mapped to checks, 0 unmapped

---

## Sources

- `docs/adr/ADR-001-monolito-modular.md` - fronteiras verificadas com `ApplicationModules.verify()`
- `docs/adr/ADR-002-multi-tenancy.md` - papéis `atlas_owner` e `atlas_app`
- `docs/DECISOES.md` D01-D03 - Maven, pacote base, sem módulos vazios

---

## Success Criteria

- [ ] `./mvnw verify` verde localmente e no CI.
- [ ] Zero caminhos para a aplicação conectar como `atlas_owner` ou superusuário.

---

## Checks

**Build**: `cd backend && ./mvnw -q -DskipTests package`
**Run backend**: `cd backend && ./mvnw verify`

**C1** - `./mvnw verify` compiles with Java 25 and passes every test (BUILD-01, AC 1)
Proof: run backend | BUILD SUCCESS

**C2** - The module verification test runs `ApplicationModules.verify()` and passes (BUILD-02, AC 2)
Proof: run backend | Tests run: 1, Failures: 0, Errors: 0, Skipped: 0, Time elapsed in io.github.abreulucass.atlas.ModularityTest

**C3** - CI runs `./mvnw verify` on PRs that change `backend/` and the check is green (BUILD-03, AC 3)
Proof: walk - PR checks list "Backend (mvnw verify)" as passed

**C4** - The Compose Postgres accepts `atlas_app` and `atlas_owner` on `atlas` (DB-01, AC 1)
Proof: `cd backend && docker compose up -d --wait && docker compose exec -T postgres psql -U atlas_app -d atlas -tAc 'select current_user' | grep -qx atlas_app && docker compose exec -T postgres psql -U atlas_owner -d atlas -tAc 'select current_user' | grep -qx atlas_owner; rc=$?; docker compose down -v; exit $rc`

**C5** - Flyway's history records `atlas_owner` as the installer of V1 (DB-02, AC 2)
Proof: run backend | DatabaseRolesIT

**C6** - The datasource user is `atlas_app` with `rolsuper` and `rolbypassrls` false (DB-03, AC 3)
Proof: run backend | DatabaseRolesIT

**C7** - `CREATE TABLE` as `atlas_app` fails with permission denied (DB-04, AC 4)
Proof: run backend | DatabaseRolesIT

**C8** - A table created by `atlas_owner` accepts `INSERT` and `SELECT` from `atlas_app` (DB-05, AC 5)
Proof: run backend | DatabaseRolesIT

**C9** - `GET /actuator/health` returns 200 with `status` = `UP` (HEALTH-01, AC 1)
Proof: run backend | HealthIT
