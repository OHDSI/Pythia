import { defineEval } from "eve/evals";

export default defineEval({
  description:
    "a new-user design restricts entry to the first qualifying event (set_event_limits) " +
    "rather than accepting the default, which counts episodes instead of people",
  tags: ["workflow", "correctness"],
  async test(t) {
    await t.send(
      "This should be a new-user design: each person should enter only at their first " +
        "qualifying ibuprofen exposure. Set that now — no plan, and do not ask me anything else.",
    );
    t.succeeded();
    t.calledTool("set_event_limits", { status: "pending" });
  },
});
