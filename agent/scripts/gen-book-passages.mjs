// Usage: node scripts/gen-book-passages.mjs <BookOfOHDSI-2ndEdition clone> <TheBookOfOhdsi clone>
//
// Source choice: the 2nd edition (OHDSI/BookOfOHDSI-2ndEdition, branch main) is
// still mostly stubs; only representation/vocabularies.md and quality/clinical.md have body
// text, so they are taken from there (clinical.md's stub banner is stripped). Every other chapter, including the cohort chapter with the
// exit and persistence-window text, comes from the 1st edition
// (OHDSI/TheBookOfOhdsi, branch master). Stub pages are dropped.
import { readFileSync, readdirSync, writeFileSync } from "node:fs";
import { join, dirname } from "node:path";
import { fileURLToPath } from "node:url";

const [second, first] = process.argv.slice(2);
if (!second || !first) {
  console.error("usage: gen-book-passages.mjs <2nd-edition clone> <1st-edition clone>");
  process.exit(1);
}

const out = join(dirname(fileURLToPath(import.meta.url)), "../resources/book-of-ohdsi/passages.edn");
const MAX_WORDS = 450;
const SECOND_FILES = ["representation/vocabularies.md", "quality/clinical.md"];
const FIRST_SKIP = new Set(["index.Rmd", "References.Rmd", "ProtocolTemplate.Rmd", "SuggestedAnswers.Rmd", "StandardizedVocabularies.Rmd", "ClinicalValidity.Rmd"]);

const slug = (s) => s.toLowerCase().replace(/[^a-z0-9 -]/g, "").trim().replace(/\s+/g, "-");

function clean(raw) {
  return raw
    .replace(/\r/g, "")
    .replace(/^---\n[\s\S]*?\n---\n/, "")
    .replace(/```\{r[\s\S]*?```/g, "")
    .replace(/```\{block2[^}]*\}\n?/g, "")
    .replace(/<!--[\s\S]*?-->/g, "")
    .replace(/\\index\{[^}]*\}/g, "")
    .replace(/:::\s*\{\.callout-note\}[^]*?currently a stub[^]*?\n:::/g, "")
    .replace(/^:::.*$/gm, "")
    .replace(/\n{3,}/g, "\n\n");
}

function headingParts(line) {
  const m = line.match(/^(#{1,6})\s+(.*?)(?:\s+\{#([^}\s]+)[^}]*\})?\s*$/);
  return m ? { level: m[1].length, text: m[2].replace(/\{[^}]*\}/g, "").trim(), id: m[3] } : null;
}

const words = (s) => s.split(/\s+/).filter(Boolean);
const flat = (s) => s.replace(/\s+/g, " ").trim();

function chunk(text, fallbackTitle) {
  const lines = text.split("\n");
  let title = null;
  const sections = [];
  let cur = { section: "Introduction", anchor: "introduction", lines: [] };
  let inFence = false;
  for (const line of lines) {
    if (/^```/.test(line)) inFence = !inFence;
    const h = inFence ? null : headingParts(line);
    if (h && h.level === 1 && /^\((PART|APPENDIX)\)/.test(h.text)) continue;
    if (h && h.level === 1 && title === null) {
      title = h.text;
      continue;
    }
    if (h && h.level === 2) {
      sections.push(cur);
      cur = { section: h.text, anchor: h.id || slug(h.text), lines: [] };
      continue;
    }
    cur.lines.push(line);
  }
  sections.push(cur);
  return { title: title || fallbackTitle, sections };
}

function splitLong(lines) {
  const paras = lines.join("\n").split(/\n\s*\n/).map((p) => p.trim()).filter(Boolean);
  const parts = [];
  let buf = [];
  let n = 0;
  for (const p of paras) {
    const w = words(p).length;
    if (n > 0 && n + w > MAX_WORDS) {
      parts.push(buf.join("\n\n"));
      buf = [];
      n = 0;
    }
    buf.push(p);
    n += w;
  }
  if (buf.length) parts.push(buf.join("\n\n"));
  return parts;
}

const passages = [];

function addFile({ raw, file, chapter, urlBase, fallbackTitle }) {
  const text = clean(raw);
  const { title, sections } = chunk(text, fallbackTitle);
  let ord = 0;
  for (const s of sections) {
    for (const part of splitLong(s.lines)) {
      const body = flat(part);
      const n = words(body).length;
      if (n < 25) continue;
      passages.push({ ord: ord++, section: s.section, file, title, chapter, url: `${urlBase}${file}`, tokens: n, anchor: s.anchor, text: body });
    }
  }
}

for (const rel of SECOND_FILES) {
  addFile({
    raw: readFileSync(join(second, rel), "utf8"),
    file: rel,
    chapter: rel.split("/")[0],
    urlBase: "https://github.com/OHDSI/BookOfOHDSI-2ndEdition/blob/main/",
    fallbackTitle: rel,
  });
}

for (const f of readdirSync(first).filter((x) => x.endsWith(".Rmd") && !FIRST_SKIP.has(x)).sort()) {
  addFile({
    raw: readFileSync(join(first, f), "utf8"),
    file: f,
    chapter: slug(f.replace(/\.Rmd$/, "")),
    urlBase: "https://github.com/OHDSI/TheBookOfOhdsi/blob/master/",
    fallbackTitle: f,
  });
}

const edn = (v) => (typeof v === "string" ? JSON.stringify(v) : String(v));
const rec = (p) => "{" + Object.entries(p).map(([k, v]) => `:${k} ${edn(v)}`).join(", ") + "}";
writeFileSync(out, "[\n" + passages.map(rec).join("\n") + "\n]\n");
console.error(`wrote ${passages.length} passages`);
