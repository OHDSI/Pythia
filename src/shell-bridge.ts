import type { MessageBus } from './main'

export type ArtifactKind =
  | 'cohort'
  | 'conceptSet'
  | 'featureAnalysis'
  | 'characterization'
  | 'pathway'
  | 'incidenceRate'

export interface ArtifactSummary {
  kind: ArtifactKind
  id: number | string
  name: string
  summary: string
}

export interface RouteContext {
  routeName: string
  routeParams: Record<string, string | number>
  artifact: ArtifactSummary | null
}

export interface ShellContext {
  route?: { name: string; params: Record<string, string | number> }
  sourceKey: string | null
  routeContext?: RouteContext
  cohort: {
    id?: number
    name: string
    description?: string
    entryEventCount: number
    inclusionRuleCount: number
  } | null
  conceptSet?: {
    id?: number | string
    name: string
    itemCount: number
  } | null
}

export interface AgentProposal {
  kind:
    | 'addEntryEvent'
    | 'addInclusionRule'
    | 'addConceptSet'
    | 'setObservationPeriod'
    | 'setExitCriteria'
    | 'addCensoringCriterion'
    | 'navigate'
    | 'createStandaloneConceptSet'
    | 'createFeatureAnalysis'
    | 'createCharacterization'
    | 'createPathway'
    | 'createIncidenceRate'
    | 'updateConceptSet'
    | 'updateFeatureAnalysis'
    | 'updateCharacterization'
    | 'updatePathway'
    | 'updateIncidenceRate'
    | 'saveCohort'
  [key: string]: unknown
}

export async function getShellContext(bus: MessageBus): Promise<ShellContext> {
  // Try the new app-wide context first; fall back to the older cohort-only
  // context for hosts that haven't been updated yet.
  try {
    return await bus.request<ShellContext>('app.getContext', {})
  } catch {
    try {
      return await bus.request<ShellContext>('cohort.getContext', {})
    } catch {
      return { sourceKey: null, cohort: null }
    }
  }
}

export function applyProposal(bus: MessageBus, proposal: AgentProposal): void {
  bus.send('cohort.applyProposal', { proposal })
}

export function rejectProposal(bus: MessageBus, id: string): void {
  bus.send('cohort.rejectProposal', { id })
}

export function notify(bus: MessageBus, message: string, type: string = 'info'): void {
  bus.send('notify.snackbar', { message, type })
}
