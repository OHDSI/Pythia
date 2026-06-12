# Tool parity audit — CLJS agent vs JVM `tool-specs`

Date: 2026-06-12
Branch: `p-hoffmann/pythia-cljs-agent`

## Goal

The CLJS agent exposes tools to Bedrock. Tool `:name`s are a frontend contract
(the `*ProposalCard.vue` components dispatch on them). The exposed CLJS
tool-name set MUST be byte-identical to the JVM source of truth:
`../trex/plugins/bao/java/src/trexsql/agent/tools.clj` → `tool-specs`.

## Source of truth — JVM `tool-specs`

40 tools total: 19 `:side :server`, 21 `:side :client`.

### Server (`:side :server`, 19)

```
search_existing_cohorts            search_existing_concept_sets
search_existing_feature_analyses   search_existing_characterizations
search_existing_pathways           search_existing_incidence_rates
search_phenotypes                  get_reference_phenotype
validate_circe                     search_ohdsi_book
web_search                         get_artifact
search_concepts                    draft_concept_set_spec
verify_concept_mapping             get_cohort_generation_summary
summarise_attrition                get_cohort_overlap
search_ohdsi_studies
```

### Client (`:side :client`, 21)

```
add_criterion              add_criteria              set_entry_event
set_observation_window     add_exit_criterion        set_censor_event
create_standalone_concept_set                        navigate_to
add_inclusion_rule         create_feature_analysis   create_characterization
create_pathway             update_concept_set        update_feature_analysis
update_characterization    update_pathway            update_incidence_rate
create_incidence_rate      ask_user                  create_plan
update_plan_step
```

## CLJS exposure

- Server tools: `pythia.tools/server` (19 maps, each with `:run`).
- Client tools: `pythia.tools.client/client-tools` (21 maps, schema-only, NO `:run`).
- `pythia.tools/all` = `(into server client-tools)` → 40 tools.
- `registry/tools-object` builds an SDK `tool(...)` WITHOUT `:execute` for any
  map lacking `:run` (verified via the `ai` package: a `tool()` built without
  `execute` has no `execute` key — `'execute' in t === false`).

## Result

**JVM count: 40. CLJS count: 40. Name sets are IDENTICAL.**

`pythia.tools.parity-test` asserts set equality both directions
(`set/difference` empty each way), 40-count, uniqueness, 21 client tools,
client SDK tools have NO `execute`, server SDK tools DO have `execute`.
All 89 tests / 318 assertions pass.

### Name mismatches found + fixed

None. The earlier-ported server tools already carry the exact JVM `:name`.
Specifically the known risk was clear: the file `search_concept_sets.cljs`
defines `:name "search_existing_concept_sets"` (not `search_concept_sets`),
matching the JVM. Other ns-derived-looking names (`search_existing_*`) are all
correct `:name` strings.

### Schema spot-check (3+ tools vs JVM `:input-schema`)

- `search_concepts` — matches verbatim (`{query, domain(enum=domain-enum)}`, required `[query]`).
- `search_existing_cohorts` — matches verbatim.
- `search_existing_concept_sets` — matches verbatim (`{query, limit}`, required `[query]`).
- `get_artifact` — matches verbatim (`kind` enum of 6, `id`, required `[kind id]`).
- `validate_circe` — **DRIFT FOUND + FIXED**: CLJS had
  `:expression {:type "object" :description "Circe CohortExpression JSON ..."}`;
  JVM declares `:type ["string" "object"]` with the longer description
  ("either a JSON string or the equivalent JSON object. Must contain at minimum
  a PrimaryCriteria.CriteriaList."). Updated `validate_circe.cljs` `schema` to
  match the JVM exactly. (The `coerce-expression` run logic already accepted
  both string and object, so only the advertised schema was wrong.)

## Notes

- `navigate_to` `view` enum: JVM computes it dynamically from
  `routes-manifest/agent-visible-views`. CLJS embeds the same 31 agent-visible
  view names (manifest order) in `pythia.routes/agent-visible-views`, sourced
  from `src/routes.manifest.json` (`agentVisible=true` entries). Regenerate this
  vector if the manifest changes.
