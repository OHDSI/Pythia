(ns pythia.tools.registry
  "Turn plain CLJS tool maps into Vercel AI SDK tools and assemble the
   `tools` object passed to streamText. A tool map is
   {:name :description :schema (JSON Schema as CLJS data) :run (fn [args ctx])}.

   A tool map WITHOUT a `:run` is a CLIENT-SIDE proposal tool: the SDK tool is
   built with NO `:execute`, so streamText emits the `tool-input-available`
   call and ends the step with finishReason:\"tool-calls\" (no server output).
   The frontend renders the accept/reject card and posts the result back."
  (:require ["ai" :refer [tool jsonSchema]]))

(defn ->sdk-tool
  "Build one server-side SDK tool. Its `execute` keywordizes the model's args,
   calls `(run args ctx)`, and returns the result as JS (a promise becomes the
   tool result). `ctx` is the per-request tool context."
  [{:keys [description schema run]} ctx]
  (tool
   #js {:description description
        :inputSchema (jsonSchema (clj->js schema))
        :execute (fn [args]
                   (-> (js/Promise.resolve
                        (run (js->clj args :keywordize-keys true) ctx))
                       (.then clj->js)))}))

(defn ->client-sdk-tool
  "Build one client-side SDK tool — schema only, NO `:execute`. streamText
   emits the call and ends the step with finishReason:\"tool-calls\"; the
   frontend handles acceptance and posts the result back."
  [{:keys [description schema]}]
  (tool
   #js {:description description
        :inputSchema (jsonSchema (clj->js schema))}))

(defn tools-object
  "Build the JS object {tool-name -> sdk tool} for streamText :tools. Tool maps
   with a `:run` become server tools (with `:execute`); maps without `:run`
   become client-side proposal tools (no `:execute`)."
  [tool-maps ctx]
  (let [obj #js {}]
    (doseq [{:keys [name run] :as m} tool-maps]
      (unchecked-set obj name (if run
                                (->sdk-tool m ctx)
                                (->client-sdk-tool m))))
    obj))
