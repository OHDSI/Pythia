import { defineToolProvider } from "eve/tools";
import { RESERVED_TOOL_NAMES } from "./reserved-tool-names.ts";

/**
 * TREX's conventional per-request tool-provider entry point.
 *
 * Pythia's compiled-in tools live under tools/. This file only handles tools
 * discovered from the current browser page. Their request metadata is
 * untrusted, so it is bounded and validated before schemas are advertised as
 * clientOnly. Selected calls are streamed back to the frontend for execution;
 * no browser code runs here.
 */

interface BrowserToolDefinition {
  description: string;
  inputSchema: Record<string, unknown>;
  clientOnly: true;
}

const MAX_TOOLS = 32;
const MAX_NAME_LENGTH = 64;
const MAX_DESCRIPTION_LENGTH = 2_000;
const MAX_SCHEMA_LENGTH = 32_000;
const TOOL_NAME = /^[a-z][a-z0-9_]*$/;
// Authored tools already win a name collision in trex's toolset merge, but
// the frontend would still execute the browser tool of the same name. Drop
// such descriptors here so a collision is never advertised at all.
const RESERVED = new Set(RESERVED_TOOL_NAMES);

function plainObject(value: unknown): value is Record<string, unknown> {
  return !!value && typeof value === "object" && !Array.isArray(value);
}

function buildBrowserToolDefinitions(metadata: unknown): Record<string, BrowserToolDefinition> {
  if (!plainObject(metadata)) return {};
  const clientTools = metadata.clientTools;
  if (!plainObject(clientTools) || clientTools.version !== 1 || !Array.isArray(clientTools.tools)) return {};

  const result: Record<string, BrowserToolDefinition> = {};
  for (const candidate of clientTools.tools.slice(0, MAX_TOOLS)) {
    if (!plainObject(candidate)) continue;
    const { name, description, inputSchema } = candidate;
    if (
      typeof name !== "string" ||
      name.length > MAX_NAME_LENGTH ||
      !TOOL_NAME.test(name) ||
      RESERVED.has(name) ||
      Object.hasOwn(result, name) ||
      typeof description !== "string" ||
      description.length === 0 ||
      description.length > MAX_DESCRIPTION_LENGTH ||
      !plainObject(inputSchema)
    ) continue;

    if (JSON.stringify(inputSchema).length > MAX_SCHEMA_LENGTH) continue;

    result[name] = { description, inputSchema, clientOnly: true };
  }
  return result;
}

export default defineToolProvider(async (ctx) => buildBrowserToolDefinitions(ctx.metadata));
