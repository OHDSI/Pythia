(ns pythia.sdk
  "Vercel AI SDK wrapper: bearer-token Bedrock + streaming chat.
   Interop traps (proven in spike 0.3): read external JS props with
   unchecked-get (string keys survive :advanced rename); convertToModelMessages
   is async — await it before streamText."
  (:require ["ai" :refer [streamText convertToModelMessages stepCountIs]]
            ["@ai-sdk/amazon-bedrock" :refer [createAmazonBedrock]]
            [pythia.config :as config]))

(defn- bedrock []
  ;; createAmazonBedrock(settings) returns a provider callable that maps a
  ;; model id -> LanguageModel. Bearer-token auth via the native :apiKey option.
  (createAmazonBedrock #js {:region (config/region)
                            :apiKey (config/bearer-token)}))

(defn stream-chat
  "Stream a tool-less chat reply. ui-messages is the raw UIMessage array from
   the request; system is the assembled system prompt string. Returns a Promise
   of a Web Response (text/event-stream) via toUIMessageStreamResponse."
  [ui-messages system]
  (-> (js/Promise.resolve (convertToModelMessages ui-messages))
      (.then (fn [model-messages]
               (let [model ((bedrock) (config/model-id))
                     result (streamText
                             #js {:model model
                                  :system system
                                  :messages model-messages
                                  :tools #js {}
                                  :stopWhen (stepCountIs 20)})]
                 (.toUIMessageStreamResponse result))))))
