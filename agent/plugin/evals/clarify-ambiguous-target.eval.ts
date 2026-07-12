import { defineEval } from "eve/evals";

export default defineEval({
  description:
    "pythia asks which artifact the user means (ask_user, clientOnly -> pending) instead of guessing between ambiguous targets",
  tags: ["workflow", "hitl"],
  async test(t) {
    await t.send(
      "I have two cohorts, 'T2DM broad' and 'T2DM strict'. Add a metformin inclusion rule to my diabetes cohort.",
    );
    t.succeeded();
    // Should surface the ambiguity, not silently pick one.
    t.calledTool("ask_user", { status: "pending", count: 1 });
    t.notCalledTool("add_inclusion_rule");
  },
});
