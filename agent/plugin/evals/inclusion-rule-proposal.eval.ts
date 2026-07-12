import { defineEval } from "eve/evals";

export default defineEval({
  description:
    "pythia emits an add_inclusion_rule proposal (a clientOnly tool: no server-side execute, " +
    "the host forwards the call to the frontend for approval) when asked to add an inclusion rule",
  tags: ["workflow", "hitl"],
  async test(t) {
    await t.send(
      "Add an inclusion rule requiring at least one prior metformin exposure before the index date.",
    );
    t.succeeded();
    // clientOnly tools never receive an action.result from the server — the
    // turn ends right after the tool-call request and the frontend resolves
    // it, so the call is visible in the stream as "pending", not "completed".
    t.calledTool("add_inclusion_rule", { status: "pending", count: 1 });
  },
});
