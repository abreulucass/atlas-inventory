#!/usr/bin/env bash
# Configures a GitHub repository per docs/CONVENCOES.md.
# Usage: scripts/setup-repo.sh <owner/repo>   (needs authenticated gh and main already pushed)
set -euo pipefail

repo="${1:?usage: scripts/setup-repo.sh <owner/repo>}"

# Minimal labels (issue templates apply feature/bug).
gh label create feature --repo "$repo" --color 1D76DB --description "Nova funcionalidade" --force
gh label create bug     --repo "$repo" --color D73A4A --description "Comportamento incorreto" --force
gh label create docs    --repo "$repo" --color 0075CA --description "Documentação" --force
gh label create chore   --repo "$repo" --color CFD3D7 --description "Manutenção e configuração" --force
gh label create blocked --repo "$repo" --color B60205 --description "Aguardando dependência" --force

# Drop GitHub's default labels that overlap or go unused (documentation≈docs, enhancement≈feature...).
for label in documentation enhancement duplicate "good first issue" "help wanted" invalid question wontfix accessibility; do
  gh label delete "$label" --repo "$repo" --yes 2>/dev/null || true
done

# Create develop from main if missing.
if ! gh api "repos/$repo/branches/develop" --silent 2>/dev/null; then
  sha="$(gh api "repos/$repo/git/ref/heads/main" --jq .object.sha)"
  gh api "repos/$repo/git/refs" -f ref=refs/heads/develop -f sha="$sha" --silent
fi

# develop as default: GitHub only closes "Fixes #N" issues on merges into the default branch.
gh repo edit "$repo" \
  --default-branch develop \
  --enable-merge-commit \
  --enable-squash-merge=false \
  --enable-rebase-merge=false \
  --delete-branch-on-merge

# Server-side secret blocking (free on public repos): rejects pushes containing known provider secrets.
gh api -X PATCH "repos/$repo" --silent --input - <<'JSON'
{
  "security_and_analysis": {
    "secret_scanning": { "status": "enabled" },
    "secret_scanning_push_protection": { "status": "enabled" }
  }
}
JSON

# PR-only, no force push or deletion. enforce_admins=false lets the owner push trivial fixes to develop.
# Required checks are left out: their names depend on each project's workflows.
# shortcut: fails on private repos without GitHub Pro; make the repo public or upgrade.
for branch in main develop; do
  gh api -X PUT "repos/$repo/branches/$branch/protection" --input - --silent <<'JSON'
{
  "required_status_checks": null,
  "enforce_admins": false,
  "required_pull_request_reviews": { "required_approving_review_count": 0 },
  "restrictions": null,
  "allow_force_pushes": false,
  "allow_deletions": false
}
JSON
done

echo "OK: $repo configured."
