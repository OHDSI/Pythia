(ns pythia.entry
  "ESM entry: exports `handler`, a Web fetch handler the mounted Deno function
   delegates to. Reads `messages` + `context` from the request JSON, assembles
   the system prompt, streams a tool-less reply. 500 on error."
  (:require [pythia.prompt :as prompt]
            [pythia.sdk :as sdk]))

(defn- ->context
  "Pull {:route :artifact} out of the request body's `context` object using
   string keys (unchecked-get survives :advanced rename)."
  [body]
  (let [ctx (unchecked-get body "context")]
    (when ctx
      (let [artifact (unchecked-get ctx "artifact")]
        {:route (unchecked-get ctx "route")
         :artifact (when artifact
                     {:kind (unchecked-get artifact "kind")
                      :id (unchecked-get artifact "id")
                      :name (unchecked-get artifact "name")})}))))

(defn- error-response [e]
  (js/Response. (js/JSON.stringify #js {:error (str (or (.-message e) e))})
                #js {:status 500
                     :headers #js {"content-type" "application/json"}}))

(defn handler [^js request]
  (-> (.json request)
      (.then (fn [body]
               (let [messages (unchecked-get body "messages")
                     system (prompt/system-prompt (->context body))]
                 (sdk/stream-chat messages system))))
      (.catch error-response)))
