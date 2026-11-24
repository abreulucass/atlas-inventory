# Organizations Context

**Gathered:** 2026-11-24
**Spec:** `.specs/features/10-organizations/spec.md`
**Status:** Ready for design

---

## Feature Boundary

Organizações (tenant, tabela global) e empresas (primeira tabela com `tenant_id`) com a barreira do ADR-002: RLS forçada, contexto `app.tenant_id` por transação e `@TenantId`. Arquivar e desarquivar as duas. Expostas como API pública dos módulos, provadas por testes de integração como `atlas_app`. Sem autenticação e sem API HTTP.

---

## Implementation Decisions

### Escopo

- Organizações + empresas + infraestrutura de RLS/contexto de tenant, sem autenticação; API HTTP fica para a feature de auth. Escolha do usuário entre: só organizações; organizações com auth de ponta a ponta.

### Criação de organização

- Self-service: qualquer usuário cria, e quem cria vira dono. Sem usuários nesta feature, a parte "vira dono" entra com memberships na feature de auth.

### Identificação

- Organização: nome (não único) + slug único global e imutável.
- Empresa: nome + CNPJ opcional, ambos únicos dentro da organização; CNPJ validado por dígito verificador.

### Arquivamento

- Organização arquivada: dados continuam legíveis, nenhuma escrita no tenant (ex.: criar empresa é recusado); pode ser desarquivada.
- Empresa arquivada: some da listagem padrão (filtro opcional para incluir), não recebe alterações, pode ser desarquivada; nome e CNPJ continuam reservados.

### Agent's Discretion

Nenhuma área delegada.

### Declined / Undiscussed Gray Areas → Assumptions

Defaults técnicos (formato do slug, limites de tamanho, comparação de nomes, idempotência do arquivar, CNPJ alfanumérico) estão em Assumptions na spec, para correção.

---

## Specific References

No specific requirements - open to standard approaches.

---

## Deferred Ideas

- Dono da organização (membership de owner ao criar) - feature de auth.
- API HTTP de organizações e empresas - feature de auth.
- Editar organização/empresa (renomear, trocar CNPJ) - não pedido; o bloqueio "empresa arquivada não recebe alterações" nasce com a primeira operação de edição.
