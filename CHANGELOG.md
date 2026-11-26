# Changelog

Todas as mudanças relevantes ficam neste arquivo. O formato segue o
[Keep a Changelog](https://keepachangelog.com/pt-BR/1.1.0/) e as versões seguem o
[Versionamento Semântico](https://semver.org/lang/pt-BR/).

## [Não lançado]

### Adicionado

- ADR-001 (monólito modular com Spring Modulith), ADR-002 (multi-tenancy com Row-Level Security) e decisões D01–D03 da fundação.
- Esqueleto do backend: Spring Boot 4.2, Java 25, Spring Modulith com teste de fronteiras, PostgreSQL 18 local (Docker Compose) com os papéis `atlas_owner` e `atlas_app`, Flyway, testes de integração com Testcontainers, health check e CI.
- Organizações (módulo `access`): criar, buscar, arquivar e desarquivar, com slug único e imutável no banco.
- Empresas (módulo `company`): criar, listar, arquivar e desarquivar no tenant atual, com nome e CNPJ (numérico ou alfanumérico) únicos na organização.
- Isolamento por tenant: contexto em `TenantContext`, `set_config` em toda transação e RLS forçada em `companies`, provados por testes conectados como `atlas_app`; decisões D04 e D05.
- Autenticação (módulo `access`): cadastro com organização, login, refresh com rotação e detecção de reuso, logout, troca de organização e `GET /api/me`; o tenant de cada requisição vem do JWT. Senhas com Argon2id; exige `ATLAS_JWT_SECRET`; decisão D06.
- API de empresas (`/api/companies`): criar, listar (com `includeArchived`), buscar por id, arquivar e desarquivar; qualquer membro lê, só `OWNER`/`ADMIN` escreve (papel lido do banco a cada requisição, `Permissions` no módulo `access`).
- Depósitos (módulo `warehouse`) e API (`/api/companies/{companyId}/warehouses`): criar, listar (com `includeArchived`), buscar por id, arquivar e desarquivar; nome e código únicos por empresa, chave composta no banco impede apontar para empresa de outro tenant; qualquer membro lê, só `OWNER`/`ADMIN` escreve; empresa arquivada bloqueia só a criação.
- Membros da organização (`/api/members`): listar, adicionar usuário já cadastrado por e-mail, mudar papel e remover; `OWNER` gerencia todos, `ADMIN` só `MEMBER`, sempre resta um `OWNER`; a remoção revoga os refresh tokens e o access token antigo recebe `401` na hora (associação conferida a cada requisição).
- Acesso de membros por empresa e depósito (`/api/members/{userId}/access`): `OWNER`/`ADMIN` concedem e revogam empresas (com todos os depósitos) ou depósitos avulsos; um `MEMBER` lista e lê só o que lhe foi concedido, o resto responde `404`, e a mudança vale na requisição seguinte; as concessões somem com a associação.
- Catálogo (módulo `catalog`): categorias planas (`/api/categories`: criar, listar com `includeArchived`, buscar, arquivar, desarquivar; nome único na organização) e produtos (`/api/products`: criar com SKU e unidade fixos, editar nome/categoria com bloqueio otimista por `version`, listar com paginação, busca por nome ou SKU e filtros de categoria e status, arquivar e desarquivar); qualquer membro lê, só `OWNER`/`ADMIN` escreve; SKU único na organização e chave composta no banco impede apontar para categoria de outro tenant.

### Alterado

### Corrigido

### Removido
