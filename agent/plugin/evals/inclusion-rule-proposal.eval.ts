import { defineEval } from "eve/evals";

export default defineEval({
  description:
    "pythia emits an add_inclusion_rule proposal (a clientOnly tool: no server-side execute, " +
    "the host forwards the call to the frontend for approval) when asked to add an inclusion rule",
  async test(t) {
    await t.send(
      "Add an inclusion rule requiring at least one prior metformin exposure before the index date.",
    );
    t.succeeded();
    // clientOnly tools (agent_tools.cljs's ->eve-tool: no :run -> `:clientOnly true`,
    // no :execute) never receive an action.result from the server — the turn ends
    // right after the tool-call request (finishReason "tool-calls", per
    // core/server/agents/service/runner.test.ts in the trex repo) and the frontend
    // is the one that resolves it. So the call is visible in the stream as a
    // "pending" tool call, not a "completed" one — status must be asserted
    // explicitly, matching the documented HITL-pending pattern
    // (`parked.calledTool("guarded", { status: "pending" })` in eve's own
    // assertions docs).
    t.calledTool("add_inclusion_rule", { status: "pending" });
  },
});
