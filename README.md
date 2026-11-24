# project-template

Template de repositório com as convenções, CI e segurança dos projetos do portfólio.

## Novo projeto

```bash
gh repo create abreulucass/<projeto> --template abreulucass/project-template --public --clone
cd <projeto>
mv README.template.md README.md          # substitui este README pelo do projeto
git config core.hooksPath .githooks      # em cada clone: hooks de segredos e formato de commit
git commit -am "docs(docs): start project readme" && git push
scripts/setup-repo.sh abreulucass/<projeto>   # labels, develop padrão, proteções, push protection
```

Proteções de branch e push protection exigem repositório público (ou GitHub Pro).

## Conteúdo

| Arquivo | Uso |
|---|---|
| `docs/CONVENCOES.md` | Idioma, branches, commits, PRs, issues, changelog, segurança |
| `AGENTS.md` | Modelo de contexto para agentes de IA (preencher) |
| `docs/DECISOES.md`, `docs/adr/ADR-000-modelo.md` | Modelos de decisões e ADR |
| `CHANGELOG.md` | Modelo Keep a Changelog |
| `.github/pull_request_template.md` | Template de PR |
| `.github/ISSUE_TEMPLATE/` | Templates de feature e bug; issues em branco desabilitadas |
| `.github/workflows/commits.yml` | Valida o formato dos commits do PR |
| `.github/workflows/security.yml` | betterleaks no CI (auditoria de dependências por stack) |
| `.github/dependabot.yml` | Atualização semanal de Actions (acrescentar a stack) |
| `scripts/setup-repo.sh` | Labels, `develop` como padrão, proteções, push protection e modo de merge via `gh` |
| `.githooks/` | `pre-commit` (betterleaks no que está staged) e `commit-msg` (formato do commit) |
| `README.template.md` | Modelo do README do projeto (renomear para `README.md`) |
| `LICENSE` | MIT |
| `.gitignore`, `.gitattributes`, `.betterleaks.toml` | Higiene do repositório |

Não incluído: workflow de qualidade (build/testes), pois depende da stack de cada projeto.
