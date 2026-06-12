(ns pythia.routes
  "Agent-visible ATLAS route names — the `agentVisible=true` entries of the
   Atlas3 routes manifest (src/routes.manifest.json), in manifest order. This
   is the source of the `navigate_to` tool's `view` enum and mirrors the JVM
   `trexsql.agent.routes-manifest/agent-visible-views`. When the manifest
   changes, regenerate this vector to keep the frontend contract in sync.")

(def agent-visible-views
  ["home"
   "cohorts"
   "cohort-new"
   "cohort-edit"
   "profiles"
   "profiles-source"
   "profile-view"
   "profile-view-cohort"
   "feature-analyses"
   "characterizations"
   "pathways"
   "incidence-rates"
   "feature-analysis-new"
   "feature-analysis-edit"
   "characterization-new"
   "characterization-edit"
   "characterization-results"
   "characterization-version-preview"
   "cohort-version-preview"
   "conceptset-version-preview"
   "concepts"
   "concept-detail"
   "pathway-new"
   "pathway-edit"
   "pathway-version-preview"
   "pathway-results"
   "incidence-rate-new"
   "incidence-rate-edit"
   "incidence-rate-version-preview"
   "datasources"
   "docs"])
