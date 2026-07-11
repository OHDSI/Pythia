(ns pythia.resources
  "Read bundled resource files (EDN corpora) from the mounted trex agent
   worker.

   The agent directory trex loads is <plugin>/agent/ (TREX_AGENT_DIR — see
   core/server/agents/service/index.ts), with resources synced alongside the
   generated tool wrappers under <plugin>/agent/resources/ (see
   agent/package.json's `sync` script). Each generated tool wrapper
   (agent/tools/<name>.js) stashes its own `import.meta.url` on globalThis at
   load, so we can also resolve resources relative to the module regardless
   of the worker's CWD.")

(defn- module-url
  "Base URL a generated tool wrapper sits next to (.../agent/tools/<name>.js).
   Each wrapper stashes its own `import.meta.url` on globalThis at load; we
   read it here. Keeping the `import.meta` literal out of this namespace lets
   the CommonJS :node-test build parse it (tests mock read-text and never
   reach this)."
  []
  (or (unchecked-get js/globalThis "__pythiaModuleUrl")
      ;; Fallback: resolve relative to the worker CWD.
      (str "file://" (when (exists? js/Deno) (js/Deno.cwd)) "/")))

(defn- env [k]
  (when (exists? js/Deno)
    (some-> js/Deno .-env (.get k))))

(defn candidate-paths
  "Filesystem paths to try for resources/<rel>, most-specific first."
  [rel]
  (let [agent-dir (env "TREX_AGENT_DIR")
        ;; module-url is .../agent/tools/<name>.js; resources/ is a sibling
        ;; of tools/, i.e. ../resources/<rel> from the wrapper's own URL.
        from-url (try (-> (js/URL. (str "../resources/" rel) (module-url)) (.-pathname))
                      (catch :default _ nil))
        fn-path (env "TREX_FUNCTION_PATH")
        cwd (when (exists? js/Deno) (js/Deno.cwd))]
    (->> [(when agent-dir (str (.replace agent-dir #"/$" "") "/resources/" rel))
          from-url
          (when fn-path (str (.replace fn-path #"/$" "") "/resources/" rel))
          (when cwd (str cwd "/resources/" rel))
          (str "resources/" rel)]
         (remove nil?)
         distinct
         vec)))

(defn read-text
  "Read a bundled resource as text. Tries each candidate path in turn; resolves
   to the first readable file's contents, or nil if none can be read."
  [rel]
  (let [paths (candidate-paths rel)]
    (letfn [(try-next [i]
              (if (>= i (count paths))
                (js/Promise.resolve nil)
                (-> (js/Deno.readTextFile (nth paths i))
                    (.catch (fn [_] (try-next (inc i)))))))]
      (try-next 0))))
