# AGENTS.md

<!-- Modelo: substitua os trechos entre <> ao copiar para um projeto. -->

## Projeto

**<Nome>** — <uma frase sobre o produto>.

- `backend/` — <linguagem + framework>.
- `frontend/` — Next.js + TypeScript + shadcn/ui.
- `docs/` — decisões, ADRs e convenções.

## Comandos

```bash
<comando para subir o ambiente>
<comando para testes unitários>
<comando para testes de integração>
<comando para lint>
```

## Regras de arquitetura

- <regras de dependência entre módulos e como são verificadas>

## Convenções

Siga `docs/CONVENCOES.md` (idioma, branches, commits, PRs, changelog). Resumo:

- Código, commits e branches em inglês; documentação em pt-BR.
- Commit: Conventional Commits — `<type>(<scope>): <description> (#<issue>)`.
- Branch: `<issue>-<tipo>-<descricao>`; PR para `develop` com o template.
- Specs em `.specs/features/<issue>-<slug>/`, seguindo o tlc-spec-driven ≥ 4.1 (skill instalada globalmente, não versionada): `spec.md`, `context.md`, `design.md` e `captures/` são versionados e entram no PR da feature; o resto da `.specs/` é local.
- Nunca commitar segredos nem usar `--no-verify`: os hooks em `.githooks/` barram segredos e commits fora do formato; corrija a causa.
