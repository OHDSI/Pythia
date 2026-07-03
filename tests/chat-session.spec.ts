import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import {
  proposals, lastNavigation, sessionRouteContext,
  scanForPlanTemplateOutput, collectPlanTemplateCallIds,
  buildAgentRequestBody, autoApproveProposals, setAutoApproveProposals,
  acceptProposal, recordProposal, setHostBridge, recordAndMaybeAutoAccept,
} from '../src/chat-session'
import type { Plan } from '../src/types'
import type { UIMessage } from 'ai'

describe('navigate_to short-circuit', () => {
  beforeEach(() => {
    for (const k of Object.keys(proposals.value)) delete proposals.value[k]
  })

  it('does NOT create a proposal card for navigate_to', async () => {
    const { handleNavigateTool } = await import('../src/chat-session')
    const addToolResult = vi.fn()
    const applyProposalSpy = vi.fn()

    handleNavigateTool({
      toolCallId: 'tc-1',
      input: { view: 'cohort-edit', id: 42, reason: 'open cohort 42' },
    }, { addToolResult, applyProposal: applyProposalSpy })

    expect(proposals.value['tc-1']).toBeUndefined()
    expect(applyProposalSpy).toHaveBeenCalledWith({
      kind: 'navigate',
      route: { name: 'cohort-edit', params: { id: 42 } },
      reason: 'open cohort 42',
    })
    expect(addToolResult).toHaveBeenCalledWith({
      tool: 'navigate_to',
      toolCallId: 'tc-1',
      output: expect.objectContaining({
        success: true,
        applied: true,
        route: 'cohort-edit',
      }),
    })
  })

  it('rejects navigate_to with an invalid view', async () => {
    const { handleNavigateTool } = await import('../src/chat-session')
    const addToolResult = vi.fn()
    const applyProposalSpy = vi.fn()

    handleNavigateTool({
      toolCallId: 'tc-2',
      input: { view: 'not-a-real-view', reason: 'x' },
    }, { addToolResult, applyProposal: applyProposalSpy })

    expect(applyProposalSpy).not.toHaveBeenCalled()
    expect(addToolResult).toHaveBeenCalledWith({
      tool: 'navigate_to',
      toolCallId: 'tc-2',
      output: expect.objectContaining({ success: false }),
    })
  })
})

describe('navigate_to captures previous route for undo', () => {
  beforeEach(() => {
    lastNavigation.value = null
    sessionRouteContext.value = null
    for (const k of Object.keys(proposals.value)) delete proposals.value[k]
  })

  it('records previous route when sessionRouteContext is set', async () => {
    const { handleNavigateTool } = await import('../src/chat-session')
    sessionRouteContext.value = {
      routeName: 'cohorts',
      routeParams: {},
      artifact: null,
    }
    handleNavigateTool(
      { toolCallId: 'tc-nav', input: { view: 'cohort-edit', id: 42, reason: 'r' } },
      { addToolResult: () => {}, applyProposal: () => {} }
    )
    expect(lastNavigation.value).not.toBeNull()
    expect(lastNavigation.value?.toName).toBe('cohort-edit')
    expect(lastNavigation.value?.previous).toEqual({ name: 'cohorts', params: {} })
  })

  it('records null previous when sessionRouteContext is missing', async () => {
    const { handleNavigateTool } = await import('../src/chat-session')
    sessionRouteContext.value = null
    handleNavigateTool(
      { toolCallId: 'tc-nav-2', input: { view: 'cohort-edit', id: 1, reason: 'r' } },
      { addToolResult: () => {}, applyProposal: () => {} }
    )
    expect(lastNavigation.value?.previous).toBeNull()
  })

  it('reports undoAvailable: false in tool result when no previous', async () => {
    const { handleNavigateTool } = await import('../src/chat-session')
    sessionRouteContext.value = null
    const addToolResult = vi.fn()
    handleNavigateTool(
      { toolCallId: 'tc-nav-3', input: { view: 'cohort-edit', id: 1, reason: 'r' } },
      { addToolResult, applyProposal: () => {} }
    )
    expect(addToolResult).toHaveBeenCalledWith({
      tool: 'navigate_to',
      toolCallId: 'tc-nav-3',
      output: expect.objectContaining({ undoAvailable: false }),
    })
  })
})

describe('proposal timers cleared on session switch', () => {
  afterEach(() => {
    vi.useRealTimers()
  })

  it('newChat clears pending proposal timers (10-min fallback does not fire)', async () => {
    vi.useFakeTimers()
    const { recordProposal, newChat } = await import('../src/chat-session')
    const addToolResult = vi.fn()
    recordProposal(
      { toolCallId: 'leak-1', toolName: 'add_criteria', input: {} },
      { addToolResult }
    )
    // Sanity: nothing fired yet
    expect(addToolResult).not.toHaveBeenCalled()
    // Switching/clearing should drop the timer
    newChat()
    // Advance well past the 10-minute fallback
    vi.advanceTimersByTime(11 * 60 * 1000)
    expect(addToolResult).not.toHaveBeenCalled()
  })
})

describe('scanForPlanTemplateOutput (server-tool output interception)', () => {
  const planPart = (toolCallId: string, output: unknown, state = 'output-available') => ({
    type: 'tool-select_plan_template',
    state,
    toolCallId,
    output,
  })
  const msg = (parts: unknown[]): UIMessage =>
    ({ id: 'm1', role: 'assistant', parts } as unknown as UIMessage)

  const payload = {
    scenario: 'standalone-concept-set',
    title: 'Create a concept set',
    steps: [{ id: 's1', label: 'A', linkedProposalKind: null, required: true }],
  }

  it('applies the gated plan from a select_plan_template output part', () => {
    const seen = new Set<string>()
    const apply = vi.fn()
    scanForPlanTemplateOutput([msg([planPart('tc-1', payload)])], seen, apply)
    expect(apply).toHaveBeenCalledTimes(1)
    expect(apply).toHaveBeenCalledWith(payload)
    expect(seen.has('tc-1')).toBe(true)
  })

  it('dedupes — does not re-apply the same toolCallId on a repeated scan', () => {
    const seen = new Set<string>()
    const apply = vi.fn()
    const messages = [msg([planPart('tc-1', payload)])]
    scanForPlanTemplateOutput(messages, seen, apply)
    scanForPlanTemplateOutput(messages, seen, apply)
    expect(apply).toHaveBeenCalledTimes(1)
  })

  it('ignores parts that are not yet output-available', () => {
    const apply = vi.fn()
    scanForPlanTemplateOutput([msg([planPart('tc-1', payload, 'input-available')])], new Set(), apply)
    expect(apply).not.toHaveBeenCalled()
  })

  it('ignores output without a steps array', () => {
    const apply = vi.fn()
    scanForPlanTemplateOutput([msg([planPart('tc-1', { error: 'unknown scenario' })])], new Set(), apply)
    expect(apply).not.toHaveBeenCalled()
  })

  it('ignores non-plan-template tool parts', () => {
    const apply = vi.fn()
    const part = { type: 'tool-create_plan', state: 'output-available', toolCallId: 'x', output: payload }
    scanForPlanTemplateOutput([msg([part])], new Set(), apply)
    expect(apply).not.toHaveBeenCalled()
  })
})

describe('collectPlanTemplateCallIds (restore dedup seeding)', () => {
  const planPart = (toolCallId: string, output: unknown, state = 'output-available') => ({
    type: 'tool-select_plan_template',
    state,
    toolCallId,
    output,
  })
  const msg = (parts: unknown[]): UIMessage =>
    ({ id: 'm1', role: 'assistant', parts } as unknown as UIMessage)

  const payload = {
    scenario: 'standalone-concept-set',
    title: 'Create a concept set',
    steps: [{ id: 's1', label: 'A', linkedProposalKind: null, required: true }],
  }

  it('returns the toolCallIds of output-available template parts', () => {
    const messages = [
      msg([planPart('tc-1', payload)]),
      msg([planPart('tc-2', payload)]),
    ]
    expect(collectPlanTemplateCallIds(messages)).toEqual(['tc-1', 'tc-2'])
  })

  it('ignores non-output-available template parts', () => {
    const messages = [msg([planPart('tc-1', payload, 'input-available')])]
    expect(collectPlanTemplateCallIds(messages)).toEqual([])
  })

  it('ignores non-plan-template parts', () => {
    const part = { type: 'tool-create_plan', state: 'output-available', toolCallId: 'x', output: payload }
    expect(collectPlanTemplateCallIds([msg([part])])).toEqual([])
  })

  it('tolerates empty / undefined inputs', () => {
    expect(collectPlanTemplateCallIds([])).toEqual([])
    expect(collectPlanTemplateCallIds(undefined as unknown as readonly unknown[])).toEqual([])
    expect(collectPlanTemplateCallIds([msg([])])).toEqual([])
  })

  it('restore regression: a seeded template output is NOT re-applied', () => {
    const messages = [msg([planPart('tc-1', payload)])]
    // Empty set (fresh stream): the plan IS applied.
    const freshApply = vi.fn()
    scanForPlanTemplateOutput(messages, new Set<string>(), freshApply)
    expect(freshApply).toHaveBeenCalledTimes(1)

    // Restore sequence: seed from the persisted messages, then scan — the
    // already-persisted output must be skipped so restored progress survives.
    const seeded = new Set<string>(collectPlanTemplateCallIds(messages))
    const restoreApply = vi.fn()
    scanForPlanTemplateOutput(messages, seeded, restoreApply)
    expect(restoreApply).not.toHaveBeenCalled()
  })
})

describe('proposal tool defers tool-result', () => {
  beforeEach(() => {
    for (const k of Object.keys(proposals.value)) delete proposals.value[k]
  })

  it('does NOT stub the tool-result on tool-call (deferred)', async () => {
    const { recordProposal } = await import('../src/chat-session')
    const addToolResult = vi.fn()
    recordProposal({
      toolCallId: 'tc-p1',
      toolName: 'add_criteria',
      input: { name: 'Test', group: 'inclusion', logic: 'AND', items: [] },
    }, { addToolResult })
    expect(proposals.value['tc-p1']).toBeDefined()
    expect(proposals.value['tc-p1'].status).toBe('pending')
    expect(addToolResult).not.toHaveBeenCalled()
  })

  it('stubs tool-result on accept', async () => {
    const { recordProposal, resolveProposal } = await import('../src/chat-session')
    const addToolResult = vi.fn()
    recordProposal({
      toolCallId: 'tc-p2',
      toolName: 'add_criteria',
      input: { name: 'Test', group: 'inclusion', logic: 'AND', items: [] },
    }, { addToolResult })
    resolveProposal('tc-p2', 'accepted', { addToolResult })
    expect(addToolResult).toHaveBeenCalledWith({
      tool: 'add_criteria',
      toolCallId: 'tc-p2',
      output: expect.objectContaining({ decision: 'accepted' }),
    })
  })

  it('stubs tool-result on reject', async () => {
    const { recordProposal, resolveProposal } = await import('../src/chat-session')
    const addToolResult = vi.fn()
    recordProposal({
      toolCallId: 'tc-p3',
      toolName: 'add_criteria',
      input: { name: 'Test', group: 'inclusion', logic: 'AND', items: [] },
    }, { addToolResult })
    resolveProposal('tc-p3', 'rejected', { addToolResult })
    expect(addToolResult).toHaveBeenCalledWith({
      tool: 'add_criteria',
      toolCallId: 'tc-p3',
      output: expect.objectContaining({ decision: 'rejected' }),
    })
  })
})

describe('buildAgentRequestBody (frontend -> agent backend context/plan wiring)', () => {
  it('omits metadata entirely when sourceKey, context, and plan are all null', () => {
    const body = buildAgentRequestBody(null, null, null)
    expect(body).toEqual({})
  })

  it('maps routeContext into the context shape the agent backend expects', () => {
    const body = buildAgentRequestBody('EUNOMIA', {
      routeName: 'cohort-edit',
      routeParams: { id: 42 },
      artifact: { kind: 'cohort', id: 42, name: 'T2DM', summary: '3 rules' },
    }, null)
    expect(body.metadata?.sourceKey).toBe('EUNOMIA')
    expect(body.metadata?.context).toEqual({
      route: 'cohort-edit',
      artifact: { kind: 'cohort', id: 42, name: 'T2DM' },
    })
  })

  it('maps a null artifact through to context.artifact: null', () => {
    const body = buildAgentRequestBody(null, { routeName: 'cohorts', routeParams: {}, artifact: null }, null)
    expect(body.metadata?.context).toEqual({ route: 'cohorts', artifact: null })
  })

  it('translates camelCase artifact kinds to the backend snake_case enum', () => {
    const cases: Array<[string, string]> = [
      ['conceptSet', 'concept_set'],
      ['featureAnalysis', 'feature_analysis'],
      ['incidenceRate', 'incidence_rate'],
      ['characterization', 'characterization'],
      ['pathway', 'pathway'],
      ['cohort', 'cohort'],
    ]
    for (const [frontendKind, backendKind] of cases) {
      const body = buildAgentRequestBody(null, {
        routeName: 'x',
        routeParams: {},
        artifact: { kind: frontendKind as never, id: 1, name: 'n', summary: '' },
      }, null)
      expect(body.metadata?.context?.artifact?.kind).toBe(backendKind)
    }
  })

  it('trims the plan to document + minimal step fields', () => {
    const plan: Plan = {
      id: 'p1', title: 'x',
      document: '## Goal\nBuild it.',
      steps: [{ id: 's1', label: 'Step 1', status: 'pending', required: true }],
      status: 'active', createdAt: 1, updatedAt: 1,
    }
    const body = buildAgentRequestBody(null, null, plan)
    expect(body.metadata?.plan).toEqual({
      document: '## Goal\nBuild it.',
      steps: [{ id: 's1', label: 'Step 1', status: 'pending', required: true }],
    })
  })

  it("defaults a step's required to false when unset", () => {
    const plan: Plan = {
      id: 'p1', title: 'x', steps: [{ id: 's1', label: 'Step 1', status: 'pending' }],
      status: 'active', createdAt: 1, updatedAt: 1,
    }
    const body = buildAgentRequestBody(null, null, plan)
    expect(body.metadata?.plan?.steps[0].required).toBe(false)
  })
})

describe('autoApproveProposals persistence', () => {
  const KEY = 'cohort-agent-plugin.autoApprove.v1'

  afterEach(() => {
    localStorage.removeItem(KEY)
  })

  it('setAutoApproveProposals updates the ref and persists to localStorage', () => {
    setAutoApproveProposals(true)
    expect(autoApproveProposals.value).toBe(true)
    expect(JSON.parse(localStorage.getItem(KEY) ?? 'null')).toBe(true)

    setAutoApproveProposals(false)
    expect(autoApproveProposals.value).toBe(false)
    expect(JSON.parse(localStorage.getItem(KEY) ?? 'null')).toBe(false)
  })
})

describe('acceptProposal (shared accept pipeline)', () => {
  const fakeBus = () => ({
    send: vi.fn(),
    request: vi.fn(),
    subscribe: vi.fn(),
  })

  beforeEach(() => {
    for (const k of Object.keys(proposals.value)) delete proposals.value[k]
  })

  it('applies a non-ID-returning proposal via bus.send and resolves accepted', async () => {
    const bus = fakeBus()
    setHostBridge({ bus, applyProposal: vi.fn() })
    recordProposal(
      {
        toolCallId: 'tc-acc-1',
        toolName: 'add_criteria',
        input: {
          name: 'Test',
          group: 'inclusion',
          logic: 'AND',
          items: [{ conceptId: 1, conceptName: 'x', domain: 'Condition' }],
        },
      },
      { addToolResult: () => {} }
    )

    const addToolResult = vi.fn()
    await acceptProposal('tc-acc-1', { addToolResult })

    expect(bus.send).toHaveBeenCalledWith(
      'cohort.applyProposal',
      expect.objectContaining({ proposal: expect.objectContaining({ kind: 'addInclusionRule' }) })
    )
    expect(proposals.value['tc-acc-1'].status).toBe('accepted')
    expect(addToolResult).toHaveBeenCalledWith({
      tool: 'add_criteria',
      toolCallId: 'tc-acc-1',
      output: expect.objectContaining({ decision: 'accepted' }),
    })
  })

  it('applies an ID-returning proposal via bus.request and forwards the saved id', async () => {
    const bus = fakeBus()
    bus.request.mockResolvedValue({ id: 42, name: 'Saved Cohort' })
    setHostBridge({ bus, applyProposal: vi.fn() })
    recordProposal(
      { toolCallId: 'tc-acc-2', toolName: 'save_cohort', input: { name: 'Saved Cohort' } },
      { addToolResult: () => {} }
    )

    const addToolResult = vi.fn()
    await acceptProposal('tc-acc-2', { addToolResult })

    expect(bus.request).toHaveBeenCalledWith(
      'cohort.applyProposal',
      expect.objectContaining({ proposal: expect.objectContaining({ kind: 'saveCohort' }) })
    )
    expect(addToolResult).toHaveBeenCalledWith({
      tool: 'save_cohort',
      toolCallId: 'tc-acc-2',
      output: expect.objectContaining({ decision: 'accepted', savedId: 42, savedName: 'Saved Cohort' }),
    })
  })

  it('does nothing for an unknown toolCallId', async () => {
    const addToolResult = vi.fn()
    await acceptProposal('does-not-exist', { addToolResult })
    expect(addToolResult).not.toHaveBeenCalled()
  })
})

describe('recordAndMaybeAutoAccept', () => {
  const fakeBus = () => ({
    send: vi.fn(),
    request: vi.fn(),
    subscribe: vi.fn(),
  })

  beforeEach(() => {
    for (const k of Object.keys(proposals.value)) delete proposals.value[k]
    setAutoApproveProposals(false)
  })

  afterEach(() => {
    setAutoApproveProposals(false)
  })

  it('leaves the proposal pending when auto-approve is off', () => {
    setHostBridge({ bus: fakeBus(), applyProposal: vi.fn() })
    recordAndMaybeAutoAccept(
      { toolCallId: 'tc-raa-1', toolName: 'add_criteria', input: {
        name: 'Test', group: 'inclusion', logic: 'AND',
        items: [{ conceptId: 1, conceptName: 'x', domain: 'Condition' }],
      } },
      { addToolResult: () => {} }
    )
    expect(proposals.value['tc-raa-1'].status).toBe('pending')
  })

  it('immediately resolves as accepted when auto-approve is on', async () => {
    const bus = fakeBus()
    setHostBridge({ bus, applyProposal: vi.fn() })
    setAutoApproveProposals(true)
    const addToolResult = vi.fn()
    recordAndMaybeAutoAccept(
      { toolCallId: 'tc-raa-2', toolName: 'add_criteria', input: {
        name: 'Test', group: 'inclusion', logic: 'AND',
        items: [{ conceptId: 1, conceptName: 'x', domain: 'Condition' }],
      } },
      { addToolResult }
    )
    // acceptProposal awaits nothing on this non-ID-returning path
    // synchronously up to the bus.send call, but is still an async fn —
    // flush microtasks before asserting.
    await Promise.resolve()
    await Promise.resolve()
    expect(proposals.value['tc-raa-2'].status).toBe('accepted')
    expect(addToolResult).toHaveBeenCalledWith(
      expect.objectContaining({ toolCallId: 'tc-raa-2', output: expect.objectContaining({ decision: 'accepted' }) })
    )
  })

  it('does not retroactively accept a proposal recorded before the toggle flips on', async () => {
    setHostBridge({ bus: fakeBus(), applyProposal: vi.fn() })
    recordAndMaybeAutoAccept(
      { toolCallId: 'tc-raa-3', toolName: 'add_criteria', input: {
        name: 'Test', group: 'inclusion', logic: 'AND',
        items: [{ conceptId: 1, conceptName: 'x', domain: 'Condition' }],
      } },
      { addToolResult: () => {} }
    )
    expect(proposals.value['tc-raa-3'].status).toBe('pending')

    setAutoApproveProposals(true)
    await Promise.resolve()
    await Promise.resolve()

    expect(proposals.value['tc-raa-3'].status).toBe('pending')
  })
})
