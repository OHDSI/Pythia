(ns pythia.agent-tools-test
  "Tests for the eve `defineTool` adapter: pythia.agent-tools/tools must
   expose all 57 pythia.tools/all entries as eve tool defs, and the
   per-tool builder must rebuild the legacy {:auth :source-key :plan} ctx
   from the trex ToolContext shape {bearerToken sessionId metadata}."
  (:require [cljs.test :refer [deftest is testing async]]
            [pythia.agent-tools :as agent-tools]
            [pythia.webapi :as webapi]))

(defn- with-webapi
  "Stub webapi/request with `responses` (path -> body), record the paths
   requested, run (f recorded restore)."
  [responses f]
  (let [orig webapi/request
        recorded (atom [])]
    (set! webapi/request
          (fn [_method path _opts]
            (swap! recorded conj path)
            (js/Promise.resolve (get responses path))))
    (f recorded (fn [] (set! webapi/request orig)))))

(defn- run-source-key
  "Execute a tool that echoes its ctx :source-key, for a ToolContext `ctx`."
  [ctx]
  (let [m {:name "sk" :description "d" :schema {:type "object"}
           :run (fn [_args ctx] {:sk (:source-key ctx)})}]
    (-> ((unchecked-get (agent-tools/->eve-tool m) "execute") #js {} ctx)
        (.then #(unchecked-get % "sk")))))

(deftest exposes-all-57-tools
  (let [names (js/Object.keys agent-tools/tools)]
    (is (= 57 (count names)))
    (doseq [n names]
      (is (true? (unchecked-get (unchecked-get agent-tools/tools n) "__trexTool"))
          (str n " must be branded __trexTool by defineTool")))))

(deftest server-tool-has-execute-no-client-only
  (let [t (unchecked-get agent-tools/tools "search_concepts")]
    (is (fn? (unchecked-get t "execute")))
    (is (undefined? (unchecked-get t "clientOnly")))))

(deftest client-tool-has-client-only-no-execute
  (let [t (unchecked-get agent-tools/tools "add_criterion")]
    (is (true? (unchecked-get t "clientOnly")))
    (is (undefined? (unchecked-get t "execute")))))

(deftest ctx-adaptation-full
  (async done
    (let [m {:name "t" :description "d" :schema {:type "object"}
             :run (fn [args ctx]
                    {:echo-auth (:auth ctx)
                     :sk (:source-key ctx)
                     :plan (:plan ctx)
                     :args args})}
          eve-tool (agent-tools/->eve-tool m)
          input #js {:x 1}
          ctx #js {:bearerToken "tok"
                   :sessionId "s"
                   :metadata #js {:sourceKey "SYNPUF"
                                  :plan #js {:steps #js []}}}]
      (-> ((unchecked-get eve-tool "execute") input ctx)
          (.then (fn [result]
                   (is (= "Bearer tok" (unchecked-get result "echo-auth")))
                   (is (= "SYNPUF" (unchecked-get result "sk")))
                   (is (= 1 (unchecked-get (unchecked-get result "args") "x")))
                   (is (some? (unchecked-get result "plan")))
                   (done)))))))

(deftest ctx-adaptation-defaults
  (async done
    (with-webapi {"/source/priorityVocabulary" {:sourceKey "SYNPUF5PCT"}}
      (fn [_recorded restore]
        (let [m {:name "t2" :description "d" :schema {:type "object"}
                 :run (fn [_args ctx]
                        {:echo-auth (:auth ctx)
                         :sk (:source-key ctx)
                         :plan (:plan ctx)})}
              eve-tool (agent-tools/->eve-tool m)]
          (-> ((unchecked-get eve-tool "execute") #js {} #js {:sessionId "s"})
              (.then (fn [result]
                       (is (nil? (unchecked-get result "echo-auth")))
                       (is (= "SYNPUF5PCT" (unchecked-get result "sk")))
                       (is (nil? (unchecked-get result "plan")))
                       (restore)
                       (done)))))))))

(deftest source-key-from-request-skips-webapi
  (async done
    (with-webapi {}
      (fn [recorded restore]
        (-> (run-source-key #js {:metadata #js {:sourceKey "SYNPUF"}})
            (.then (fn [sk]
                     (is (= "SYNPUF" sk))
                     (is (empty? @recorded) "no WebAPI lookup when the host sent a source")
                     (restore)
                     (done))))))))

(deftest source-key-defaults-to-priority-vocabulary
  (async done
    (with-webapi {"/source/priorityVocabulary" {:sourceKey "SYNPUF5PCT"}}
      (fn [recorded restore]
        (-> (run-source-key #js {:bearerToken "tok"})
            (.then (fn [sk]
                     (is (= "SYNPUF5PCT" sk))
                     (is (= ["/source/priorityVocabulary"] @recorded))
                     (restore)
                     (done))))))))

(deftest source-key-falls-back-to-first-listed-source
  (async done
    (with-webapi {"/source/sources" [{:sourceKey "CDM_A"} {:sourceKey "CDM_B"}]}
      (fn [recorded restore]
        (-> (run-source-key #js {:bearerToken "tok"})
            (.then (fn [sk]
                     (is (= "CDM_A" sk))
                     (is (= ["/source/priorityVocabulary" "/source/sources"] @recorded))
                     (restore)
                     (done))))))))

(deftest source-key-last-resort-when-webapi-has-none
  (async done
    (with-webapi {}
      (fn [_recorded restore]
        (-> (run-source-key #js {:bearerToken "tok"})
            (.then (fn [sk]
                     (is (= "EUNOMIA" sk))
                     (restore)
                     (done))))))))
