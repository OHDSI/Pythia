import { defineEval } from "eve/evals";

export default defineEval({
  description:
    "pythia grounds methodology guidance in the Book of OHDSI via search_ohdsi_book and cites it",
  tags: ["rag"],
  async test(t) {
    await t.send(
      "What washout period should I use before the index event in a new-user cohort design? " +
        "Check the Book of OHDSI for guidance.",
    );
    t.succeeded();
    t.calledTool("search_ohdsi_book");
    t.judge.autoevals
      .closedQA(
        "gives concrete washout-period guidance for a new-user cohort design AND attributes it " +
          "to the Book of OHDSI (names the book, and ideally a chapter or section)",
      )
      .atLeast(0.7);
  },
});
