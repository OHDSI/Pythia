(ns pythia.tools
  "Aggregated tool list. Adding a tool is one line here."
  (:require [pythia.tools.search-concepts :as search-concepts]
            [pythia.tools.search-concept-sets :as search-concept-sets]
            [pythia.tools.search-existing-cohorts :as search-existing-cohorts]
            [pythia.tools.search-characterizations :as search-characterizations]
            [pythia.tools.search-feature-analyses :as search-feature-analyses]
            [pythia.tools.search-incidence-rates :as search-incidence-rates]
            [pythia.tools.search-pathways :as search-pathways]))

(def all
  [search-concepts/tool
   search-concept-sets/tool
   search-existing-cohorts/tool
   search-characterizations/tool
   search-feature-analyses/tool
   search-incidence-rates/tool
   search-pathways/tool])
