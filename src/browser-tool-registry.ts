/**
 * Frontend adapter for tools supplied dynamically by the page hosting Pythia.
 *
 * These are not Pythia's compiled-in clientOnly proposal tools. The host page
 * publishes whichever browser tools are currently mounted through
 * `window.__pythiaClientTools`; Pythia reads them for each model request and
 * resolves selected calls against the live registry.
 *
 * The host page is untrusted: `list()` may throw or return garbage, and a
 * descriptor may reuse the name of one of Pythia's own tools. Both are
 * filtered here with the same rules `agent/plugin/agent/dynamic-tools.ts`
 * applies server-side, so the frontend never advertises or dispatches a tool
 * the backend would drop.
 */
import { RESERVED_TOOL_NAMES } from '../agent/plugin/agent/reserved-tool-names'

export interface BrowserToolDescriptor {
  name: string
  description: string
  inputSchema: Record<string, unknown>
}

export interface BrowserToolResult {
  content: Array<{ type: string; text?: string; [key: string]: unknown }>
  isError?: boolean
}

export interface BrowserToolRegistry {
  version: 1
  list: () => BrowserToolDescriptor[]
  call: (name: string, args?: Record<string, unknown>) => Promise<BrowserToolResult>
}

declare global {
  interface Window {
    __pythiaClientTools?: BrowserToolRegistry
  }
}

function registry(): BrowserToolRegistry | undefined {
  const value = typeof window === 'undefined' ? undefined : window.__pythiaClientTools
  return value?.version === 1 ? value : undefined
}

const MAX_TOOLS = 32
const MAX_DESCRIPTION_LENGTH = 2_000
const TOOL_NAME = /^[a-z][a-z0-9_]{0,63}$/
const RESERVED = new Set(RESERVED_TOOL_NAMES)

function plainObject(value: unknown): value is Record<string, unknown> {
  return !!value && typeof value === 'object' && !Array.isArray(value)
}

function isDescriptor(value: unknown): value is BrowserToolDescriptor {
  return plainObject(value) &&
    typeof value.name === 'string' &&
    TOOL_NAME.test(value.name) &&
    !RESERVED.has(value.name) &&
    typeof value.description === 'string' &&
    value.description.length > 0 &&
    value.description.length <= MAX_DESCRIPTION_LENGTH &&
    plainObject(value.inputSchema)
}

export function listBrowserTools(): BrowserToolDescriptor[] {
  let raw: unknown
  try {
    raw = registry()?.list()
  } catch {
    return []
  }
  if (!Array.isArray(raw)) return []
  const seen = new Set<string>()
  const tools: BrowserToolDescriptor[] = []
  for (const candidate of raw) {
    if (tools.length >= MAX_TOOLS) break
    if (!isDescriptor(candidate) || seen.has(candidate.name)) continue
    seen.add(candidate.name)
    tools.push({
      name: candidate.name,
      description: candidate.description,
      inputSchema: candidate.inputSchema,
    })
  }
  return tools
}

export async function callBrowserTool(
  name: string,
  input: unknown,
): Promise<BrowserToolResult> {
  const live = registry()
  if (!live) throw new Error('Browser tools are unavailable.')
  const args = input && typeof input === 'object' && !Array.isArray(input)
    ? input as Record<string, unknown>
    : {}
  const result: unknown = await live.call(name, args)
  if (!plainObject(result) || !Array.isArray(result.content)) {
    throw new Error(`Browser tool "${name}" returned an invalid result.`)
  }
  return result as unknown as BrowserToolResult
}
