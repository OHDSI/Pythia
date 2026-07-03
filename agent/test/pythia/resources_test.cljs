(ns pythia.resources-test
  "candidate-paths/read-text tests (task P3): TREX_AGENT_DIR is now the
   primary resource-resolution path (the trex agents runtime sets it to the
   loaded agent directory, e.g. <plugin>/agent — see
   core/server/agents/service/index.ts). Faking `js/globalThis.Deno` with a
   real Node fs-backed readTextFile lets this run under the :node-test build
   without needing a real Deno process."
  (:require [cljs.test :refer [deftest is async]]
            [pythia.resources :as resources]))

(defonce ^:private fs (js/require "fs"))
(defonce ^:private os (js/require "os"))
(defonce ^:private node-path (js/require "path"))

(defn- fs-read-text-file
  "A Deno.readTextFile-shaped fn backed by real Node fs, for the fake Deno global."
  [p]
  (js/Promise.
   (fn [resolve reject]
     (.readFile fs p "utf8"
                (fn [err data]
                  (if err (reject err) (resolve data)))))))

(defn- with-fake-deno!
  "Install a minimal fake `js/globalThis.Deno` (env.get + readTextFile + cwd)
   for the duration of `f` (a fn returning a Promise), restoring whatever was
   there before once `f`'s promise settles."
  [env-map f]
  (let [had-deno? (some? (unchecked-get js/globalThis "Deno"))
        orig (unchecked-get js/globalThis "Deno")]
    (unchecked-set js/globalThis "Deno"
                   #js {:env #js {:get (fn [k] (get env-map k))}
                        :readTextFile fs-read-text-file
                        :cwd (fn [] (or (get env-map "TREX_AGENT_DIR") "/"))})
    (-> (f)
        (.finally (fn []
                    (if had-deno?
                      (unchecked-set js/globalThis "Deno" orig)
                      (js-delete js/globalThis "Deno")))))))

(deftest read-text-finds-file-under-trex-agent-dir
  (async done
    (let [tmp (.mkdtempSync fs (.join node-path (.tmpdir os) "pythia-resources-"))
          res-dir (.join node-path tmp "resources")]
      (.mkdirSync fs res-dir)
      (.writeFileSync fs (.join node-path res-dir "x.edn") "{:a 1}")
      (-> (with-fake-deno! {"TREX_AGENT_DIR" tmp}
            (fn [] (resources/read-text "x.edn")))
          (.then (fn [text]
                   (is (= "{:a 1}" text)
                       "read-text must find resources/x.edn via TREX_AGENT_DIR")
                   (done)))))))

(deftest read-text-nil-when-no-candidate-readable
  (async done
    (-> (with-fake-deno! {"TREX_AGENT_DIR" "/does/not/exist"}
          (fn [] (resources/read-text "missing.edn")))
        (.then (fn [text]
                 (is (nil? text))
                 (done))))))
