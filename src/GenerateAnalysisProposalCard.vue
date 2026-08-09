<script setup lang="ts">
import type { ProposalState } from './types'

defineProps<{ proposal: ProposalState }>()
defineEmits<{ accept: [id: string]; reject: [id: string] }>()

const KIND_LABELS: Record<string, string> = {
  pathway: 'pathway analysis',
  characterization: 'characterization',
  incidenceRate: 'incidence rate analysis',
}
</script>

<template>
  <div
    class="proposal-card"
    :class="{ accepted: proposal.status === 'accepted', rejected: proposal.status === 'rejected' }"
  >
    <div class="card-header">
      <span class="badge">RUN</span>
      <span class="kind">Generate</span>
    </div>
    <div class="title">
      Run the {{ KIND_LABELS[String(proposal.args.analysisType)] ?? 'analysis' }} against the data
    </div>
    <div class="detail">
      Executes it on
      <strong>{{ proposal.args.sourceKey || 'the current data source' }}</strong>
      and brings the results back into the editor.
    </div>

    <div
      v-if="proposal.status === 'pending'"
      class="actions"
    >
      <button
        type="button"
        class="accept"
        @click="$emit('accept', proposal.id)"
      >
        Generate
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
      Generation started
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
.proposal-card.accepted { border-color: #86efac; background: #f0fdf4; }
.proposal-card.rejected { opacity: 0.65; }
.card-header { display: flex; align-items: center; gap: 6px; margin-bottom: 4px; }
.badge { font-size: 0.625rem; font-weight: 700; letter-spacing: 0.04em; background: #ecfdf5; color: #047857; border-radius: 4px; padding: 1px 5px; }
.kind { font-size: 0.6875rem; color: #6b7280; }
.title { font-weight: 600; color: #111827; }
.detail { color: #4b5563; margin-top: 2px; }
.actions { display: flex; gap: 6px; margin-top: 8px; }
.actions button { flex: 1; padding: 5px 8px; border-radius: 6px; border: 1px solid #d1d5db; cursor: pointer; font-size: 0.8125rem; }
.actions .accept { background: #16a34a; color: #fff; border-color: #16a34a; }
.actions .reject { background: #fff; color: #6b7280; }
.status-line { margin-top: 6px; font-size: 0.75rem; color: #047857; }
.status-line.muted { color: #9ca3af; }
</style>
