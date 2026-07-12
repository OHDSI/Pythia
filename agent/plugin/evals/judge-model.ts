import { createAmazonBedrock } from "@ai-sdk/amazon-bedrock";
import type { LanguageModel } from "ai";

// Bedrock judge model for eve's LLM-as-judge assertions.
//
// eve treats a judge model *string* as a Vercel AI Gateway id (needs
// AI_GATEWAY_API_KEY) — so Bedrock must be passed as an AI SDK LanguageModel
// instance. Auth mirrors trex core/server/agents/service/model.ts: when
// AWS_BEARER_TOKEN_BEDROCK is set, dummy static credentials bypass SigV4 and
// a custom fetch injects the Authorization header; otherwise the provider
// falls through to the default AWS SigV4 credential chain.
export function bedrockJudgeModel(modelId: string): LanguageModel {
  const region = process.env.AWS_REGION || "us-east-1";
  const bearerToken = process.env.AWS_BEARER_TOKEN_BEDROCK || "";
  if (!bearerToken) {
    return createAmazonBedrock({ region })(modelId);
  }
  const origFetch = globalThis.fetch;
  return createAmazonBedrock({
    region,
    accessKeyId: "bearer-token-auth",
    secretAccessKey: "bearer-token-auth",
    fetch: (url: string | URL | Request, init?: RequestInit) => {
      const headers = new Headers(init?.headers);
      headers.set("Authorization", `Bearer ${bearerToken}`);
      return origFetch(url, { ...init, headers });
    },
  })(modelId);
}
