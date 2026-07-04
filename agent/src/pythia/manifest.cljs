(ns pythia.manifest
  "Loads the ATLAS-authored capability manifest (vendored from Atlas3 at
   agent/resources/capabilities.manifest.json) — the single source of truth for
   the 19 artifact-editing tool schemas. `shadow.resource/inline` bakes the file
   in at compile time from the classpath (resources is on :source-paths).
   Refresh by regenerating in Atlas3 (npm run generate:capabilities) and copying
   the file here. The manifest's :requiresApproval field is intentionally
   ignored — approval is a host concern, not part of the tool schema."
  (:require [shadow.resource :as rc]))

(def ^:private raw (rc/inline "capabilities.manifest.json"))

(defn- ->tool-map [entry]
  {:name (:name entry)
   :description (:description entry)
   :schema (:schema entry)})

(def capability-tools
  "The 19 artifact-editing tools as {:name :description :schema}. Schema keys are
   keywordized so they match what registry/->client-sdk-tool consumes (it calls
   clj->js on the schema); enum values stay strings. NO :run — schema-only."
  (mapv ->tool-map (js->clj (js/JSON.parse raw) :keywordize-keys true)))
