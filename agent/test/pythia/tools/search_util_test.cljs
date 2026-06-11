(ns pythia.tools.search-util-test
  (:require [cljs.test :refer [deftest is async testing]]
            [pythia.webapi :as webapi]
            [pythia.tools.search-util :as su]))

(deftest score-match-weights-name-over-description
  ;; name hit = +3, desc hit = +1, per distinct query term
  (is (= 3 (su/score-match "diabetes" {:name "Type 2 Diabetes" :description "x"})))
  (is (= 1 (su/score-match "diabetes" {:name "Hypertension" :description "diabetes comorbidity"})))
  (is (= 4 (su/score-match "diabetes" {:name "Diabetes" :description "diabetes related"})))
  ;; two distinct terms, both in name = 6
  (is (= 6 (su/score-match "type diabetes" {:name "Type 2 Diabetes mellitus" :description ""})))
  ;; no match = 0
  (is (= 0 (su/score-match "asthma" {:name "Diabetes" :description "sugar"})))
  ;; duplicate terms counted once
  (is (= 3 (su/score-match "diabetes diabetes" {:name "Diabetes" :description ""})))
  ;; reads string-keyed entities too
  (is (= 3 (su/score-match "diabetes" {"name" "Diabetes" "description" ""}))))

(deftest extract-content-handles-bare-and-wrapped
  (is (= [1 2 3] (su/extract-content [1 2 3])))
  (is (= [:a :b] (su/extract-content {:content [:a :b]})))
  (is (= [] (su/extract-content nil)))
  (is (= [] (su/extract-content 42))))

(deftest ranked-results-scores-sorts-and-shapes
  (let [entities [{:id 1 :name "Diabetes cohort" :description ""}
                  {:id 2 :name "Asthma" :description "diabetes mention"}
                  {:id 3 :name "Hypertension" :description "nothing"}]
        out (su/ranked-results "diabetes" entities 10 su/default-result)]
    (is (= 2 (count out)) "only positive scores kept")
    (is (= 1 (:id (first out))) "highest score first")
    (is (= 3 (:matchScore (first out))))
    (is (= 1 (:matchScore (second out))))))

(deftest list-entities-gets-path-forwards-auth-and-returns-body
  (async done
    (let [recorded (atom nil)
          orig webapi/request]
      (set! webapi/request
            (fn [method path opts]
              (reset! recorded {:method method :path path :opts opts})
              (js/Promise.resolve [{:id 1 :name "x"}])))
      (-> (su/list-entities "/conceptset" "Bearer xyz")
          (.then (fn [body]
                   (let [{:keys [method path opts]} @recorded]
                     (is (= "GET" method))
                     (is (= "/conceptset" path))
                     (is (= "Bearer xyz" (:auth opts))))
                   (is (= [{:id 1 :name "x"}] body))
                   (set! webapi/request orig)
                   (done)))))))

(deftest list-entities-returns-empty-on-nil
  (async done
    (let [orig webapi/request]
      (set! webapi/request (fn [_ _ _] (js/Promise.resolve nil)))
      (-> (su/list-entities "/conceptset" nil)
          (.then (fn [body]
                   (is (= [] body))
                   (set! webapi/request orig)
                   (done)))))))
