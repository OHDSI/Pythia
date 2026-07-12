import { defineEval } from "eve/evals";
import { satisfies } from "eve/evals/expect";

export default defineEval({
  description:
    "concept IDs quoted in the reply come from search_concepts results, not model recall",
  tags: ["grounding"],
  async test(t) {
    const turn = await t.send(
      "Search the vocabulary for the standard OMOP concept for essential hypertension and tell me its concept ID.",
    );
    t.succeeded();

    // Topical assertion: the agent did search for hypertension.
    t.calledTool("search_concepts", { input: { query: /hypertens/i } });

    // Grounding check computed EAGERLY here in the test body — not inside a
    // deferred assertion. In eve@0.19.0 `t.calledTool` is a *deferred* scoped
    // assertion (AssertionCollector.recordScoped): its output predicate only
    // runs during finalize(), AFTER test() returns. But `t.check` is an
    // *eager* assertion (recordValue → settleEntry invokes the score fn
    // synchronously at record time), so any value it scores must already be
    // final in the test body. We therefore use `turn.toolCalls` — eve's
    // pre-derived, typed view of this turn's tool calls (same facts
    // `t.calledTool` matches against), available synchronously here — unlike
    // deferred assertion matchers, which only evaluate at finalize(), after
    // test() returns.
    const outputs = turn.toolCalls
      .filter((c) => c.name === "search_concepts")
      .map((c) => c.output);

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
