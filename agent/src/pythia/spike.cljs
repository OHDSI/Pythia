(ns pythia.spike
  (:require ["ai" :refer [streamText tool convertToModelMessages stepCountIs jsonSchema]]
            ["@ai-sdk/amazon-bedrock" :refer [createAmazonBedrock]]))

;; Bedrock provider with bearer-token auth.
;; @ai-sdk/amazon-bedrock natively reads AWS_BEARER_TOKEN_BEDROCK from env,
;; but under Deno process.env may not be populated, so pass apiKey explicitly.
(defn- bearer-token []
  (or (when (exists? js/process)
        (unchecked-get (unchecked-get js/process "env") "AWS_BEARER_TOKEN_BEDROCK"))
      (when (exists? js/Deno) (.. js/Deno -env (get "AWS_BEARER_TOKEN_BEDROCK")))))

(defn- bedrock []
  (createAmazonBedrock #js {:region "us-east-1"
                            :apiKey (bearer-token)}))

(def get-time
  (tool #js {:description "Return the current server time as a fixed string."
             :inputSchema (jsonSchema #js {:type "object" :properties #js {} :additionalProperties false})
             :execute (fn [_args] (js/Promise.resolve "2026-06-11T12:00:00Z"))}))

(defn handler [^js request]
  (-> (.json request)
      (.then (fn [body]
               ;; convertToModelMessages is async in AI SDK v6.
               (convertToModelMessages (unchecked-get body "messages"))))
      (.then (fn [model-messages]
               (let [model ((bedrock) "us.anthropic.claude-sonnet-4-6")
                     result (streamText
                              #js {:model model
                                   :system "You are a terse assistant. Follow the user's instructions exactly."
                                   :messages model-messages
                                   :tools #js {:get_time get-time}
                                   :stopWhen (stepCountIs 5)})]
                 (.toUIMessageStreamResponse result))))))
