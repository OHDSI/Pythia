import { defineEval } from "eve/evals";

export default defineEval({
  description:
    "a cohort described as adults encodes the age restriction (add_demographic_criterion) " +
    "instead of leaving it in the name only",
  tags: ["workflow", "correctness"],
  async test(t) {
    // Observed live before this capability existed: the agent named a cohort
    // "Adult osteoarthritis patients …" and then reported honestly that age was
    // not encoded, because nothing could express it. A cohort named for adults
    // that silently contains children is the same class of defect as an
    // inverted exclusion.
    await t.send(
      "Restrict the cohort I'm building to adults, 18 and over. Propose it now — " +
        "no plan, and do not ask me anything else.",
    );
    t.succeeded();
    t.calledTool("add_demographic_criterion", { status: "pending" });
  },
});
