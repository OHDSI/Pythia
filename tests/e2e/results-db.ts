import { execFileSync } from 'node:child_process'

// Read access to the generated cohort rows. WebAPI only reports counts per
// cohort (no intersection), so comparing who is in two cohorts needs the
// results table itself. It lives in the ATLAS Postgres of the compose stack,
// which publishes no port, so the query goes through `docker compose exec`.
// When that is unavailable the caller skips the person-level comparison.

const PROJECT = process.env.PYTHIA_E2E_COMPOSE_PROJECT || 'atlas3-trex'

function psql(sql: string): string {
  return execFileSync('docker', [
    'compose', '-p', PROJECT, 'exec', '-T', 'atlas3-postgres',
    'psql', '-U', 'postgres', '-d', 'postgres', '-t', '-A', '-c', sql,
  ], { encoding: 'utf8', timeout: 60_000, stdio: ['ignore', 'pipe', 'pipe'] }).trim()
}

let resultsSchema: string | null | undefined

function schema(sourceKey: string): string | null {
  if (resultsSchema !== undefined) return resultsSchema
  try {
    const key = sourceKey.replace(/'/g, "''")
    const q = psql(`select d.table_qualifier from webapi.source s join webapi.source_daimon d on d.source_id = s.source_id where s.source_key = '${key}' and d.daimon_type = 2`)
    resultsSchema = /^[A-Za-z_][A-Za-z0-9_]*$/.test(q) ? q : null
  } catch {
    resultsSchema = null
  }
  return resultsSchema
}

export function resultsDbAvailable(sourceKey: string): boolean {
  return schema(sourceKey) !== null
}

export function cohortSubjects(sourceKey: string, cohortId: number): Set<number> {
  const s = schema(sourceKey)
  if (!s) throw new Error('results schema not reachable')
  const out = psql(`select distinct subject_id from ${s}.cohort where cohort_definition_id = ${Number(cohortId)}`)
  return new Set(out ? out.split('\n').map(Number) : [])
}

export function jaccard<T>(a: Set<T>, b: Set<T>): number {
  if (a.size === 0 && b.size === 0) return 1
  let inter = 0
  for (const x of a) if (b.has(x)) inter++
  return inter / (a.size + b.size - inter)
}
