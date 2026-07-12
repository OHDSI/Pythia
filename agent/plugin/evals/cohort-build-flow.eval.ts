import { defineEval } from "eve/evals";

export default defineEval({
  description:
    "multi-turn: design request + explicit go-ahead ends in a pending cohort-build proposal (set_entry_event)",
  tags: ["flow", "slow"],
  timeoutMs: 300_000,
  async test(t) {
    // Scenario calibrated on a live Sonnet 4.6 run, on two axes:
    // - "single cohort, no plan": an open-ended cohort design is multi-phase,
    //   so the prompt's Plans section makes the agent select_plan_template
    //   first — and plan flows dead-end in the eval harness (plan-step tools
    //   are clientOnly; nothing in `eve eval` resolves the pending call, the
    //   Atlas3 frontend does). The prompt documents single-cohort requests as
    //   the no-plan case, which keeps the flow on the direct proposal path.
    // - GI hemorrhage instead of T2DM: Eunomia's subset vocabulary actually
    //   contains it, so search_concepts returns real rows and the agent can
    //   propose concrete criteria instead of stalling on an empty vocabulary.
    await t.send(
      "Design a new cohort of adults with gastrointestinal hemorrhage. Assume no existing " +
        "cohort fits. Keep it simple: one single cohort, no plan needed.",
    );
    await t.send(
      "Yes — proceed with the full design now. Resolve the concept IDs and propose all " +
        "inclusion and exclusion criteria in one batch. Do not create a plan and do not " +
        "ask me anything else.",
    );
    t.succeeded();
    t.calledTool("search_concepts");
    // Terminal proposal calibrated on a live Sonnet 4.6 run: for a
    // from-scratch cohort the FIRST proposal is set_entry_event (the index
    // event precedes any criteria — prompt "ATLAS v3.0 cohort model"), and a
    // turn ends at its first pending clientOnly proposal. add_criteria can
    // only follow after the user accepts the entry event in the real
    // frontend, which `eve eval` cannot do — so the flow's observable
    // terminal state here is a pending set_entry_event, not add_criteria.
    t.calledTool("set_entry_event", { status: "pending" });
    t.noFailedActions();
  },
});
