/// <reference types="vite/client" />

// pdf.js ships no types for its worker module; attachments.ts only hands the
// module object to pdf.js (globalThis.pdfjsWorker) and never reads it.
declare module 'pdfjs-dist/legacy/build/pdf.worker.mjs' {
  export const WorkerMessageHandler: unknown
}
