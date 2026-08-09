import { defineEval } from "eve/evals";

export default defineEval({
  description:
    "pythia validates a draft Circe cohort expression via validate_circe before proposing it",
  tags: ["workflow"],
  async test(t) {
    // Calibrated the way cohort-build-flow is, and for the same reason: a turn
    // ends at its first client-side proposal, and the design workflow (reuse
    // check, library search, concept resolution) can fill a whole turn on its
    // own — so a single turn measured whether the agent got that far, not
    // whether it validates. The concept id is given, so none of that research
    // is needed; the second turn asks for the one action under test.
    await t.send(
      "Draft a simple cohort: adults with a condition occurrence of essential hypertension " +
        "(SNOMED concept 320128) as the index event, no additional inclusion rules. " +
        "The concept id is given, so do not search for existing cohorts, library " +
        "phenotypes or concepts.",
    );
    await t.send(
      "Now compose that Circe cohort expression and check it compiles with validate_circe " +
        "before showing it to me. Do not propose anything to the editor and do not search.",
    );
    t.succeeded();
    t.calledTool("validate_circe");
    t.noFailedActions();
  },
});
