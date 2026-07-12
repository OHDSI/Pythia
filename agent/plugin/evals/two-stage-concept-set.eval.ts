import { defineEval } from "eve/evals";

export default defineEval({
  description:
    "two-stage pattern: draft_concept_set_spec commits clinical terms BEFORE search_concepts resolves any IDs",
  tags: ["workflow"],
  async test(t) {
    await t.send(
      "There are no existing cohorts I want to reuse. Design a brand-new type 2 diabetes " +
        "cohort from scratch: draft the concept sets, then resolve the concept IDs.",
    );
    t.succeeded();
    t.calledTool("draft_concept_set_spec", {
      input: { clinical_terms: (v: unknown) => Array.isArray(v) && v.length > 0 },
    });
    t.calledTool("search_concepts");
    t.toolOrder(["draft_concept_set_spec", "search_concepts"]);
  },
});
