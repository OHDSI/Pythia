import { defineEval } from "eve/evals";
import { satisfies } from "eve/evals/expect";

export default defineEval({
  description:
    "an exclusion is encoded, not named: asking to exclude prior events must produce zero " +
    "cardinality (add_criterion group=exclusion, or add_inclusion_rule AT_MOST 0), never an " +
    "ordinary ALL rule that REQUIRES the event it claims to exclude",
  tags: ["workflow", "correctness"],
  async test(t) {
    // Observed live: the agent named a rule "…prior GI safety exclusions" and
    // encoded all three criteria as "at least 1", so the cohort REQUIRED a GI
    // bleed. It built, generated and read back fine — the definition simply
    // answered the opposite question.
    const turn = await t.send(
      "Build a cohort of adults starting ibuprofen, excluding anyone with a previous " +
        "gastrointestinal bleed. Propose the exclusion.",
    );
    t.succeeded();

    const calls = turn.toolCalls;
    const encodedAsAbsence = calls.some((c) => {
      const input = (c.input ?? {}) as Record<string, unknown>
      if (c.name === "add_criterion") return input.group === "exclusion";
      if (c.name === "add_criteria") {
        const items = (input.items ?? []) as Array<{ group?: string }>;
        return items.some((i) => i.group === "exclusion");
      }
      if (c.name === "add_inclusion_rule") {
        return input.logicType === "AT_MOST" && Number(input.count ?? -1) === 0;
      }
      return false;
    });

    t.check(
      encodedAsAbsence,
      satisfies(
        (v: boolean) => v,
        "the exclusion carries zero cardinality (group=exclusion, or AT_MOST 0)",
      ),
    );
  },
});
