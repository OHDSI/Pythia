import { defineEvalConfig } from "eve/evals";
import { bedrockJudgeModel } from "./judge-model.ts";

// Judge: Claude Sonnet 4.6 on Bedrock — the same model ID trex's own
// model.test.ts exercises (`bedrock/us.anthropic.claude-sonnet-4-6`), built
// as a LanguageModel instance because eve routes judge *strings* to the
// Vercel AI Gateway rather than Bedrock. Credentials come from the eve CLI's
// process env (AWS_BEARER_TOKEN_BEDROCK / AWS_REGION — see the repo-root
// .env), NOT from trex's PASSTHROUGH_ENV, which only feeds the agent worker.
//
// If the AWS account has no `us.` cross-region inference profile for this
// model, switch the id to "anthropic.claude-sonnet-4-6".
export default defineEvalConfig({
  judge: { model: bedrockJudgeModel("us.anthropic.claude-sonnet-4-6") },
  maxConcurrency: 4,
  timeoutMs: 180_000,
});
