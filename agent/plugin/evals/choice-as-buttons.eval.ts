import { defineEval } from "eve/evals";

export default defineEval({
  description:
    "a choice the user has to make comes as ask_user buttons, also when it closes an advisory answer " +
    "(it used to end in a plain-text either/or question)",
  tags: ["workflow", "ask-user"],
  async test(t) {
    await t.send("What phenotype should I use for chronic kidney disease?");
    t.succeeded();
    t.calledTool("search_phenotypes", { input: { query: /kidney/i } });
    t.calledTool("ask_user", { status: "pending", count: 1 });
  },
});
