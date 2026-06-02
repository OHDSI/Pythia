// Vendored copy of Atlas3's generated route manifest — the single source
// of truth for route names, param keys, and labels exposed to Pythia.
// Refresh by running `npm run generate:routes` in the Atlas3 root and
// copying src/router/routes.manifest.json over this file.
import manifestJson from './routes.manifest.json'

export interface RouteManifestEntry {
  name: string
  path: string
  params: string[]
  agentVisible: boolean
  label?: string
}

const manifest = manifestJson as RouteManifestEntry[]
const byName = new Map(manifest.map(r => [r.name, r]))

export function agentVisibleViews(): string[] {
  return manifest.filter(r => r.agentVisible).map(r => r.name)
}

export function isAgentVisibleView(name: string): boolean {
  return byName.get(name)?.agentVisible === true
}

export function getViewParams(name: string): string[] {
  return byName.get(name)?.params ?? []
}

export function getViewLabel(name: string): string {
  const r = byName.get(name)
  return r?.label ?? name
}

export function fullManifest(): RouteManifestEntry[] {
  return manifest
}
