import { defineEval } from "eve/evals";
import { satisfies } from "eve/evals/expect";

export default defineEval({
  description:
    "concept IDs quoted in the reply come from search_concepts results, not model recall",
  tags: ["grounding"],
  async test(t) {
    await t.send(
      "Search the vocabulary for the standard OMOP concept for essential hypertension and tell me its concept ID.",
    );
    t.succeeded();

    // Topical assertion: the agent did search for hypertension.
    t.calledTool("search_concepts", { input: { query: /hypertens/i } });

    // Capture EVERY search_concepts output (no input constraint, so a
    // rephrased second search still lands in the grounding set — eve's
    // matcher short-circuits on input before evaluating output).
    const outputs: unknown[] = [];
    t.calledTool("search_concepts", {
      output: (v: unknown) => {
        outputs.push(v);
        return true;
      },
    });

    const returnedIds = new Set(JSON.stringify(outputs).match(/\d{4,10}/g) ?? []);
    const quotedIds = String(t.reply).match(/\b\d{6,10}\b/g) ?? [];
    t.check(
      quotedIds,
      satisfies(
        (ids: string[]) => ids.every((id) => returnedIds.has(id)),
        "every concept ID quoted in the reply appears in a search_concepts result",
      ),
    );
  },
});
