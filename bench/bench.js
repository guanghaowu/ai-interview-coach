#!/usr/bin/env node
'use strict';

/**
 * 零依赖 HTTP 压测器（Node 内置 http 模块，不需要 wrk / JMeter / autocannon）
 *
 * 为什么自己写：
 *   1. 本机没有任何压测工具，装 JMeter 太重，npx 拉包又受沙箱限制
 *   2. 本项目的业务错误是「HTTP 200 + body.code != 0」，通用工具只看 HTTP 状态码会误判成功
 *      —— 所以必须解析 body.code，这是自己写的主要理由
 *
 * 用法：
 *   node bench.js --url http://localhost:8080 --path "/api/health" \
 *     --concurrency 50 --requests 3000
 *
 *   node bench.js --url http://localhost:8080 --path /api/interview/sessions?page=1\&size=10 \
 *     --token "$TOKEN" --concurrency 50 --requests 2000 --label "会话列表"
 *
 *   node bench.js --url http://localhost:8080 --path /api/interview/create \
 *     --method POST --token "$TOKEN" --body '{"jdContent":"..."}' \
 *     --concurrency 20 --requests 100 --label "出题提交"
 *
 * 参数：
 *   --url          目标地址（默认 http://localhost:8080）
 *   --path         请求路径（含 query）
 *   --method       GET / POST / PUT ...（默认 GET）
 *   --token        JWT，自动加 Authorization: Bearer
 *   --body         JSON 请求体（自动带 Content-Type）
 *   --concurrency  并发数（默认 20）
 *   --requests     总请求数（默认 200）
 *   --warmup       预热请求数，不计入统计（默认 0，建议设为 concurrency*2）
 *   --label        输出标题
 *   --json         输出机器可读 JSON
 */

const http = require('http');
const https = require('https');

function parseArgs(argv) {
    const args = {};
    for (let i = 2; i < argv.length; i++) {
        const a = argv[i];
        if (!a.startsWith('--')) continue;
        const key = a.slice(2);
        const next = argv[i + 1];
        if (next !== undefined && !next.startsWith('--')) {
            args[key] = next;
            i++;
        } else {
            args[key] = 'true';
        }
    }
    return args;
}

const args = parseArgs(process.argv);
const target = new URL(args.url || 'http://localhost:8080');
const reqPath = args.path || '/api/health';
const method = (args.method || 'GET').toUpperCase();
const concurrency = Math.max(1, parseInt(args.concurrency || '20', 10));
const total = Math.max(1, parseInt(args.requests || '200', 10));
const warmup = Math.max(0, parseInt(args.warmup || '0', 10));
const token = args.token || '';
const body = args.body || null;
const label = args.label || `${method} ${reqPath}`;
const asJson = args.json === 'true';

// 防御：Git Bash(MSYS) 会把以 / 开头的参数改写成 Windows 路径，
// 表现是「所有请求都返回 400 Bad Request」，很难联想到是 shell 的锅。
// 与其让人对着 400 猜半天，不如在这里直接报错。
if (!reqPath.startsWith('/')) {
    console.error(`✗ 请求路径必须以 / 开头，实际收到: ${reqPath}`);
    console.error('  Git Bash 会改写以 / 开头的命令行参数，请先执行: export MSYS_NO_PATHCONV=1');
    process.exit(1);
}

const transport = target.protocol === 'https:' ? https : http;
const agent = new transport.Agent({ keepAlive: true, maxSockets: concurrency });

let firstBad = null;

const headers = {};
if (token) headers['Authorization'] = 'Bearer ' + token;
if (body) {
    headers['Content-Type'] = 'application/json';
    headers['Content-Length'] = Buffer.byteLength(body);
}

function once() {
    return new Promise((resolve) => {
        const start = process.hrtime.bigint();
        const req = transport.request({
            hostname: target.hostname,
            port: target.port || (target.protocol === 'https:' ? 443 : 80),
            path: reqPath,
            method,
            agent,
            headers,
        }, (res) => {
            let data = '';
            res.setEncoding('utf8');
            res.on('data', (c) => { data += c; });
            res.on('end', () => {
                const ms = Number(process.hrtime.bigint() - start) / 1e6;
                let bizCode = null;
                try {
                    // 只有 body 里真的有数字型 code 才算业务码。
                    // /api/health 之类返回裸 JSON（无 code 字段），不能当成「异常」。
                    const parsed = JSON.parse(data);
                    if (parsed && typeof parsed.code === 'number') bizCode = parsed.code;
                } catch (e) { /* 非 JSON 响应 */ }
                // 记下首个异常响应，便于定位「为什么全是 4xx」
                if (!firstBad && (res.statusCode >= 400 || (bizCode !== null && bizCode !== 0))) {
                    firstBad = { status: res.statusCode, body: data.slice(0, 300) };
                }
                resolve({ status: res.statusCode, bizCode, ms });
            });
        });
        req.on('error', (e) => {
            const ms = Number(process.hrtime.bigint() - start) / 1e6;
            resolve({ status: 0, bizCode: null, ms, err: e.code || e.message });
        });
        if (body) req.write(body);
        req.end();
    });
}

async function phase(count) {
    let issued = 0;
    const results = [];
    const worker = async () => {
        for (;;) {
            const idx = issued++;
            if (idx >= count) return;
            results.push(await once());
        }
    };
    const t0 = process.hrtime.bigint();
    await Promise.all(Array.from({ length: Math.min(concurrency, count) }, worker));
    const elapsedMs = Number(process.hrtime.bigint() - t0) / 1e6;
    return { results, elapsedMs };
}

function percentile(sorted, p) {
    if (sorted.length === 0) return 0;
    const idx = Math.ceil((p / 100) * sorted.length) - 1;
    return sorted[Math.min(sorted.length - 1, Math.max(0, idx))];
}

function summarize(results, elapsedMs) {
    const lat = results.map((r) => r.ms).sort((a, b) => a - b);
    const sum = lat.reduce((a, b) => a + b, 0);

    const statusDist = {};
    const bizDist = {};
    let errors = 0;
    for (const r of results) {
        const s = r.status === 0 ? `ERR(${r.err})` : String(r.status);
        statusDist[s] = (statusDist[s] || 0) + 1;
        if (r.bizCode !== null) {
            bizDist[r.bizCode] = (bizDist[r.bizCode] || 0) + 1;
        }
        if (r.status === 0) errors++;
    }

    return {
        label,
        concurrency,
        requests: results.length,
        elapsedMs: Math.round(elapsedMs),
        qps: +(results.length / (elapsedMs / 1000)).toFixed(1),
        latency: {
            min: +lat[0].toFixed(1),
            mean: +(sum / lat.length).toFixed(1),
            p50: +percentile(lat, 50).toFixed(1),
            p90: +percentile(lat, 90).toFixed(1),
            p95: +percentile(lat, 95).toFixed(1),
            p99: +percentile(lat, 99).toFixed(1),
            max: +lat[lat.length - 1].toFixed(1),
        },
        statusDist,
        bizDist,
        errors,
    };
}

function print(s) {
    console.log('');
    console.log(`━━━ ${s.label} ━━━`);
    console.log(`  并发 ${s.concurrency} ｜ 请求 ${s.requests} ｜ 耗时 ${s.elapsedMs}ms ｜ QPS ${s.qps}`);
    console.log(`  延迟(ms)  min ${s.latency.min} ｜ mean ${s.latency.mean} ｜ p50 ${s.latency.p50}`
        + ` ｜ p90 ${s.latency.p90} ｜ p95 ${s.latency.p95} ｜ p99 ${s.latency.p99} ｜ max ${s.latency.max}`);
    console.log(`  HTTP      ${JSON.stringify(s.statusDist)}`);
    console.log(`  业务码    ${JSON.stringify(s.bizDist)}`);
    if (s.errors) console.log(`  ⚠ 连接错误 ${s.errors}`);
    if (firstBad) {
        console.log(`  首个异常响应  HTTP ${firstBad.status}  body=${firstBad.body}`);
    }
}

(async () => {
    if (warmup > 0) {
        if (!asJson) console.log(`预热 ${warmup} 次（不计入统计）…`);
        await phase(warmup);
    }
    const { results, elapsedMs } = await phase(total);
    const summary = summarize(results, elapsedMs);
    if (asJson) {
        console.log(JSON.stringify(summary, null, 2));
    } else {
        print(summary);
    }
    agent.destroy();
})().catch((e) => {
    console.error('压测失败:', e);
    process.exit(1);
});
