(ns pythia.artifacts
  "Shared artifact-kind -> WebAPI path table. Used by get_artifact (fetch
   only) and review_artifact (fetch + structural checks) so both tools
   resolve a kind to the same WebAPI path.")

(def kind->path
  {"cohort"           (fn [id] (str "/cohortdefinition/" id))
   "concept_set"      (fn [id] (str "/conceptset/" id "/expression"))
   "feature_analysis" (fn [id] (str "/feature-analysis/" id))
   "characterization" (fn [id] (str "/cohort-characterization/" id "/design"))
   "pathway"          (fn [id] (str "/pathway-analysis/" id))
   "incidence_rate"   (fn [id] (str "/ir/" id))})
