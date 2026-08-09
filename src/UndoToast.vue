<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { dismissLastNavigation, lastNavigation, undoLastNavigation } from './chat-session'

const TIMEOUT_MS = 5000

const now = ref(Date.now())
let tickHandle: ReturnType<typeof setInterval> | null = null

onMounted(() => {
  tickHandle = setInterval(() => {
    now.value = Date.now()
  }, 250)
})
onUnmounted(() => {
  if (tickHandle !== null) clearInterval(tickHandle)
})

// Show the toast for EVERY agent navigation. It used to be suppressed unless a
// previous route had been captured, so the most common case — Pythia opening a
// fresh cohort from the landing page — moved the user to a different screen
// with no notification at all. Undo simply isn't offered when there's nowhere
// to go back to.
const visible = computed(() => {
  const ln = lastNavigation.value
  if (!ln) return false
  return now.value - ln.at < TIMEOUT_MS
})

const canUndo = computed(() => !!lastNavigation.value?.previous)

// Route names are internal ("cohort-new"); say what actually happened.
const ROUTE_LABELS: Record<string, string> = {
  'cohort-new': 'a new cohort definition',
  'cohort-edit': 'the cohort definition',
  'cohorts': 'the cohort list',
  'concept-sets': 'the concept sets',
  'concept-set-edit': 'the concept set',
  'pathways': 'the pathway analyses',
  'pathway-edit': 'the pathway analysis',
  'characterizations': 'the characterizations',
  'characterization-edit': 'the characterization',
  'incidence-rates': 'the incidence rate analyses',
  'incidence-rate-edit': 'the incidence rate analysis',
  'datasources': 'the data sources',
}

const label = computed(() => {
  const ln = lastNavigation.value
  if (!ln) return ''
  const what = ROUTE_LABELS[ln.toName] ?? ln.toName
  return ln.reason ? `Pythia opened ${what} — ${ln.reason}` : `Pythia opened ${what}`
})

function onUndo() {
  undoLastNavigation()
}

function onDismiss() {
  dismissLastNavigation()
}
</script>

<template>
  <div
    v-if="visible"
    class="undo-toast"
    role="status"
    aria-live="polite"
  >
    <span class="undo-toast__label">{{ label }}</span>
    <button
      v-if="canUndo"
      type="button"
      class="undo-toast__action"
      @click="onUndo"
    >
      Undo
    </button>
    <button
      type="button"
      class="undo-toast__dismiss"
      aria-label="Dismiss"
      @click="onDismiss"
    >
      &times;
    </button>
  </div>
</template>

<style scoped>
.undo-toast {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 6px 10px;
  margin: 4px 8px 0;
  background: #1f2937;
  color: #f9fafb;
  border-radius: 6px;
  font-size: 0.75rem;
}
.undo-toast__label { flex: 1 1 auto; }
.undo-toast__action {
  background: transparent;
  border: 1px solid #6b7280;
  color: #f9fafb;
  border-radius: 4px;
  padding: 2px 8px;
  font-size: 0.75rem;
  cursor: pointer;
}
.undo-toast__action:hover { background: rgba(255, 255, 255, 0.1); }
.undo-toast__dismiss {
  background: transparent;
  border: 0;
  color: #9ca3af;
  cursor: pointer;
  font-size: 1rem;
  line-height: 1;
}
.undo-toast__dismiss:hover { color: #f9fafb; }
</style>
