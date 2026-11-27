// Asserts the frontend CI workflow shape (FE-02/03). Usage: node scripts/check-workflow.mjs [file]
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";

import { parse } from "yaml";

const file =
  process.argv[2] ?? fileURLToPath(new URL("../../.github/workflows/frontend.yml", import.meta.url));
const wf = parse(readFileSync(file, "utf8"));
const errors = [];
const check = (ok, message) => ok || errors.push(message);
const same = (a, b) => JSON.stringify([...(a ?? [])].sort()) === JSON.stringify([...b].sort());

const PATHS = ["frontend/**", ".github/workflows/frontend.yml"];
check(wf.on?.pull_request && same(wf.on.pull_request.paths, PATHS), "pull_request with the frontend paths");
check(same(wf.on?.push?.branches, ["main", "develop"]), "push to main and develop");
check(same(wf.on?.push?.paths, PATHS), "push with the frontend paths");

// Each expected command must come after the previous one (steps flattened to run lines), in the given directory.
function steps(jobName, expected) {
  const job = wf.jobs?.[jobName];
  if (!job) return errors.push(`job ${jobName} missing`);
  const defaultDir = job.defaults?.run?.["working-directory"];
  const lines = job.steps.flatMap((s) =>
    typeof s.run === "string"
      ? s.run.split("\n").map((line) => ({ line: line.trim(), dir: s["working-directory"] ?? defaultDir }))
      : [],
  );
  let from = 0;
  for (const [pattern, dir] of expected) {
    const i = lines.findIndex((l, idx) => idx >= from && pattern.test(l.line) && l.dir === dir);
    if (check(i >= 0, `${jobName}: ${pattern} in ${dir ?? "the root"}, in order`)) from = i + 1;
  }
}

steps("check", [
  [/^npm ci$/, "frontend"],
  [/^npm run lint$/, "frontend"],
  [/^npm run typecheck$/, "frontend"],
  [/^npm test$/, "frontend"],
  [/^npm run build$/, "frontend"],
  [/^npm run check:bundle$/, "frontend"],
]);
steps("e2e", [
  [/docker compose up -d --wait/, "backend"],
  [/\.\/mvnw .*spring-boot:run/, "backend"],
  [/curl -fs http:\/\/localhost:8080\/actuator\/health/, undefined],
  [/npx playwright install/, "frontend"],
  [/npx playwright test/, "frontend"],
]);

if (errors.length) {
  console.error(`check:workflow failed for ${file}:\n- ${errors.join("\n- ")}`);
  process.exit(1);
}
console.log("check:workflow: ok");
