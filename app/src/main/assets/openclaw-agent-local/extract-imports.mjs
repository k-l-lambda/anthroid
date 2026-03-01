import fs from "node:fs";
import path from "node:path";

const dir = "agent";
const result = {};

for (const f of fs.readdirSync(dir).filter(f => f.endsWith(".mjs"))) {
  const content = fs.readFileSync(path.join(dir, f), "utf-8");

  // Named imports: import { X, Y as Z } from "pkg"
  for (const m of content.matchAll(/import\s*\{([^}]+)\}\s*from\s*"([^"]+)"/g)) {
    const pkg = m[2];
    if (pkg.startsWith(".") || pkg.startsWith("node:")) continue;
    if (!result[pkg]) result[pkg] = { named: new Set(), hasDefault: false };
    for (const raw of m[1].split(",")) {
      const name = raw.trim().split(/\s+as\s+/)[0].trim();
      if (name) result[pkg].named.add(name);
    }
  }

  // Default imports: import X from "pkg"
  for (const m of content.matchAll(/import\s+(\w+)\s+from\s*"([^"]+)"/g)) {
    const pkg = m[2];
    if (pkg.startsWith(".") || pkg.startsWith("node:")) continue;
    if (!result[pkg]) result[pkg] = { named: new Set(), hasDefault: false };
    result[pkg].hasDefault = true;
  }
}

// Output as JS code for stub generation
for (const [pkg, info] of Object.entries(result).sort()) {
  const names = [...info.named].sort();
  console.log(`${pkg}:`);
  console.log(`  named: [${names.map(n => `"${n}"`).join(", ")}]`);
  console.log(`  default: ${info.hasDefault}`);
}
