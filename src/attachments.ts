// Images and PDFs the user attaches to a chat message.
//
// Everything happens in the browser. A file becomes part of the user message
// itself and travels to the model with the request, so nothing is uploaded or
// stored on the server (trex drops attachment content before it records a
// turn; see core/server/agents/service/redact-attachments.ts in trex).
//
//   - An image becomes an AI SDK `file` part holding a base64 data URL. Large
//     images are scaled down first, which keeps them under the providers'
//     per-image limits and saves tokens.
//   - A PDF becomes a `text` part: pdf.js pulls its text out here, so every
//     model can read it, including those that cannot take PDF files. Charts,
//     images and scanned pages carry no text and are lost.
//
// Both kinds carry `providerMetadata.trex.attachment` describing the file.
// That marker lets the panel draw a chip instead of a text bubble, and lets
// trex recognise attachment text it must not store. Providers ignore the key.
//
// Saved chats in localStorage could not hold the files, so
// `stripAttachmentsForStorage` swaps each one for a short "no longer available"
// note before a chat is written there. The content is kept in IndexedDB
// instead (attachment-store.ts) and put back when the chat is opened; the note
// is what remains when that copy is gone (expired, deleted, other browser).

import type { FileUIPart, ProviderMetadata, TextUIPart, UIMessage } from 'ai'

/** Files must be smaller than this. */
export const MAX_FILE_BYTES = 20 * 1024 * 1024
/** Most files one message can carry. */
export const MAX_ATTACHMENTS = 10
/** Longest edge an image is sent at; larger images are scaled down. */
export const IMAGE_MAX_EDGE = 1600
/** Encoded size above which an image is re-encoded (Bedrock's per-image limit is 3.75 MB). */
export const IMAGE_MAX_ENCODED_BYTES = 3_500_000
/** Most PDF text sent per file; the rest is cut off and marked as truncated. */
export const MAX_PDF_TEXT_CHARS = 200_000

export const IMAGE_TYPES = ['image/png', 'image/jpeg', 'image/gif', 'image/webp'] as const
export const PDF_TYPE = 'application/pdf'
/** `accept` value for the file picker. */
export const ACCEPT = [...IMAGE_TYPES, PDF_TYPE, '.pdf'].join(',')

export interface AttachmentInfo {
  filename: string
  mediaType: string
  /** Size of the original file in bytes. */
  size?: number
  /** PDFs: number of pages. */
  pages?: number
  /** PDFs: true when the text was cut off at MAX_PDF_TEXT_CHARS. */
  truncated?: boolean
  /** True once the content is gone (after a reload). */
  unavailable?: boolean
}

export type AttachmentPart = FileUIPart | TextUIPart

export interface PreparedAttachment {
  info: AttachmentInfo
  part: AttachmentPart
  /** Data URL for a thumbnail (images only). */
  previewUrl?: string
}

/** A file that cannot be attached, with a reason fit to show the user. */
export class AttachmentError extends Error {
  constructor(readonly filename: string, readonly reason: string) {
    super(`${filename}: ${reason}`)
    this.name = 'AttachmentError'
  }
}

type TrexMeta = { trex?: { attachment?: AttachmentInfo } }

function withInfo(info: AttachmentInfo): ProviderMetadata {
  // AttachmentInfo holds only JSON values; TypeScript just cannot see that
  // through an interface without an index signature.
  return { trex: { attachment: { ...info } as Record<string, string | number | boolean> } }
}

function isPdf(file: { name: string; type: string }): boolean {
  return file.type === PDF_TYPE || (!file.type && file.name.toLowerCase().endsWith('.pdf'))
}

function isImage(file: { type: string }): boolean {
  return (IMAGE_TYPES as readonly string[]).includes(file.type)
}

export function formatBytes(n: number | undefined): string {
  if (n == null) return ''
  if (n < 1024) return `${n} B`
  if (n < 1024 * 1024) return `${Math.round(n / 1024)} KB`
  return `${(n / (1024 * 1024)).toFixed(1)} MB`
}

/** Why a file cannot be attached, or null when it can. Cheap: reads no content. */
export function rejectReason(file: { name: string; type: string; size: number }): string | null {
  if (!isImage(file) && !isPdf(file)) return 'is not supported. Attach images (PNG, JPG, GIF, WebP) or PDFs.'
  if (file.size >= MAX_FILE_BYTES) return `is ${formatBytes(file.size)}. Files must be under 20 MB.`
  if (file.size === 0) return 'is empty.'
  return null
}

// ---------------------------------------------------------------- images

function readAsDataUrl(blob: Blob): Promise<string> {
  return new Promise((resolve, reject) => {
    const r = new FileReader()
    r.onload = () => resolve(String(r.result))
    r.onerror = () => reject(r.error ?? new Error('read failed'))
    r.readAsDataURL(blob)
  })
}

/** Bytes a base64 data URL decodes to (close enough for limits). */
export function dataUrlBytes(url: string): number {
  const comma = url.indexOf(',')
  return Math.floor(((url.length - comma - 1) * 3) / 4)
}

async function prepareImage(file: File): Promise<PreparedAttachment> {
  let url = await readAsDataUrl(file)
  let mediaType = file.type
  let bitmap: ImageBitmap
  try {
    bitmap = await createImageBitmap(file)
  } catch {
    throw new AttachmentError(file.name, 'could not be read as an image.')
  }
  const { width, height } = bitmap
  const longEdge = Math.max(width, height)
  if (longEdge > IMAGE_MAX_EDGE || dataUrlBytes(url) > IMAGE_MAX_ENCODED_BYTES) {
    const scale = Math.min(1, IMAGE_MAX_EDGE / longEdge)
    const canvas = document.createElement('canvas')
    canvas.width = Math.max(1, Math.round(width * scale))
    canvas.height = Math.max(1, Math.round(height * scale))
    const ctx = canvas.getContext('2d')
    if (!ctx) throw new AttachmentError(file.name, 'could not be resized.')
    ctx.drawImage(bitmap, 0, 0, canvas.width, canvas.height)
    // PNG keeps screenshots sharp; fall back to JPEG when PNG is still too big.
    // An animated GIF keeps only its first frame.
    mediaType = file.type === 'image/jpeg' || file.type === 'image/webp' ? file.type : 'image/png'
    url = canvas.toDataURL(mediaType, 0.9)
    if (dataUrlBytes(url) > IMAGE_MAX_ENCODED_BYTES) {
      mediaType = 'image/jpeg'
      url = canvas.toDataURL(mediaType, 0.85)
    }
  }
  bitmap.close?.()
  const info: AttachmentInfo = { filename: file.name, mediaType, size: file.size }
  return {
    info,
    previewUrl: url,
    part: { type: 'file', mediaType, filename: file.name, url, providerMetadata: withInfo(info) },
  }
}

// ---------------------------------------------------------------- PDFs

type PdfJs = typeof import('pdfjs-dist/legacy/build/pdf.mjs')
let pdfjsPromise: Promise<PdfJs> | null = null

// pdf.js is large, so it loads on the first PDF only. Its worker code runs on
// the main thread (pdf.js's "fake worker"): the plugin is a single SystemJS
// bundle with no place to serve a separate worker file from, and text
// extraction of a few hundred pages is quick enough not to need one.
function loadPdfJs(): Promise<PdfJs> {
  pdfjsPromise ??= (async () => {
    const worker = await import('pdfjs-dist/legacy/build/pdf.worker.mjs')
    ;(globalThis as { pdfjsWorker?: unknown }).pdfjsWorker = worker
    return import('pdfjs-dist/legacy/build/pdf.mjs')
  })()
  return pdfjsPromise
}

function escapeAttr(s: string): string {
  return s.replace(/&/g, '&amp;').replace(/"/g, '&quot;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
}

/** Wraps extracted text so the model knows where it came from and where it ends. */
export function attachmentText(info: AttachmentInfo, body: string): string {
  const attrs = [
    `filename="${escapeAttr(info.filename)}"`,
    `type="${escapeAttr(info.mediaType)}"`,
    info.pages != null ? `pages="${info.pages}"` : '',
    info.truncated ? 'truncated="true"' : '',
    info.unavailable ? 'unavailable="true"' : '',
  ].filter(Boolean).join(' ')
  // The file's own text must not be able to close the wrapper early.
  const safe = body.replace(/<\/attachment/gi, '<\\/attachment')
  return `<attachment ${attrs}>\n${safe}\n</attachment>`
}

export async function extractPdfText(
  data: ArrayBuffer,
  filename: string,
  maxChars = MAX_PDF_TEXT_CHARS,
): Promise<{ text: string; pages: number; truncated: boolean }> {
  const pdfjs = await loadPdfJs()
  const task = pdfjs.getDocument({ data: new Uint8Array(data), verbosity: pdfjs.VerbosityLevel.ERRORS })
  let doc
  try {
    doc = await task.promise
  } catch (e) {
    void task.destroy()
    const name = (e as { name?: string })?.name
    if (name === 'PasswordException') throw new AttachmentError(filename, 'is password-protected.')
    throw new AttachmentError(filename, 'could not be read as a PDF.')
  }
  try {
    const chunks: string[] = []
    let length = 0
    let truncated = false
    let anyText = false
    for (let n = 1; n <= doc.numPages; n++) {
      const page = await doc.getPage(n)
      const content = await page.getTextContent()
      let pageText = ''
      for (const item of content.items) {
        if (!('str' in item)) continue
        pageText += item.str + (item.hasEOL ? '\n' : '')
      }
      pageText = pageText.replace(/[ \t]+\n/g, '\n').trim()
      page.cleanup()
      if (pageText) anyText = true
      const chunk = `${n > 1 ? '\n' : ''}--- Page ${n} ---\n${pageText}\n`
      if (length + chunk.length > maxChars) {
        chunks.push(chunk.slice(0, Math.max(0, maxChars - length)))
        truncated = true
        break
      }
      chunks.push(chunk)
      length += chunk.length
    }
    if (!anyText) {
      throw new AttachmentError(filename, "has no text to read. Scanned PDFs can't be read.")
    }
    return { text: chunks.join('').trim(), pages: doc.numPages, truncated }
  } finally {
    void task.destroy()
  }
}

async function preparePdf(file: File): Promise<PreparedAttachment> {
  const { text, pages, truncated } = await extractPdfText(await file.arrayBuffer(), file.name)
  const info: AttachmentInfo = {
    filename: file.name,
    mediaType: PDF_TYPE,
    size: file.size,
    pages,
    ...(truncated ? { truncated: true } : {}),
  }
  return {
    info,
    part: { type: 'text', text: attachmentText(info, text), providerMetadata: withInfo(info) },
  }
}

/** Turns a file into a message part. Throws AttachmentError with a user-facing reason. */
export async function prepareAttachment(file: File): Promise<PreparedAttachment> {
  const reason = rejectReason(file)
  if (reason) throw new AttachmentError(file.name, reason)
  return isPdf(file) ? preparePdf(file) : prepareImage(file)
}

// ---------------------------------------------------------------- reading messages

/** The attachment a message part holds, or null for an ordinary part. */
export function attachmentOf(part: unknown): { info: AttachmentInfo; previewUrl?: string } | null {
  if (!part || typeof part !== 'object') return null
  const p = part as { type?: string; url?: string; mediaType?: string; filename?: string; providerMetadata?: TrexMeta }
  const meta = p.providerMetadata?.trex?.attachment
  if (p.type === 'file') {
    const info: AttachmentInfo = meta ?? {
      filename: p.filename ?? 'attachment',
      mediaType: p.mediaType ?? 'application/octet-stream',
    }
    const previewUrl = typeof p.url === 'string' && p.url.startsWith('data:image/') ? p.url : undefined
    return { info, previewUrl }
  }
  if (p.type === 'text' && meta) return { info: meta }
  return null
}

export function isAttachmentPart(part: unknown): boolean {
  return attachmentOf(part) !== null
}

// ---------------------------------------------------------------- storage

const UNAVAILABLE_NOTE =
  'This file is no longer available: the browser no longer holds it (files are kept for 30 days, ' +
  'and only in the browser they were attached in). Ask the user to attach it again if you need its content.'

function unavailablePart(info: AttachmentInfo): TextUIPart {
  const gone: AttachmentInfo = { ...info, unavailable: true }
  return { type: 'text', text: attachmentText(gone, UNAVAILABLE_NOTE), providerMetadata: withInfo(gone) }
}

/**
 * The messages as they may be written to localStorage: every attachment is
 * replaced by a note that it is no longer available. When the chat is opened
 * again the stored content takes the note's place; if there is none, the note
 * is what the model sees and the panel draws a "no longer available" chip.
 */
export function stripAttachmentsForStorage<T extends UIMessage>(messages: T[]): T[] {
  return messages.map(m => {
    if (m.role !== 'user' || !m.parts.some(isAttachmentPart)) return m
    return {
      ...m,
      parts: m.parts.map(p => {
        const a = attachmentOf(p)
        if (!a || a.info.unavailable) return p
        return unavailablePart(a.info)
      }),
    }
  })
}
