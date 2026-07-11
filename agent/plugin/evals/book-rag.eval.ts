import { defineEval } from "eve/evals";

export default defineEval({
  description:
    "pythia grounds methodology guidance in the Book of OHDSI via search_ohdsi_book (BM25 RAG over resources/book-of-ohdsi/passages.edn)",
  async test(t) {
    await t.send(
      "What washout period should I use before the index event in a new-user cohort design? " +
        "Check the Book of OHDSI for guidance.",
    );
    t.succeeded();
    t.calledTool("search_ohdsi_book");
  },
});
