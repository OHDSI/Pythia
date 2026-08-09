import { defineEval } from "eve/evals";

export default defineEval({
  description:
    "dropping part of a cohort uses remove_inclusion_rule rather than rebuilding the " +
    "definition or telling the user to edit it by hand",
  tags: ["workflow", "hitl"],
  async test(t) {
    await t.send(
      "The cohort has an inclusion rule called 'Exclude prior GI bleed'. Drop that rule.",
    );
    t.succeeded();
    // clientOnly proposal: visible as pending, resolved by the frontend.
    t.calledTool("remove_inclusion_rule", { status: "pending" });
  },
});
