/**
 * Frontend adapter for tools supplied dynamically by the page hosting Pythia.
 *
 * These are not Pythia's compiled-in clientOnly proposal tools. The host page
 * publishes whichever browser tools are currently mounted through
 * `window.__pythiaClientTools`; Pythia reads them for each model request and
 * resolves selected calls against the live registry.
 */
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

export function listBrowserTools(): BrowserToolDescriptor[] {
  return registry()?.list() ?? []
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
  return live.call(name, args)
}
