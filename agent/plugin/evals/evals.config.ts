import { defineEvalConfig } from "eve/evals";

// Per the proven trex reference (plugins-dev/toy-agent/evals/evals.config.ts
// in the trex repo, Task 9), an empty defineEvalConfig({}) is sufficient —
// judge/reporters/maxConcurrency/timeoutMs are all optional per eve's own
// docs (evals/overview.mdx, "Everything is optional").
//
// This tree adds one thing beyond the proven baseline: a default `judge`
// model, needed by evals/medical-advice-refusal.eval.ts's
// `t.judge.autoevals.closedQA(...)` assertion. No toy-agent eval used a
// judge, so there is no proven value to copy here — this is a placeholder,
// not a verified one. Pick a model actually credentialed for the target
// deployment's judge role before relying on this in CI; this repo's agent
// itself resolves models via TREX_AGENTS_DEFAULT_MODEL
// (bedrock/${BAO_AGENT_MODEL:-minimax.minimax-m2.5} per docker-compose.yml),
// so a Bedrock judge model is used here as a same-provider guess, unverified
// against a live judge call.
export default defineEvalConfig({
  judge: { model: "bedrock/anthropic.claude-3-5-sonnet-20241022-v2:0" },
});
