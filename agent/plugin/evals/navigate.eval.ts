import { defineEval } from "eve/evals";

export default defineEval({
  description:
    "pythia proposes navigation via the clientOnly navigate_to tool (pending, view from the route manifest)",
  tags: ["workflow", "hitl"],
  async test(t) {
    await t.send("Take me to the cohorts list, please.");
    t.succeeded();
    t.calledTool("navigate_to", {
      input: { view: "cohorts" },
      status: "pending",
      count: 1,
    });
  },
});
