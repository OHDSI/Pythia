import { defineEval } from "eve/evals";

export default defineEval({
  description:
    "when the OHDSI Phenotype Library has a matching phenotype, pythia names it and asks whether to " +
    "rebuild it or design a custom cohort, instead of silently proposing its own entry event",
  tags: ["workflow", "phenotype-library"],
  async test(t) {
    await t.send("Define a Type 2 Diabetes cohort");
    t.succeeded();
    t.calledTool("search_phenotypes", { input: { query: /diabet|t2d/i } });
    // The library has #503 "Type 2 diabetes mellitus" and others: the user chooses.
    t.calledTool("ask_user", { status: "pending", count: 1 });
    t.toolOrder(["search_phenotypes", "ask_user"]);
    t.notCalledTool("set_entry_event");
    t.notCalledTool("add_criteria");
  },
});
