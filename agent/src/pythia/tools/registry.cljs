(ns pythia.tools.registry
  "Turn plain CLJS tool maps into Vercel AI SDK tools and assemble the
   `tools` object passed to streamText. A tool map is
   {:name :description :schema (JSON Schema as CLJS data) :run (fn [args ctx])}."
  (:require ["ai" :refer [tool jsonSchema]]))

(defn ->sdk-tool
  "Build one SDK tool. Its `execute` keywordizes the model's args, calls
   `(run args ctx)`, and returns the result as JS (a promise becomes the
   tool result). `ctx` is the per-request tool context."
  [{:keys [description schema run]} ctx]
  (tool
   #js {:description description
        :inputSchema (jsonSchema (clj->js schema))
        :execute (fn [args]
                   (-> (js/Promise.resolve
                        (run (js->clj args :keywordize-keys true) ctx))
                       (.then clj->js)))}))

(defn tools-object
  "Build the JS object {tool-name -> sdk tool} for streamText :tools."
  [tool-maps ctx]
  (let [obj #js {}]
    (doseq [{:keys [name] :as m} tool-maps]
      (unchecked-set obj name (->sdk-tool m ctx)))
    obj))
