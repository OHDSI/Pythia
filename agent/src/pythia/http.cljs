(ns pythia.http
  "fetch-based helper for EXTERNAL (non-WebAPI) HTTP: absolute URLs, custom
   headers, query params, form-encoded POST bodies, and raw-text responses.
   Used by tools that hit GitHub / DuckDuckGo / etc. WebAPI-relative calls
   should keep using pythia.webapi/request."
  (:require [clojure.string :as str]))

(defn- search-params [m]
  (let [p (js/URLSearchParams.)]
    (doseq [[k v] m] (.append p (name k) (str v)))
    p))

(defn- with-query [url query]
  (if (seq query)
    (str url "?" (.toString (search-params query)))
    url))

(defn get-json
  "GET an absolute `url`. Opts: :query (clj map), :headers (clj map).
   Resolves {:status <int> :body <keywordized JSON|nil>}; {:status 0 :body nil}
   on network error."
  [url {:keys [query headers]}]
  (let [opts (cond-> {:method "GET"}
               (seq headers) (assoc :headers (clj->js headers)))]
    (-> (js/fetch (with-query url query) (clj->js opts))
        (.then (fn [resp]
                 (let [status (unchecked-get resp "status")]
                   (-> (.json resp)
                       (.then (fn [j] {:status status :body (js->clj j :keywordize-keys true)}))
                       (.catch (fn [_] {:status status :body nil}))))))
        (.catch (fn [_] {:status 0 :body nil})))))

(defn post-form
  "POST an absolute `url` with a form-encoded body. Opts: :form (clj map ->
   application/x-www-form-urlencoded), :headers (clj map). Resolves
   {:status <int> :text <string>}; {:status 0 :text \"\"} on network error."
  [url {:keys [form headers]}]
  (let [body (.toString (search-params form))
        hdrs (assoc (or headers {}) "Content-Type" "application/x-www-form-urlencoded")
        opts {:method "POST" :headers (clj->js hdrs) :body body}]
    (-> (js/fetch url (clj->js opts))
        (.then (fn [resp]
                 (let [status (unchecked-get resp "status")]
                   (-> (.text resp)
                       (.then (fn [t] {:status status :text (or t "")}))
                       (.catch (fn [_] {:status status :text ""}))))))
        (.catch (fn [_] {:status 0 :text ""})))))
