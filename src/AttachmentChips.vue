<script setup lang="ts">
// Attachment chips: the files waiting in the composer (removable, with a
// spinner while a PDF's text is being read) and the files a sent message
// carried (read-only; a dashed "not kept" chip once the content is gone).
import { computed } from 'vue'
import { formatBytes, type AttachmentInfo } from './attachments'
import type { Translator } from './main'

export interface AttachmentChipItem {
  id: string
  info: AttachmentInfo
  previewUrl?: string
  reading?: boolean
}

const props = defineProps<{
  items: AttachmentChipItem[]
  removable?: boolean
  align?: 'start' | 'end'
  t?: Translator
}>()

const emit = defineEmits<{ remove: [id: string] }>()

const t = (key: string, fallback: string) => props.t?.(key, fallback) ?? fallback

function isPdf(info: AttachmentInfo) {
  return info.mediaType === 'application/pdf'
}

function detail(item: AttachmentChipItem): string {
  const { info } = item
  if (item.reading) return t('cohortAgent.attachmentReading', 'Reading…')
  if (info.unavailable) return t('cohortAgent.attachmentGone', 'No longer available')
  const bits: string[] = []
  if (info.pages != null) bits.push(`${info.pages} ${info.pages === 1 ? 'page' : 'pages'}`)
  if (info.size != null) bits.push(formatBytes(info.size))
  return bits.join(' · ')
}

function badge(info: AttachmentInfo): string | null {
  if (info.unavailable) return null
  if (info.truncated) return t('cohortAgent.attachmentTruncated', 'Text, cut short')
  if (isPdf(info)) return t('cohortAgent.attachmentAsText', 'Sent as text')
  return null
}

function badgeTitle(info: AttachmentInfo): string {
  return info.truncated
    ? t('cohortAgent.attachmentTruncatedHint', 'Only the first part of this PDF\'s text is sent. Charts, images and scanned pages are left out.')
    : t('cohortAgent.attachmentAsTextHint', 'The text of this PDF is sent to the model. Charts, images and scanned pages are left out.')
}

const justify = computed(() => (props.align === 'end' ? 'flex-end' : 'flex-start'))
</script>

<template>
  <div
    class="attachment-chips"
    :style="{ justifyContent: justify }"
  >
    <div
      v-for="item in items"
      :key="item.id"
      class="attachment-chip"
      :class="{ 'attachment-chip--gone': item.info.unavailable }"
      :title="item.info.unavailable
        ? t('cohortAgent.attachmentGoneHint', 'Files are kept in this browser for 30 days. Attach it again if needed.')
        : item.info.filename"
    >
      <img
        v-if="item.previewUrl"
        :src="item.previewUrl"
        alt=""
        class="attachment-chip__thumb"
      >
      <span
        v-else-if="isPdf(item.info)"
        class="attachment-chip__icon attachment-chip__icon--pdf"
        aria-hidden="true"
      >PDF</span>
      <v-icon
        v-else
        class="attachment-chip__icon"
        icon="mdi-image-outline"
        size="18"
      />
      <span class="attachment-chip__text">
        <span class="attachment-chip__name">{{ item.info.filename }}</span>
        <span class="attachment-chip__detail">
          <v-progress-circular
            v-if="item.reading"
            indeterminate
            size="10"
            width="2"
            class="me-1"
          />
          {{ detail(item) }}
          <span
            v-if="badge(item.info) && !item.reading"
            class="attachment-chip__badge"
            :title="badgeTitle(item.info)"
          >{{ badge(item.info) }}</span>
        </span>
      </span>
      <button
        v-if="removable"
        type="button"
        class="attachment-chip__remove"
        :aria-label="`${t('cohortAgent.attachmentRemove', 'Remove')} ${item.info.filename}`"
        @click="emit('remove', item.id)"
      >
        <v-icon
          icon="mdi-close"
          size="14"
        />
      </button>
    </div>
  </div>
</template>

<style scoped>
.attachment-chips {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}
.attachment-chip {
  display: flex;
  align-items: center;
  gap: 6px;
  min-height: 36px;
  max-width: 220px;
  padding: 3px 4px;
  box-sizing: border-box;
  border: 1px solid #d5dce4;
  border-radius: 8px;
  background: #f7f9fb;
  color: #1f2937;
}
.attachment-chip--gone {
  border-style: dashed;
  border-color: #b8c2cc;
  background: #ffffff;
  color: #5b6573;
}
.attachment-chip__thumb {
  width: 28px;
  height: 28px;
  flex-shrink: 0;
  border-radius: 4px;
  object-fit: cover;
  background: #eef2f6;
}
.attachment-chip__icon {
  width: 28px;
  height: 28px;
  flex-shrink: 0;
  border-radius: 4px;
  background: #eef2f6;
  display: flex;
  align-items: center;
  justify-content: center;
}
.attachment-chip__icon--pdf {
  background: #fdecea;
  color: #b42318;
  font-size: 9px;
  font-weight: 700;
}
.attachment-chip--gone .attachment-chip__icon--pdf {
  background: #eef1f4;
  color: #5b6573;
}
.attachment-chip__text {
  display: flex;
  flex-direction: column;
  min-width: 0;
  padding-right: 4px;
}
.attachment-chip__name {
  font-size: 12px;
  font-weight: 500;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.attachment-chip__detail {
  display: flex;
  align-items: center;
  gap: 4px;
  font-size: 10.5px;
  color: #5b6573;
  white-space: nowrap;
}
.attachment-chip__badge {
  padding: 0 5px;
  border-radius: 999px;
  background: #e6eef7;
  color: #1d3a5c;
  font-weight: 500;
  cursor: help;
}
.attachment-chip__remove {
  width: 24px;
  height: 24px;
  flex-shrink: 0;
  border: 0;
  border-radius: 12px;
  background: transparent;
  color: #5b6573;
  display: flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
}
.attachment-chip__remove:hover {
  background: rgba(0, 0, 0, 0.06);
}
</style>
