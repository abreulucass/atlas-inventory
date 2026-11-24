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

### Alterado

### Corrigido

### Removido
