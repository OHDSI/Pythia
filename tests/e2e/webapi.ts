// Minimal OHDSI WebAPI client for the e2e suite. Every assertion about what
// Pythia built is made against what WebAPI persisted, never against the page:
// the ATLAS editor renders an unsaved cohort just as happily as a saved one.

const BASE = (process.env.PYTHIA_E2E_WEBAPI_URL || 'http://localhost:8080/WebAPI').replace(/\/$/, '')

export interface CohortSummary { id: number; name: string }

export interface ConceptSetItem {
  concept: { CONCEPT_ID: number; CONCEPT_NAME?: string; DOMAIN_ID?: string; STANDARD_CONCEPT?: string }
  includeDescendants?: boolean
  isExcluded?: boolean
}

export interface CohortExpression {
  ConceptSets: { id: number; name: string; expression: { items: ConceptSetItem[] } }[]
  PrimaryCriteria: { CriteriaList: Record<string, any>[] }
  InclusionRules?: { name: string; expression: Record<string, any> }[]
  [k: string]: unknown
}

export interface Generation { status: string; personCount: number | null; isValid: boolean; failMessage?: string | null }

export class WebApi {
  private constructor(private readonly token: string) {}

  static async login(
    user = process.env.ATLAS_USER || 'admin',
    password = process.env.ATLAS_PASS || 'admin',
  ): Promise<WebApi> {
    const res = await fetch(`${BASE}/user/login/db`, {
      method: 'POST',
      headers: { 'content-type': 'application/x-www-form-urlencoded' },
      body: new URLSearchParams({ login: user, password }),
    })
    if (!res.ok) throw new Error(`WebAPI login failed: HTTP ${res.status}`)
    // trex's WebAPI returns the jwt in the body; stock WebAPI in the header.
    const header = res.headers.get('bearer') || res.headers.get('authorization')?.replace(/^Bearer\s+/i, '')
    const body = await res.json().catch(() => ({} as { jwt?: string }))
    const token = (body as { jwt?: string }).jwt || header
    if (!token) throw new Error('WebAPI login returned no token')
    return new WebApi(token)
  }

  private async req<T>(path: string, init: RequestInit = {}): Promise<T> {
    const res = await fetch(`${BASE}${path}`, {
      ...init,
      headers: { authorization: `Bearer ${this.token}`, accept: 'application/json', ...(init.headers || {}) },
    })
    if (!res.ok) throw new Error(`${init.method || 'GET'} ${path}: HTTP ${res.status} ${await res.text().catch(() => '')}`.slice(0, 500))
    const text = await res.text()
    return (text ? JSON.parse(text) : undefined) as T
  }

  async sourceKey(): Promise<string> {
    if (process.env.PYTHIA_E2E_SOURCE_KEY) return process.env.PYTHIA_E2E_SOURCE_KEY
    const sources = await this.req<{ sourceKey: string; daimons?: { daimonType: string }[] }[]>('/source/sources')
    const cdm = sources.find(s => s.daimons?.some(d => d.daimonType === 'CDM')) ?? sources[0]
    if (!cdm) throw new Error('WebAPI lists no data sources')
    return cdm.sourceKey
  }

  cohorts(): Promise<CohortSummary[]> {
    return this.req<CohortSummary[]>('/cohortdefinition/')
  }

  async maxCohortId(): Promise<number> {
    return (await this.cohorts()).reduce((m, c) => Math.max(m, c.id), 0)
  }

  async cohortsAfter(id: number): Promise<CohortSummary[]> {
    return (await this.cohorts()).filter(c => c.id > id).sort((a, b) => a.id - b.id)
  }

  async expression(id: number): Promise<CohortExpression> {
    const def = await this.req<{ expression: string | CohortExpression }>(`/cohortdefinition/${id}`)
    return typeof def.expression === 'string' ? JSON.parse(def.expression) : def.expression
  }

  async generation(id: number, sourceKey: string): Promise<Generation | undefined> {
    const infos = await this.req<(Generation & { id: { sourceId: number } })[]>(`/cohortdefinition/${id}/info`)
    const sources = await this.req<{ sourceKey: string; sourceId: number }[]>('/source/sources')
    const sourceId = sources.find(s => s.sourceKey === sourceKey)?.sourceId
    return infos.find(i => i.id?.sourceId === sourceId)
  }

  async generate(id: number, sourceKey: string): Promise<void> {
    await this.req(`/cohortdefinition/${id}/generate/${encodeURIComponent(sourceKey)}`)
  }

  // Waits for a generation to finish. Triggers one first if none was started,
  // so a definition the agent saved but never ran is still checked by running it.
  async generateAndWait(id: number, sourceKey: string, timeoutMs = 180_000): Promise<Generation> {
    let info = await this.generation(id, sourceKey)
    if (!info) await this.generate(id, sourceKey)
    const t0 = Date.now()
    while (Date.now() - t0 < timeoutMs) {
      info = await this.generation(id, sourceKey)
      if (info && /COMPLETE|FAILED/i.test(info.status)) return info
      await new Promise(r => setTimeout(r, 2_000))
    }
    throw new Error(`cohort ${id} did not finish generating on ${sourceKey} within ${timeoutMs / 1000}s (last: ${JSON.stringify(info)})`)
  }

  concept(sourceKey: string, id: number) {
    return this.req<{ CONCEPT_ID: number; CONCEPT_NAME: string; STANDARD_CONCEPT: string | null; DOMAIN_ID: string; INVALID_REASON?: string | null }>(
      `/vocabulary/${encodeURIComponent(sourceKey)}/concept/${id}`,
    )
  }

  // The concept ids a concept-set expression stands for on this source's
  // vocabulary: descendants expanded, excluded items removed. Comparing
  // resolved sets is what makes two differently written concept sets
  // comparable (a parent with descendants vs. its children listed one by one).
  async resolveConceptSet(sourceKey: string, expression: { items: ConceptSetItem[] }): Promise<number[]> {
    const ids = await this.req<number[]>(`/vocabulary/${encodeURIComponent(sourceKey)}/resolveConceptSetExpression`, {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify(expression),
    })
    return [...new Set(ids)].sort((a, b) => a - b)
  }

  async createCohort(name: string, expression: CohortExpression): Promise<number> {
    const def = await this.req<{ id: number }>('/cohortdefinition/', {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ name, expressionType: 'SIMPLE_EXPRESSION', expression }),
    })
    return def.id
  }

  async deleteCohort(id: number): Promise<void> {
    await this.req(`/cohortdefinition/${id}`, { method: 'DELETE' }).catch(() => {})
  }
}

// --- expression helpers ------------------------------------------------------

export function codesetItems(expr: CohortExpression, codesetId: unknown): ConceptSetItem[] {
  return expr.ConceptSets.find(cs => cs.id === codesetId)?.expression.items ?? []
}

// Every criterion object in the expression, wherever it nests (primary,
// inclusion rules, correlated criteria, additional criteria), as [domain, body].
export function allCriteria(node: unknown, out: [string, Record<string, any>][] = []): [string, Record<string, any>][] {
  if (Array.isArray(node)) { node.forEach(n => allCriteria(n, out)); return out }
  if (!node || typeof node !== 'object') return out
  for (const [k, v] of Object.entries(node as Record<string, unknown>)) {
    if (/^(ConditionOccurrence|DrugExposure|ProcedureOccurrence|Measurement|Observation|VisitOccurrence|DeviceExposure|ConditionEra|DrugEra|Death|Specimen|ObservationPeriod)$/.test(k) && v && typeof v === 'object') {
      out.push([k, v as Record<string, any>])
    }
    allCriteria(v, out)
  }
  return out
}

export function allConceptIds(expr: CohortExpression): number[] {
  return [...new Set(expr.ConceptSets.flatMap(cs => cs.expression.items.map(i => i.concept.CONCEPT_ID)))]
}
