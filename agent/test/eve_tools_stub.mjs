// Minimal mirror of trex's eve/tools shim for tests (brand + validation only).
export function defineTool(def) {
  if (!def.description) throw new Error("defineTool: description is required");
  if (!def.inputSchema) throw new Error("defineTool: inputSchema is required");
  if (!def.execute && !def.clientOnly) throw new Error("defineTool: execute is required unless clientOnly");
  return Object.assign({}, def, { __trexTool: true });
}
