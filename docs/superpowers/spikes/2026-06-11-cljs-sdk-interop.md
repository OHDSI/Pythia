# SPIKE: ClojureScript <-> Vercel AI SDK interop under Deno (bearer-token Bedrock)

**Task 0.3 — highest-risk investigative spike.**
Date: 2026-06-11. Workdir: `/Users/ph/code/trex-dx`. Project: `agent/`.

## Verdict

**ALL THREE THINGS PROVEN, END TO END. SUCCESS.**

1. shadow-cljs `:target :esm` compiles ClojureScript to ESM that Deno imports and runs. YES.
2. From CLJS we call `streamText` (`ai`) + `createAmazonBedrock` (`@ai-sdk/amazon-bedrock`) + one `tool()`,
   and return `result.toUIMessageStreamResponse()`. YES.
3. **CRITICAL:** the provider authenticates with the **Bedrock long-term API key (bearer token)** — NOT
   SigV4 — and actually streams tokens AND runs the full tool-call loop. **YES.**

Did the SDK stream from Bedrock via the bearer token? **YES.**
How? **Native provider option** `createAmazonBedrock({ apiKey: <bearer>, region })`. No custom-fetch
workaround was needed for auth. (The provider also auto-reads `AWS_BEARER_TOKEN_BEDROCK` from env, but
under Deno we pass `apiKey` explicitly because `process.env` is not reliably populated.)

Which shadow-cljs target worked? **`:target :esm`** (with `:js-provider :import` so npm deps stay external).

## Environment (versions actually present)

```
deno 2.8.1 (aarch64-apple-darwin)
node v26.0.0
npx 11.12.1
openjdk 21.0.11 (Homebrew)   # shadow-cljs needs a JVM; present.
```
There is NO `clojure`/`clj` CLI on PATH — see "shadow-cljs config gotcha" below.

Installed npm versions that work:
```
ai                        6.0.201
@ai-sdk/amazon-bedrock     4.0.115   # IMPORTANT — see "provider version" finding
zod (transitive)           v4
```

## The working build

### `agent/shadow-cljs.edn`
```edn
{:source-paths ["src"]
 :dependencies [[org.clojure/clojurescript "1.11.132"]]
 :builds
 {:fn
  {:target :esm
   :output-dir "out"
   :modules {:handler {:exports {handler pythia.spike/handler}}}
   :js-options {:js-provider :import
                :keep-as-import #{"ai" "@ai-sdk/amazon-bedrock"}}
   :compiler-options {:infer-externs :auto}}}}
```

### Build command
```
cd agent && npx shadow-cljs release fn
# => [:fn] Build completed. (47 files, 2 compiled, 0 warnings)
```

### shadow-cljs config gotcha (would have BLOCKED us)
There is no `clojure` CLI on this machine. If `shadow-cljs.edn` uses `:deps {:aliases [...]}` it shells out
to `clojure` and dies with `Executable 'clojure' not found on system path.` Fix: use shadow's *own*
dependency management via top-level `:source-paths` + `:dependencies` (it then runs purely under node+JVM via
the bundled launcher). Note shadow ignores the pinned clojurescript version and uses its built-in one — that
is fine.

### `:js-provider :import` is essential
By default `:target :esm` in *release* mode runs the npm deps through the Closure bundler and **inlines**
`ai` + `@ai-sdk/amazon-bedrock` into one ~1.1 MB `handler.js`. That bundle then crashed at runtime
(`TypeError: R.some is not a function`) because Closure mangled the third-party code. Setting
`:js-options {:js-provider :import :keep-as-import #{"ai" "@ai-sdk/amazon-bedrock"}}` makes shadow emit bare
`import * as ... from "ai"` / `from "@ai-sdk/amazon-bedrock"` and leave the packages external (resolved at
runtime by Deno's import map). Output drops to ~1.2 KB and works. This is also what the mounted-function model
wants (the npm deps come from the devx `deno.json` map, not bundled into the fn).

## The CLJS interop that WORKED — `agent/src/pythia/spike.cljs`

```clojure
(ns pythia.spike
  (:require ["ai" :refer [streamText tool convertToModelMessages stepCountIs jsonSchema]]
            ["@ai-sdk/amazon-bedrock" :refer [createAmazonBedrock]]))

;; ---- Bearer-token auth (THE CRUX) ----
;; @ai-sdk/amazon-bedrock natively reads AWS_BEARER_TOKEN_BEDROCK from env,
;; but under Deno process.env may not be populated, so pass apiKey explicitly.
(defn- bearer-token []
  (or (when (exists? js/process)
        (unchecked-get (unchecked-get js/process "env") "AWS_BEARER_TOKEN_BEDROCK"))
      (when (exists? js/Deno) (.. js/Deno -env (get "AWS_BEARER_TOKEN_BEDROCK")))))

(defn- bedrock []
  (createAmazonBedrock #js {:region "us-east-1"
                            :apiKey (bearer-token)}))      ; native bearer option

(def get-time
  (tool #js {:description "Return the current server time as a fixed string."
             :inputSchema (jsonSchema #js {:type "object" :properties #js {} :additionalProperties false})
             :execute (fn [_args] (js/Promise.resolve "2026-06-11T12:00:00Z"))}))

(defn handler [^js request]
  (-> (.json request)
      (.then (fn [body]
               ;; convertToModelMessages is ASYNC in AI SDK v6 — must await.
               (convertToModelMessages (unchecked-get body "messages"))))
      (.then (fn [model-messages]
               (let [model ((bedrock) "us.anthropic.claude-sonnet-4-6")
                     result (streamText
                              #js {:model model
                                   :system "You are a terse assistant. Follow the user's instructions exactly."
                                   :messages model-messages
                                   :tools #js {:get_time get-time}
                                   :stopWhen (stepCountIs 5)})]
                 (.toUIMessageStreamResponse result))))))
```

### Two CLJS interop traps found the hard way
- **`unchecked-get`, not `(.-messages body)`.** Under `:advanced` optimization Closure renames property
  access on *external* JS objects it doesn't have externs for. `(.-messages body)` compiled to `body.i`
  (and `.env.AWS_BEARER_TOKEN_BEDROCK` to `.env.h`), so `messages` came through as `undefined`
  (`messages is not iterable`). Use `(unchecked-get body "messages")` (string key, never renamed). Same for
  the env read.
- **`convertToModelMessages` is async (Promise) in AI SDK v6.** Passing its return value straight into
  `streamText({messages ...})` yields `messages.some is not a function` because `messages` is a Promise, not
  an array. Await it (second `.then`) before calling `streamText`.

The provider construction `((bedrock) "us.anthropic.claude-sonnet-4-6")` is the AI SDK pattern
`createAmazonBedrock(settings)(modelId)` — the provider is a callable that returns a `LanguageModel`.

## deno.json import map (`agent/deno.json`)

shadow's `:js-provider :import` emits bare specifiers, so Deno needs a map from bare name -> `npm:` specifier:
```json
{
  "imports": {
    "ai": "npm:ai@^6",
    "@ai-sdk/amazon-bedrock": "npm:@ai-sdk/amazon-bedrock@^4.0.115"
  }
}
```
Run with: `deno run -A --config deno.json <driver>.ts`. Deno resolves the npm packages from
`agent/node_modules` (installed by `npm install`).

**Mounted-function note:** the real fn will rely on the **devx framework's** `deno.json`
(`/Users/ph/code/trex/plugins/devx/functions/deno.json`), which already maps `npm:ai` and
`npm:@ai-sdk/amazon-bedrock` to `@latest`. Two caveats for the integration task:
1. That map keys are `"npm:ai"` / `"npm:@ai-sdk/amazon-bedrock"`, but shadow emits **bare** `"ai"` /
   `"@ai-sdk/amazon-bedrock"`. The devx map will need bare-name entries added (or shadow configured to emit
   `npm:`-prefixed specifiers). Verify when mounting.
2. `@latest` for `@ai-sdk/amazon-bedrock` currently resolves to the `4.0.115` line (`latest` tag = 4.0.115),
   which is the fixed version — good — but pin it to avoid drift back into the broken 3.x `ai-v5` line.

## Proof: captured SSE frames (the exact shapes `@ai-sdk/vue` useChat consumes)

Driver `agent/spike_serve.ts` imports the compiled `handler`, POSTs
`{"messages":[{"id":"1","role":"user","parts":[{"type":"text","text":"Say the word pong, then call get_time."}]}]}`,
and prints the streamed `Response` body. Run:
```
cd agent && set -a; . ../.env; set +a; deno run -A --config deno.json spike_serve.ts
```
Output (`STATUS 200`, `content-type: text/event-stream`):
```
data: {"type":"start"}
data: {"type":"start-step"}
data: {"type":"text-start","id":"0"}
data: {"type":"text-delta","id":"0","delta":"P"}
data: {"type":"text-delta","id":"0","delta":"ong."}
data: {"type":"text-end","id":"0"}
data: {"type":"tool-input-start","toolCallId":"tooluse_OZwOPXDtMBIE32L1ElU7yO","toolName":"get_time"}
data: {"type":"tool-input-available","toolCallId":"tooluse_OZwOPXDtMBIE32L1ElU7yO","toolName":"get_time","input":{}}
data: {"type":"tool-output-available","toolCallId":"tooluse_OZwOPXDtMBIE32L1ElU7yO","output":"2026-06-11T12:00:00Z"}
data: {"type":"finish-step"}
data: {"type":"start-step"}
data: {"type":"text-delta","id":"0","delta":"The current server time is **2026-06-11T12:00:00Z**."}
data: {"type":"finish-step"}
data: {"type":"finish","finishReason":"stop"}
data: [DONE]
```
This is the complete loop: model streams "Pong.", calls the tool, the tool's output is fed back, the model
streams a final answer, and finishes with `finishReason:"stop"`. Bearer-token auth, streaming, and tool calls
all verified.

## The one real bug found (and fixed): provider version

`@ai-sdk/amazon-bedrock` dist-tags are split by AI SDK major:
```
ai-v5 -> 3.0.101     # for ai@5 — DO NOT use with ai@6
ai-v6 -> 4.0.81
latest -> 4.0.115    # what we pin
```
Installing `@ai-sdk/amazon-bedrock@^3` (the originally-specified range) pulls the **ai-v5** build against
`ai@6`. Worse, both `3.0.101` and `4.0.81` carry a zod-v4 bug: their stream schema declares
`BedrockToolUseSchema.input` as `z.unknown()` (required). Bedrock's `contentBlockStart` tool-use event has no
`input` (it arrives later via deltas), so under zod v4 validation throws
`AI_TypeValidationError: ... path ["contentBlockStart","start","toolUse","input"] expected nonoptional`.
Text streamed fine but **every tool call errored**. Reproduced in pure TypeScript too (so NOT a CLJS issue).

Fix: `@ai-sdk/amazon-bedrock@4.0.115` changed it to `input: z.unknown().optional()`. With that version the
tool loop works (frames above). **Pin `@ai-sdk/amazon-bedrock` to `^4.0.115`** and keep `ai` at `^6`.

## Files (all under `/Users/ph/code/trex-dx/agent`)
- `package.json` — type module; dev `shadow-cljs`; deps `ai@^6`, `@ai-sdk/amazon-bedrock@^4.0.115`.
- `shadow-cljs.edn` — `:target :esm`, `:js-provider :import`.
- `deps.edn` — clojurescript dep (unused by shadow's own launcher but kept for tooling/REPL).
- `deno.json` — bare-name -> npm: import map.
- `src/pythia/spike.cljs` — the handler.
- `spike_serve.ts` — Deno driver that proves the stream.
- `.gitignore` — ignores `node_modules/`, `out/`, `.shadow-cljs/`.

## Open items for the integration task
- Wire shadow's bare `"ai"` / `"@ai-sdk/amazon-bedrock"` imports to the devx `deno.json` map (currently keyed
  with the `npm:` prefix). Either add bare entries there or have shadow emit `npm:`-prefixed specifiers.
- Pin the bedrock provider to `^4.0.115`+ everywhere (devx map uses `@latest`).
- `:advanced` property-rename hazard: any future interop reading fields off external JS objects must use
  `unchecked-get`/string keys or proper externs, not `(.-field obj)`.
