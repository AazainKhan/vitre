#!/usr/bin/env node
/*
 * Bakes the pieces of github.com/kailashsr38/ascii-magic-art (MIT) into frame packs Glass can play without a JS engine (P327).
 *
 *   node scripts/ascii-bake.mjs <checkout> <out dir> [--shard i/n] [--only slug,slug]
 *
 * Every piece is a pure function of t, so it is run once here. A piece that repeats exactly (a loop, or a still) is baked as one
 * period; any other is baked for CLIP_S seconds with its last DISSOLVE_S seconds dissolving cell by cell into its first, so the
 * loop point has no jump. Output per piece: <slug>.asc, a header (JSON) and the frames (cell planes, each frame XOR the one before,
 * deflated as one raw stream so a device can inflate it a frame at a time; v2 adds the closing delta back to frame 0). Then: one <category>.zip per category, index.json,
 * categories.json, and the licence files. Nothing is fetched; the checkout is read from disk.
 *
 *   node scripts/ascii-bake.mjs <checkout> <out dir> --previews   after baking: one still frame per piece (a one-frame pack, taken a third
 *                                                                 of the way through its loop), zipped per category for the menu's thumbnails
 */
import fs from "fs";
import path from "path";
import zlib from "zlib";
import crypto from "crypto";
import { execFileSync } from "child_process";
import { pathToFileURL } from "url";

const MAX_PERIOD_S = 45;
const CLIP_S = 12;
const DISSOLVE_S = 1;
const KEYS = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ";

const args = process.argv.slice(2);
const flag = (name) => { const i = args.indexOf(name); return i < 0 ? null : args.splice(i, 2)[1]; };
const shard = (flag("--shard") ?? "0/1").split("/").map(Number);
const only = flag("--only")?.split(",");
const [src, out] = args;
if (!src || !out) { console.error("usage: ascii-bake.mjs <checkout> <out dir> [--shard i/n] [--only slugs]"); process.exit(2); }
fs.mkdirSync(path.join(out, "asc"), { recursive: true });

/** One frame as three planes (text low byte, text high byte, colour key index + 1, 0 for none): 3 * cols * rows bytes. */
function planes(frame, cols, rows) {
  const text = typeof frame === "string" ? frame : frame.text;
  const color = typeof frame === "string" ? "" : frame.color ?? "";
  const n = cols * rows, buf = Buffer.alloc(3 * n);
  const tr = text.split("\n"), cr = color ? color.split("\n") : [];
  if (tr.length !== rows) throw new Error(`frame has ${tr.length} rows, want ${rows}`);
  for (let y = 0; y < rows; y++) {
    if (tr[y].length !== cols) throw new Error(`row ${y} has ${tr[y].length} cells, want ${cols}`);
    for (let x = 0; x < cols; x++) {
      const c = tr[y].charCodeAt(x), i = y * cols + x;
      buf[i] = c & 255; buf[n + i] = c >> 8;
      const k = cr[y] ? KEYS.indexOf(cr[y][x]) : -1;
      buf[2 * n + i] = k + 1;
    }
  }
  return buf;
}

/** The smallest P (in frames) after which the piece repeats exactly (checked over two seconds), or 0. P = 1 is a still. */
function period(frames, fps) {
  const hash = frames.map((f) => crypto.createHash("md5").update(f).digest("hex"));
  const check = Math.min(2 * fps, frames.length >> 1);
  for (let p = 1; p <= frames.length - check; p++) {
    if (hash[p] !== hash[0]) continue;
    let ok = true;
    for (let i = 0; i < check && ok; i++) ok = hash[i] === hash[i + p] && Buffer.compare(frames[i], frames[i + p]) === 0;
    if (ok) return p;
  }
  return 0;
}

/** Cell by cell: [a] where a cell's fixed random threshold is at or above w, else [b] (w 0 is all a, 1 is all b). */
function dissolve(a, b, w, n) {
  const o = Buffer.from(a);
  for (let i = 0; i < n; i++) {
    const u = (Math.imul(i + 1, 2654435761) >>> 0) / 4294967296;
    if (u < w) { o[i] = b[i]; o[n + i] = b[n + i]; o[2 * n + i] = b[2 * n + i]; }
  }
  return o;
}

async function bake(file) {
  const mod = await import(pathToFileURL(path.join(src, "pieces", file)).href);
  const m = mod.meta, { cols, rows, fps } = m, n = cols * rows;
  const draw = mod.default();
  const total = Math.round(MAX_PERIOD_S * fps);
  const long = Array.from({ length: total }, (_, i) => planes(draw(i / fps), cols, rows));
  const p = period(long, fps);
  let clip, loop;
  if (p === 1) { clip = [long[0]]; loop = "still"; }
  else if (p > 0) { clip = long.slice(0, p); loop = "period"; }
  else {
    const N = Math.round(CLIP_S * fps), X = Math.round(DISSOLVE_S * fps);
    const at = (i) => (i < total ? long[i] : planes(draw(i / fps), cols, rows));
    // Frames [X, N) are the piece as it is; [0, X) dissolve from the natural continuation of the end into the start, so the
    // step from the last frame to the first is a normal step and the start is reached by the end of the dissolve.
    clip = Array.from({ length: N }, (_, k) => (k >= X ? at(k) : dissolve(at(N + k), at(k), (k + 1) / (X + 1), n)));
    loop = "dissolve";
  }
  // Frame 0 whole, then each frame as a delta from the one before, then one last delta from the final frame back to frame 0: the
  // loop then closes with a step as small as any other, not a repaint of the whole grid.
  const delta = (a, b) => { const d = Buffer.alloc(a.length); for (let i = 0; i < a.length; i++) d[i] = a[i] ^ b[i]; return d; };
  const stream = Buffer.concat([...clip.map((f, k) => (k === 0 ? f : delta(f, clip[k - 1]))), delta(clip[0], clip.at(-1))]);
  const body = zlib.deflateRawSync(stream, { level: 9 });
  const head = {
    v: 2, slug: m.slug, name: m.name, category: m.category, note: m.note, tags: m.tags, cols, rows, fps, n: clip.length, loop,
    cell: m.cell ?? "text", look: m.look ?? null, dots: m.dots ?? null, palette: [...KEYS].map((k) => m.palette?.[k] ?? null),
  };
  const hb = Buffer.from(JSON.stringify(head));
  const lenb = Buffer.alloc(4); lenb.writeUInt32BE(hb.length);
  const asc = Buffer.concat([Buffer.from("ASC1"), lenb, hb, body]);
  fs.writeFileSync(path.join(out, "asc", m.slug + ".asc"), asc);
  return { slug: m.slug, name: m.name, category: m.category, tags: m.tags, cols, rows, fps, cell: head.cell, look: head.look, frames: clip.length, loop, bytes: asc.length, sha256: crypto.createHash("sha256").update(asc).digest("hex") };
}

const files = args.includes("--pack") ? [] : fs.readdirSync(path.join(src, "pieces")).filter((f) => f.endsWith(".js")).sort()
  .filter((f) => !only || only.includes(f.slice(0, -3))).filter((_, i) => i % shard[1] === shard[0]);
const rowsOut = [];
for (const f of files) {
  const t0 = Date.now();
  try { const r = await bake(f); rowsOut.push(r); console.log(`${r.slug}  ${r.cols}x${r.rows}@${r.fps}  ${r.loop} ${r.frames}f  ${(r.bytes / 1024).toFixed(0)} KB  ${Date.now() - t0} ms`); }
  catch (e) { console.error(`FAILED ${f}: ${e.message}`); process.exitCode = 1; }
}
if (files.length) fs.writeFileSync(path.join(out, `index.part-${shard[0]}.json`), JSON.stringify(rowsOut));

// With one shard (or after the last, by hand: scripts/ascii-bake.mjs <checkout> <out> --pack) the parts are joined and zipped.
if (shard[1] === 1 || args.includes("--pack")) pack();
if (args.includes("--previews")) previews();

/** Decodes a baked .asc: its header and its frames' cell planes (each frame whole, rebuilt from the deltas). */
function readAsc(file) {
  const b = fs.readFileSync(file);
  const head = JSON.parse(b.subarray(8, 8 + b.readUInt32BE(4)).toString("utf8"));
  const raw = zlib.inflateRawSync(b.subarray(8 + b.readUInt32BE(4)));
  const size = 3 * head.cols * head.rows;
  const frames = [];
  let cur = Buffer.alloc(size);
  for (let k = 0; k < head.n; k++) {
    const chunk = raw.subarray(k * size, (k + 1) * size);
    cur = Buffer.from(cur.map((v, i) => v ^ chunk[i]));
    frames.push(cur);
  }
  return { head, frames };
}

/** One-frame packs (the piece a third of the way through), zipped per category, and the index told where they are. */
export function previews() {
  const indexFile = path.join(out, "vitre-ascii-index.json");
  const index = JSON.parse(fs.readFileSync(indexFile, "utf8"));
  fs.mkdirSync(path.join(out, "preview"), { recursive: true });
  for (const p of index.pieces) {
    const { head, frames } = readAsc(path.join(out, "asc", p.slug + ".asc"));
    const still = frames[Math.floor(head.n / 3)];
    const stream = Buffer.concat([still, Buffer.alloc(still.length)]);   // the frame, then the (empty) closing delta
    const h = Buffer.from(JSON.stringify({ ...head, n: 1, loop: "still" }));
    const len = Buffer.alloc(4); len.writeUInt32BE(h.length);
    const asc = Buffer.concat([Buffer.from("ASC1"), len, h, zlib.deflateRawSync(stream, { level: 9 })]);
    fs.writeFileSync(path.join(out, "preview", p.slug + ".asc"), asc);
    p.previewBytes = asc.length;
    p.previewSha256 = crypto.createHash("sha256").update(asc).digest("hex");
  }
  for (const c of index.categories) {
    const members = index.pieces.filter((p) => p.category === c.id);
    if (!members.length) continue;
    const name = `vitre-ascii-preview-${c.id}.zip`;
    const zip = path.join(path.resolve(out), name);
    fs.rmSync(zip, { force: true });
    execFileSync("zip", ["-q", "-j", "-X", zip, ...members.map((p) => path.join(out, "preview", p.slug + ".asc"))]);
    c.previewZip = name;
    c.previewZipBytes = fs.statSync(zip).size;
  }
  fs.writeFileSync(indexFile, JSON.stringify(index));
  console.log(`previews for ${index.pieces.length} pieces: ${index.categories.reduce((n, c) => n + (c.previewZipBytes ?? 0), 0)} bytes in zips`);
}
export function pack() {
  // The index is built from the baked files themselves (their headers), so it can be rebuilt without baking again.
  const index = fs.readdirSync(path.join(out, "asc")).filter((f) => f.endsWith(".asc")).map((f) => {
    const b = fs.readFileSync(path.join(out, "asc", f));
    const h = JSON.parse(b.subarray(8, 8 + b.readUInt32BE(4)).toString("utf8"));
    return { slug: h.slug, name: h.name, category: h.category, tags: h.tags, note: h.note ?? "", cols: h.cols, rows: h.rows, fps: h.fps, cell: h.cell, look: h.look, frames: h.n, loop: h.loop, bytes: b.length, sha256: crypto.createHash("sha256").update(b).digest("hex") };
  }).sort((a, b) => a.slug.localeCompare(b.slug));
  const cats = JSON.parse(fs.readFileSync(path.join(src, "categories.json"), "utf8"));
  for (const c of cats) {
    const members = index.filter((p) => p.category === c.id);
    if (!members.length) continue;
    const name = `vitre-ascii-${c.id}.zip`;
    const zip = path.join(path.resolve(out), name);
    fs.rmSync(zip, { force: true });
    // The licence and the notice (two Unsplash-licensed scenes) travel inside every zip, as the MIT licence asks.
    execFileSync("zip", ["-q", "-j", "-X", zip, path.join(src, "LICENSE"), path.join(src, "NOTICE"), ...members.map((p) => path.join(out, "asc", p.slug + ".asc"))]);
    for (const p of members) p.zip = name;
    c.pieces = members.length;
    c.zipBytes = fs.statSync(zip).size;
  }
  fs.writeFileSync(path.join(out, "vitre-ascii-index.json"), JSON.stringify({ v: 1, categories: cats, pieces: index }));
  for (const f of ["LICENSE", "NOTICE"]) fs.copyFileSync(path.join(src, f), path.join(out, `vitre-ascii-${f}.txt`));
  console.log(`packed ${index.length} pieces into ${cats.filter((c) => c.pieces).length} zips`);
}
