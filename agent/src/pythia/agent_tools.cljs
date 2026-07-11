(ns pythia.agent-tools
  "Adapter from plain CLJS tool maps (pythia.tools/all) to trex's eve
   `defineTool`. The legacy Vercel-AI-SDK adapter (pythia.tools.registry)
   and the standalone request loop (pythia.entry / pythia.sdk) have been
   deleted — trex's shared agent runtime (core/server/agents/ in the trex
   repo: model loop, sessions, streaming) now owns what those used to do.

   A tool map is {:name :description :schema (JSON Schema as CLJS data)
   :run (fn [args ctx])}. A tool map WITHOUT :run is a client-side
   proposal tool: the eve tool def is built with `:clientOnly true` and
   no `:execute` — the host forwards the call to the frontend.

   eve passes a ToolContext as the second `execute` arg: a JS object
   {bearerToken, sessionId, metadata} where metadata is
   {sourceKey?, context? {route, artifact}, plan?}. `->tool-ctx` rebuilds
   the legacy ctx shape {:auth :source-key :plan} that `:run` functions
   expect (same shape entry.cljs built pre-port), reading every JS
   boundary field with `unchecked-get` (:advanced rename safety)."
  (:require ["eve/tools" :refer [defineTool]]
            [pythia.tools :as tools]))

(defn- ->tool-ctx
  "Rebuild the legacy per-request ctx {:auth :source-key :plan} from the
   eve ToolContext JS object. :auth re-adds the \"Bearer \" prefix eve
   strips off; :source-key defaults to \"EUNOMIA\"; :plan is keywordized
   from ToolContext.metadata.plan."
  [^js ctx-js]
  (let [bearer (unchecked-get ctx-js "bearerToken")
        metadata (unchecked-get ctx-js "metadata")
        source-key (and metadata (unchecked-get metadata "sourceKey"))
        plan (and metadata (unchecked-get metadata "plan"))]
    {:auth (when bearer (str "Bearer " bearer))
     :source-key (or source-key "EUNOMIA")
     :plan (when plan (js->clj plan :keywordize-keys true))}))

(defn ->eve-tool
  "Build one eve tool def from a plain CLJS tool map. Server tools
   (`:run` present) get an `:execute` that keywordizes `input`, rebuilds
   the legacy ctx from the eve ToolContext, calls `:run`, and `clj->js`'s
   the (possibly Promise-wrapped) result. Client tools (no `:run`) get
   `:clientOnly true` and no `:execute`."
  [{:keys [description schema run]}]
  (defineTool
   (if run
     #js {:description description
          :inputSchema (clj->js schema)
          :execute (fn [input ctx-js]
                     (-> (js/Promise.resolve
                          (run (js->clj input :keywordize-keys true)
                               (->tool-ctx ctx-js)))
                         (.then clj->js)))}
     #js {:description description
          :inputSchema (clj->js schema)
          :clientOnly true})))

(defn tools-object
  "Build the JS object {tool-name -> eve tool def} for every tool map."
  [tool-maps]
  (let [obj #js {}]
    (doseq [{:keys [name] :as m} tool-maps]
      (unchecked-set obj name (->eve-tool m)))
    obj))

(def tools
  "eve tool defs for every pythia.tools/all entry, keyed by tool name."
  (tools-object tools/all))
