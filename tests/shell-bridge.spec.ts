import { describe, it, expect, vi } from 'vitest'
import { getShellContext, applyProposal, rejectProposal, notify } from '../src/shell-bridge'
import type { MessageBus } from '../src/main'

// The CIRCE translation (proposalFromToolCall and its helpers) moved to
// ATLAS's translateCapability (Task A3/A5) — that coverage now lives in
// ATLAS's translate.spec.ts. This file covers what's left in shell-bridge.ts:
// the thin bus-plumbing helpers used directly by ChatPanel.vue.

describe('getShellContext', () => {
  it('returns the app.getContext response when available', async () => {
    const bus: MessageBus = {
      send: vi.fn(),
      request: vi.fn().mockResolvedValue({ sourceKey: 'EUNOMIA', cohort: null }),
      subscribe: vi.fn(),
    }
    const ctx = await getShellContext(bus)
    expect(bus.request).toHaveBeenCalledWith('app.getContext', {})
    expect(ctx).toEqual({ sourceKey: 'EUNOMIA', cohort: null })
  })

  it('falls back to cohort.getContext when app.getContext is unavailable', async () => {
    const bus: MessageBus = {
      send: vi.fn(),
      request: vi.fn()
        .mockRejectedValueOnce(new Error('unsupported'))
        .mockResolvedValueOnce({ sourceKey: 'EUNOMIA', cohort: null }),
      subscribe: vi.fn(),
    }
    const ctx = await getShellContext(bus)
    expect(bus.request).toHaveBeenNthCalledWith(1, 'app.getContext', {})
    expect(bus.request).toHaveBeenNthCalledWith(2, 'cohort.getContext', {})
    expect(ctx).toEqual({ sourceKey: 'EUNOMIA', cohort: null })
  })

  it('falls back to an empty context when both requests fail', async () => {
    const bus: MessageBus = {
      send: vi.fn(),
      request: vi.fn().mockRejectedValue(new Error('down')),
      subscribe: vi.fn(),
    }
    const ctx = await getShellContext(bus)
    expect(ctx).toEqual({ sourceKey: null, cohort: null })
  })
})

describe('applyProposal / rejectProposal / notify', () => {
  const fakeBus = (): MessageBus => ({ send: vi.fn(), request: vi.fn(), subscribe: vi.fn() })

  it('applyProposal sends cohort.applyProposal with the proposal', () => {
    const bus = fakeBus()
    applyProposal(bus, { kind: 'saveCohort' } as never)
    expect(bus.send).toHaveBeenCalledWith('cohort.applyProposal', { proposal: { kind: 'saveCohort' } })
  })

  it('rejectProposal sends cohort.rejectProposal with the id', () => {
    const bus = fakeBus()
    rejectProposal(bus, 'tc-1')
    expect(bus.send).toHaveBeenCalledWith('cohort.rejectProposal', { id: 'tc-1' })
  })

  it('notify sends notify.snackbar with message and the default "info" type', () => {
    const bus = fakeBus()
    notify(bus, 'hello')
    expect(bus.send).toHaveBeenCalledWith('notify.snackbar', { message: 'hello', type: 'info' })
  })

  it('notify supports a custom type', () => {
    const bus = fakeBus()
    notify(bus, 'oops', 'error')
    expect(bus.send).toHaveBeenCalledWith('notify.snackbar', { message: 'oops', type: 'error' })
  })
})
