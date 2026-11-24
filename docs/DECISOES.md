# Decisões

Registro numerado de decisões de produto e técnicas menores. Decisões de arquitetura com alternativas relevantes viram ADR em `docs/adr/`.

<!-- Modelo de entrada:

## D01: <Título>

- **Data:** AAAA-MM-DD
- **Decisão:** <o que foi decidido>.
- **Motivo:** <por quê>.
- **Impacto:** <o que muda; issues ou ADRs relacionados>.
-->

## D01: Maven como build do backend

- **Data:** 2026-11-24
- **Decisão:** o backend usa Maven com o wrapper (`mvnw`) versionado.
- **Motivo:** padrão do ecossistema Spring e dos projetos Java do portfólio; o Dependabot já prevê o ecossistema `maven` em `/backend`.
- **Impacto:** comandos de build e teste no `AGENTS.md` usam `./mvnw`; o `dependabot.yml` ganha a entrada `maven` com o backend.

## D02: Pacote base `io.github.abreulucass.atlas`

- **Data:** 2026-11-24
- **Decisão:** `groupId` `io.github.abreulucass` e pacote base `io.github.abreulucass.atlas`; cada módulo de domínio é um subpacote direto dele.
- **Motivo:** domínio derivado da conta do GitHub, único e sem precisar de domínio próprio.
- **Impacto:** o Spring Modulith detecta os módulos pelos subpacotes diretos ([ADR-001](adr/ADR-001-monolito-modular.md)).

## D03: Módulos de domínio nascem com a feature

- **Data:** 2026-11-24
- **Decisão:** a fundação não cria pacotes vazios para módulos previstos; cada módulo é criado pela primeira feature que precisa dele.
- **Motivo:** fronteiras desenhadas antes do código tendem a errar; um pacote vazio não é verificado por nada.
- **Impacto:** a fundação do backend contém só a aplicação, a configuração e o teste de fronteiras.

## D04: Contexto de tenant num `ScopedValue`, aplicado pelo gerenciador de transação

- **Data:** 2026-11-24
- **Decisão:** o tenant fica em `access.TenantContext`, um `ScopedValue` do Java 25. Um `JpaTransactionManager` próprio executa `set_config('app.tenant_id', <id>, true)` no início de toda transação, e o resolver do `@TenantId` do Hibernate lê o mesmo valor. Módulos de tenant nunca definem o contexto.
- **Motivo:** o `ScopedValue` é imutável e preso ao escopo, então não vaza entre requisições como um `ThreadLocal` esquecido. Aplicar no gerenciador de transação cobre toda transação sem depender de cada serviço. Descartados: `bind()` explícito por serviço e `MultiTenantConnectionProvider`, que deixa o valor na conexão.
- **Impacto:** a feature de auth só precisa chamar `TenantContext.callAs` por requisição. Sem contexto, o Hibernate usa o UUID nulo e a RLS não devolve linha nenhuma ([ADR-002](adr/ADR-002-multi-tenancy.md)).

## D05: Módulos `access` e `company`

- **Data:** 2026-11-24
- **Decisão:** `access` guarda as organizações e o contexto de tenant; usuários e memberships entram nele depois. `company` guarda as empresas e usa só a API pública de `access`.
- **Motivo:** organização e contexto de tenant mudam juntos com a autenticação. Descartados: `tenancy` + `organization` + `company`, e um módulo só.
- **Impacto:** o `ModularityTest` verifica `company` → `access`; `Company` guarda só o id da organização, sem relação JPA entre módulos ([ADR-001](adr/ADR-001-monolito-modular.md)).
