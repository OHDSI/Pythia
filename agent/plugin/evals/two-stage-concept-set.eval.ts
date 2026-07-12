import { defineEval } from "eve/evals";

export default defineEval({
  description:
    "two-stage pattern: draft_concept_set_spec commits clinical terms BEFORE search_concepts resolves any IDs",
  tags: ["workflow"],
  async test(t) {
    // Scenario calibrated on a live Sonnet 4.6 run: a "cohort from scratch"
    // request is multi-phase, so the prompt's Plans section (correctly) makes
    // the agent's FIRST call select_plan_template — and plan flows dead-end
    // in the eval harness, because plan-step tools (update_plan_step) are
    // clientOnly: the turn ends with the call pending and nothing in `eve
    // eval` resolves it (in Atlas3 the frontend does). A SINGLE standalone
    // concept set is the prompt's documented no-plan case ("Do NOT plan
    // trivial work"), and it exercises the two-stage pattern directly.
    await t.send(
      "Draft one brand-new standalone concept set for type 2 diabetes — just the concept set, " +
        "no cohort and no plan. Commit the clinical terms in a draft spec first, then resolve " +
        "the concept IDs against the vocabulary.",
    );
    t.succeeded();
    t.calledTool("draft_concept_set_spec", {
      input: { clinical_terms: (v: unknown) => Array.isArray(v) && v.length > 0 },
    });
    t.calledTool("search_concepts");
    t.toolOrder(["draft_concept_set_spec", "search_concepts"]);
  },
});
