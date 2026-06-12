(ns pythia.webapi-test
  (:require [cljs.test :refer [deftest is async]]
            [pythia.webapi :as webapi]))

(defn- mock-fetch!
  "Install a js/fetch stub that records the call and resolves a fake Response.
   Returns an atom holding the recorded {:url :opts}."
  [{:keys [status json-body]}]
  (let [recorded (atom nil)]
    (set! js/fetch
          (fn [url opts]
            (reset! recorded {:url url :opts opts})
            (js/Promise.resolve
             #js {:ok (= 200 (or status 200))
                  :status (or status 200)
                  :json (fn [] (js/Promise.resolve (clj->js json-body)))})))
    recorded))

(deftest request-builds-url-method-and-forwards-auth
  (async done
    (let [recorded (mock-fetch! {:status 200 :json-body [{:CONCEPT_ID 1}]})]
      (-> (webapi/request "POST" "/vocabulary/EUNOMIA/search"
                          {:body {:QUERY "diabetes"}
                           :auth "Bearer abc123"})
          (.then (fn [result]
                   (let [{:keys [url opts]} @recorded]
                     (is (= "http://localhost:8080/WebAPI/vocabulary/EUNOMIA/search" url))
                     (is (= "POST" (unchecked-get opts "method")))
                     (let [headers (unchecked-get opts "headers")]
                       (is (= "Bearer abc123" (unchecked-get headers "Authorization")))
                       (is (= "application/json" (unchecked-get headers "Content-Type"))))
                     (is (= "{\"QUERY\":\"diabetes\"}" (unchecked-get opts "body")))
                     ;; parsed + keywordized JSON
                     (is (= [{:CONCEPT_ID 1}] result)))
                   (done)))))))

(deftest request-appends-query-string
  (async done
    (let [recorded (mock-fetch! {:status 200 :json-body []})]
      (-> (webapi/request "GET" "/cohortdefinition"
                          {:query {:foo "bar baz"}})
          (.then (fn [_]
                   (is (= "http://localhost:8080/WebAPI/cohortdefinition?foo=bar%20baz"
                          (:url @recorded)))
                   (done)))))))

(deftest request-returns-nil-on-non-200
  (async done
    (let [_ (mock-fetch! {:status 404 :json-body {:error "nope"}})]
      (-> (webapi/request "GET" "/missing" {})
          (.then (fn [result]
                   (is (nil? result))
                   (done)))))))
