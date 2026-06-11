(ns pythia.config
  "Runtime config from the Deno environment.")

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

(defn model-id []
  (env-or "BAO_AGENT_MODEL" "us.anthropic.claude-sonnet-4-6"))

(defn region []
  (env-or "AWS_REGION" "us-east-1"))

(defn bearer-token []
  (env "AWS_BEARER_TOKEN_BEDROCK"))

(defn webapi-url []
  (env-or "BAO_AGENT_WEBAPI_URL" "http://localhost:8080/WebAPI"))
