(ns pythia.tools.get-artifact-test
  (:require [cljs.test :refer [deftest is async]]
            [pythia.tools.get-artifact :as ga]))

;; Observed live, mid-recording: asked about the cohort being built, the model
;; called get_artifact with id "draft". WebAPI answered 400
;; (NumberFormatException converting the path variable to int) — an error the
;; agent could do nothing useful with. The honest answer is that the thing on
;; screen has no id until it is saved.
(deftest non-numeric-id-explains-itself-instead-of-calling-webapi
  (async done
    (-> (js/Promise.resolve ((:run ga/tool) {:kind "cohort" :id "draft"} {}))
        (.then (fn [out]
                 (is (re-find #"numeric id" (str (:error out))))
                 (is (re-find #"no id until save_cohort" (str (:error out))))
                 (done))))))

(deftest a-numeric-id-passes-the-guard
  (async done
    ;; No auth or stubbed webapi here, so this fails at the request rather than
    ;; the guard — the point is that a valid id is not rejected by the guard.
    (-> (js/Promise.resolve ((:run ga/tool) {:kind "cohort" :id 42} {}))
        (.then (fn [out]
                 (is (not (re-find #"numeric id" (str (:error out)))))
                 (done))
               (fn [_] (is true) (done))))))
