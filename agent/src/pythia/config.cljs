(ns pythia.config
  "Runtime config from the Deno environment.

   Model selection and Bedrock credentials moved out of here (task P3): the
   trex agents runtime resolver owns the model (agent.edn's :model, or
   TREX_AGENTS_DEFAULT_MODEL) and provider credentials (ANTHROPIC_API_KEY /
   AWS_BEARER_TOKEN_BEDROCK / etc, forwarded per-provider — see
   core/server/agents/README.md's \"Models and credentials\"). This namespace
   now only reads config tools still need directly: the WebAPI base URL.")

(defn- env
  "Read an env var via Deno (preferred) or Node process, nil if absent."
  [k]
  (or (when (exists? js/Deno)
        (.. js/Deno -env (get k)))
      (when (exists? js/process)
        (unchecked-get (unchecked-get js/process "env") k))))

(defn- env-or [k default]
  (let [v (env k)]
    (if (and v (pos? (count v))) v default)))

(defn webapi-url []
  (env-or "BAO_AGENT_WEBAPI_URL" "http://localhost:8080/WebAPI"))
