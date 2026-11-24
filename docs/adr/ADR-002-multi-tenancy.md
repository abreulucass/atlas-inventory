# ADR-002: Multi-tenancy com schema compartilhado e Row-Level Security

- **Status:** Aceito
- **Data:** 2026-11-24

## Contexto

Várias organizações usam a mesma instância. Vazamento de dados entre elas é a falha mais grave possível do produto; um único `WHERE` esquecido não pode bastar para causá-la. Ao mesmo tempo, o projeto tem um banco PostgreSQL e um desenvolvedor: migrações e conexões precisam continuar simples.

### Modelo de tenant

- **Organização** é o tenant. Ela contém **empresas**, e cada empresa contém **depósitos**. Transferências entre empresas da mesma organização são possíveis; entre organizações, nunca.
- **Usuário** tem identidade global (um login) e pertence a várias organizações por meio de **memberships**. Dentro de cada organização, a membership concede acesso a uma ou mais empresas e depósitos, conforme as permissões.
- O usuário escolhe a organização ativa e, quando a operação exigir, a empresa ativa.

## Opções consideradas

1. **Banco por tenant** — isolamento máximo / um banco, um pool e uma migração por organização; caro de operar e de testar.
2. **Schema por tenant** — isolamento forte / Flyway roda N vezes, pool ou `search_path` por requisição, relatórios entre tenants complicados.
3. **Schema compartilhado com `tenant_id` só na aplicação** (Hibernate `@TenantId`) — simples / uma consulta nativa ou um bug no filtro expõe outra organização; nada no banco impede.
4. **Schema compartilhado com `tenant_id` e Row-Level Security** — um schema e uma migração; o banco recusa linhas de outra organização mesmo quando a aplicação erra / exige papel de banco sem privilégio de bypass e o contexto definido em toda transação.

## Decisão

Opção 4: filtro na aplicação e barreira no banco.

**Dados**

- Toda tabela de dados de uma organização tem `tenant_id uuid not null` referenciando a organização, e o `tenant_id` lidera os índices e as restrições de unicidade (`unique (tenant_id, sku)`).
- Tabelas com escopo de empresa ou depósito têm também `company_id` / `warehouse_id`; esse escopo é autorização da aplicação, não RLS.
- Tabelas globais — usuários, organizações, memberships — não têm RLS por tenant e só são acessadas pelo módulo de identidade e acesso.

**Banco**

- Toda tabela com `tenant_id` tem `ENABLE` e `FORCE ROW LEVEL SECURITY` e a política `USING (tenant_id = current_setting('app.tenant_id', true)::uuid) WITH CHECK (<mesma expressão>)`.
- Sem contexto definido, `current_setting(..., true)` devolve `NULL`, e a política não deixa ler nem gravar nada: falha fechada.
- Dois papéis: `atlas_owner` é dono do schema e roda o Flyway; `atlas_app` é o da aplicação, sem `SUPERUSER`, sem `BYPASSRLS` e sem ser dono das tabelas.

**Aplicação**

- No início de cada transação, a aplicação executa `set_config('app.tenant_id', <id>, true)`. O terceiro argumento limita o valor à transação, então a conexão volta ao pool sem contexto.
- O Hibernate preenche e filtra `tenant_id` pelo `@TenantId`, com o mesmo valor resolvido do contexto.
- O `tenant_id` vem exclusivamente do contexto autenticado (token ou sessão), nunca do corpo, de parâmetros ou de cabeçalhos enviados pelo cliente.
- Trocar a organização ou a empresa ativa é uma operação do backend: ele valida que o usuário tem membership e permissões no contexto pedido antes de emiti-lo.
- Eventos, jobs e tarefas assíncronas carregam o `tenant_id` e definem o contexto antes de tocar no banco.

## Consequências

- **Positivas:** um bug de filtro na aplicação não vaza dados entre organizações; uma migração e um pool para todos; transferência entre empresas da organização é uma transação local.
- **Negativas:** toda transação paga um `set_config`; consultas administrativas entre tenants precisam de um papel próprio e auditado; um vizinho barulhento afeta os demais (sem isolamento de recursos por tenant).
- **Verificação:** teste de integração com Testcontainers, conectado como `atlas_app`, prova que (1) com o contexto da organização A não se lê nem se grava linha da organização B, (2) sem contexto nenhuma linha é lida, e (3) toda tabela com coluna `tenant_id` tem RLS habilitada e forçada (consulta em `pg_class`/`pg_policies`).
