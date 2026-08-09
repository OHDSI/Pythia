import { defineEval } from "eve/evals";

export default defineEval({
  description:
    "pythia emits an add_inclusion_rule proposal (a clientOnly tool: no server-side execute, " +
    "the host forwards the call to the frontend for approval) when asked to add an inclusion rule",
  tags: ["workflow", "hitl"],
  async test(t) {
    // Ibuprofen, not metformin: Eunomia's subset vocabulary contains no
    // metformin concept at all, so search_concepts returns nothing and the
    // agent correctly refuses to invent an ID (prompt: Limited Vocabulary
    // Fallback) — the turn then ends in searches and this eval measured the
    // vocabulary rather than the proposal it means to check.
    await t.send(
      "Add an inclusion rule requiring at least one prior ibuprofen exposure before the index date.",
    );
    t.succeeded();
    // clientOnly tools never receive an action.result from the server — the
    // turn ends right after the tool-call request and the frontend resolves
    // it, so the call is visible in the stream as "pending", not "completed".
    t.calledTool("add_inclusion_rule", { status: "pending", count: 1 });
  },
});
