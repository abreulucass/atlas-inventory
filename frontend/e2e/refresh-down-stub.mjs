// Backend stand-in for e2e: /api/auth/refresh answers 503, everything else goes to the real backend.
import http from "node:http";

const PORT = Number(process.env.STUB_PORT ?? 8081);
const TARGET = process.env.STUB_TARGET ?? "http://localhost:8080";

http
  .createServer(async (req, res) => {
    if (req.url === "/health") return res.end("ok");
    if (req.url === "/api/auth/refresh") {
      res.writeHead(503, { "content-type": "application/problem+json" });
      return res.end('{"status":503}');
    }
    const chunks = [];
    for await (const c of req) chunks.push(c);
    const headers = { ...req.headers };
    delete headers.host;
    delete headers["content-length"];
    try {
      const up = await fetch(TARGET + req.url, {
        method: req.method,
        headers,
        body: chunks.length ? Buffer.concat(chunks) : undefined,
      });
      const out = {};
      up.headers.forEach((v, k) => {
        if (k !== "content-encoding" && k !== "content-length" && k !== "transfer-encoding") out[k] = v;
      });
      res.writeHead(up.status, out);
      res.end(Buffer.from(await up.arrayBuffer()));
    } catch {
      res.writeHead(502);
      res.end();
    }
  })
  .listen(PORT);
