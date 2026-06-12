(ns pythia.http-test
  (:require [cljs.test :refer [deftest is async]]
            [pythia.http :as http]))

(defn- mock-fetch! [{:keys [status json-body text-body]}]
  (let [recorded (atom nil)]
    (set! js/fetch
          (fn [url opts]
            (reset! recorded {:url url :opts opts})
            (js/Promise.resolve
             #js {:status (or status 200)
                  :json (fn [] (js/Promise.resolve (clj->js json-body)))
                  :text (fn [] (js/Promise.resolve (or text-body "")))})))
    recorded))

(deftest get-json-absolute-url-query-headers
  (async done
    (let [recorded (mock-fetch! {:status 200 :json-body {:items [1 2]}})]
      (-> (http/get-json "https://api.github.com/search/repositories"
                         {:query {:q "covid" :per_page 8}
                          :headers {"Accept" "application/vnd.github+json"}})
          (.then (fn [{:keys [status body]}]
                   (let [{:keys [url opts]} @recorded]
                     (is (= "https://api.github.com/search/repositories?q=covid&per_page=8" url))
                     (is (= "GET" (unchecked-get opts "method")))
                     (is (= "application/vnd.github+json"
                            (unchecked-get (unchecked-get opts "headers") "Accept"))))
                   (is (= 200 status))
                   (is (= {:items [1 2]} body))
                   (done)))))))

(deftest get-json-non-200-keeps-status
  (async done
    (let [_ (mock-fetch! {:status 403 :json-body {}})]
      (-> (http/get-json "https://api.github.com/x" {})
          (.then (fn [{:keys [status]}]
                   (is (= 403 status))
                   (done)))))))

(deftest post-form-encodes-body-returns-text
  (async done
    (let [recorded (mock-fetch! {:status 200 :text-body "<html>hi</html>"})]
      (-> (http/post-form "https://lite.duckduckgo.com/lite/"
                          {:form {:q "type 2 diabetes"}
                           :headers {"User-Agent" "Pythia/1.0"}})
          (.then (fn [{:keys [status text]}]
                   (let [{:keys [url opts]} @recorded]
                     (is (= "https://lite.duckduckgo.com/lite/" url))
                     (is (= "POST" (unchecked-get opts "method")))
                     (is (= "q=type+2+diabetes" (unchecked-get opts "body")))
                     (is (= "application/x-www-form-urlencoded"
                            (unchecked-get (unchecked-get opts "headers") "Content-Type"))))
                   (is (= 200 status))
                   (is (= "<html>hi</html>" text))
                   (done)))))))
