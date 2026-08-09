import { defineEval } from "eve/evals";

export default defineEval({
  description:
    "a named study period is encoded as a censor window (set_censor_window), not left to " +
    "span the whole database",
  tags: ["workflow", "correctness"],
  async test(t) {
    await t.send(
      "Limit this cohort to the study period 2015-01-01 to 2019-12-31. Propose it now — " +
        "no plan, and do not ask me anything else.",
    );
    t.succeeded();
    t.calledTool("set_censor_window", { status: "pending" });
  },
});
