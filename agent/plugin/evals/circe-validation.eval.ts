import { defineEval } from "eve/evals";

export default defineEval({
  description:
    "pythia validates a draft Circe cohort expression via validate_circe before proposing it",
  tags: ["workflow"],
  async test(t) {
    await t.send(
      "Draft a simple cohort: adults with a condition occurrence of essential hypertension " +
        "(SNOMED concept 320128) as the index event, no additional inclusion rules. " +
        "Validate the Circe expression before showing it to me.",
    );
    t.succeeded();
    t.calledTool("validate_circe");
    t.noFailedActions();
  },
});
