#!/usr/bin/env node
/**
 * MiMo 面板 · 桌面版
 * 本地 Node 服务：静态页面 + 同源转发（解决浏览器跨域，不依赖任何第三方代理）
 * 仅监听 127.0.0.1，Key 只在本进程内存里转发，不落盘、不外发。
 */
const http = require("http");
const https = require("https");
const fs = require("fs");
const path = require("path");
const url = require("url");

const PORT = Number(process.env.PORT || 8787);
const HOST = "127.0.0.1";
const ROOT = path.join(__dirname, "public");

const MIME = {
  ".html": "text/html; charset=utf-8",
  ".js": "application/javascript; charset=utf-8",
  ".css": "text/css; charset=utf-8",
  ".png": "image/png",
  ".svg": "image/svg+xml",
  ".ico": "image/x-icon"
};

/* ---------- 演示数据（?mock=1，用于无 Key 预览）---------- */
function mockBody(u) {
  if (u.hostname === "api.xiaomimimo.com" && u.pathname.includes("/balance")) {
    return { is_available: true, balance_infos: [{ currency: "CNY", total_available: "298.42", cash_balance: "286.42", gift_balance: "12.00", frozen_balance: "0.00", credit_available: "0.00" }] };
  }
  if (u.hostname === "api.xiaomimimo.com") {
    const days = [];
    const start = u.searchParams.get("start_date");
    const end = u.searchParams.get("end_date");
    const d0 = new Date(start + "T00:00:00Z");
    const d1 = new Date(end + "T00:00:00Z");
    for (let t = d0.getTime(); t <= d1.getTime(); t += 86400000) {
      const ds = new Date(t).toISOString().slice(0, 10);
      const rows = ["mimo-v2.6-pro", "mimo-v2.6-flash", "mimo-v2.6-pro-ultra"].map((n, i) => ({
        date: ds, resource_type: "model", name: n, masked_api_key: "sk-****3f2a",
        consumed_amount: (3 + Math.random() * 5 * (i + 1)).toFixed(6),
        input_cache_hit_amount: (1 + Math.random()).toFixed(6),
        input_cache_miss_amount: (1 + Math.random() * 2).toFixed(6),
        output_amount: (0.6 + Math.random()).toFixed(6),
        total_tokens: 1200000 + Math.floor(Math.random() * 2000000),
        input_cache_hit_tokens: 800000, input_cache_miss_tokens: 200000, output_tokens: 200000,
        request_count: 60 + Math.floor(Math.random() * 120), inference_type: i === 2 ? "batch" : "online"
      }));
      if (u.pathname.includes("/billing")) {
        days.push({ date: ds, original_amount: "5.20", discount_amount: "0.40", gift_amount: "0.60", final_paid_amount: "4.60" });
      } else {
        days.push(...rows);
      }
    }
    return { data: days };
  }
  if (u.hostname === "api.deepseek.com") {
    return { is_available: true, balance_infos: [{ currency: "CNY", total_balance: "86.40", granted_balance: "6.40", topped_up_balance: "80.00" }] };
  }
  if (u.hostname === "api.moonshot.cn") {
    return { code: 0, data: { available_balance: 42.5, voucher_balance: 5, cash_balance: 37.5 } };
  }
  if (u.hostname === "api.siliconflow.cn") {
    return { code: 20000, data: { totalBalance: "58.10", chargeBalance: "50.00", balance: "58.10" } };
  }
  if (u.hostname === "openrouter.ai") {
    return { data: { total_credits: 25, total_usage: 7.35 } };
  }
  return { data: { balance: 12.34 } };
}

/* ---------- 转发 ---------- */
function proxy(target, key, res) {
  const lib = target.protocol === "https:" ? https : http;
  const req = lib.request({
    protocol: target.protocol,
    hostname: target.hostname,
    port: target.port || (target.protocol === "https:" ? 443 : 80),
    path: target.pathname + target.search,
    method: "GET",
    timeout: 20000,
    headers: {
      "Authorization": "Bearer " + key,
      "api-key": key,
      "Accept": "application/json",
      "User-Agent": "mimo-panel-desktop/3.0"
    }
  }, (up) => {
    let body = "";
    up.setEncoding("utf8");
    up.on("data", (c) => { body += c; });
    up.on("end", () => {
      res.writeHead(up.statusCode || 502, { "Content-Type": "application/json; charset=utf-8", "Cache-Control": "no-store" });
      res.end(body);
    });
  });
  req.on("timeout", () => req.destroy(new Error("请求超时")));
  req.on("error", (e) => {
    res.writeHead(502, { "Content-Type": "application/json; charset=utf-8" });
    res.end(JSON.stringify({ error: { message: e.message || "网络请求失败" } }));
  });
  req.end();
}

/* ---------- HTTP 服务 ---------- */
const server = http.createServer((req, res) => {
  const u = new url.URL(req.url, "http://" + HOST + ":" + PORT);

  if (u.pathname === "/api/fetch") {
    let t;
    try { t = new url.URL(u.searchParams.get("url") || ""); }
    catch (e) {
      res.writeHead(400, { "Content-Type": "application/json" });
      return res.end(JSON.stringify({ error: { message: "接口地址不合法" } }));
    }
    if (!/^https?:$/.test(t.protocol)) {
      res.writeHead(400, { "Content-Type": "application/json" });
      return res.end(JSON.stringify({ error: { message: "只支持 http/https" } }));
    }
    if (u.searchParams.get("mock") === "1") {
      res.writeHead(200, { "Content-Type": "application/json; charset=utf-8", "Cache-Control": "no-store" });
      return res.end(JSON.stringify(mockBody(t)));
    }
    const key = u.searchParams.get("key") || "";
    if (!key) {
      res.writeHead(400, { "Content-Type": "application/json" });
      return res.end(JSON.stringify({ error: { message: "缺少 API Key" } }));
    }
    return proxy(t, key, res);
  }

  if (u.pathname === "/api/meta") {
    res.writeHead(200, { "Content-Type": "application/json; charset=utf-8" });
    return res.end(JSON.stringify({ app: "mimo-panel-desktop", version: "3.0", host: HOST, port: PORT }));
  }

  // 静态文件
  let rel = decodeURIComponent(u.pathname);
  if (rel === "/") rel = "/index.html";
  const file = path.join(ROOT, rel);
  if (!file.startsWith(ROOT)) {
    res.writeHead(403); return res.end("forbidden");
  }
  fs.readFile(file, (err, buf) => {
    if (err) { res.writeHead(404, { "Content-Type": "text/plain; charset=utf-8" }); return res.end("404"); }
    res.writeHead(200, { "Content-Type": MIME[path.extname(file)] || "application/octet-stream", "Cache-Control": "no-cache" });
    res.end(buf);
  });
});

server.listen(PORT, HOST, () => {
  console.log("MiMo 面板桌面版已启动： http://" + HOST + ":" + PORT);
  console.log("按 Ctrl+C 停止");
});
