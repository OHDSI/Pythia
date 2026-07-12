import { defineEval } from "eve/evals";
import { satisfies } from "eve/evals/expect";

export default defineEval({
  description:
    "concept IDs quoted in the reply come from search_concepts results, not model recall",
  tags: ["grounding"],
  async test(t) {
    // Scenario term calibrated on a live Eunomia run: Eunomia's subset
    // vocabulary has NO hypertension concepts at all (search_concepts
    // returned 0 rows, leaving the agent nothing to ground its answer in),
    // but "Gastrointestinal hemorrhage" (a Standard Condition concept) IS
    // present — so the grounding invariant is actually satisfiable. The
    // assertion itself stays data-independent: it checks reply/tool-output
    // consistency, never a specific ID.
    const turn = await t.send(
      "Search the vocabulary for the standard OMOP concept for gastrointestinal hemorrhage and tell me its concept ID.",
    );
    t.succeeded();

    // Topical assertion: the agent did search for GI hemorrhage.
    t.calledTool("search_concepts", { input: { query: /h(a?)emorrhage|gi bleed/i } });

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

    // Asymmetric windows, deliberately: the allow-set regex (4-10 digits over
    // stringified outputs) over-collects (record counts, years) — it can only
    // ever make the subset check MORE permissive, never false-fail. The reply
    // side floors at 6 digits so prose years ("2020") don't count as concept
    // IDs; the cost is that a fabricated 4-5 digit ID slips past this check.
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
