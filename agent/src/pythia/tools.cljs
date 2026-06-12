(ns pythia.tools
  "Aggregated tool list. Adding a tool is one line here."
  (:require [pythia.tools.search-concepts :as search-concepts]
            [pythia.tools.search-concept-sets :as search-concept-sets]
            [pythia.tools.search-existing-cohorts :as search-existing-cohorts]
            [pythia.tools.search-characterizations :as search-characterizations]
            [pythia.tools.search-feature-analyses :as search-feature-analyses]
            [pythia.tools.search-incidence-rates :as search-incidence-rates]
            [pythia.tools.search-pathways :as search-pathways]
            [pythia.tools.verify-concept-mapping :as verify-concept-mapping]
            [pythia.tools.get-artifact :as get-artifact]
            [pythia.tools.get-cohort-generation-summary :as get-cohort-generation-summary]
            [pythia.tools.summarise-attrition :as summarise-attrition]
            [pythia.tools.get-cohort-overlap :as get-cohort-overlap]
            [pythia.tools.draft-concept-set-spec :as draft-concept-set-spec]
            [pythia.tools.search-ohdsi-studies :as search-ohdsi-studies]
            [pythia.tools.web-search :as web-search]
            [pythia.tools.search-phenotypes :as search-phenotypes]
            [pythia.tools.get-reference-phenotype :as get-reference-phenotype]
            [pythia.tools.validate-circe :as validate-circe]
            [pythia.tools.search-ohdsi-book :as search-ohdsi-book]
            [pythia.tools.client :as client]))

(def server
  "Server-side tools — each has an `:run` that the handler executes."
  [search-concepts/tool
   search-concept-sets/tool
   search-existing-cohorts/tool
   search-characterizations/tool
   search-feature-analyses/tool
   search-incidence-rates/tool
   search-pathways/tool
   verify-concept-mapping/tool
   get-artifact/tool
   get-cohort-generation-summary/tool
   summarise-attrition/tool
   get-cohort-overlap/tool
   draft-concept-set-spec/tool
   search-ohdsi-studies/tool
   web-search/tool
   search-phenotypes/tool
   get-reference-phenotype/tool
   validate-circe/tool
   search-ohdsi-book/tool])

(def all
  "Every tool exposed to Bedrock: server tools (with :run) + client-side
   proposal tools (schema-only, no :run). The exposed name set must equal the
   JVM `tool-specs` name set."
  (into server client/client-tools))
