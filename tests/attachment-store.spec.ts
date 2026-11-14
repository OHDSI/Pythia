import 'fake-indexeddb/auto'
import { IDBFactory } from 'fake-indexeddb'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { UIMessage } from 'ai'
import { attachmentOf, attachmentText, stripAttachmentsForStorage } from '../src/attachments'
import {
  FILE_RETENTION_MS,
  deleteAttachments,
  pruneOldAttachments,
  resetAttachmentStoreForTests,
  restoreAttachments,
  saveAttachments,
} from '../src/attachment-store'

const imageInfo = { filename: 'attrition.png', mediaType: 'image/png', size: 3 }
const pdfInfo = { filename: 'protocol.pdf', mediaType: 'application/pdf', size: 9, pages: 1 }

const messages = [
  {
    id: 'u1',
    role: 'user',
    parts: [
      { type: 'file', mediaType: 'image/png', filename: 'attrition.png', url: 'data:image/png;base64,AAAA', providerMetadata: { trex: { attachment: imageInfo } } },
      { type: 'text', text: attachmentText(pdfInfo, 'protocol text'), providerMetadata: { trex: { attachment: pdfInfo } } },
      { type: 'text', text: 'Build section 3' },
    ],
  },
  { id: 'a1', role: 'assistant', parts: [{ type: 'text', text: 'Done.' }] },
] as UIMessage[]

beforeEach(() => {
  resetAttachmentStoreForTests()
  globalThis.indexedDB = new IDBFactory()
})

describe('attachment store', () => {
  it('puts saved files back in place of the placeholders', async () => {
    await saveAttachments('s1', messages)
    const stored = stripAttachmentsForStorage(messages)
    expect(attachmentOf(stored[0].parts[0])?.info.unavailable).toBe(true)

    resetAttachmentStoreForTests() // as after a page reload
    const restored = await restoreAttachments('s1', stored)
    expect(restored).not.toBeNull()
    expect(restored![0].parts).toEqual(messages[0].parts)
    expect(restored![1]).toEqual(messages[1])
  })

  it('keeps files apart per chat', async () => {
    await saveAttachments('s1', messages)
    expect(await restoreAttachments('s2', stripAttachmentsForStorage(messages))).toBeNull()
  })

  it('returns null when there is nothing to restore', async () => {
    expect(await restoreAttachments('s1', messages)).toBeNull()
  })

  it('forgets the files of a deleted chat', async () => {
    await saveAttachments('s1', messages)
    await deleteAttachments('s1')
    expect(await restoreAttachments('s1', stripAttachmentsForStorage(messages))).toBeNull()
  })

  it('removes files of chats unused for 30 days, and only those', async () => {
    const day = 24 * 60 * 60 * 1000
    const t0 = new Date('2026-01-01T00:00:00Z').getTime()
    vi.useFakeTimers({ toFake: ['Date'] })
    try {
      vi.setSystemTime(t0)
      await saveAttachments('old', messages)
      await saveAttachments('recent', messages)
      // Opening a chat restarts its retention period.
      vi.setSystemTime(t0 + 20 * day)
      await restoreAttachments('recent', stripAttachmentsForStorage(messages))
      vi.setSystemTime(t0 + 31 * day)
      await pruneOldAttachments()
      expect(await restoreAttachments('old', stripAttachmentsForStorage(messages))).toBeNull()
      expect(await restoreAttachments('recent', stripAttachmentsForStorage(messages))).not.toBeNull()
      expect(FILE_RETENTION_MS).toBe(30 * day)
    } finally {
      vi.useRealTimers()
    }
  })

  it('does nothing without IndexedDB', async () => {
    // @ts-expect-error simulating a browser without IndexedDB
    delete globalThis.indexedDB
    await saveAttachments('s1', messages)
    expect(await restoreAttachments('s1', stripAttachmentsForStorage(messages))).toBeNull()
  })
})
