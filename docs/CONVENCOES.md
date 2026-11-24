# Convenções

Padrão comum aos projetos do portfólio.

## Idioma

| O quê | Idioma |
|---|---|
| Código: identificadores, nomes de arquivo, mensagens de log e de erro | Inglês |
| Commits, nomes de branch, títulos de PR | Inglês |
| Comentários e docstrings, inclusive em CI, scripts e configs | Inglês |
| `README.md`, `docs/`, `AGENTS.md`, `CHANGELOG.md` | pt-BR |
| Templates de issue/PR e corpo de issues/PRs | pt-BR |

## Branches

- `main`: produção; só recebe merge de `develop` (release) ou hotfix.
- Hotfix: branch `<issue>-fix-...` a partir de `main`, PR para `main` e, depois do merge, PR de `main` para `develop`.
- `develop`: integração; destino padrão dos PRs.
- Trabalho: `<issue>-<tipo>-<descricao-curta>` em kebab-case e inglês.
  - Ex.: `12-feature-stock-reservation`, `31-fix-duplicate-webhook-delivery`.
  - `<tipo>`: `feature`, `fix`, `docs`, `chore`.
- Toda branch de trabalho nasce de uma issue.
- `develop` é a branch **padrão** no GitHub: o GitHub só fecha issues com `Fixes #N` em merges na branch padrão.
- Configuração do repositório (labels, branch padrão, proteções, modo de merge): `scripts/setup-repo.sh <owner/repo>`.

## Commits

Padrão [Conventional Commits 1.0.0](https://www.conventionalcommits.org/pt-br/v1.0.0/), o mesmo que o `check_commit.py` do tlc-spec valida.

### Em branch de trabalho

Formato: `<type>(<scope>): <description> (#<issue>)`

- `<description>`: inglês, imperativo, minúsculo, sem ponto final.
- `<scope>`: camada/área — `backend`, `frontend`, `worker`, `api`, `db`, `infra`, `ci`, `tests`, `arch`, `docs`.
- `<issue>`: número da issue (primeiro número da branch).
- Mudança incompatível: `!` antes dos dois-pontos (`feat(api)!: ...`) e rodapé `BREAKING CHANGE: <detalhe>`.

| Type | Uso |
|---|---|
| `feat` | nova funcionalidade |
| `fix` | correção de bug |
| `refactor` | refatoração sem mudança de comportamento |
| `perf` | melhoria de desempenho |
| `test` | testes |
| `docs` | documentação |
| `style` | formatação, sem mudança de lógica |
| `build` | build e dependências |
| `ci` | pipelines de CI |
| `chore` | manutenção e configuração |

```text
feat(backend): add stock reservation with optimistic locking (#12)
fix(worker): stop retrying events already sent to the DLQ (#31)
test(tests): cover concurrent transfers with Testcontainers (#12)
docs(docs): record ADR for multi-tenant isolation (#4)
```

Um commit = uma mudança coesa. Código e o teste dele podem ir juntos ou em commits seguidos.

### Direto na `develop`

Só para ajustes triviais (typo, bump de versão). Mesmo formato, sem a issue:

```text
chore: bump version to 0.2.0
```

## Pull Requests

- Destino: `develop`. Título no formato do commit principal, sem o `(#<issue>)` (vai em "Issues Relacionadas").
- Preencher `.github/pull_request_template.md`; `Fixes #<issue>` fecha a issue no merge.
- CI verde é obrigatório antes do merge. `.github/workflows/commits.yml` valida o formato dos commits do PR (exceto Dependabot e o PR de release).
- Merge commit (preserva os commits atômicos da branch). Apagar a branch após o merge.
- Release: PR `develop` → `main`, atualizando `CHANGELOG.md` e criando a tag `vX.Y.Z`.

## Issues

- Templates em `.github/ISSUE_TEMPLATE/`: `🚀 Feature / Task` e `🐞 Relatar problema`.
- Toda feature tem critérios de aceite verificáveis.
- Labels mínimas: `feature`, `bug`, `docs`, `chore`, `blocked` (criadas pelo `setup-repo.sh`).
- Issues em branco estão desabilitadas (`.github/ISSUE_TEMPLATE/config.yml`).

## Versionamento e changelog

- [SemVer](https://semver.org/lang/pt-BR/); `0.x` até o primeiro release considerado estável.
- `CHANGELOG.md` no formato [Keep a Changelog](https://keepachangelog.com/pt-BR/1.1.0/), com seção `[Não lançado]` atualizada a cada PR relevante.

## Documentação

- `README.md`: o que é, como rodar, como testar — curto.
- `AGENTS.md`: contexto, comandos e regras para agentes de IA.
- `docs/DECISOES.md`: decisões de produto e técnicas, numeradas (`D01`, `D02`…).
- `docs/adr/ADR-NNN-titulo.md`: decisões de arquitetura com contexto, opções e consequências (modelo em `docs/adr/ADR-000-modelo.md`).

## Specs (tlc-spec)

Features trabalhadas com agentes usam o tlc-spec em `.specs/features/<issue>-<slug>/`.

- **Versionados**, no mesmo PR do código: `spec.md` (requisitos e critérios de aceite), `context.md` (respostas do usuário às áreas cinzentas), `design.md` e `captures/` (telas de referência, exceto `*.running.png`).
- **Locais** (ignorados no `.gitignore`): `STATE.md`, `LESSONS.md`, `lessons.json`, `tasks.md`, `proofs.md`, `screens.md`, `verification.md` — estado de sessão.
- Uma fonte por assunto:
  - Issue: o quê, curto, com link para a `spec.md`.
  - `spec.md`: detalhe e critérios de aceite; é o que os agentes leem.
  - Decisão arquitetural no `design.md` vira ADR; o design aponta para ele.
  - Decisão do `STATE.md` que sobrevive à feature vira entrada em `docs/DECISOES.md`.

## Segurança

- Nunca commitar segredos; `.env` fica fora do git, `.env.example` documenta as variáveis.
- Três camadas contra segredos:
  1. `.githooks/pre-commit`: betterleaks no que está staged, antes do commit (`git config core.hooksPath .githooks` em cada clone).
  2. Push protection do GitHub: bloqueia o push no servidor (ativado pelo `setup-repo.sh`).
  3. `.github/workflows/security.yml`: betterleaks (sucessor do gitleaks) no histórico, a cada PR.
- Falsos positivos vão no `prefilter` do `.betterleaks.toml`, com justificativa; o hook e o CI usam o mesmo arquivo.
- Dependabot (`.github/dependabot.yml`) atualiza GitHub Actions e as dependências da stack semanalmente.
- Credenciais de teste são fictícias e marcadas como tal.
