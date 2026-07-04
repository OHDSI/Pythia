// Module-level chat session manager. The Chat instance lives outside any
// component setup() so it survives parcel unmount/remount. Multiple sessions
// are stored in localStorage; the user can switch between them via the
// toolbar picker without losing in-flight history.

import { ref, watch } from 'vue'
import { Chat } from '@ai-sdk/vue'
import {
  DefaultChatTransport,
  lastAssistantMessageIsCompleteWithToolCalls,
  type UIMessage,
} from 'ai'
import type { ArtifactKind, RouteContext } from './shell-bridge'
import type { MessageBus } from './main'
import type { AskState, Plan, PlanStepStatus, ProposalState } from './types'
import {
  activePlan,
  applyGatedPlan,
  applyPlanToolCall,
  gateProposal,
  isPlanTool,
  markStepProgress,
  planHistory,
  resetPlans,
  restorePlans,
  snapshotPlans,
} from './plan-state'
import type { GatedPlanPayload } from './plan-state'

let _hostBus: MessageBus | null = null
let hostApplyProposal: ((p: unknown) => void) | null = null
export function setHostBridge(opts: { bus: MessageBus; applyProposal: (p: unknown) => void }) {
  _hostBus = opts.bus
  hostApplyProposal = opts.applyProposal
}

export interface LastNavigation {
  id: string             // tool call id, used to dedupe display
  toName: string         // route the agent navigated to
  reason?: string
  previous: { name: string; params: Record<string, string | number> } | null
  at: number             // Date.now() — used by ChatPanel to expire after 5s
}

export const lastNavigation = ref<LastNavigation | null>(null)

export interface NavigateHandlerDeps {
  addToolResult: (r: { tool: string; toolCallId: string; output: unknown }) => void
}

interface CapabilityApplyResult {
  applied: boolean
  kind?: string
  id?: number | string
  name?: string
}

// Delegates translation+apply to ATLAS's `capability.apply` bus handler
// (Task A3 moved the CIRCE translation there). ATLAS validates the view
// against its route manifest and performs the navigation; we only rebuild
// the undo toast from the tool call's own args plus our local
// sessionRouteContext, since ATLAS's reply doesn't echo the route/reason.
export async function handleNavigateTool(
  toolCall: { toolCallId: string; input: unknown },
  deps: NavigateHandlerDeps
): Promise<void> {
  const args = (toolCall.input ?? {}) as Record<string, unknown>

  // Capture the previous route from the last-fetched shell context so the
  // user can undo. sessionRouteContext is refreshed by ChatPanel before
  // each message send, so it reflects where the user was AT SEND TIME —
  // which is when the model decided to navigate.
  const ctx = sessionRouteContext.value
  const previous = ctx?.routeName
    ? { name: ctx.routeName, params: { ...(ctx.routeParams ?? {}) } as Record<string, string | number> }
    : null

  const res = await _hostBus?.request<CapabilityApplyResult>('capability.apply', {
    name: 'navigate_to',
    args,
  })

  if (!res?.applied) {
    deps.addToolResult({
      tool: 'navigate_to',
      toolCallId: toolCall.toolCallId,
      output: {
        success: false,
        applied: false,
        error: 'Unknown or hidden view',
        instruction:
          'navigate_to was rejected because the view is not in the route manifest. List your available views or pick a different one.',
      },
    })
    return
  }

  const toName = args.view as string
  const reason = typeof args.reason === 'string' ? args.reason : undefined

  lastNavigation.value = {
    id: toolCall.toolCallId,
    toName,
    reason,
    previous,
    at: Date.now(),
  }

  deps.addToolResult({
    tool: 'navigate_to',
    toolCallId: toolCall.toolCallId,
    output: {
      success: true,
      applied: true,
      route: toName,
      undoAvailable: previous !== null,
      instruction:
        'You took the user to a new view. Continue your turn; the user can undo via the toast if they wanted to stay.',
    },
  })
}

export function undoLastNavigation(): boolean {
  const ln = lastNavigation.value
  if (!ln || !ln.previous || !hostApplyProposal) return false
  hostApplyProposal({
    kind: 'navigate',
    route: { name: ln.previous.name, params: ln.previous.params },
    reason: 'Undo Pythia navigation',
  })
  lastNavigation.value = null
  return true
}

export function dismissLastNavigation(): void {
  lastNavigation.value = null
}

const INDEX_KEY = 'cohort-agent-plugin.sessions.v1.index'
const ACTIVE_KEY = 'cohort-agent-plugin.sessions.v1.active'
const SESSION_KEY = (id: string) => `cohort-agent-plugin.session.v1.${id}`

export const CLIENT_SIDE_TOOLS = new Set([
  'add_criterion',
  'add_criteria',
  'set_entry_event',
  'set_observation_window',
  'add_exit_criterion',
  'set_censor_event',
  'add_inclusion_rule',
  'save_cohort',
  'create_standalone_concept_set',
  'create_feature_analysis',
  'create_characterization',
  'create_pathway',
  'create_incidence_rate',
  // Edit-existing artifact tools (Phase 3 — partial-merge into open editor)
  'update_concept_set',
  'update_feature_analysis',
  'update_characterization',
  'update_pathway',
  'update_incidence_rate',
])

// Discriminates the `AgentProposal.kind` a client-side tool call would
// produce, WITHOUT doing the full CIRCE translation — that logic is
// single-homed in ATLAS's `translateCapability`
// (src/plugins/host/capabilities/translate.ts). This is used only by the
// plan gate-skip check in `onToolCall`, which needs the kind synchronously,
// before the (async) `capability.apply` round-trip to ATLAS happens. Must be
// kept in lockstep with `translateCapability`'s kind mapping — see the
// fidelity test in chat-session.spec.ts.
export function proposalKind(name: string, args: Record<string, unknown>): string | null {
  switch (name) {
    case 'add_criterion':
      return args.group ? 'addInclusionRule' : 'addEntryEvent'
    case 'add_criteria':
    case 'add_inclusion_rule':
      return 'addInclusionRule'
    case 'set_entry_event':
      return 'addEntryEvent'
    case 'set_observation_window':
      return 'setObservationPeriod'
    case 'add_exit_criterion':
      return 'setExitCriteria'
    case 'set_censor_event':
      return 'addCensoringCriterion'
    case 'navigate_to':
      return 'navigate'
    case 'save_cohort':
      return 'saveCohort'
    case 'create_concept_set':
      return 'addConceptSet'
    case 'create_standalone_concept_set':
      return 'createStandaloneConceptSet'
    case 'create_feature_analysis':
      return 'createFeatureAnalysis'
    case 'create_characterization':
      return 'createCharacterization'
    case 'create_pathway':
      return 'createPathway'
    case 'create_incidence_rate':
      return 'createIncidenceRate'
    case 'update_concept_set':
      return 'updateConceptSet'
    case 'update_feature_analysis':
      return 'updateFeatureAnalysis'
    case 'update_characterization':
      return 'updateCharacterization'
    case 'update_pathway':
      return 'updatePathway'
    case 'update_incidence_rate':
      return 'updateIncidenceRate'
    default:
      return null
  }
}

export const sessionToken = ref<string | null>(null)
export const sessionSourceKey = ref<string | null>(null)
// Updated by ChatPanel.send() with a fresh ShellContext snapshot before
// each chat request, so the model sees where the user is at submit time
// rather than at panel-mount time.
export const sessionRouteContext = ref<RouteContext | null>(null)
export const proposals = ref<Record<string, ProposalState>>({})
// Active ask_user prompts. Keyed by toolCallId so a refresh mid-question
// can re-render the buttons without losing state.
export const asks = ref<Record<string, AskState>>({})

// True when the last completed agent turn finished because we capped the
// auto-loop at MAX_AUTO_STEPS (model still wanted more tool calls, but we
// stopped sending). Drives the "Continue?" inline card in ChatPanel.
// Cleared whenever a new message is sent.
export const maxStepsReached = ref(false)

// Live token getter set by the host on parcel mount. We prefer this over
// reading sessionToken because Atlas3 silently refreshes the JWT in the
// background — a snapshot taken at panel-mount time goes stale and the
// next chat request 401s.
let tokenProvider: (() => Promise<string>) | null = null
export function setTokenProvider(fn: (() => Promise<string>) | null) {
  tokenProvider = fn
}

export interface SessionMeta {
  id: string
  title: string
  updatedAt: number
}

export const sessionIndex = ref<SessionMeta[]>([])
export const activeSessionId = ref<string>('')

interface PersistedSession {
  messages: UIMessage[]
  proposals: Record<string, ProposalState>
  activePlan?: Plan | null
  planHistory?: Plan[]
  // Legacy field names from before the Checklist→Plan rename. Kept on the
  // read path so old persisted sessions don't drop their plan card. Not
  // written by current code.
  activeChecklist?: Plan | null
  checklistHistory?: Plan[]
  asks?: Record<string, AskState>
}

function safeRead<T>(key: string, fallback: T): T {
  if (typeof localStorage === 'undefined') return fallback
  try {
    const raw = localStorage.getItem(key)
    return raw ? (JSON.parse(raw) as T) : fallback
  } catch {
    return fallback
  }
}

function safeWrite(key: string, value: unknown) {
  if (typeof localStorage === 'undefined') return
  try {
    localStorage.setItem(key, JSON.stringify(value))
  } catch {
    // quota exceeded or storage disabled — silently ignore
  }
}

// Global, independent of any specific proposal card and any single chat
// session — a standing user preference for how much manual review they
// want, mirroring Claude Code's own auto-accept-edits toggle. Persisted so
// it survives reloads; NOT part of PersistedSession, since it isn't tied
// to one chat's content.
const AUTO_APPROVE_KEY = 'cohort-agent-plugin.autoApprove.v1'
export const autoApproveProposals = ref<boolean>(safeRead<boolean>(AUTO_APPROVE_KEY, false))

export function setAutoApproveProposals(value: boolean): void {
  autoApproveProposals.value = value
  safeWrite(AUTO_APPROVE_KEY, value)
}

function readIndex(): SessionMeta[] {
  return safeRead<SessionMeta[]>(INDEX_KEY, [])
}

function writeIndex(idx: SessionMeta[]) {
  safeWrite(INDEX_KEY, idx)
  sessionIndex.value = [...idx].sort((a, b) => b.updatedAt - a.updatedAt)
}

function readSession(id: string): PersistedSession {
  const sess = safeRead<PersistedSession>(SESSION_KEY(id), { messages: [], proposals: {} })
  return {
    messages: sess.messages ?? [],
    proposals: sess.proposals ?? {},
    // Prefer the new field names; fall back to the legacy
    // `activeChecklist`/`checklistHistory` for sessions persisted before
    // the rename so old chats don't lose their plan card.
    activePlan: sess.activePlan ?? sess.activeChecklist ?? null,
    planHistory: Array.isArray(sess.planHistory)
      ? sess.planHistory
      : Array.isArray(sess.checklistHistory)
        ? sess.checklistHistory
        : [],
    asks: sess.asks ?? {},
  }
}

function writeSession(id: string, sess: PersistedSession) {
  safeWrite(SESSION_KEY(id), sess)
}

function deleteSession(id: string) {
  if (typeof localStorage === 'undefined') return
  try { localStorage.removeItem(SESSION_KEY(id)) } catch { /* noop */ }
}

function newSessionId(): string {
  if (typeof crypto !== 'undefined' && 'randomUUID' in crypto) return crypto.randomUUID()
  return `s-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`
}

function deriveTitle(messages: UIMessage[]): string {
  for (const m of messages) {
    if (m.role === 'user') {
      const parts = (m.parts ?? []) as Array<{ type?: string; text?: string }>
      for (const p of parts) {
        if (p.type === 'text' && typeof p.text === 'string' && p.text.trim()) {
          const t = p.text.trim().replace(/\s+/g, ' ')
          return t.length > 60 ? t.slice(0, 57) + '…' : t
        }
      }
    }
  }
  return 'New chat'
}

let chatInstance: Chat<UIMessage> | null = null

function ensureActiveSession(): string {
  let active = safeRead<string>(ACTIVE_KEY, '')
  const idx = readIndex()
  sessionIndex.value = [...idx].sort((a, b) => b.updatedAt - a.updatedAt)
  if (!active || !idx.some(s => s.id === active)) {
    if (idx.length > 0) {
      active = idx[0].id
    } else {
      active = newSessionId()
      const meta: SessionMeta = { id: active, title: 'New chat', updatedAt: Date.now() }
      writeIndex([...idx, meta])
    }
    safeWrite(ACTIVE_KEY, active)
  }
  activeSessionId.value = active
  return active
}

function attachPersistence(chat: Chat<UIMessage>) {
  const persistAll = () => {
    const id = activeSessionId.value
    if (!id) return
    const snap = snapshotPlans()
    writeSession(id, {
      messages: JSON.parse(JSON.stringify(chat.messages)) as UIMessage[],
      proposals: proposals.value,
      activePlan: snap.active,
      planHistory: snap.history,
      asks: asks.value,
    })
  }
  watch(
    () => chat.messages,
    msgs => {
      // Intercept select_plan_template server-tool output (a message part, not
      // an onToolCall event) and instantiate the gated plan card.
      scanForPlanTemplateOutput(msgs, appliedPlanTemplateCallIds, payload => {
        applyGatedPlan(payload)
      })
      const id = activeSessionId.value
      if (!id) return
      persistAll()
      const idx = readIndex()
      const next: SessionMeta[] = idx.some(s => s.id === id)
        ? idx.map(s => (s.id === id ? { ...s, title: deriveTitle(msgs), updatedAt: Date.now() } : s))
        : [...idx, { id, title: deriveTitle(msgs), updatedAt: Date.now() }]
      writeIndex(next)
    },
    { deep: true }
  )
  watch(proposals, persistAll, { deep: true })
  watch(activePlan, persistAll, { deep: true })
  watch(planHistory, persistAll, { deep: true })
  watch(asks, persistAll, { deep: true })
}

function resolveMaxAutoSteps(): number {
  const DEFAULT = 50
  const parse = (raw: unknown): number | null => {
    if (typeof raw !== 'string' && typeof raw !== 'number') return null
    const n = typeof raw === 'number' ? raw : parseInt(raw, 10)
    return Number.isFinite(n) && n > 0 ? n : null
  }
  // Runtime override (dev convenience). Set with
  //   localStorage.setItem('pythiaMaxAutoSteps', '25')
  // in DevTools, then refresh — no rebuild needed.
  if (typeof localStorage !== 'undefined') {
    const ls = parse(localStorage.getItem('pythiaMaxAutoSteps'))
    if (ls !== null) return ls
  }
  // Build-time env var, baked in by Vite (prefix VITE_* so it's exposed
  // to the bundle). Set in docker-compose.yml or your shell when
  // building the plugin.
  const env = parse(import.meta.env?.VITE_PYTHIA_MAX_AUTO_STEPS)
  if (env !== null) return env
  return DEFAULT
}

import { locateGroup } from './locate-group'
export { locateGroup }

export interface ProposalResolver {
  addToolResult: (r: { tool: string; toolCallId: string; output: unknown }) => void
}

const PROPOSAL_TIMEOUT_MS = 10 * 60 * 1000 // 10 minutes
const proposalTimers = new Map<string, ReturnType<typeof setTimeout>>()

function clearProposalTimers() {
  for (const t of proposalTimers.values()) clearTimeout(t)
  proposalTimers.clear()
}

export function recordProposal(
  toolCall: { toolCallId: string; toolName: string; input: unknown },
  deps: ProposalResolver,
  groupInfo?: { groupId?: string; groupIndex?: number }
): void {
  const groupId = groupInfo?.groupId
  const groupIndex = groupInfo?.groupIndex
  proposals.value[toolCall.toolCallId] = {
    id: toolCall.toolCallId,
    toolName: toolCall.toolName,
    args: (toolCall.input ?? {}) as ProposalState['args'],
    status: 'pending',
    groupId,
    groupIndex,
  }
  // Timeout fallback: if the user closes the panel without deciding, the
  // model would otherwise be stuck waiting on this tool-result forever.
  // After 10 minutes, stub a pending decision so the loop terminates.
  const t = setTimeout(() => {
    if (proposals.value[toolCall.toolCallId]?.status === 'pending') {
      deps.addToolResult({
        tool: toolCall.toolName,
        toolCallId: toolCall.toolCallId,
        output: {
          decision: 'pending',
          timeout: true,
          instruction:
            'The user has not decided within 10 minutes. End your turn with a brief recap; they can act on the proposal card later.',
        },
      })
    }
    proposalTimers.delete(toolCall.toolCallId)
  }, PROPOSAL_TIMEOUT_MS)
  proposalTimers.set(toolCall.toolCallId, t)
}

// Called from onToolCall for every client-side proposal tool. Records the
// proposal exactly as before; if autoApproveProposals is on, immediately
// resolves it too — same code path a manual click takes (acceptProposal),
// just triggered synchronously instead of waiting on the user. Does NOT
// retroactively touch proposals already recorded before the toggle flips
// on — this check only runs at record time.
export function recordAndMaybeAutoAccept(
  toolCall: { toolCallId: string; toolName: string; input: unknown },
  deps: ProposalResolver,
  groupInfo?: { groupId?: string; groupIndex?: number }
): void {
  recordProposal(toolCall, deps, groupInfo)
  if (autoApproveProposals.value) {
    void acceptProposal(toolCall.toolCallId, deps)
  }
}

export function resolveProposal(
  toolCallId: string,
  decision: 'accepted' | 'rejected',
  deps: ProposalResolver,
  result?: { id?: number | string; name?: string }
): void {
  const p = proposals.value[toolCallId]
  if (!p) return
  const timer = proposalTimers.get(toolCallId)
  if (timer) { clearTimeout(timer); proposalTimers.delete(toolCallId) }
  const savedBits =
    decision === 'accepted' && result && result.id != null
      ? {
          savedId: result.id,
          savedName: result.name,
          instruction:
            `Accepted. The artifact was saved with id ${result.id}` +
            (result.name ? ` ("${result.name}")` : '') +
            `. Use this id when referencing it in later tools (e.g. create_pathway targetCohorts/eventCohorts, create_characterization cohorts/featureAnalyses). Continue your plan.`,
        }
      : {
          instruction:
            decision === 'accepted'
              ? 'The user accepted your proposal. Continue your turn — propose the next step or summarise.'
              : 'The user rejected your proposal. Ask one clarifying question or propose an alternative; do not re-propose the same thing.',
        }
  deps.addToolResult({
    tool: p.toolName,
    toolCallId,
    output: { decision, ...savedBits },
  })
}

// Shared accept pipeline: delegate the recorded tool call to ATLAS's
// `capability.apply` bus handler, which owns the full CIRCE translation
// (Task A3) and now also applies it, advance any linked plan step from the
// returned kind, then resolve the tool result. Used by both a manual card
// click (ChatPanel.vue's onAccept) and the auto-approve path
// (recordAndMaybeAutoAccept) — same effect either way, just triggered
// differently. Reuses the module's own _hostBus (set once at mount by
// setHostBridge) rather than requiring a bus be passed in.
export async function acceptProposal(
  toolCallId: string,
  deps: ProposalResolver
): Promise<void> {
  const p = proposals.value[toolCallId]
  if (!p) return
  p.status = 'accepted'
  let result: { id?: number | string; name?: string } | undefined
  if (_hostBus) {
    const applied = await _hostBus.request<CapabilityApplyResult>('capability.apply', {
      name: p.toolName,
      args: p.args,
    })
    if (applied?.applied) {
      result = { id: applied.id, name: applied.name }
      if (applied.kind) markStepProgress(applied.kind, 'done')
    }
  }
  resolveProposal(toolCallId, 'accepted', deps, result)
}

// `select_plan_template` is a SERVER tool (has `:run` agent-side). Its OUTPUT
// — the gated-plan payload — is streamed back as a `tool-select_plan_template`
// message part with `state: 'output-available'`. Server-tool outputs do NOT
// flow through `onToolCall` (that only fires for client-executed tools and
// carries input, not output), so we observe the message parts instead. We
// dedupe by toolCallId so a deep watch firing repeatedly only instantiates the
// plan once.
const appliedPlanTemplateCallIds = new Set<string>()

export function scanForPlanTemplateOutput(
  messages: ReadonlyArray<UIMessage>,
  seen: Set<string>,
  apply: (payload: GatedPlanPayload) => void
): void {
  for (const m of messages) {
    if (m.role !== 'assistant' || !Array.isArray(m.parts)) continue
    for (const part of m.parts) {
      const p = part as {
        type?: string
        state?: string
        toolCallId?: string
        output?: unknown
      }
      if (p.type !== 'tool-select_plan_template') continue
      if (p.state !== 'output-available') continue
      const id = p.toolCallId ?? ''
      if (id && seen.has(id)) continue
      const out = p.output
      if (out && typeof out === 'object' && 'steps' in (out as object)) {
        apply(out as GatedPlanPayload)
        if (id) seen.add(id)
      }
    }
  }
}

// Seed the dedup set from a (persisted) messages array. On session restore the
// persisted messages already contain the `select_plan_template` output part —
// without seeding, the deep watch would see it with an empty set and re-apply
// the gated plan, clobbering the progress `restorePlans` just restored. We
// collect every toolCallId of an output-available template part, matching the
// SAME part-shape predicate `scanForPlanTemplateOutput` uses (type + state +
// toolCallId) so the two stay in sync.
export function collectPlanTemplateCallIds(messages: readonly unknown[]): string[] {
  const ids: string[] = []
  for (const m of (messages ?? []) as Array<{ parts?: unknown[] }>) {
    for (const part of (m?.parts ?? []) as Array<Record<string, unknown>>) {
      if (part?.type === 'tool-select_plan_template' && part?.state === 'output-available'
          && typeof part?.toolCallId === 'string') {
        ids.push(part.toolCallId as string)
      }
    }
  }
  return ids
}

// Backend context/artifact `kind` enum is snake_case (get_artifact /
// review_artifact schemas); the frontend's ArtifactKind is camelCase. Only
// the 3 multi-word kinds actually differ — the rest are identity mappings,
// listed explicitly so a typo here fails loudly instead of falling through.
const ARTIFACT_KIND_TO_BACKEND: Record<ArtifactKind, string> = {
  cohort: 'cohort',
  conceptSet: 'concept_set',
  featureAnalysis: 'feature_analysis',
  characterization: 'characterization',
  pathway: 'pathway',
  incidenceRate: 'incidence_rate',
}

export interface AgentPlanPayload {
  document?: string
  steps: Array<{ id: string; label: string; status: PlanStepStatus; required: boolean }>
}

export interface AgentRequestBody {
  sourceKey: string | null
  routeContext: RouteContext | null
  context: { route: string; artifact: { kind: string; id: number | string; name: string } | null } | null
  plan: AgentPlanPayload | null
}

// Shapes the request body agent/src/pythia/entry.cljs expects
// (`context: {route, artifact}`, `plan`) from the client-side refs that
// already exist for other purposes (routeContext drives navigate_to undo;
// activePlan drives the plan card). Exported and pure so it's testable
// without constructing a Chat instance.
export function buildAgentRequestBody(
  sourceKey: string | null,
  routeContext: RouteContext | null,
  plan: Plan | null
): AgentRequestBody {
  return {
    sourceKey,
    routeContext,
    context: routeContext
      ? {
          route: routeContext.routeName,
          artifact: routeContext.artifact
            ? {
                kind: ARTIFACT_KIND_TO_BACKEND[routeContext.artifact.kind],
                id: routeContext.artifact.id,
                name: routeContext.artifact.name,
              }
            : null,
        }
      : null,
    plan: plan
      ? {
          document: plan.document,
          steps: plan.steps.map(s => ({
            id: s.id,
            label: s.label,
            status: s.status,
            required: s.required ?? false,
          })),
        }
      : null,
  }
}

export function getChatInstance(): Chat<UIMessage> {
  if (chatInstance) return chatInstance
  const id = ensureActiveSession()
  const persisted = readSession(id)
  proposals.value = persisted.proposals
  asks.value = persisted.asks ?? {}
  restorePlans({
    active: persisted.activePlan ?? null,
    history: persisted.planHistory ?? [],
  })
  // Seed the dedup set from the persisted messages BEFORE the Chat is
  // constructed and its deep watch attached, so the already-persisted
  // template output is treated as already-applied and `scanForPlanTemplateOutput`
  // does not re-apply the gated plan (which would reset the restored progress).
  appliedPlanTemplateCallIds.clear()
  for (const cid of collectPlanTemplateCallIds(persisted.messages)) appliedPlanTemplateCallIds.add(cid)

  const transport = new DefaultChatTransport({
    api: '/WebAPI/trexsql/agent/chat',
    headers: async () => {
      // Always re-read the token at request time. Atlas3 refreshes JWTs in
      // the background, so a cached value goes stale and causes 401s on
      // long-lived sessions.
      const live = tokenProvider ? await tokenProvider() : null
      const token = live || sessionToken.value
      const h: Record<string, string> = {}
      if (token) h['Authorization'] = `Bearer ${token}`
      return h
    },
    body: () => buildAgentRequestBody(sessionSourceKey.value, sessionRouteContext.value, activePlan.value),
  })

  // Hard cap on the auto-loop. The @ai-sdk/vue Chat keeps ONE assistant
  // message per user turn — auto-resends extend that same message's
  // `parts` array with more tool-call/text blocks rather than appending
  // a fresh message. We use the number of tool-input-available parts on
  // the last assistant message as the step proxy: each auto-resend
  // typically produces one new tool call. Two earlier versions of this
  // predicate (counting whole messages, then counting `step-start`
  // parts) didn't bite because (a) message count stays at 1 and (b)
  // bao's SSE doesn't emit `start-step` chunks, so step-start parts
  // never materialise.
  // Configurable via VITE_PYTHIA_MAX_AUTO_STEPS at build time, with a
  // dev-convenience runtime override at localStorage.pythiaMaxAutoSteps
  // (no rebuild needed — set it in DevTools, refresh).
  // 50 is the default so multi-cohort / pipeline flows (build + save several
  // cohorts, then a pathway/characterization that references them) complete in
  // one turn; the env + localStorage overrides still apply. Tighten it if you
  // observe the "tool-loop forever" failure mode the cap was added to prevent.
  const MAX_AUTO_STEPS = resolveMaxAutoSteps()
  // bao runs ONE Bedrock turn per request and ends with a `finish` chunk
  // carrying a `finishReason` ("tool-calls" | "stop" | "length" | "error").
  // The client is supposed to drive the multi-turn loop only when the model
  // wants more tool round-trips (i.e. `tool-calls`). bao does NOT emit
  // step boundary chunks, so the AI SDK's `lastAssistantMessageIsComplete-
  // WithToolCalls` predicate looks at the whole message as a single step
  // and stays true even after the model has finalised — re-prompting
  // forever. We capture the last finishReason in `onFinish` (fired before
  // `shouldSendAutomatically`) and gate the predicate on it.
  let lastFinishReason: string | undefined
  const sendAutomaticallyWithCap: NonNullable<ConstructorParameters<typeof Chat<UIMessage>>[0]['sendAutomaticallyWhen']> = ({ messages }) => {
    if (lastFinishReason !== 'tool-calls') return false
    if (!lastAssistantMessageIsCompleteWithToolCalls({ messages })) return false
    const last = messages[messages.length - 1]
    if (!last || last.role !== 'assistant' || !Array.isArray(last.parts)) return false
    let toolCallCount = 0
    for (const p of last.parts) {
      const t = (p as { type?: string }).type
      if (t === 'tool-input-available') {
        toolCallCount += 1
      } else if (typeof t === 'string' && t.startsWith('tool-') && t !== 'tool-output-available') {
        toolCallCount += 1
      }
    }
    if (toolCallCount < MAX_AUTO_STEPS) return true
    // Model wanted to keep calling tools but we hit the cap — surface the
    // "Continue?" affordance so the user can choose to extend the budget.
    maxStepsReached.value = true
    return false
  }

  const chat = new Chat<UIMessage>({
    transport,
    messages: persisted.messages,
    sendAutomaticallyWhen: sendAutomaticallyWithCap,
    onFinish: ({ finishReason }) => {
      lastFinishReason = finishReason
    },
    onToolCall: ({ toolCall }: { toolCall: { toolCallId: string; toolName: string; input: unknown } }) => {
      if (toolCall.toolName === 'navigate_to') {
        void handleNavigateTool(
          { toolCallId: toolCall.toolCallId, input: toolCall.input },
          { addToolResult: (r) => chat.addToolResult(r) }
        )
        return
      }
      if (isPlanTool(toolCall.toolName)) {
        const result = applyPlanToolCall(toolCall.toolName, toolCall.input)
        chat.addToolResult({
          tool: toolCall.toolName,
          toolCallId: toolCall.toolCallId,
          output: result,
        })
        return
      }
      if (toolCall.toolName === 'ask_user') {
        const args = (toolCall.input ?? {}) as {
          question?: unknown
          options?: unknown
          allowCustom?: unknown
        }
        const optionsRaw = Array.isArray(args.options) ? args.options : []
        const options: AskState['options'] = optionsRaw
          .map((o: unknown) => {
            const oo = o as { id?: unknown; label?: unknown; description?: unknown }
            const id = typeof oo.id === 'string' ? oo.id : ''
            const label = typeof oo.label === 'string' ? oo.label : ''
            if (!id || !label) return null
            return {
              id,
              label,
              description: typeof oo.description === 'string' ? oo.description : undefined,
            }
          })
          .filter((o): o is NonNullable<typeof o> => o !== null)
        const { groupId, groupIndex } = locateGroup(chat.messages, toolCall.toolCallId)
        asks.value[toolCall.toolCallId] = {
          id: toolCall.toolCallId,
          question: typeof args.question === 'string' ? args.question : '',
          options,
          allowCustom: !!args.allowCustom,
          status: 'pending',
          groupId,
          groupIndex,
        }
        chat.addToolResult({
          tool: toolCall.toolName,
          toolCallId: toolCall.toolCallId,
          output: {
            success: true,
            presented: true,
            awaitingUserChoice: true,
            instruction:
              'The question has been shown to the user as clickable options. STOP calling tools. End your turn with a one-line preamble; the user will pick an option and the next user message will be their answer.',
          },
        })
        return
      }
      if (CLIENT_SIDE_TOOLS.has(toolCall.toolName)) {
        // Gate skip-ahead: if a GATED plan is active and this proposal jumps
        // past its first not-done required step, don't render the card —
        // feed the correction back as the tool-result so the model retries
        // the correct step (same addToolResult path the timeout handler uses).
        const kind = proposalKind(toolCall.toolName, (toolCall.input ?? {}) as Record<string, unknown>)
        if (kind) {
          const gate = gateProposal(kind)
          if (!gate.ok) {
            chat.addToolResult({
              tool: toolCall.toolName,
              toolCallId: toolCall.toolCallId,
              output: { instruction: gate.reason },
            })
            return
          }
        }
        const { groupId, groupIndex } = locateGroup(chat.messages, toolCall.toolCallId)
        recordAndMaybeAutoAccept(
          { toolCallId: toolCall.toolCallId, toolName: toolCall.toolName, input: toolCall.input },
          { addToolResult: (r) => chat.addToolResult(r) },
          { groupId, groupIndex }
        )
        // If autoApproveProposals is off, this only records — accept/reject
        // in ChatPanel calls resolveProposal, which sends the real outcome
        // as the tool-result. If auto-approve is on, recordAndMaybeAutoAccept
        // also resolves it immediately as accepted.
      }
    },
  })

  attachPersistence(chat)
  chatInstance = chat
  return chat
}

export function newChat() {
  clearProposalTimers()
  appliedPlanTemplateCallIds.clear()
  const id = newSessionId()
  safeWrite(ACTIVE_KEY, id)
  activeSessionId.value = id
  proposals.value = {}
  asks.value = {}
  maxStepsReached.value = false
  resetPlans()
  if (chatInstance) chatInstance.messages = []
  // Add the empty session to the index so the picker shows it; it gets a
  // real title once the user sends the first message.
  const idx = readIndex()
  writeIndex([...idx, { id, title: 'New chat', updatedAt: Date.now() }])
}

export function switchToSession(id: string) {
  if (id === activeSessionId.value) return
  clearProposalTimers()
  const persisted = readSession(id)
  safeWrite(ACTIVE_KEY, id)
  activeSessionId.value = id
  proposals.value = persisted.proposals
  asks.value = persisted.asks ?? {}
  maxStepsReached.value = false
  restorePlans({
    active: persisted.activePlan ?? null,
    history: persisted.planHistory ?? [],
  })
  // Seed the dedup set from the restored messages BEFORE assigning them to the
  // Chat (which fires the deep watch), so the persisted template output is
  // treated as already-applied and the gated plan is not re-applied — that
  // would reset the step progress `restorePlans` just restored.
  appliedPlanTemplateCallIds.clear()
  for (const cid of collectPlanTemplateCallIds(persisted.messages)) appliedPlanTemplateCallIds.add(cid)
  if (chatInstance) chatInstance.messages = persisted.messages
}

export function deleteChatSession(id: string) {
  deleteSession(id)
  const idx = readIndex().filter(s => s.id !== id)
  writeIndex(idx)
  if (activeSessionId.value === id) {
    if (idx.length > 0) {
      switchToSession(idx[0].id)
    } else {
      newChat()
    }
  }
}

// Tell the agent to keep going after we capped its auto-loop. bao doesn't
// expose a true "resume" endpoint — sending a fresh user turn is the
// established way to extend the conversation, and matches what a human
// would type to nudge a stalled chat. Clearing the flag here also covers
// the case where the user types their own message instead of clicking.
export function continueChat(): void {
  if (!chatInstance) return
  maxStepsReached.value = false
  void chatInstance.sendMessage({ text: 'continue' })
}

export function clearCurrentSession() {
  clearProposalTimers()
  appliedPlanTemplateCallIds.clear()
  if (chatInstance) chatInstance.messages = []
  proposals.value = {}
  asks.value = {}
  maxStepsReached.value = false
  resetPlans()
  const id = activeSessionId.value
  if (id) {
    writeSession(id, {
      messages: [],
      proposals: {},
      activePlan: null,
      planHistory: [],
      asks: {},
    })
    const idx = readIndex().map(s =>
      s.id === id ? { ...s, title: 'New chat', updatedAt: Date.now() } : s
    )
    writeIndex(idx)
  }
}
