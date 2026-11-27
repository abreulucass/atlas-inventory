// Fails when the browser bundle leaks the backend: build with ATLAS_API_URL=http://atlas-api.invalid:8080 first.
import { readdirSync, readFileSync } from "node:fs";
import { join } from "node:path";

const DIR = ".next/static";
const FORBIDDEN = ["atlas-api.invalid", "ATLAS_API_URL", "localhost:8080", "/api/auth/"];

const files = readdirSync(DIR, { recursive: true, withFileTypes: true })
  .filter((entry) => entry.isFile())
  .map((entry) => join(entry.parentPath, entry.name));

const leaks = files.flatMap((file) => {
  const content = readFileSync(file, "utf8");
  return FORBIDDEN.filter((needle) => content.includes(needle)).map((needle) => `${file}: ${needle}`);
});

if (leaks.length > 0) {
  console.error(`Bundle leaks the backend:\n${leaks.join("\n")}`);
  process.exit(1);
}
console.log(`check-bundle: ${files.length} files in ${DIR}, no backend leak.`);
