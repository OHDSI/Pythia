(ns pythia.resources
  "Read bundled resource files (EDN corpora) from the mounted worker.

   The compiled handler.js lives at <plugin>/functions/handler.js and the
   resources are copied alongside it under <plugin>/functions/resources/. We
   resolve each path relative to the module via import.meta.url so the lookup
   works regardless of the worker's CWD.")

(defn- module-url
  "Base URL the resources sit next to. The ESM entry (pythia.entry) stashes its
   own `import.meta.url` on globalThis at load; we read it here. Keeping the
   `import.meta` literal out of this namespace lets the CommonJS :node-test build
   parse it (tests mock read-text and never reach this)."
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
  (let [from-url (try (-> (js/URL. (str "resources/" rel) (module-url)) (.-pathname))
                      (catch :default _ nil))
        fn-path (env "TREX_FUNCTION_PATH")
        cwd (when (exists? js/Deno) (js/Deno.cwd))]
    (->> [from-url
          (when fn-path (str (.replace fn-path #"/$" "") "/resources/" rel))
          (str "/usr/src/plugins-dev/pythia-agent/functions/resources/" rel)
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
