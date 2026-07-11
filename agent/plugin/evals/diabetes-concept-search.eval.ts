import { defineEval } from "eve/evals";
import { includes } from "eve/evals/expect";

export default defineEval({
  description:
    "pythia searches OMOP standard concepts for a diabetes cohort via search_concepts and surfaces standard concepts in its reply",
  async test(t) {
    await t.send(
      "I'm building a cohort of type 2 diabetes patients. Search for the standard OMOP concepts for type 2 diabetes.",
    );
    t.succeeded();
    t.calledTool("search_concepts");
    t.check(t.reply, includes(/standard concept/i));
  },
});
