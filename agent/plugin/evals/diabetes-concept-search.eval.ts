import { defineEval } from "eve/evals";
import { includes } from "eve/evals/expect";

export default defineEval({
  description:
    "pythia searches OMOP standard concepts for a diabetes cohort via search_concepts and surfaces standard concepts in its reply",
  tags: ["workflow"],
  async test(t) {
    await t.send(
      "I'm building a cohort of type 2 diabetes patients. Search for the standard OMOP concepts for type 2 diabetes.",
    );
    t.succeeded();
    t.calledTool("search_concepts", { input: { query: /diabet/i } });
    t.noFailedActions();
    // Flaky as an exact phrase: identical input produced pass/fail on
    // successive runs because the reply sometimes says "Standard concepts",
    // sometimes "SNOMED standard codes", sometimes names the vocabulary
    // outright. The intent is that the reply talks about standard vocabulary
    // rather than source codes, so accept how it is actually phrased.
    t.check(t.reply, includes(/standard\s+concept|standard\s+vocabular|SNOMED/i));
  },
});
