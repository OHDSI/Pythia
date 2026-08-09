<script setup lang="ts">
// Cards for the parts of a cohort that carry no concept: an age or sex
// restriction, which qualifying events count, a study window, the era gap,
// and removals. Without this they fell through to the criterion card, which
// looks for a concept, finds none, and renders "Unnamed concept" — the same
// way save_cohort did before it got its own card. A proposal the user cannot
// read is a proposal they cannot meaningfully approve.
import { computed } from 'vue'
import type { ProposalState } from './types'

const props = defineProps<{ proposal: ProposalState }>()
defineEmits<{ accept: [id: string]; reject: [id: string] }>()

const args = computed(() => props.proposal.args as Record<string, unknown>)
const tool = computed(() => props.proposal.toolName)

const label = computed(() => {
  switch (tool.value) {
    case 'add_demographic_criterion': return 'Demographics'
    case 'set_event_limits': return 'Qualifying events'
    case 'set_censor_window': return 'Study window'
    case 'set_era_collapse': return 'Cohort eras'
    case 'use_concept_set': return 'Existing concept set'
    case 'remove_inclusion_rule':
    case 'remove_entry_event': return 'Remove'
    default: return 'Cohort setting'
  }
})

const summary = computed(() => {
  const a = args.value
  const n = (k: string) => (typeof a[k] === 'number' ? (a[k] as number) : undefined)
  const s = (k: string) => (typeof a[k] === 'string' ? (a[k] as string) : undefined)
  switch (tool.value) {
    case 'add_demographic_criterion': {
      const min = n('minAge'); const max = n('maxAge'); const sex = s('sex')
      const age = min !== undefined && max !== undefined ? `Age ${min}–${max}`
        : min !== undefined ? `Age ${min} and over`
        : max !== undefined ? `Age up to ${max}` : null
      return [age, sex ? sex[0].toUpperCase() + sex.slice(1) : null].filter(Boolean).join(' · ') || 'No restriction given'
    }
    case 'set_event_limits': {
      const parts = [
        s('entryEvents') ? `entry: ${s('entryEvents')} qualifying event` : null,
        s('qualifyingEvents') ? `rules apply to: ${s('qualifyingEvents')}` : null,
        s('inclusionRuleEvents') ? `after rules: ${s('inclusionRuleEvents')}` : null,
      ].filter(Boolean)
      return parts.join(' · ') || 'No limit given'
    }
    case 'set_censor_window':
      return [s('startDate') ?? 'no start', s('endDate') ?? 'no end'].join(' → ')
    case 'set_era_collapse':
      return `Merge gaps shorter than ${n('gapDays') ?? 0} days`
    case 'use_concept_set':
      return `Concept set #${n('conceptSetId') ?? '?'}${s('group') ? ` · ${s('group')}` : ''}`
    case 'remove_inclusion_rule':
      return s('name') ?? `rule ${s('id') ?? ''}`
    case 'remove_entry_event':
      return s('conceptName') ?? `concept ${n('conceptId') ?? ''}`
    default:
      return ''
  }
})
</script>

<template>
  <div
    class="proposal-card"
    :class="{
      accepted: proposal.status === 'accepted',
      rejected: proposal.status === 'rejected',
      removal: tool.startsWith('remove_'),
    }"
  >
    <div class="card-header">
      <span class="badge">{{ label }}</span>
    </div>
    <div class="value-line">{{ summary }}</div>
    <div
      v-if="proposal.status === 'pending'"
      class="actions"
    >
      <button
        type="button"
        class="accept"
        @click="$emit('accept', proposal.id)"
      >
        Accept
      </button>
      <button
        type="button"
        class="reject"
        @click="$emit('reject', proposal.id)"
      >
        Reject
      </button>
    </div>
    <div
      v-else-if="proposal.status === 'accepted'"
      class="status-line"
    >
      Applied
    </div>
    <div
      v-else
      class="status-line muted"
    >
      {{ proposal.status === 'dismissed' ? 'Dismissed — you replied instead' : 'Rejected' }}
    </div>
  </div>
</template>

<style scoped>
.proposal-card { border: 1px solid #e5e7eb; border-radius: 8px; padding: 8px 10px; margin: 6px 0; background: #ffffff; font-size: 0.8125rem; }
.proposal-card.accepted { border-color: #16a34a; background: #f0fdf4; }
.proposal-card.rejected { opacity: 0.55; }
.card-header { margin-bottom: 4px; }
.badge { display: inline-block; padding: 1px 6px; border-radius: 999px; background: #fef3c7; color: #92400e; font-size: 0.6875rem; text-transform: uppercase; letter-spacing: 0.04em; }
.removal .badge { background: #fee2e2; color: #991b1b; }
.value-line { font-family: ui-monospace, SFMono-Regular, monospace; font-size: 0.8125rem; color: #1f2937; }
.actions { display: flex; gap: 6px; margin-top: 8px; }
.actions button { flex: 1; padding: 4px 8px; border-radius: 6px; border: 1px solid transparent; font-size: 0.75rem; cursor: pointer; }
.actions .accept { background: #16a34a; color: white; }
.actions .reject { background: white; color: #6b7280; border-color: #d1d5db; }
.status-line { margin-top: 6px; font-size: 0.75rem; color: #16a34a; }
.status-line.muted { color: #9ca3af; }
</style>
