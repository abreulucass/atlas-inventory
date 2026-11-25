# Atlas Inventory

[![Backend](https://github.com/abreulucass/atlas-inventory/actions/workflows/backend.yml/badge.svg)](https://github.com/abreulucass/atlas-inventory/actions/workflows/backend.yml)
[![Security](https://github.com/abreulucass/atlas-inventory/actions/workflows/security.yml/badge.svg)](https://github.com/abreulucass/atlas-inventory/actions/workflows/security.yml)
[![License: MIT](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)

Plataforma de gestão de estoque multiempresa e multidepósito, com foco em consistência de dados e regras de negócio complexas.

> Em desenvolvimento: o backend sobe e é testado, mas ainda não tem funcionalidades de domínio.

## Por que este projeto

Estoque é um domínio em que erros custam dinheiro: duas reservas simultâneas do mesmo item, uma transferência que sai de um depósito e não chega ao outro, um movimento registrado duas vezes. O Atlas Inventory trata concorrência, transações, integridade de dados, idempotência e isolamento multi-tenant como requisitos de primeira classe.

## Funcionalidades

- Produtos, categorias e fornecedores.
- Estoque em múltiplos depósitos e transferência entre unidades.
- Reserva de estoque para pedidos.
- Controle de lotes e validade.
- Histórico auditável de movimentações.
- Compras e reposição.
- Controle de acesso e isolamento multi-tenant.
- Dashboard de indicadores operacionais.

## Arquitetura

Monólito modular com Spring Modulith: cada módulo de domínio expõe uma API pública e se comunica com os demais por eventos; as fronteiras são verificadas em teste.

## Stack

| Camada | Tecnologia |
|---|---|
| Backend | Java 25, Spring Boot, Spring Modulith |
| Persistência | PostgreSQL, Spring Data JPA, Flyway |
| Frontend | Next.js, TypeScript, shadcn/ui |
| Testes | JUnit, Testcontainers, ArchUnit |
| Infraestrutura | Docker, GitHub Actions |

## Como rodar

Requisitos: JDK 25 e Docker.

```bash
cd backend
docker compose up -d --wait   # PostgreSQL 18 com os papéis atlas_owner e atlas_app
export ATLAS_JWT_SECRET=dev-only-secret-dev-only-secret-0123   # fictício, só para desenvolvimento; mínimo de 32 bytes
./mvnw spring-boot:run        # http://localhost:8080/actuator/health
./mvnw verify                 # testes; os de integração sobem o próprio Postgres (Testcontainers)
```

### Autenticação

A API usa JWT (access de 15 min) e refresh token opaco rotativo (30 dias). `ATLAS_JWT_SECRET` é obrigatório: sem ele a aplicação não sobe.

| Endpoint | Acesso |
| -------- | ------ |
| `POST /api/auth/register`, `/login`, `/refresh`, `/logout` | público |
| `POST /api/auth/switch-organization`, `GET /api/me` | com `Authorization: Bearer <access token>` |
| `GET /api/members` | com `Bearer`; qualquer membro |
| `POST /api/members`, `PATCH /api/members/{userId}`, `DELETE /api/members/{userId}` | com `Bearer`; `OWNER` gerencia todos, `ADMIN` só `MEMBER` |

## Documentação

- [Convenções](docs/CONVENCOES.md)
- [Decisões](docs/DECISOES.md)
- [ADRs](docs/adr/)
- [Changelog](CHANGELOG.md)

## Licença

[MIT](LICENSE)
