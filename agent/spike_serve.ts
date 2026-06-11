// Driver: import the shadow-cljs-compiled ESM handler, build a Request,
// invoke it, and stream the Response body to stdout line by line.
import { handler } from "./out/handler.js";

const body = {
  messages: [
    {
      id: "1",
      role: "user",
      parts: [{ type: "text", text: "Say the word pong, then call get_time." }],
    },
  ],
};

const req = new Request("http://localhost/agent", {
  method: "POST",
  headers: { "content-type": "application/json" },
  body: JSON.stringify(body),
});

const res: Response = await handler(req);
console.error("STATUS", res.status);
console.error("CONTENT-TYPE", res.headers.get("content-type"));

if (!res.body) {
  console.error("NO BODY");
  Deno.exit(1);
}

const reader = res.body.getReader();
const dec = new TextDecoder();
let buf = "";
while (true) {
  const { done, value } = await reader.read();
  if (done) break;
  buf += dec.decode(value, { stream: true });
  let idx;
  while ((idx = buf.indexOf("\n")) >= 0) {
    const line = buf.slice(0, idx);
    buf += "";
    buf = buf.slice(idx + 1);
    if (line.length) console.log(line);
  }
}
if (buf.length) console.log(buf);
console.error("STREAM DONE");
