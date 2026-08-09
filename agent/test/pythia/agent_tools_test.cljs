(ns pythia.agent-tools-test
  "Tests for the eve `defineTool` adapter: pythia.agent-tools/tools must
   expose all 55 pythia.tools/all entries as eve tool defs, and the
   per-tool builder must rebuild the legacy {:auth :source-key :plan} ctx
   from the trex ToolContext shape {bearerToken sessionId metadata}."
  (:require [cljs.test :refer [deftest is testing async]]
            [pythia.agent-tools :as agent-tools]))

(deftest exposes-all-55-tools
  (let [names (js/Object.keys agent-tools/tools)]
    (is (= 55 (count names)))
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
    (let [m {:name "t2" :description "d" :schema {:type "object"}
             :run (fn [_args ctx]
                    {:echo-auth (:auth ctx)
                     :sk (:source-key ctx)
                     :plan (:plan ctx)})}
          eve-tool (agent-tools/->eve-tool m)
          input #js {}
          ctx #js {:sessionId "s"}]
      (-> ((unchecked-get eve-tool "execute") input ctx)
          (.then (fn [result]
                   (is (nil? (unchecked-get result "echo-auth")))
                   (is (= "EUNOMIA" (unchecked-get result "sk")))
                   (is (nil? (unchecked-get result "plan")))
                   (done)))))))
