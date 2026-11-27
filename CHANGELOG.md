# Changelog

Todas as mudanças relevantes ficam neste arquivo. O formato segue o
[Keep a Changelog](https://keepachangelog.com/pt-BR/1.1.0/) e as versões seguem o
[Versionamento Semântico](https://semver.org/lang/pt-BR/).

## [Não lançado]

### Adicionado

- Telas de catálogo no frontend: `/categorias` lista, cria, arquiva e desarquiva categorias; `/produtos` lista SKU, nome, categoria, unidade ("Quilograma (KG)") e status, com busca por nome ou prefixo de SKU, filtros de categoria e status e paginação na URL; `OWNER`/`ADMIN` criam e editam produtos em modal (nome e categoria, com controle de versão), arquivam com confirmação e desarquivam direto, com erros do backend em pt-BR; `MEMBER` vê o catálogo sem controles de escrita.
- Telas de empresas e depósitos no frontend: `/empresas` lista as empresas (nome, CNPJ com máscara, status) e `/empresas/[id]` mostra a empresa e seus depósitos; `OWNER`/`ADMIN` criam em modal, arquivam com confirmação e desarquivam direto, com erros do backend em pt-BR; arquivados ficam ocultos atrás de um filtro na URL; `MEMBER` vê só o que o acesso dele permite, sem controles de escrita.
- Troca de organização: `GET /api/me/organizations` lista as organizações do usuário (id, nome, slug, papel e status, inclusive arquivadas) e o header do frontend ganha um seletor que troca a sessão por Server Action (tokens só em cookies httpOnly) e leva ao início; com uma só organização, mostra apenas o nome.
- Frontend (`frontend/`, Next.js 16 + shadcn/ui): cadastro com organização (slug sugerido do nome), login, logout e shell autenticado (sidebar, menu do usuário com papel, tema claro/escuro/sistema), em pt-BR. BFF com `proxy.ts` e Server Actions: tokens só em cookies httpOnly, renovação de sessão com uma única chamada de refresh por token, rotas protegidas e origem das ações verificada. Vitest, Playwright contra o backend real, verificação de vazamento no bundle e workflow `Frontend` no CI.
- Documentação da API: OpenAPI e Swagger UI atrás de `ATLAS_API_DOCS_ENABLED`, e README do MVP com o que existe, roadmap e todos os endpoints.
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
- Estoque (módulo `stock`): entradas, saídas e ajustes por depósito (`POST .../warehouses/{warehouseId}/movements`) com `Idempotency-Key` obrigatória (repetir a chave devolve o movimento original, chave com outro pedido responde `422`) e saldo que nunca fica negativo, nem sob concorrência (`UPDATE` condicional atômico, `CHECK` no banco); histórico imutável e paginado (`GET .../movements`, `/movements/{id}`), saldos do depósito (`GET .../stock`) e saldo de um produto nos depósitos visíveis (`GET /api/products/{id}/stock`); `OWNER`/`ADMIN` em qualquer depósito, `MEMBER` só nos que vê (`404` nos demais); `stock_balances` e `stock_movements` com RLS forçada e sem `UPDATE`/`DELETE` de movimentos para a aplicação.
- Transferência entre depósitos (`POST /api/transfers`, `GET /api/transfers/{id}`): até 100 itens movidos da origem ao destino numa única transação, tudo ou nada (item sem saldo responde `409` com o `productId`), inclusive entre empresas da organização; `Idempotency-Key` obrigatória e compartilhada com os movimentos; cada item gera `TRANSFER_OUT`/`TRANSFER_IN` com `transferId` no histórico; locks por depósito e produto em ordem fixa evitam deadlock entre transferências opostas; `OWNER`/`ADMIN` entre quaisquer depósitos, `MEMBER` só se vê origem e destino (lê a transferência se vê um dos lados); `stock_transfers` e `stock_transfer_items` com RLS forçada e imutáveis para a aplicação.

### Alterado

### Corrigido

### Removido
