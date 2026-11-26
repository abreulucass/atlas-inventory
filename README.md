# Atlas Inventory

[![Backend](https://github.com/abreulucass/atlas-inventory/actions/workflows/backend.yml/badge.svg)](https://github.com/abreulucass/atlas-inventory/actions/workflows/backend.yml)
[![Security](https://github.com/abreulucass/atlas-inventory/actions/workflows/security.yml/badge.svg)](https://github.com/abreulucass/atlas-inventory/actions/workflows/security.yml)
[![License: MIT](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)

Plataforma de gestão de estoque multiempresa e multidepósito, com foco em consistência de dados e regras de negócio complexas.

> Em desenvolvimento: o backend do MVP está completo e testado; o frontend é o próximo passo.

## Por que este projeto

Estoque é um domínio em que erros custam dinheiro: duas reservas simultâneas do mesmo item, uma transferência que sai de um depósito e não chega ao outro, um movimento registrado duas vezes. O Atlas Inventory trata concorrência, transações, integridade de dados, idempotência e isolamento multi-tenant como requisitos de primeira classe.

## Feito

- Autenticação com JWT e refresh token rotativo; organizações (tenants) e troca de organização.
- Membros e papéis (`OWNER`, `ADMIN`, `MEMBER`) e acesso por empresa e depósito.
- Empresas e depósitos.
- Categorias e produtos (SKU, unidade, busca, filtros, arquivamento, controle de versão).
- Movimentações de estoque idempotentes (entrada, saída, ajuste) e saldos por depósito.
- Transferências atômicas entre depósitos, com vários itens.
- Isolamento multi-tenant com RLS forçada no PostgreSQL.

## Roadmap

- Fornecedores.
- Reservas de estoque para pedidos.
- Lotes e validade.
- Compras e reposição.
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
export ATLAS_API_DOCS_ENABLED=true   # opcional: publica o OpenAPI; desligado por padrão
./mvnw spring-boot:run        # http://localhost:8080/actuator/health
./mvnw verify                 # testes; os de integração sobem o próprio Postgres (Testcontainers)
```

Com `ATLAS_API_DOCS_ENABLED=true`, o Swagger UI fica em `http://localhost:8080/swagger-ui.html` e o documento OpenAPI em `http://localhost:8080/v3/api-docs`, ambos sem token. Sem a variável, respondem `404`.

### Frontend e CORS

O frontend (Next.js) chega à API por um BFF: os route handlers do Next chamam o backend no servidor e guardam os tokens em cookies httpOnly. O navegador nunca fala direto com a API, então o backend não tem CORS, de propósito.

### Autenticação

A API usa JWT (access de 15 min) e refresh token opaco rotativo (30 dias). `ATLAS_JWT_SECRET` é obrigatório: sem ele a aplicação não sobe.

| Endpoint | Acesso |
| -------- | ------ |
| `POST /api/auth/register` | público |
| `POST /api/auth/login` | público |
| `POST /api/auth/refresh` | público |
| `POST /api/auth/logout` | público |
| `POST /api/auth/switch-organization` | com `Bearer` |
| `GET /api/me` | com `Bearer` |
| `GET /api/members` | com `Bearer`; qualquer membro |
| `POST /api/members` | com `Bearer`; `OWNER` gerencia todos, `ADMIN` só `MEMBER` |
| `PATCH /api/members/{userId}` | com `Bearer`; `OWNER` gerencia todos, `ADMIN` só `MEMBER` |
| `DELETE /api/members/{userId}` | com `Bearer`; `OWNER` gerencia todos, `ADMIN` só `MEMBER` |
| `GET /api/members/{userId}/access` | com `Bearer`; `OWNER`/`ADMIN` leem de qualquer membro, `MEMBER` só o próprio |
| `POST /api/members/{userId}/access` | com `Bearer`; `OWNER` e `ADMIN` |
| `DELETE /api/members/{userId}/access/companies/{companyId}` | com `Bearer`; `OWNER` e `ADMIN` |
| `DELETE /api/members/{userId}/access/warehouses/{warehouseId}` | com `Bearer`; `OWNER` e `ADMIN` |
| `POST /api/companies/{companyId}/warehouses/{warehouseId}/movements` (header `Idempotency-Key` obrigatório) | com `Bearer`; `OWNER`/`ADMIN` em qualquer depósito, `MEMBER` só nos que vê |
| `GET /api/companies/{companyId}/warehouses/{warehouseId}/movements` | com `Bearer`; quem vê o depósito |
| `GET /api/companies/{companyId}/warehouses/{warehouseId}/movements/{id}` | com `Bearer`; quem vê o depósito |
| `GET /api/companies/{companyId}/warehouses/{warehouseId}/stock` | com `Bearer`; quem vê o depósito |
| `GET /api/products/{id}/stock` | com `Bearer`; saldo do produto nos depósitos que o chamador vê |
| `GET /api/companies` | com `Bearer`; `OWNER`/`ADMIN` veem todas, `MEMBER` só as do seu acesso |
| `GET /api/companies/{id}` | com `Bearer`; `OWNER`/`ADMIN` veem todas, `MEMBER` só as do seu acesso |
| `POST /api/companies` | com `Bearer`; `OWNER` e `ADMIN` |
| `POST /api/companies/{id}/archive` | com `Bearer`; `OWNER` e `ADMIN` |
| `POST /api/companies/{id}/unarchive` | com `Bearer`; `OWNER` e `ADMIN` |
| `GET /api/companies/{companyId}/warehouses` | com `Bearer`; `OWNER`/`ADMIN` veem todos, `MEMBER` só os do seu acesso |
| `GET /api/companies/{companyId}/warehouses/{id}` | com `Bearer`; `OWNER`/`ADMIN` veem todos, `MEMBER` só os do seu acesso |
| `POST /api/companies/{companyId}/warehouses` | com `Bearer`; `OWNER` e `ADMIN` |
| `POST /api/companies/{companyId}/warehouses/{id}/archive` | com `Bearer`; `OWNER` e `ADMIN` |
| `POST /api/companies/{companyId}/warehouses/{id}/unarchive` | com `Bearer`; `OWNER` e `ADMIN` |
| `GET /api/categories` | com `Bearer`; qualquer membro |
| `GET /api/categories/{id}` | com `Bearer`; qualquer membro |
| `POST /api/categories` | com `Bearer`; `OWNER` e `ADMIN` |
| `POST /api/categories/{id}/archive` | com `Bearer`; `OWNER` e `ADMIN` |
| `POST /api/categories/{id}/unarchive` | com `Bearer`; `OWNER` e `ADMIN` |
| `GET /api/products` | com `Bearer`; qualquer membro |
| `GET /api/products/{id}` | com `Bearer`; qualquer membro |
| `POST /api/products` | com `Bearer`; `OWNER` e `ADMIN` |
| `PATCH /api/products/{id}` | com `Bearer`; `OWNER` e `ADMIN` |
| `POST /api/products/{id}/archive` | com `Bearer`; `OWNER` e `ADMIN` |
| `POST /api/products/{id}/unarchive` | com `Bearer`; `OWNER` e `ADMIN` |
| `POST /api/transfers` (header `Idempotency-Key` obrigatório) | com `Bearer`; `OWNER`/`ADMIN` entre quaisquer depósitos, `MEMBER` só quando vê origem e destino |
| `GET /api/transfers/{id}` | com `Bearer`; `OWNER`/`ADMIN`, ou `MEMBER` que vê a origem ou o destino |

## Documentação

- [Convenções](docs/CONVENCOES.md)
- [Decisões](docs/DECISOES.md)
- [ADRs](docs/adr/)
- [Changelog](CHANGELOG.md)

## Licença

[MIT](LICENSE)
