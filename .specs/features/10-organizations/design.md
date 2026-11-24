# Organizations Design

**Spec**: `.specs/features/10-organizations/spec.md`
**Status**: Approved

Abordagem escolhida pelo usuário: contexto em `ScopedValue` aplicado por um `JpaTransactionManager` próprio (descartados: `bind()` explícito por serviço; `MultiTenantConnectionProvider`, que deixa o valor na conexão e contraria o ADR-002). Módulos `access` e `company` (descartados: `tenancy` + `organization` + `company`; um módulo só).

---

## Architecture Overview

```mermaid
sequenceDiagram
    participant Caller as Caller (test; auth filter later)
    participant TC as access.TenantContext
    participant CS as company.Companies
    participant TM as TenantTransactionManager
    participant ORG as access.Organizations
    participant DB as PostgreSQL (atlas_app)
    Caller->>TC: callAs(orgId, () -> companies.create(...))
    TC->>CS: create(name, cnpj) [ScopedValue bound]
    CS->>TM: begin transaction
    TM->>DB: select set_config('app.tenant_id', orgId, true)
    CS->>TC: require() -> orgId (else MissingTenantContextException)
    CS->>ORG: requireWritable(orgId)
    ORG->>DB: select archived_at from organizations where id = ? for share
    CS->>DB: insert into companies (tenant_id from @TenantId) - RLS WITH CHECK
    CS->>TM: commit (setting dies with the transaction)
```

- O tenant só existe dentro de `TenantContext.callAs/runAs`; a feature de auth vai chamar isso por requisição, depois de validar a membership.
- Toda transação passa pelo `TenantTransactionManager`: com contexto, `set_config(..., true)`; sem contexto, nada, e a política não casa nenhuma linha.
- O `@TenantId` do Hibernate preenche e filtra `tenant_id` com o mesmo valor (filtro na aplicação), e a RLS é a barreira no banco (ADR-002).

---

## Code Reuse Analysis

### Existing Components to Leverage

| Component | Location | How to Use |
| --------- | -------- | ---------- |
| `TestcontainersConfiguration` | `backend/src/test/java/io/github/abreulucass/atlas/TestcontainersConfiguration.java` | `@Import` em todos os novos `*IT`; mesmos papéis da aplicação |
| Padrão do `DatabaseRolesIT` (`owner()` via `DriverManagerDataSource`) | `backend/src/test/java/io/github/abreulucass/atlas/DatabaseRolesIT.java` | Preparar e inspecionar dados como `atlas_owner` nos testes de RLS |
| Default privileges da V1 | `backend/src/main/resources/db/migration/V1__grant_application_privileges.sql` | Novas tabelas já ganham DML para `atlas_app`; só a V2 restringe colunas |
| `ModularityTest` | `backend/src/test/java/io/github/abreulucass/atlas/ModularityTest.java` | Passa a verificar `access` e `company` sem mudança |

### Integration Points

| System | Integration Method |
| ------ | ------------------ |
| PostgreSQL | Migrações V2 (organizations) e V3 (companies + RLS), como `atlas_owner` |
| Spring Boot JPA | `spring-boot-starter-data-jpa`; o bean `transactionManager` próprio substitui o do auto-config |
| Hibernate | `CurrentTenantIdentifierResolver` registrado por `HibernatePropertiesCustomizer` |

---

## Components

### access.TenantContext (API pública)

- **Purpose**: Portador do tenant da execução atual.
- **Location**: `backend/src/main/java/io/github/abreulucass/atlas/access/TenantContext.java`
- **Interfaces**:
  - `static <T> T callAs(UUID organizationId, Callable<T>)` / `static void runAs(UUID, Runnable)` - vincula o tenant ao escopo
  - `static Optional<UUID> current()`
  - `static UUID require()` - lança `MissingTenantContextException`
- **Dependencies**: `java.lang.ScopedValue` (final no Java 25)
- **Reuses**: nada

### access.Organizations (API pública)

- **Purpose**: Criar, buscar, arquivar e desarquivar organizações; dizer a outros módulos se uma organização aceita escrita.
- **Location**: `backend/src/main/java/io/github/abreulucass/atlas/access/Organizations.java` (+ `internal/Organization`, `internal/OrganizationRepository`)
- **Interfaces**:
  - `UUID create(String name, String slug)` - ORG-01..04
  - `OrganizationView find(UUID id)` - ORG-06, ORG-07
  - `void archive(UUID id)` / `void unarchive(UUID id)` - ARCH-01, 02, 07
  - `void requireWritable(UUID id)` - `select ... for share`; `OrganizationNotFoundException` / `OrganizationArchivedException` (ARCH-04)
- **Records/enums**: `OrganizationView(UUID id, String name, String slug, Status status)`, `Status { ACTIVE, ARCHIVED }`
- **Exceptions** (pacote raiz): `DuplicateSlugException`, `InvalidSlugException`, `InvalidNameException`, `OrganizationNotFoundException`, `OrganizationArchivedException`, `MissingTenantContextException`

### access.internal.TenantTransactionManager

- **Purpose**: Aplicar o tenant do contexto a toda transação.
- **Location**: `backend/src/main/java/io/github/abreulucass/atlas/access/internal/TenantTransactionManager.java`
- **Interfaces**: `extends JpaTransactionManager`; `doBegin` chama `super.doBegin` e, com `TenantContext.current()` presente, executa `select set_config('app.tenant_id', ?, true)` no `EntityManager` da transação.
- **Dependencies**: `TenantContext`
- **Reuses**: `JpaTransactionManager` do Spring

### access.internal.TenantPersistenceConfiguration

- **Purpose**: Registrar o `TenantTransactionManager` como `transactionManager` e o `CurrentTenantIdentifierResolver<UUID>` do Hibernate.
- **Location**: `backend/src/main/java/io/github/abreulucass/atlas/access/internal/TenantPersistenceConfiguration.java`
- **Interfaces**: resolver devolve `TenantContext.current()`, ou o UUID nulo `00000000-0000-0000-0000-000000000000` sem contexto (nenhuma organização tem esse id).

### company.Companies (API pública)

- **Purpose**: Criar, listar, arquivar e desarquivar empresas do tenant atual.
- **Location**: `backend/src/main/java/io/github/abreulucass/atlas/company/Companies.java` (+ `internal/Company`, `internal/CompanyRepository`, `internal/Cnpj`)
- **Interfaces**:
  - `UUID create(String name, @Nullable String cnpj)` - COMP-01..08, 12, TEN-04, TEN-07, ARCH-04
  - `List<CompanyView> list(boolean includeArchived)` - COMP-09, 10, ARCH-03
  - `void archive(UUID id)` / `void unarchive(UUID id)` - COMP-11, ARCH-04..07
- **Records/enums**: `CompanyView(UUID id, String name, @Nullable String cnpj, Status status)`, `Status { ACTIVE, ARCHIVED }`
- **Exceptions** (pacote raiz): `InvalidNameException`, `InvalidCnpjException`, `DuplicateCompanyNameException`, `DuplicateCnpjException`, `CompanyNotFoundException`
- **Dependencies**: `access.TenantContext`, `access.Organizations`

### company.internal.Cnpj

- **Purpose**: Normalizar e validar CNPJ numérico e alfanumérico.
- **Interfaces**: `static String parse(String raw)` - remove `.`, `/`, `-`; maiúsculas; 14 caracteres, 12 de `[0-9A-Z]` + 2 dígitos; recusa os 14 iguais; DV módulo 11 com valor = ASCII − 48, pesos 5..2,9..2 e 6..2,9..2; lança `InvalidCnpjException`.

---

## Data Models

```sql
-- V2__organizations.sql (global: sem tenant_id, sem RLS - ADR-002)
create table organizations (
    id          uuid primary key,
    name        varchar(120) not null,
    slug        varchar(63)  not null constraint organizations_slug_key unique,
    archived_at timestamptz
);
revoke update on organizations from atlas_app;
grant update (name, archived_at) on organizations to atlas_app;  -- slug imutável (ORG-05)

-- V3__companies.sql
create table companies (
    id          uuid primary key,
    tenant_id   uuid not null references organizations (id),
    name        varchar(120) not null,
    cnpj        char(14),
    archived_at timestamptz,
    constraint companies_tenant_cnpj_key unique (tenant_id, cnpj)
);
create unique index companies_tenant_name_key on companies (tenant_id, lower(name));
alter table companies enable row level security;
alter table companies force row level security;
create policy tenant_isolation on companies
    using (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    with check (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);
```

- Entidades: `Organization` (`slug` com `updatable = false`) e `Company` (`@TenantId UUID tenantId`, `cnpj` `updatable = false`). Ids UUID gerados pelo Hibernate. Status = `archived_at is null ? ACTIVE : ARCHIVED`.
- Sem relação JPA entre módulos: `Company.tenantId` é só o identificador (ADR-001); a FK fica no banco (ADR-002).

---

## Error Handling Strategy

| Error Scenario | Handling | User Impact |
| -------------- | -------- | ----------- |
| Slug, nome ou CNPJ repetido, inclusive em corrida | `saveAndFlush`; `DataIntegrityViolationException` traduzida pelo nome da constraint (`organizations_slug_key`, `companies_tenant_name_key`, `companies_tenant_cnpj_key`) | Exceção tipada; a transação volta e nada fica gravado |
| Entrada inválida | Validada no serviço antes de tocar o banco | `InvalidSlug/Name/CnpjException` |
| Sem contexto de tenant em operação de empresa | `TenantContext.require()` no início | `MissingTenantContextException` |
| Organização arquivada em escrita de empresa | `requireWritable` com `for share` antes de gravar | `OrganizationArchivedException`, nada gravado |
| Empresa de outro tenant | RLS e `@TenantId` não a acham | `CompanyNotFoundException`, sem revelar que existe |

---

## Risks & Concerns

| Concern | Location (file:line) | Impact | Mitigation |
| ------- | -------------------- | ------ | ---------- |
| Depois de um `set_config(..., true)` na sessão, `current_setting('app.tenant_id', true)` volta `''`, não `NULL`; `''::uuid` é erro | política RLS em V3 | Conexão reaproveitada do pool falharia com erro de cast em vez de devolver zero linhas | `nullif(..., '')` na política; TEN-06 roda com pool de 1 conexão; nota no ADR-002 |
| Hibernate pode recusar sessão sem tenant quando há entidade com `@TenantId` | `TenantPersistenceConfiguration` | Operações de organização sem contexto quebrariam | Resolver devolve o UUID nulo sem contexto; não confirmado na doc do Hibernate 7 (Context7 não cobre o caso), provado pelos testes de organização sem contexto |
| `open-in-view` abre o `EntityManager` antes do filtro de auth vincular o tenant | `application.yaml` | Sessão com tenant errado na feature de auth | `spring.jpa.open-in-view: false` |
| Arquivar a organização em paralelo com a criação de uma empresa | `Organizations.requireWritable` | Empresa criada em organização recém-arquivada | `select ... for share` serializa com o `update` do arquivamento |
| Surefire/failsafe não imprimem uma linha por teste | `backend/pom.xml` | Checks sem linha por teste | `reportFormat=plain` nos dois plugins; a linha `Classe.metodo -- Time elapsed` é a prova |

---

## Tech Decisions (only non-obvious ones)

| Decision | Choice | Rationale |
| -------- | ------ | --------- |
| Portador do tenant | `ScopedValue` | Imutável e preso ao escopo: não vaza entre requisições como um `ThreadLocal` esquecido |
| Onde aplicar `set_config` | `JpaTransactionManager.doBegin` | Toda transação, sem depender de cada serviço lembrar (AD-001) |
| Slug imutável | Privilégio por coluna no banco + `updatable = false` | O banco recusa, não só a aplicação |
| Unicidade | Índices únicos, inclusive `lower(name)` | Resolve corrida (COMP-12) sem lock na aplicação |
| Exceções e validação de nome | Uma por módulo (`access`, `company`) | Duas linhas duplicadas valem mais que acoplar `company` a uma regra de `access` |
