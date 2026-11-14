// Keeps attachment content in the browser's IndexedDB, so a chat still has its
// files after a page reload or after switching to another chat and back.
//
// Saved chats in localStorage hold only a placeholder for each attachment
// (stripAttachmentsForStorage in attachments.ts): localStorage is limited to a
// few MB. The content itself lives here, keyed by chat, message and part
// position, and is put back into the messages when a chat is opened.
// Nothing leaves the browser.
//
// Files are removed with their chat (deleted or cleared), and every file of a
// chat nobody opened or wrote to for FILE_RETENTION_MS is removed on startup:
// protocols and screenshots can hold sensitive data, so they should not stay
// on a machine indefinitely.
//
// Every operation fails soft. Without IndexedDB (some private windows, blocked
// site data) chats behave as if the files had expired: the placeholder stays
// and the model is told the file is no longer available.

import type { UIMessage } from 'ai'
import { attachmentOf, type AttachmentPart } from './attachments'

export const FILE_RETENTION_MS = 30 * 24 * 60 * 60 * 1000

const DB_NAME = 'pythia-attachments'
const DB_VERSION = 1
const FILES = 'files'
const CHATS = 'chats'

interface FileRecord {
  key: string
  sessionId: string
  part: AttachmentPart
}

interface ChatRecord {
  sessionId: string
  lastUsedAt: number
}

let dbPromise: Promise<IDBDatabase | null> | null = null

function openDb(): Promise<IDBDatabase | null> {
  dbPromise ??= new Promise(resolve => {
    try {
      if (typeof indexedDB === 'undefined') return resolve(null)
      const req = indexedDB.open(DB_NAME, DB_VERSION)
      req.onupgradeneeded = () => {
        const db = req.result
        const files = db.createObjectStore(FILES, { keyPath: 'key' })
        files.createIndex('sessionId', 'sessionId')
        db.createObjectStore(CHATS, { keyPath: 'sessionId' })
      }
      req.onsuccess = () => resolve(req.result)
      req.onerror = () => resolve(null)
      req.onblocked = () => resolve(null)
    } catch {
      resolve(null)
    }
  })
  return dbPromise
}

/** For tests: forget the open connection so the next call opens a fresh one. */
export function resetAttachmentStoreForTests(): void {
  void dbPromise?.then(db => db?.close())
  dbPromise = null
  savedKeys.clear()
}

function done(tx: IDBTransaction): Promise<void> {
  return new Promise((resolve, reject) => {
    tx.oncomplete = () => resolve()
    tx.onerror = () => reject(tx.error)
    tx.onabort = () => reject(tx.error)
  })
}

function request<T>(req: IDBRequest<T>): Promise<T> {
  return new Promise((resolve, reject) => {
    req.onsuccess = () => resolve(req.result)
    req.onerror = () => reject(req.error)
  })
}

function fileKey(sessionId: string, messageId: string, partIndex: number): string {
  return `${sessionId}:${messageId}:${partIndex}`
}

// Keys already written in this page, so the persistence watcher (which fires
// on every streamed chunk) does not rewrite the same files over and over.
const savedKeys = new Set<string>()

/** Stores the content of every attachment in the chat that is not stored yet. */
export async function saveAttachments(sessionId: string, messages: UIMessage[]): Promise<void> {
  const fresh: FileRecord[] = []
  for (const m of messages) {
    if (m.role !== 'user') continue
    m.parts.forEach((part, idx) => {
      const a = attachmentOf(part)
      if (!a || a.info.unavailable) return
      const key = fileKey(sessionId, m.id, idx)
      if (savedKeys.has(key)) return
      fresh.push({ key, sessionId, part: JSON.parse(JSON.stringify(part)) as AttachmentPart })
    })
  }
  if (fresh.length === 0) return
  const db = await openDb()
  if (!db) return
  try {
    const tx = db.transaction([FILES, CHATS], 'readwrite')
    for (const rec of fresh) tx.objectStore(FILES).put(rec)
    tx.objectStore(CHATS).put({ sessionId, lastUsedAt: Date.now() } satisfies ChatRecord)
    await done(tx)
    for (const rec of fresh) savedKeys.add(rec.key)
  } catch {
    // Quota exceeded or storage blocked: the files stay in memory for this page.
  }
}

/**
 * The messages with each placeholder that has stored content swapped back
 * for the original part. Returns null when nothing was restored. Marks the
 * chat as used, which restarts its retention period.
 */
export async function restoreAttachments<T extends UIMessage>(
  sessionId: string,
  messages: T[],
): Promise<T[] | null> {
  const wanted = new Map<string, { m: number; p: number }>()
  messages.forEach((msg, m) => {
    if (msg.role !== 'user') return
    msg.parts.forEach((part, p) => {
      if (attachmentOf(part)?.info.unavailable) wanted.set(fileKey(sessionId, msg.id, p), { m, p })
    })
  })
  if (wanted.size === 0) return null
  const db = await openDb()
  if (!db) return null
  try {
    const tx = db.transaction([FILES, CHATS], 'readwrite')
    const records = await request(tx.objectStore(FILES).index('sessionId').getAll(sessionId) as IDBRequest<FileRecord[]>)
    tx.objectStore(CHATS).put({ sessionId, lastUsedAt: Date.now() } satisfies ChatRecord)
    await done(tx)
    let changed = false
    const out = messages.map(msg => ({ ...msg, parts: [...msg.parts] }))
    for (const rec of records) {
      const at = wanted.get(rec.key)
      if (!at) continue
      out[at.m].parts[at.p] = rec.part as T['parts'][number]
      savedKeys.add(rec.key)
      changed = true
    }
    return changed ? out : null
  } catch {
    return null
  }
}

/** Removes every stored file of a chat. */
export async function deleteAttachments(sessionId: string): Promise<void> {
  const db = await openDb()
  if (!db) return
  try {
    const tx = db.transaction([FILES, CHATS], 'readwrite')
    const keys = await request(tx.objectStore(FILES).index('sessionId').getAllKeys(sessionId))
    for (const k of keys) tx.objectStore(FILES).delete(k)
    tx.objectStore(CHATS).delete(sessionId)
    await done(tx)
    for (const k of keys) savedKeys.delete(String(k))
  } catch {
    // Nothing more to do: the files expire with the retention rule.
  }
}

/** Removes the files of every chat not used for FILE_RETENTION_MS. */
export async function pruneOldAttachments(now = Date.now()): Promise<void> {
  const db = await openDb()
  if (!db) return
  try {
    const chats = await request(db.transaction(CHATS).objectStore(CHATS).getAll() as IDBRequest<ChatRecord[]>)
    const stale = chats.filter(c => now - c.lastUsedAt > FILE_RETENTION_MS)
    for (const c of stale) await deleteAttachments(c.sessionId)
  } catch {
    // Try again next time the page loads.
  }
}
