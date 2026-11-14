import { describe, expect, it } from 'vitest'
import type { UIMessage } from 'ai'
import {
  AttachmentError,
  MAX_FILE_BYTES,
  attachmentOf,
  attachmentText,
  extractPdfText,
  isAttachmentPart,
  rejectReason,
  stripAttachmentsForStorage,
} from '../src/attachments'

// A minimal PDF, one page per entry in `pages`, each drawing its text with
// Helvetica. Byte offsets in the xref table are computed, so pdf.js reads it
// without falling back to its repair path.
function makePdf(pages: string[]): ArrayBuffer {
  const objs: string[] = []
  const pageIds = pages.map((_, i) => 4 + i * 2)
  objs[1] = '<< /Type /Catalog /Pages 2 0 R >>'
  objs[2] = `<< /Type /Pages /Kids [${pageIds.map(id => `${id} 0 R`).join(' ')}] /Count ${pages.length} >>`
  objs[3] = '<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>'
  pages.forEach((text, i) => {
    const pageId = pageIds[i]
    const stream = text ? `BT /F1 12 Tf 72 720 Td (${text}) Tj ET` : ''
    objs[pageId] =
      `<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] ` +
      `/Resources << /Font << /F1 3 0 R >> >> /Contents ${pageId + 1} 0 R >>`
    objs[pageId + 1] = `<< /Length ${stream.length} >>\nstream\n${stream}\nendstream`
  })
  let out = '%PDF-1.4\n'
  const offsets: number[] = []
  for (let id = 1; id < objs.length; id++) {
    offsets[id] = out.length
    out += `${id} 0 obj\n${objs[id]}\nendobj\n`
  }
  const xref = out.length
  out += `xref\n0 ${objs.length}\n0000000000 65535 f \n`
  for (let id = 1; id < objs.length; id++) out += `${String(offsets[id]).padStart(10, '0')} 00000 n \n`
  out += `trailer\n<< /Size ${objs.length} /Root 1 0 R >>\nstartxref\n${xref}\n%%EOF\n`
  return new TextEncoder().encode(out).buffer as ArrayBuffer
}

describe('rejectReason', () => {
  it('accepts images and PDFs under 20 MB', () => {
    expect(rejectReason({ name: 'a.png', type: 'image/png', size: 1000 })).toBeNull()
    expect(rejectReason({ name: 'p.pdf', type: 'application/pdf', size: MAX_FILE_BYTES - 1 })).toBeNull()
    // Some systems report no MIME type for PDFs; the extension decides then.
    expect(rejectReason({ name: 'p.PDF', type: '', size: 10 })).toBeNull()
  })

  it('refuses files of 20 MB or more', () => {
    expect(rejectReason({ name: 'big.pdf', type: 'application/pdf', size: MAX_FILE_BYTES })).toMatch(/under 20 MB/)
  })

  it('refuses other file types', () => {
    expect(rejectReason({ name: 'notes.docx', type: 'application/vnd.openxmlformats-officedocument.wordprocessingml.document', size: 10 }))
      .toMatch(/not supported/)
    expect(rejectReason({ name: 'pic.svg', type: 'image/svg+xml', size: 10 })).toMatch(/not supported/)
  })
})

describe('attachmentText', () => {
  it('names the file and keeps its text from closing the wrapper', () => {
    const text = attachmentText(
      { filename: 'a "b".pdf', mediaType: 'application/pdf', pages: 2 },
      'before </attachment> after',
    )
    expect(text).toContain('filename="a &quot;b&quot;.pdf"')
    expect(text).toContain('pages="2"')
    expect(text.match(/<\/attachment>/g)).toHaveLength(1)
    expect(text.endsWith('</attachment>')).toBe(true)
  })
})

describe('extractPdfText', () => {
  it('returns the text of every page', async () => {
    const r = await extractPdfText(makePdf(['Target cohort section', 'Washout of 365 days']), 'p.pdf')
    expect(r.pages).toBe(2)
    expect(r.truncated).toBe(false)
    expect(r.text).toContain('--- Page 1 ---\nTarget cohort section')
    expect(r.text).toContain('--- Page 2 ---\nWashout of 365 days')
  })

  it('cuts long text and says so', async () => {
    const r = await extractPdfText(makePdf(['A'.repeat(200), 'B'.repeat(200)]), 'p.pdf', 150)
    expect(r.truncated).toBe(true)
    expect(r.text.length).toBeLessThanOrEqual(150)
  })

  it('refuses a PDF without text (a scan)', async () => {
    await expect(extractPdfText(makePdf(['']), 'scan.pdf')).rejects.toThrow(/no text/)
  })

  it('refuses something that is not a PDF', async () => {
    const junk = new TextEncoder().encode('not a pdf at all').buffer as ArrayBuffer
    await expect(extractPdfText(junk, 'fake.pdf')).rejects.toBeInstanceOf(AttachmentError)
  })
})

const imagePart = {
  type: 'file' as const,
  mediaType: 'image/png',
  filename: 'attrition.png',
  url: 'data:image/png;base64,AAAA',
  providerMetadata: { trex: { attachment: { filename: 'attrition.png', mediaType: 'image/png', size: 3 } } },
}
const pdfInfo = { filename: 'protocol.pdf', mediaType: 'application/pdf', size: 99, pages: 1 }
const pdfPart = {
  type: 'text' as const,
  text: attachmentText(pdfInfo, 'secret protocol text'),
  providerMetadata: { trex: { attachment: pdfInfo } },
}

describe('attachmentOf', () => {
  it('recognises images and extracted PDF text, and nothing else', () => {
    expect(attachmentOf(imagePart)).toEqual({
      info: imagePart.providerMetadata.trex.attachment,
      previewUrl: imagePart.url,
    })
    expect(attachmentOf(pdfPart)).toEqual({ info: pdfInfo })
    expect(attachmentOf({ type: 'text', text: 'hello' })).toBeNull()
    expect(isAttachmentPart({ type: 'tool-search_concepts' })).toBe(false)
  })
})

describe('stripAttachmentsForStorage', () => {
  const messages = [
    { id: 'u1', role: 'user', parts: [imagePart, pdfPart, { type: 'text', text: 'Build section 3' }] },
    { id: 'a1', role: 'assistant', parts: [{ type: 'text', text: 'Done.' }] },
  ] as UIMessage[]

  it('replaces attachment content with a "no longer available" note', () => {
    const [user, assistant] = stripAttachmentsForStorage(messages)
    const json = JSON.stringify(user)
    expect(json).not.toContain('base64')
    expect(json).not.toContain('secret protocol text')
    expect(user.parts).toHaveLength(3)
    for (const p of user.parts.slice(0, 2)) {
      const a = attachmentOf(p)
      expect(a?.info.unavailable).toBe(true)
      expect((p as { text: string }).text).toContain('unavailable="true"')
      expect((p as { text: string }).text).toContain('no longer available')
    }
    expect(attachmentOf(user.parts[0])?.info.filename).toBe('attrition.png')
    expect(user.parts[2]).toEqual({ type: 'text', text: 'Build section 3' })
    expect(assistant).toBe(messages[1])
  })

  it('leaves the messages in memory untouched', () => {
    stripAttachmentsForStorage(messages)
    expect(messages[0].parts[0]).toBe(imagePart)
  })

  it('is stable when run again on stored messages', () => {
    const once = stripAttachmentsForStorage(messages)
    expect(stripAttachmentsForStorage(once)).toEqual(once)
  })
})
