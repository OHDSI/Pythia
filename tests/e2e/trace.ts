import type { Page } from '@playwright/test'

// Records the conversation between the Pythia panel and the agent at the
// protocol level, so tests can assert on how the LLM got to its cohort and not
// only on the end state.
//
// Two sources, because the two halves of a tool call travel separately:
// - the agent's response stream (AI SDK UI message stream over SSE) carries
//   every tool call the model makes and the outputs of server-side tools;
// - the next request's `messages` carry the outputs of client-side tools
//   (proposals, ask_user, navigate_to), which the panel resolves in the browser
//   and the agent sees only as part of the history.

export interface ToolCall {
  id: string
  name: string
  input: unknown
  output?: unknown
  error?: string
  // server: executed by the agent. client: resolved by the panel/ATLAS.
  side?: 'server' | 'client'
  // Position in the order the model made the calls.
  seq: number
}

export class ChatTrace {
  readonly calls = new Map<string, ToolCall>()
  readonly text: string[] = []
  readonly streamErrors: string[] = []
  requests = 0
  private seq = 0
  private pending: Promise<void>[] = []

  static attach(page: Page, pathPattern = /\/trex\/pythia\/chat\b|\/pythia\/chat\b/): ChatTrace {
    const trace = new ChatTrace()
    page.on('request', req => {
      if (req.method() !== 'POST' || !pathPattern.test(req.url())) return
      try { trace.ingestRequest(req.postDataJSON()) } catch { /* not JSON */ }
    })
    page.on('response', res => {
      if (res.request().method() !== 'POST' || !pathPattern.test(res.url())) return
      // body() resolves once the stream has finished.
      trace.pending.push(res.text().then(body => trace.ingestStream(body), () => {}))
    })
    return trace
  }

  // Waits for streams still in flight, so assertions see the final turn.
  async settle(): Promise<void> {
    await Promise.all(this.pending)
  }

  private call(id: string, name?: string): ToolCall {
    let c = this.calls.get(id)
    if (!c) {
      c = { id, name: name ?? '?', input: undefined, seq: this.seq++ }
      this.calls.set(id, c)
    } else if (name && c.name === '?') c.name = name
    return c
  }

  ingestRequest(body: any) {
    this.requests++
    for (const msg of body?.messages ?? []) {
      if (msg.role !== 'assistant') continue
      for (const part of msg.parts ?? []) {
        const name = part.type === 'dynamic-tool' ? part.toolName
          : typeof part.type === 'string' && part.type.startsWith('tool-') ? part.type.slice(5) : undefined
        if (!name || !part.toolCallId) continue
        const c = this.call(part.toolCallId, name)
        if (c.input === undefined) c.input = part.input
        if (part.state === 'output-available' && c.output === undefined) { c.output = part.output; c.side ??= 'client' }
        if (part.state === 'output-error' && c.error === undefined) { c.error = String(part.errorText ?? 'error'); c.side ??= 'client' }
      }
    }
  }

  ingestStream(body: string) {
    for (const line of body.split('\n')) {
      if (!line.startsWith('data:')) continue
      const data = line.slice(5).trim()
      if (!data || data === '[DONE]') continue
      let ev: any
      try { ev = JSON.parse(data) } catch { continue }
      switch (ev.type) {
        case 'tool-input-available': {
          const c = this.call(ev.toolCallId, ev.toolName)
          c.input = ev.input
          break
        }
        case 'tool-output-available': {
          const c = this.call(ev.toolCallId)
          c.output = ev.output
          c.side = 'server'
          break
        }
        case 'tool-output-error':
        case 'tool-input-error': {
          const c = this.call(ev.toolCallId, ev.toolName)
          c.error = String(ev.errorText ?? 'error')
          c.side = 'server'
          break
        }
        case 'text-delta':
          if (typeof ev.delta === 'string') this.text.push(ev.delta)
          break
        case 'error':
          this.streamErrors.push(String(ev.errorText ?? JSON.stringify(ev)))
          break
      }
    }
  }

  ordered(): ToolCall[] {
    return [...this.calls.values()].sort((a, b) => a.seq - b.seq)
  }

  named(...names: string[]): ToolCall[] {
    return this.ordered().filter(c => names.includes(c.name))
  }

  first(name: string): ToolCall | undefined {
    return this.named(name)[0]
  }

  // Every concept id that came back from a tool before call `before` (by
  // sequence), read from any field named like a concept id. These are the ids
  // the model was shown, which is what a grounded proposal may use.
  groundedConceptIds(before = Infinity): Set<number> {
    const ids = new Set<number>()
    for (const c of this.ordered()) {
      if (c.seq >= before || c.output === undefined) continue
      collectConceptIds(c.output, ids)
    }
    return ids
  }

  summary(): string {
    return this.ordered().map(c => {
      const status = c.error ? `ERROR ${c.error.slice(0, 120)}` : c.output === undefined ? 'no result' : decisionOf(c) ?? 'ok'
      return `${String(c.seq).padStart(3)} ${c.name} [${c.side ?? '?'}] ${status} ${JSON.stringify(c.input ?? {}).slice(0, 160)}`
    }).join('\n')
  }

  toJSON() {
    return { requests: this.requests, streamErrors: this.streamErrors, calls: this.ordered(), text: this.text.join('') }
  }
}

// The panel answers a proposal with { decision: accepted|rejected|dismissed|pending, ... }.
export function decisionOf(c: ToolCall): string | undefined {
  const o = c.output as any
  if (o && typeof o === 'object' && typeof o.decision === 'string') return o.applied === false ? `${o.decision} (not applied)` : o.decision
  return undefined
}

const CONCEPT_KEY = /^(concept_?id|CONCEPT_ID|conceptIds?|concept_ids|standardConceptId|standard_concept_id|targetConceptId|target_concept_id)$/i

export function collectConceptIds(node: unknown, out: Set<number>, keyIsConcept = false): Set<number> {
  if (Array.isArray(node)) { for (const n of node) collectConceptIds(n, out, keyIsConcept); return out }
  if (typeof node === 'number') { if (keyIsConcept && Number.isInteger(node) && node > 0) out.add(node); return out }
  if (typeof node === 'string') {
    // Some tools return their payload as a JSON string, or ids as "1, 2".
    const s = node.trim()
    if ((s.startsWith('{') || s.startsWith('[')) && s.length < 2_000_000) {
      try { return collectConceptIds(JSON.parse(s), out, keyIsConcept) } catch { /* not JSON */ }
    }
    if (keyIsConcept) for (const m of s.matchAll(/\b\d{2,10}\b/g)) out.add(Number(m[0]))
    return out
  }
  if (node && typeof node === 'object') {
    for (const [k, v] of Object.entries(node)) collectConceptIds(v, out, CONCEPT_KEY.test(k))
  }
  return out
}
