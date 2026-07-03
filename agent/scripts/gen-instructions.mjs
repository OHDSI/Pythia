#!/usr/bin/env -S deno run --allow-read --allow-write
// Generates agent/plugin/agent/instructions.md from the compiled
// pythia.prompt/base-prompt string, exported as `instructions` from the
// :tools shadow-cljs module (see agent/shadow-cljs.edn). Run under deno with
// the eve/tools stub import map for the same reason as gen-wrappers.mjs: the
// compiled out/tools.js bundle has a bare `eve/tools` import that only
// resolves via an import map.
import { instructions } from "../out/tools.js";

if (typeof instructions !== "string" || instructions.length < 1000) {
  console.error(
    `gen-instructions: expected a substantial instructions string from out/tools.js, got ${
      typeof instructions
    } of length ${instructions?.length ?? "n/a"}`,
  );
  Deno.exit(1);
}

const outPath = new URL("../plugin/agent/instructions.md", import.meta.url);
await Deno.mkdir(new URL(".", outPath), { recursive: true });
await Deno.writeTextFile(outPath, instructions);

console.log(`gen-instructions: wrote ${outPath.pathname} (${instructions.length} chars)`);
