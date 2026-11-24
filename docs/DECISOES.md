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
