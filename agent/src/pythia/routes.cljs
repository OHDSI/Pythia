(ns pythia.routes
  "Agent-visible ATLAS route entries — the `agentVisible=true` entries of the
   Atlas3 routes manifest (src/routes.manifest.json), in manifest order. This
   is the source of the `navigate_to` tool's `view` enum and the prompt's
   `format-views` block, and mirrors the JVM
   `trexsql.agent.routes-manifest/agent-visible-entries` /
   `agent-visible-views`. When the manifest changes, regenerate these to keep
   the frontend contract in sync.")

(def agent-visible-entries
  "Manifest entries with agentVisible=true: {:name :params :label}."
  [{:name "home" :params [] :label "Home"}
   {:name "cohorts" :params [] :label "Cohorts"}
   {:name "cohort-new" :params [] :label "New cohort"}
   {:name "cohort-edit" :params ["id"] :label "Cohort editor"}
   {:name "profiles" :params [] :label "Profiles"}
   {:name "profiles-source" :params ["sourceKey"] :label "Profiles (source)"}
   {:name "profile-view" :params ["sourceKey" "personId"] :label "Patient profile"}
   {:name "profile-view-cohort" :params ["sourceKey" "personId" "cohortId"] :label "Patient profile (cohort)"}
   {:name "feature-analyses" :params [] :label "Feature analyses"}
   {:name "characterizations" :params [] :label "Characterizations"}
   {:name "pathways" :params [] :label "Pathways"}
   {:name "incidence-rates" :params [] :label "Incidence rates"}
   {:name "feature-analysis-new" :params [] :label "New feature analysis"}
   {:name "feature-analysis-edit" :params ["id"] :label "Feature analysis editor"}
   {:name "characterization-new" :params [] :label "New characterization"}
   {:name "characterization-edit" :params ["id"] :label "Characterization editor"}
   {:name "characterization-results" :params ["id" "executionId"] :label "Characterization results"}
   {:name "characterization-version-preview" :params ["id" "version"] :label "Characterization version preview"}
   {:name "cohort-version-preview" :params ["id" "version"] :label "Cohort version preview"}
   {:name "conceptset-version-preview" :params ["id" "version"] :label "Concept set version preview"}
   {:name "concepts" :params [] :label "Concept sets"}
   {:name "concept-detail" :params ["sourceKey" "conceptId"] :label "Concept detail"}
   {:name "pathway-new" :params [] :label "New pathway"}
   {:name "pathway-edit" :params ["id"] :label "Pathway editor"}
   {:name "pathway-version-preview" :params ["id" "version"] :label "Pathway version preview"}
   {:name "pathway-results" :params ["id" "executionId"] :label "Pathway results"}
   {:name "incidence-rate-new" :params [] :label "New incidence rate"}
   {:name "incidence-rate-edit" :params ["id"] :label "Incidence rate editor"}
   {:name "incidence-rate-version-preview" :params ["id" "version"] :label "Incidence rate version preview"}
   {:name "datasources" :params ["sourceKey" "reportType"] :label "Data sources"}
   {:name "docs" :params [] :label "Documentation"}])

(def agent-visible-views
  "List of view names (route :name) the agent is allowed to navigate to —
   the `navigate_to` tool's `view` enum, in manifest order."
  (mapv :name agent-visible-entries))
