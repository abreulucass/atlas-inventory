# Organizations Specification

**Approved**: 2026-11-24 - user: "pode seguir"

## Problem Statement

O backend não tem nenhum dado de domínio nem a barreira de isolamento do ADR-002. Toda feature de estoque grava dados de uma organização; sem a organização como tenant, a RLS forçada e o contexto por transação, a primeira delas teria de construir o isolamento junto e um `WHERE` esquecido vazaria dados entre organizações.

## Goals

- [ ] Com o contexto da organização A, a aplicação (`atlas_app`) não lê nem grava nenhuma linha da organização B, mesmo com SQL nativo.
- [ ] Organizações e empresas são criadas, listadas, arquivadas e desarquivadas pela API pública dos módulos.

## Out of Scope

| Feature | Reason |
| ------- | ------ |
| Autenticação, usuários e memberships | Feature de auth; ela preenche o contexto de tenant que esta feature define |
| Dono da organização (quem cria vira owner) | Precisa de usuários e memberships; entra com a feature de auth |
| API HTTP de organizações e empresas | Sem autenticação, não há de onde tirar o tenant (ADR-002); entra com a feature de auth |
| Listar organizações | Depende de memberships ("as organizações do usuário") |
| Editar organização ou empresa (renomear, trocar CNPJ) | Não pedido; o bloqueio "empresa arquivada não recebe alterações" nasce com a primeira operação de edição |
| Excluir organização ou empresa | Arquivar substitui a exclusão |
| Depósitos | Feature própria |
| Registro de eventos do Spring Modulith (`event_publication`) | Nenhum evento entre módulos nesta feature |
| Frontend | Issue própria |

---

## Assumptions & Open Questions

| Assumption / decision | Chosen default | Rationale | Confirmed? |
| --------------------- | -------------- | --------- | ---------- |
| Escopo | Organizações + empresas + RLS/contexto, sem auth nem HTTP | Escolha do usuário | y |
| Quem cria organização | Qualquer usuário; o criador vira dono (adiado para auth) | Escolha do usuário | y |
| Identificação | Organização: nome + slug único global imutável; empresa: nome + CNPJ opcional, únicos na organização | Escolha do usuário | y |
| Arquivamento | Organização arquivada: leitura sim, escrita no tenant não, desarquivável. Empresa arquivada: fora da listagem padrão, desarquivável, nome/CNPJ reservados | Escolha do usuário | y |
| Formato do slug | 3 a 63 caracteres, `^[a-z0-9]+(-[a-z0-9]+)*$`; nunca normalizado, entrada fora do formato é recusada | Seguro em URL e subdomínio (limite de rótulo DNS); recusar evita surpresa | n |
| Nome | Espaços das pontas removidos; 1 a 120 caracteres depois disso, contados em pontos de código Unicode (como o `varchar` do PostgreSQL) | Limite de exibição comum; um emoji conta como um caractere | n |
| Unicidade do nome da empresa | Sem diferenciar maiúsculas/minúsculas, depois de remover espaços das pontas | "Acme" e "ACME" na mesma organização confundem | n |
| CNPJ | Aceita 14 caracteres ou a máscara `00.000.000/0000-00`; guarda os 14 sem máscara, em maiúsculas; recusa os 14 iguais; aceita o CNPJ alfanumérico (12 primeiros de `0-9A-Z`, valor = código ASCII − 48, mesmos pesos módulo 11, 2 dígitos verificadores numéricos) | A Receita emite CNPJ alfanumérico desde julho de 2026 (IN RFB 2.229/2024); regra não conferida na fonte nesta sessão | n |
| Arquivar o que já está arquivado / desarquivar o ativo | Sucesso sem mudança (idempotente) | Repetir a operação não é erro do usuário | n |
| Onde as regras rodam | API pública dos módulos (serviços Java); erros como exceções tipadas | Sem HTTP nesta feature; os testes chamam a API | n |
| Contexto de tenant | Um portador do contexto que a feature de auth vai preencher; os testes o preenchem | ADR-002: o tenant vem do contexto autenticado | n |

**Open questions:** none - all resolved or logged above (required before the spec is confirmed).

---

## User Stories

### P1: Criar e consultar organização ⭐ MVP

**User Story**: As a user, I want to create an organization with a name and a slug so that it becomes the tenant that holds my companies.

**Why P1**: A organização é o tenant; nada mais existe sem ela.

**Acceptance Criteria**:

1. WHEN an organization is created with a valid name and slug THEN the system SHALL persist it as active and return its id.
2. IF the slug is already used by another organization THEN the system SHALL reject the creation with `DuplicateSlugException` and persist nothing.
3. IF the slug has fewer than 3 or more than 63 characters or does not match `^[a-z0-9]+(-[a-z0-9]+)*$` THEN the system SHALL reject the creation with `InvalidSlugException`.
4. IF the trimmed name is empty or longer than 120 characters THEN the system SHALL reject the creation with `InvalidNameException`.
5. IF `atlas_app` issues an `UPDATE` that sets `organizations.slug` THEN PostgreSQL SHALL reject it with a permission error.
6. WHEN an organization is fetched by id THEN the system SHALL return its id, name, slug and status.
7. IF an organization id does not exist THEN find, archive and unarchive SHALL reject the operation with `OrganizationNotFoundException`.
8. IF `atlas_app` issues a `DELETE` on `organizations` THEN PostgreSQL SHALL reject it with a permission error.

**Independent Test**: criar uma organização e buscá-la pelo id; criar outra com o mesmo slug e ver a recusa.

---

### P1: Isolamento por tenant ⭐ MVP

**User Story**: As an organization, I want the database itself to refuse rows of other organizations so that an application bug never leaks my data.

**Why P1**: Vazamento entre organizações é a falha mais grave do produto (ADR-002).

**Acceptance Criteria**:

1. WHILE the tenant context is organization A, a `SELECT` on `companies` by `atlas_app` SHALL return only rows whose `tenant_id` is A.
2. WHILE the tenant context is organization A, IF `atlas_app` inserts a `companies` row with `tenant_id` B THEN PostgreSQL SHALL reject it with a row-level security error.
3. WHILE no tenant context is set, a `SELECT` on `companies` by `atlas_app` SHALL return zero rows.
4. WHILE no tenant context is set, IF a company operation is called THEN the system SHALL reject it with `MissingTenantContextException`.
5. Every table in schema `public` with a `tenant_id` column SHALL have row-level security enabled and forced and at least one policy.
6. WHEN a transaction that set the tenant context ends THEN the next transaction on the same connection SHALL see no tenant context.
7. WHEN a company is created THEN its `tenant_id` SHALL equal the organization of the tenant context.
8. WHILE the tenant context is organization A, IF `atlas_app` updates or deletes `companies` rows of organization B THEN PostgreSQL SHALL affect zero rows.
9. WHILE the tenant context is organization A, IF `atlas_app` updates a row's `tenant_id` to B THEN PostgreSQL SHALL reject it with a row-level security error.
10. WHILE a transaction is bound to organization A, IF the tenant context is switched to another organization THEN the system SHALL reject the switch with `IllegalStateException` before running any code in it.

**Independent Test**: teste de integração conectado como `atlas_app` com duas organizações, cada uma com uma empresa.

---

### P1: Empresas ⭐ MVP

**User Story**: As a member of an organization, I want to register its companies so that stock can later be held per company.

**Why P1**: Primeira tabela de tenant; exercita a RLS.

**Acceptance Criteria**:

1. WHEN a company is created with a valid name and no CNPJ in the tenant context THEN the system SHALL persist it as active and return its id.
2. WHEN a company is created with a valid CNPJ THEN the system SHALL store the CNPJ as its 14 characters without mask, in upper case.
3. IF the trimmed name is empty or longer than 120 characters THEN the system SHALL reject the creation with `InvalidNameException`.
4. IF another company of the same organization, active or archived, has the same trimmed name ignoring case THEN the system SHALL reject the creation with `DuplicateCompanyNameException`.
5. WHEN a company is created with the name of a company of another organization THEN the system SHALL create it.
6. IF the CNPJ is neither 14 characters without punctuation nor the full mask `XX.XXX.XXX/XXXX-XX`, has a wrong check digit, or has all 14 characters equal THEN the system SHALL reject the creation with `InvalidCnpjException`.
7. IF another company of the same organization, active or archived, has the same CNPJ THEN the system SHALL reject the creation with `DuplicateCnpjException`.
8. WHEN two companies of the same organization are created without CNPJ THEN the system SHALL create both.
9. WHEN companies are listed without the archived filter THEN the system SHALL return the active companies of the tenant ordered by name.
10. WHEN companies are listed with the archived filter THEN the system SHALL return active and archived companies of the tenant ordered by name.
11. IF a company id does not belong to the tenant of the context THEN the system SHALL reject the operation with `CompanyNotFoundException`.
12. IF two companies with the same name are created concurrently in the same organization THEN the system SHALL create exactly one and reject the other with `DuplicateCompanyNameException`.
13. WHEN a company is created with a CNPJ used by a company of another organization THEN the system SHALL create it.

**Independent Test**: no contexto de uma organização, criar empresas com e sem CNPJ, listar, e ver as recusas de nome e CNPJ repetidos.

---

### P1: Arquivar e desarquivar

**User Story**: As a member of an organization, I want to archive organizations and companies instead of deleting them so that history stays and they can come back.

**Why P1**: Pedido junto com a feature; substitui a exclusão.

**Acceptance Criteria**:

1. WHEN an active organization is archived THEN the system SHALL set its status to archived.
2. WHEN an archived organization is unarchived THEN the system SHALL set its status to active.
3. WHILE an organization is archived, a company listing in its tenant context SHALL return its companies.
4. WHILE an organization is archived, IF a company is created, archived or unarchived in its tenant context THEN the system SHALL reject it with `OrganizationArchivedException`, before validating the input, and change no row.
5. WHEN an active company is archived THEN the system SHALL set its status to archived.
6. WHEN an archived company is unarchived THEN the system SHALL set its status to active.
7. WHEN an organization or company is archived while already archived, or unarchived while already active, THEN the system SHALL succeed without changing it.

**Independent Test**: arquivar uma organização, listar as empresas dela, tentar criar uma e ver a recusa; desarquivar e criar.

---

## Edge Cases

- IF the slug is already used THEN the system SHALL reject with `DuplicateSlugException` (ORG-02).
- IF the CNPJ check digit is wrong THEN the system SHALL reject with `InvalidCnpjException` (COMP-06).
- IF a company id belongs to another organization THEN the system SHALL answer `CompanyNotFoundException`, never revealing it exists (COMP-11).
- IF two equal names are created concurrently THEN exactly one SHALL be created (COMP-12).
- IF no tenant context is set THEN nothing SHALL be read or written (TEN-03, TEN-04).

Dimensions: input validation (ORG-03/04, COMP-03/06); failure and rollback (ORG-02, ARCH-04 persist nothing); idempotency (ARCH-07); auth boundaries (TEN-01..07; user auth out of scope); concurrency (COMP-12); lifecycle (ARCH-*). Observability N/A because there is no operation to monitor beyond the existing health check; external dependencies N/A because the database is the only one.

---

## Requirement Traceability

| Requirement ID | Story | Phase | Status |
| -------------- | ----- | ----- | ------ |
| ORG-01 | P1: Organização, AC 1 | Design | Pending |
| ORG-02 | P1: Organização, AC 2 | Design | Pending |
| ORG-03 | P1: Organização, AC 3 | Design | Pending |
| ORG-04 | P1: Organização, AC 4 | Design | Pending |
| ORG-05 | P1: Organização, AC 5 | Design | Pending |
| ORG-06 | P1: Organização, AC 6 | Design | Pending |
| ORG-07 | P1: Organização, AC 7 | Design | Pending |
| ORG-08 | P1: Organização, AC 8 | Verify | Pending |
| TEN-01 | P1: Isolamento, AC 1 | Design | Pending |
| TEN-02 | P1: Isolamento, AC 2 | Design | Pending |
| TEN-03 | P1: Isolamento, AC 3 | Design | Pending |
| TEN-04 | P1: Isolamento, AC 4 | Design | Pending |
| TEN-05 | P1: Isolamento, AC 5 | Design | Pending |
| TEN-06 | P1: Isolamento, AC 6 | Design | Pending |
| TEN-07 | P1: Isolamento, AC 7 | Design | Pending |
| TEN-08 | P1: Isolamento, AC 8 | Verify | Pending |
| TEN-09 | P1: Isolamento, AC 9 | Verify | Pending |
| TEN-10 | P1: Isolamento, AC 10 | Verify | Pending |
| COMP-01 | P1: Empresas, AC 1 | Design | Pending |
| COMP-02 | P1: Empresas, AC 2 | Design | Pending |
| COMP-03 | P1: Empresas, AC 3 | Design | Pending |
| COMP-04 | P1: Empresas, AC 4 | Design | Pending |
| COMP-05 | P1: Empresas, AC 5 | Design | Pending |
| COMP-06 | P1: Empresas, AC 6 | Design | Pending |
| COMP-07 | P1: Empresas, AC 7 | Design | Pending |
| COMP-08 | P1: Empresas, AC 8 | Design | Pending |
| COMP-09 | P1: Empresas, AC 9 | Design | Pending |
| COMP-10 | P1: Empresas, AC 10 | Design | Pending |
| COMP-11 | P1: Empresas, AC 11 | Design | Pending |
| COMP-12 | P1: Empresas, AC 12 | Design | Pending |
| COMP-13 | P1: Empresas, AC 13 | Verify | Pending |
| ARCH-01 | P1: Arquivar, AC 1 | Design | Pending |
| ARCH-02 | P1: Arquivar, AC 2 | Design | Pending |
| ARCH-03 | P1: Arquivar, AC 3 | Design | Pending |
| ARCH-04 | P1: Arquivar, AC 4 | Design | Pending |
| ARCH-05 | P1: Arquivar, AC 5 | Design | Pending |
| ARCH-06 | P1: Arquivar, AC 6 | Design | Pending |
| ARCH-07 | P1: Arquivar, AC 7 | Design | Pending |

**Coverage:** 38 total (5 added from the Verifier's findings), 38 mapped to checks, 0 unmapped

---

## Sources

- Issue #10 - escopo e critérios de aceite
- `.specs/features/10-organizations/context.md` - respostas do usuário às áreas cinzentas
- `docs/adr/ADR-002-multi-tenancy.md` - RLS forçada, `set_config` local à transação, `@TenantId`, verificação exigida
- `docs/adr/ADR-001-monolito-modular.md` - módulos por API pública, sem FK de entidade entre módulos
- `.specs/features/7-backend-skeleton/spec.md` - itens adiados para esta feature

---

## Success Criteria

- [ ] `./mvnw verify` verde com os testes de isolamento conectados como `atlas_app`.
- [ ] Nenhuma tabela com `tenant_id` sem RLS forçada (o teste falha se aparecer uma).
