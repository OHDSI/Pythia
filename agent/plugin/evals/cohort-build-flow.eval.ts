import { defineEval } from "eve/evals";

export default defineEval({
  description:
    "multi-turn: design request + explicit go-ahead ends in a pending add_criteria batch proposal",
  tags: ["flow", "slow"],
  timeoutMs: 300_000,
  async test(t) {
    await t.send(
      "Design a new cohort of adults with type 2 diabetes. Assume no existing cohort fits.",
    );
    await t.send(
      "Yes — proceed with the full design now. Resolve the concept IDs and propose all " +
        "inclusion and exclusion criteria in one batch. Do not ask me anything else.",
    );
    t.succeeded();
    t.calledTool("search_concepts");
    t.calledTool("add_criteria", { status: "pending" });
    t.noFailedActions();
  },
});
