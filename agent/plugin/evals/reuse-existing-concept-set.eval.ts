import { defineEval } from "eve/evals";

export default defineEval({
  description:
    "concept sets are reused, not duplicated: pythia looks for an existing set before " +
    "assembling one concept by concept",
  tags: ["workflow", "reuse"],
  async test(t) {
    await t.send(
      "Add a statins criterion to this cohort. I think I already have a statins concept set.",
    );
    t.succeeded();
    t.calledTool("search_existing_concept_sets");
  },
});
