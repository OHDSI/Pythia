(ns pythia.entry
  "ESM entry: exports `handler`, a Web fetch handler the mounted Deno function
   delegates to. Reads `messages` + `context` from the request JSON, assembles
   the system prompt, streams a tool-less reply. 500 on error."
  (:require [pythia.prompt :as prompt]
            [pythia.sdk :as sdk]
            [pythia.tools :as tools]
            [pythia.tools.registry :as registry]))

(defn- ->context
  "Pull {:route :artifact :plan} out of the request body using string keys
   (unchecked-get survives :advanced rename)."
  [body]
  (let [ctx (unchecked-get body "context")
        plan (unchecked-get body "plan")]
    (when (or ctx plan)
      (let [artifact (and ctx (unchecked-get ctx "artifact"))]
        {:route (and ctx (unchecked-get ctx "route"))
         :artifact (when artifact
                     {:kind (unchecked-get artifact "kind")
                      :id (unchecked-get artifact "id")
                      :name (unchecked-get artifact "name")})
         :plan (when plan (js->clj plan :keywordize-keys true))}))))

(defn- header
  "Case-insensitively read a request header value (Web Headers lowercase keys)."
  [^js request k]
  (.. request -headers (get k)))

(defn- ->tool-ctx
  "Per-request tool context: forward the end user's bearer, resolve the
   vocabulary source key (request body `sourceKey`, else EUNOMIA demo
   source), and thread the live plan through so tools like review_plan can
   check it structurally — the same plan pythia.prompt renders into the
   ## Active plan block."
  [^js request body plan]
  {:auth (header request "authorization")
   :source-key (or (unchecked-get body "sourceKey") "EUNOMIA")
   :plan plan})

(defn- error-response [e]
  (js/Response. (js/JSON.stringify #js {:error (str (or (.-message e) e))})
                #js {:status 500
                     :headers #js {"content-type" "application/json"}}))

(defn handler [^js request]
  (-> (.json request)
      (.then (fn [body]
               (let [messages (unchecked-get body "messages")
                     context (->context body)
                     system (prompt/system-prompt context)
                     ctx (->tool-ctx request body (:plan context))
                     tool-obj (registry/tools-object tools/all ctx)]
                 (sdk/stream-chat messages system tool-obj))))
      (.catch error-response)))
