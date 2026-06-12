import { describe, it, expect, beforeEach } from 'vitest'
import { applyGatedPlan, gateProposal, resetPlans, activePlan, applyCreatePlan } from '../src/plan-state'

const payload = {
  scenario: 'standalone-concept-set',
  title: 'Create a concept set',
  steps: [
    { id: 'resolve-concept-ids', label: 'Resolve concepts', linkedProposalKind: null, linkedRoute: null, required: true },
    { id: 'create-concept-set', label: 'Create the concept set', linkedProposalKind: 'createStandaloneConceptSet', linkedRoute: 'concepts', required: true },
  ],
}

describe('gated plan', () => {
  beforeEach(() => resetPlans())

  it('instantiates a gated plan from a template payload', () => {
    const r = applyGatedPlan(payload)
    expect(r.ok).toBe(true)
    expect(activePlan.value?.gated).toBe(true)
    expect(activePlan.value?.steps).toHaveLength(2)
    expect(activePlan.value?.steps[1].required).toBe(true)
  })

  it('blocks a proposal that skips ahead of the first pending required step', () => {
    applyGatedPlan(payload)
    const g = gateProposal('createStandaloneConceptSet')
    expect(g.ok).toBe(false)
    if (!g.ok) expect(g.reason).toMatch(/Resolve concepts/)
  })

  it('allows the proposal once the prior required step is done', () => {
    applyGatedPlan(payload)
    activePlan.value!.steps[0].status = 'done'
    expect(gateProposal('createStandaloneConceptSet').ok).toBe(true)
  })

  it('allows out-of-band proposals not in the plan', () => {
    applyGatedPlan(payload)
    expect(gateProposal('navigate').ok).toBe(true)
  })

  it('does not gate when no gated plan is active', () => {
    expect(gateProposal('createStandaloneConceptSet').ok).toBe(true)
  })

  it('does not gate a free-form (non-gated) plan', () => {
    applyCreatePlan({
      title: 'free form',
      steps: [{ id: 'a', label: 'A', linkedProposalKind: 'createStandaloneConceptSet' }],
    })
    expect(activePlan.value?.gated).toBeFalsy()
    expect(gateProposal('createStandaloneConceptSet').ok).toBe(true)
  })

  it('allows an optional step whose proposal sits before the first required step', () => {
    applyGatedPlan({
      scenario: 'x', title: 'x',
      steps: [
        { id: 'opt', label: 'Optional first', linkedProposalKind: 'addEntryEvent', required: false },
        { id: 'req', label: 'Required next', linkedProposalKind: 'createStandaloneConceptSet', required: true },
      ],
    })
    expect(gateProposal('addEntryEvent').ok).toBe(true)
  })

  it('applyGatedPlan rejects an empty step list', () => {
    const r = applyGatedPlan({ scenario: 'x', title: 'x', steps: [] })
    expect(r.ok).toBe(false)
    expect(activePlan.value).toBeNull()
  })

  it('sets required on the first step too', () => {
    applyGatedPlan(payload)
    expect(activePlan.value?.steps[0].required).toBe(true)
  })
})
