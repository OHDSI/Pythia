<script setup lang="ts">
import type { ProposalState } from './types'

defineProps<{ proposal: ProposalState }>()
defineEmits<{ accept: [id: string]; reject: [id: string] }>()
</script>

<template>
  <div
    class="proposal-card"
    :class="{ accepted: proposal.status === 'accepted', rejected: proposal.status === 'rejected' }"
  >
    <div class="card-header">
      <span class="badge">SAVE</span>
      <span class="kind">Cohort</span>
    </div>
    <div class="title">
      {{ proposal.args.name || 'Save the open cohort' }}
    </div>
    <div
      v-if="proposal.args.description"
      class="detail"
    >
      {{ proposal.args.description }}
    </div>
    <div class="detail muted">
      Persists it to WebAPI so analyses can reference it by id.
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
        Save
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
      Saved
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
.badge { font-size: 0.625rem; font-weight: 700; letter-spacing: 0.04em; background: #eff6ff; color: #1d4ed8; border-radius: 4px; padding: 1px 5px; }
.kind { font-size: 0.6875rem; color: #6b7280; }
.title { font-weight: 600; color: #111827; }
.detail { color: #4b5563; margin-top: 2px; }
.detail.muted { color: #9ca3af; }
.actions { display: flex; gap: 6px; margin-top: 8px; }
.actions button { flex: 1; padding: 5px 8px; border-radius: 6px; border: 1px solid #d1d5db; cursor: pointer; font-size: 0.8125rem; }
.actions .accept { background: #16a34a; color: #fff; border-color: #16a34a; }
.actions .reject { background: #fff; color: #6b7280; }
.status-line { margin-top: 6px; font-size: 0.75rem; color: #047857; }
.status-line.muted { color: #9ca3af; }
</style>
