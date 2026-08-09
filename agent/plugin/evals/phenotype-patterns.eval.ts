import { defineEval } from "eve/evals";

export default defineEval({
  description:
    "designing against evidence: pythia reads what accepted library definitions of a " +
    "condition actually exclude (phenotype_patterns) rather than reciting exclusions from recall",
  tags: ["workflow", "grounding"],
  async test(t) {
    await t.send(
      "I'm designing a type 2 diabetes phenotype. What do accepted OHDSI library " +
        "definitions of it usually exclude, and do they enter on the first event?",
    );
    t.succeeded();
    t.calledTool("phenotype_patterns", { input: { condition: /diabet/i } });
    t.noFailedActions();
  },
});
