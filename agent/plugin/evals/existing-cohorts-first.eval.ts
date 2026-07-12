import { defineEval } from "eve/evals";

export default defineEval({
  description:
    "workflow step 1: pythia checks for existing user cohorts (search_existing_cohorts) before designing a new cohort",
  tags: ["workflow"],
  async test(t) {
    await t.send("Define a cohort of patients with type 2 diabetes for me.");
    t.succeeded();
    t.calledTool("search_existing_cohorts", { input: { query: /diabet/i } });
  },
});
