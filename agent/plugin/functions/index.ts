// Pythia agent edge function. Delegates to the shadow-cljs-compiled ESM
// `handler` (Web fetch handler returning a streaming Response). Mounted at
// /usr/src/plugins-dev/pythia-agent; served at /plugins/trexsql/agent/* on :8001.
//
// handler.js is the release build of agent/src/pythia/entry.cljs, copied here by
// `npm run sync` (see agent/README.md). Its bare "ai" / "@ai-sdk/amazon-bedrock"
// imports resolve via ./deno.json.
// Stash this module's URL (its dir holds handler.js and ./resources/) so the
// compiled handler can resolve bundled resource files regardless of the worker
// CWD. Set BEFORE importing handler.js so it is present at module load. Kept
// out of the Closure-compiled handler because `import.meta` breaks :advanced.
(globalThis as Record<string, unknown>).__pythiaModuleUrl = import.meta.url;

import { handler } from "./handler.js";

Deno.serve((req: Request): Response | Promise<Response> => {
  const { pathname } = new URL(req.url);

  // Health probe — no model call.
  if (req.method === "GET" && pathname.endsWith("/health")) {
    return Response.json({ ok: true, agent: "pythia" });
  }

  // Chat is the only POST route; accept it at /agent and /agent/chat.
  if (req.method === "POST") {
    return handler(req);
  }

  return new Response("Not Found", { status: 404 });
});
