#!/usr/bin/env node
// Local sidecar proxy used only as a fallback for `run-evals.sh`.
//
// The canonical eval URL (https://localhost/WebAPI/trex/pythia) goes through
// trex's bao agent proxy, which — in the currently pinned trexsql image —
// 500s on bodyless GETs (/eve/v1/health, /eve/v1/info), blocking
// `eve eval --url` (it polls those first). The fix is committed upstream on
// trex branch fix/agent-proxy-get-body but hasn't reached a released image
// yet. This proxy forwards straight to the agent mount
// (http://localhost:8001/plugins/ohdsi/pythia) and injects the `apikey`
// header eve itself cannot send. Delete this file once the pinned trexsql
// image includes the agent-proxy GET fix.
import http from "node:http";

const PORT = Number(process.env.EVAL_PROXY_PORT || 8901);
const UPSTREAM = process.env.EVAL_PROXY_UPSTREAM ||
  "http://localhost:8001/plugins/ohdsi/pythia";
const APIKEY = process.env.EVAL_PROXY_APIKEY;

if (!APIKEY) {
  console.error(
    "eval-auth-proxy: EVAL_PROXY_APIKEY is required (service-role key) — refusing to start",
  );
  process.exit(1);
}

const upstreamUrl = new URL(UPSTREAM);

const basePath = upstreamUrl.pathname.replace(/\/$/, "");

const server = http.createServer((req, res) => {
  const target = new URL(basePath + req.url, upstreamUrl.origin);
  const headers = { ...req.headers, host: upstreamUrl.host, apikey: APIKEY };

  const upstreamReq = http.request(
    target,
    { method: req.method, headers },
    (upstream) => {
      res.writeHead(upstream.statusCode || 502, upstream.headers);
      upstream.pipe(res);
    },
  );

  upstreamReq.on("error", (err) => {
    if (!res.headersSent) {
      res.writeHead(502, { "content-type": "text/plain" });
    }
    res.end(`eval-auth-proxy: upstream error: ${err.message}`);
  });

  req.pipe(upstreamReq);
});

server.listen(PORT, "127.0.0.1", () => {
  console.error(
    `eval-auth-proxy: listening on 127.0.0.1:${PORT} -> ${UPSTREAM}`,
  );
});
