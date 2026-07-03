#!/usr/bin/env -S deno run --allow-read
// Post-`npm run dist` smoke test: imports every generated
// agent/plugin/agent/tools/*.js wrapper (the trex agents loader's exact
// discovery rule — files directly in tools/, _build/ is a directory and
// therefore invisible) and asserts the shape core/server/agents/loader.ts
// requires, plus clientOnly/execute presence for 5 spot-checked tools
// (matching the P2 agent_tools_test.cljs expectations).
//
// Run under deno with the eve/tools stub import map (scripts/import-map.deno.json)
// — same reason as gen-wrappers.mjs/gen-instructions.mjs: each wrapper's
// _build/tools.js re-export chain still carries the bare `eve/tools` import.
const EXPECTED_TOOL_COUNT = 44;

// name -> expected clientOnly-ness, per pythia.tools.client/client-tools
// (clientOnly) vs pythia.tools/server (execute) in agent/src/pythia/tools.cljs.
const SPOT_CHECK = {
  add_criterion: "client",
  navigate_to: "client",
  save_cohort: "client",
  search_concepts: "server",
  search_ohdsi_book: "server",
};

const toolsDir = new URL("../plugin/agent/tools/", import.meta.url);

const files = [];
for await (const entry of Deno.readDir(toolsDir)) {
  // Mirrors core/server/agents/loader.ts: only FILES matching
  // *.{ts,js,mts,mjs} directly in tools/ are discovered; _build/ (a
  // directory) is skipped, matching how the real loader ignores it too.
  if (entry.isFile && /\.(js|mjs)$/.test(entry.name)) files.push(entry.name);
}
files.sort();

if (files.length !== EXPECTED_TOOL_COUNT) {
  console.error(`smoke: expected ${EXPECTED_TOOL_COUNT} tool wrapper files, got ${files.length}: ${files.join(", ")}`);
  Deno.exit(1);
}

let failures = 0;
const fail = (name, msg) => {
  failures++;
  console.error(`smoke: ${name}: ${msg}`);
};

for (const file of files) {
  const name = file.replace(/\.(js|mjs)$/, "");
  const mod = await import(new URL(file, toolsDir).href);
  const def = mod.default;

  if (!def) {
    fail(name, "no default export");
    continue;
  }
  if (def.__trexTool !== true) fail(name, "missing __trexTool === true brand (must be built by defineTool)");
  if (typeof def.description !== "string" || def.description.length === 0) fail(name, "empty/missing description");
  if (!def.inputSchema || def.inputSchema.type !== "object") fail(name, 'inputSchema.type must be "object"');

  const expected = SPOT_CHECK[name];
  if (expected === "client") {
    if (def.clientOnly !== true) fail(name, "expected clientOnly === true");
    if (def.execute !== undefined) fail(name, "expected no execute (clientOnly tool)");
  } else if (expected === "server") {
    if (typeof def.execute !== "function") fail(name, "expected execute to be a function");
    if (def.clientOnly !== undefined) fail(name, "expected no clientOnly (server tool)");
  }
}

if (failures > 0) {
  console.error(`smoke: ${failures} failure(s) across ${files.length} tools`);
  Deno.exit(1);
}

console.log(`smoke: ${files.length} tool wrappers OK (${Object.keys(SPOT_CHECK).length} spot-checked for clientOnly/execute)`);
