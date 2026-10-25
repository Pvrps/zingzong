'use strict';

// ---- the model: components, lanes, rows ----------------------------------------------------------
//
// One continuous diagram per recording. Lanes are the components that took part (one per bean,
// resource, queue, data source), grouped under their application. Time runs down: an entry
// point (a request, or any trace nothing in the recording caused) is a heading; under it every
// call is a row, its return another row, and work that continued on another thread is drawn
// right under the call that caused it. A run of GROUP_MIN or more consecutive sibling calls of
// the same method that called nothing further and did not fail is one row until expanded;
// nothing is dropped, the row says what it stands for.

const KIND_TEXT = { CLIENT: 'caller', REST: 'REST resource', SOAP: 'SOAP endpoint', STATELESS: 'stateless EJB', STATEFUL: 'stateful EJB', SINGLETON: 'singleton EJB', EJB2: 'EJB 2.x', EJB: 'EJB', MDB: 'message-driven bean', QUEUE: 'JMS queue', DATASOURCE: 'data source' };
const CAT = { CLIENT: 'client', REST: 'web', SOAP: 'web', STATELESS: 'ejb', STATEFUL: 'ejb', SINGLETON: 'ejb', EJB2: 'ejb', EJB: 'ejb', MDB: 'mdb', QUEUE: 'queue', DATASOURCE: 'db' };
const KIND_ORDER = ['CLIENT', 'REST', 'SOAP', 'STATELESS', 'STATEFUL', 'SINGLETON', 'EJB', 'EJB2', 'MDB', 'QUEUE', 'DATASOURCE'];
const CALL = 38, RET = 22, HOP = 30, ENTRY = 40, GAP = 14, DUR_COL = 84, GROUP_MIN = 4, PAGE = 200;

function kindOf(s) {
  if (s.kind === 'SQL') return 'DATASOURCE';
  if (s.ejb) {
    if (s.ejb.invocation === 'MESSAGE' || s.ejb.beanType === 'MESSAGE_DRIVEN') return 'MDB';
    if (s.ejb.ejb2x) return 'EJB2';
    return s.ejb.beanType || 'EJB';
  }
  if (s.layer === 'jaxrs') return 'REST';
  if (s.layer === 'jaxws') return 'SOAP';
  return 'CLIENT';
}
function compName(s) {
  if (s.kind === 'SQL') return s.sql.dataSource || '?';
  if (s.ejb) return s.ejb.beanName || simple(s.ejb.beanClass);
  if (s.layer) return simple(s.http.resourceClass);
  return 'client';
}
/** What a row says: the method, or for SQL the statement itself, whitespace folded. */
function methodLabel(s) {
  if (s.kind === 'SQL') return (s.sql.sql || s.sql.operation || '').replace(/\s+/g, ' ').trim() || s.sql.operation;
  if (s.ejb) return s.ejb.method;
  if (s.layer) return s.http.resourceMethod;
  return `${s.http.method || ''} ${s.http.path || ''}`;
}
function spanLabel(s) { return s.comp && s.comp.kind === 'CLIENT' ? methodLabel(s) : `${compName(s)}.${s.kind === 'SQL' ? s.sql.operation : methodLabel(s)}`; }
function simple(name) { if (!name) return '?'; const i = name.lastIndexOf('.'); return i < 0 ? name : name.substring(i + 1); }

function buildModel() {
  const traces = [...state.traces.values()];
  const byId = new Map(traces.map(t => [t.trace.id, t]));
  for (const t of traces) {
    t.byId = new Map(t.spans.map(s => [s.id, s]));
    for (const s of t.spans) if (!ORIGINAL.has(s)) ORIGINAL.set(s, Object.keys(s));
    t.startMs = Date.parse(t.trace.startedAt);
    for (const s of t.spans) {
      s.trace = t; s.parent = s.parentId ? t.byId.get(s.parentId) : null; s.kids = []; s.spawns = []; s.row = null; s.ret = null;
      s.comp = { kind: kindOf(s), name: compName(s), app: s.kind === 'SQL' ? 'data sources' : s.app };
    }
    for (const s of t.spans) if (s.parent) s.parent.kids.push(s);
    for (const s of t.spans) s.kids.sort((a, b) => a.order - b.order);
  }
  // a REST/SOAP resource belongs to the application of what it calls (the agent records app only on
  // EJB spans); decided once per resource across the recording, so a request that called no EJB
  // still lands in the right lane
  const resourceApp = new Map();
  for (const t of traces) for (const s of t.spans) if (s.comp.kind === 'REST' || s.comp.kind === 'SOAP') { const k = s.kids.find(x => x.comp.app && x.comp.app !== 'data sources'); if (k) resourceApp.set(s.comp.name, k.comp.app); }
  for (const t of traces) for (const s of t.spans) if (!s.comp.app && (s.comp.kind === 'REST' || s.comp.kind === 'SOAP')) s.comp.app = resourceApp.get(s.comp.name) || 'web';
  for (const t of traces) {
    t.cause = null;
    if (t.trace.parentTraceId && byId.has(t.trace.parentTraceId)) {
      const p = byId.get(t.trace.parentTraceId);
      t.cause = (t.trace.parentSpanId && p.byId.get(t.trace.parentSpanId)) || p.spans.find(s => s.parentId == null) || p.spans[0];
      if (t.cause) t.cause.spawns.push(t);
    }
  }
  for (const t of traces) for (const s of t.spans) s.spawns.sort((a, b) => a.startMs - b.startMs);
  const entries = traces.filter(t => !t.cause).sort((a, b) => a.startMs - b.startMs || a.trace.id.localeCompare(b.trace.id));
  entries.forEach((t, i) => t.seq = i + 1);

  // lanes
  const lanes = [], laneOf = new Map();
  const lane = (kind, name, app) => { const k = `${kind}:${name}:${app}`; if (!laneOf.has(k)) { const l = { key: k, kind, name, app: app || '?', i: lanes.length }; lanes.push(l); laneOf.set(k, l); } return laneOf.get(k); };
  for (const t of entries) visitInOrder(t, s => {
    if (s.comp.kind === 'MDB' && s.ejb.destination) s.queue = lane('QUEUE', s.ejb.destination, 'JMS');
    s.lane = lane(s.comp.kind, s.comp.name, s.comp.kind === 'CLIENT' ? 'client' : s.comp.app);
  });
  const appOrder = []; for (const l of lanes) if (!appOrder.includes(l.app)) appOrder.push(l.app);
  if (appOrder.includes('JMS')) { appOrder.splice(appOrder.indexOf('JMS'), 1); const mdb = lanes.find(l => l.kind === 'MDB'); appOrder.splice(mdb ? appOrder.indexOf(mdb.app) : appOrder.length, 0, 'JMS'); }
  if (appOrder.includes('data sources')) { appOrder.splice(appOrder.indexOf('data sources'), 1); appOrder.push('data sources'); }
  lanes.sort((a, b) => appOrder.indexOf(a.app) - appOrder.indexOf(b.app) || KIND_ORDER.indexOf(a.kind) - KIND_ORDER.indexOf(b.kind) || a.i - b.i);
  lanes.forEach((l, i) => l.i = i);

  // rows
  const rows = []; let yy = GAP;
  const emit = (s, from, hop) => {
    const t = s.trace, root = t.spans.find(x => x.parentId == null) || t.spans[0];
    const resource = s === root && s.comp.kind === 'CLIENT' ? s.kids.find(x => x.comp.kind === 'REST' || x.comp.kind === 'SOAP') : null;
    const to = resource ? resource.lane : s.lane;
    const row = { kind: 'call', span: s, y: yy, h: CALL, from, to, hop, resource }; s.row = row; rows.push(row); yy += CALL;
    const kidSpans = (resource ? [...s.kids.filter(x => x !== resource), ...resource.kids] : s.kids).sort((a, b) => a.startNs - b.startNs);
    const kids = kidSpans.map(x => ({ at: x.startNs / 1e6, span: x }));
    for (const c of s.spawns) kids.push({ at: (c.startMs - t.startMs) + (s.startNs / 1e6) * 0, spawn: c, after: s });
    kids.sort((a, b) => a.at - b.at);
    for (let i = 0; i < kids.length;) {
      const k = kids[i];
      if (k.spawn) {
        const c = k.spawn, croot = c.spans.find(x => x.parentId == null) || c.spans[0], delay = c.startMs - t.startMs;
        if (croot.queue) { rows.push({ kind: 'hop', span: s, child: c, y: yy, h: HOP, from: to, to: croot.queue, delay }); yy += HOP; emit(croot, croot.queue, { kind: 'JMS', delay }); }
        else emit(croot, to, { kind: '@Asynchronous', delay });
        i++; continue;
      }
      // a run of identical leaf calls
      const label = methodLabel(k.span); let j = i;
      while (j < kids.length && kids[j].span && !kids[j].span.kids.length && !kids[j].span.spawns.length && !kids[j].span.exception && kids[j].span.lane === k.span.lane && methodLabel(kids[j].span) === label) j++;
      if (j - i >= GROUP_MIN) {
        const run = kids.slice(i, j).map(x => x.span), gkey = `${t.trace.id}/${run[0].id}+${run.length}`, shown = state.expanded.get(gkey) || 0;
        if (shown === 0) {
          const g = { kind: 'call', span: run[0], group: run, gkey, y: yy, h: CALL, from: to, to: run[0].lane }; rows.push(g); yy += CALL;
          const gr = { kind: 'ret', span: run[0], group: run, y: yy, h: RET, from: run[0].lane, to }; rows.push(gr); yy += RET;
          for (const x of run) { x.row = g; x.ret = gr; }
        } else {
          run.slice(0, shown).forEach(x => emit(x, to, null));
          if (shown < run.length) { rows.push({ kind: 'more', gkey, shown, total: run.length, y: yy, h: HOP, from: to, to: run[0].lane }); yy += HOP; }
          else { rows.push({ kind: 'more', gkey, shown: run.length, total: run.length, y: yy, h: HOP, from: to, to: run[0].lane }); yy += HOP; }
        }
        i = j; continue;
      }
      emit(k.span, to, null); i++;
    }
    const ret = { kind: 'ret', span: s, y: yy, h: RET, from: to, to: from, resource }; s.ret = ret; rows.push(ret); yy += RET;
  };
  for (const t of entries) { t.y = yy; rows.push({ kind: 'entry', trace: t, y: yy, h: ENTRY }); yy += ENTRY + 6; emit(t.spans.find(x => x.parentId == null) || t.spans[0], null, null); t.yEnd = yy; yy += GAP; }
  // nesting: how many enclosing calls are already active in the same lane when a call arrives (a → b → c → b)
  for (const t of traces) for (const s of t.spans) { let n = 0; for (let p = up(s); p; p = up(p)) if (p.row && p.row.to === (s.row ? s.row.to : s.lane) && p.row.span === p) n++; s.nest = n; }
  return { traces, entries, lanes, appOrder, rows, H: yy };
}
/** A span's keys as the agent wrote them, before the page added its own bookkeeping. */
const ORIGINAL = new WeakMap();
function up(s) { return s.parent || (s.trace.cause && !s.parent ? s.trace.cause : null); }
function visitInOrder(t, fn) { for (const s of t.spans) fn(s); for (const s of t.spans) for (const c of s.spawns) visitInOrder(c, fn); }
function rootOf(traceId) { const t = state.traces.get(traceId); if (!t) return null; const r = t.spans.find(s => s.parentId == null) || t.spans[0]; return r && r.id; }

// ---- render: lanes and rows ---------------------------------------------------------------------

const svgNS = 'http://www.w3.org/2000/svg';
const mk = (tag, attrs, parent) => { const e = document.createElementNS(svgNS, tag); for (const k in attrs) e.setAttribute(k, attrs[k]); if (parent) parent.appendChild(e); return e; };
const measure = (() => { const c = document.createElement('canvas').getContext('2d'); return (text, font) => { c.font = font; return c.measureText(text).width; }; })();
const segments = name => String(name).replace(/([a-z0-9])([A-Z])/g, '$1\u200b$2').replace(/([\/_.])/g, '$1\u200b').split('\u200b');
let LANES_W = 0;
const laneX = l => l.x;

function fitLanes() {
  const m = state.model, avail = $('seq').clientWidth - 16 - DUR_COL;
  for (const l of m.lanes) l.need = Math.ceil(Math.max(...segments(l.name).map(s => measure(s, '600 12px -apple-system, system-ui, sans-serif')), measure(KIND_TEXT[l.kind] || '', '10.5px -apple-system, system-ui, sans-serif'), 64)) + 18;
  const natural = m.lanes.reduce((n, l) => n + l.need, 0), extra = Math.max(0, (avail - natural) / m.lanes.length);
  let x = 0; for (const l of m.lanes) { l.w = l.need + extra; l.x = x + l.w / 2; x += l.w; } LANES_W = x;
  state.scale = Math.min(1, (avail + 16 + DUR_COL) / (LANES_W + DUR_COL));
  $('zoomwrap').style.zoom = state.scale;
}

function renderLanes() {
  const m = state.model, apps = $('apps'), heads = $('heads'); apps.innerHTML = ''; heads.innerHTML = '';
  for (const app of m.appOrder) { const a = el('div', 'app' + (app === 'client' || app === 'JMS' ? ' plain' : ''), app === 'client' ? '' : app); a.style.width = m.lanes.filter(l => l.app === app).reduce((n, l) => n + l.w, 0) + 'px'; a.title = app; apps.appendChild(a); }
  for (const l of m.lanes) {
    const h = el('div', 'lane-h ' + CAT[l.kind]); h.style.width = l.w + 'px';
    const kindText = l.kind === 'DATASOURCE' ? dataSourceText(l) : KIND_TEXT[l.kind];
    h.title = `${l.name} · ${kindText}`;
    h.innerHTML = `<div class="name">${segments(l.name).map(esc).join('\u200b')}</div><div class="kind">${esc(kindText)}</div>`;
    heads.appendChild(h);
  }
  $('lanes').style.width = LANES_W + DUR_COL + 'px';
}
function dataSourceText(l) { for (const t of state.model.traces) for (const s of t.spans) if (s.kind === 'SQL' && s.sql.dataSource === l.name) return 'data source' + (s.sql.database ? ' · ' + s.sql.database.split(' ')[0] : ''); return 'data source'; }

function renderBody() {
  const m = state.model, W = LANES_W + DUR_COL, body = $('body');
  body.className = ''; body.innerHTML = ''; body.style.width = W + 'px'; body.style.height = m.H + 'px';
  if (!m.entries.length) { body.className = 'empty'; body.textContent = 'No traces yet.'; return; }
  const svg = mk('svg', { width: W, height: m.H }, body), defs = mk('defs', {}, svg);
  for (const [id, c] of Object.entries({ call: '#475569', fail: '#dc2626' })) { const mk1 = mk('marker', { id: 'arr-' + id, viewBox: '0 0 8 8', refX: 7.5, refY: 4, markerWidth: 7, markerHeight: 7, orient: 'auto' }, defs); mk('path', { d: 'M0,1 L7,4 L0,7 z', fill: c }, mk1); }
  for (const [id, c] of Object.entries({ ret: '#94a3b8', retfail: '#dc2626' })) { const mk1 = mk('marker', { id: 'arr-' + id, viewBox: '0 0 8 8', refX: 7.5, refY: 4, markerWidth: 8, markerHeight: 8, orient: 'auto' }, defs); mk('path', { d: 'M0.5,1 L7,4 L0.5,7 z', fill: '#fff', stroke: c }, mk1); }
  for (const t of m.entries) for (const l of m.lanes) mk('line', { class: 'lifeline', x1: laneX(l), y1: t.y + ENTRY, x2: laneX(l), y2: t.yEnd }, svg);
  const drawn = new Set();
  for (const t of m.traces) for (const s of t.spans) {
    if (!s.row || !s.ret || drawn.has(s.row)) continue; drawn.add(s.row);
    const l = s.row.to, off = s.nest * 6;
    mk('rect', { class: 'act ' + CAT[l.kind], x: laneX(l) - 4 + off, y: s.row.y + 24, width: 8, height: s.ret.y + 12 - (s.row.y + 24), rx: 2 }, svg);
  }
  for (const r of m.rows) {
    if (r.kind === 'entry') {
      const t = r.trace, root = t.spans.find(x => x.parentId == null) || t.spans[0], h = el('div', 'entry'); h.style.top = r.y + 'px'; h.style.width = W + 'px';
      const who = root.http && root.http.remoteAddr ? ` · from ${root.http.remoteAddr}` : '';
      h.innerHTML = `<span class="n">#${t.seq}</span><span class="t">${esc(spanLabel(root))}</span><span class="m">${esc(fmtTime(t.trace.startedAt))} · ${fmtDuration(t.trace.durationMs)}${root.http && root.http.status != null ? ` · HTTP ${root.http.status}` : ''}${t.trace.principal ? ' · ' + esc(t.trace.principal) : ''}${who}${root.exception || (root.http && root.http.status >= 400) ? ' · <span class="chip err">failed</span>' : ''}${(n => n ? ` · <span class="chip" style="color:var(--err);border-color:#fca5a5">${n} failed call${n === 1 ? '' : 's'} inside</span>` : '')(t.spans.filter(s => s.exception && s !== root).length)}</span>`;
      h.title = t.trace.id;
      h.addEventListener('click', () => selectSpan(t.trace.id, root.id));
      body.appendChild(h); continue;
    }
    const sel = r.group ? state.selectedGroup === r.gkey : (state.selectedSpan && r.span && r.span.id === state.selectedSpan && r.span.trace.trace.id === state.selectedTrace && !state.selectedGroup);
    const div = el('div', 'row' + (sel && r.kind !== 'hop' && r.kind !== 'more' ? ' selected' : '')); div.style.top = r.y + 'px'; div.style.height = r.h + 'px'; div.style.width = W + 'px';
    body.appendChild(div);
    if (r.kind === 'more') {
      const y = r.y + 19, x = laneX(r.to); const lbl = el('div', 'lbl'); lbl.style.left = (Math.min(laneX(r.from), x) + 14) + 'px'; lbl.style.top = '6px';
      const rem = r.total - r.shown;
      lbl.innerHTML = `<span class="sub">${r.shown} of ${r.total} identical calls drawn one by one · </span><a>${rem > 0 ? `draw ${Math.min(PAGE * 5, rem)} more` : 'fold them back into one row'}</a>`;
      div.appendChild(lbl);
      div.addEventListener('click', () => { if (rem > 0) state.expanded.set(r.gkey, r.shown + PAGE * 5); else state.expanded.delete(r.gkey); state.model = buildModel(); render(); });
      continue;
    }
    const s = r.span;
    div.addEventListener('click', () => { if (r.group) selectGroup(r); else selectSpan(s.trace.trace.id, s.id); });
    const x1 = r.from ? laneX(r.from) + (r.kind === 'ret' ? s.nest : (s.parent ? s.parent.nest : 0)) * 6 : 10, x2 = r.to ? laneX(r.to) + (r.kind === 'ret' ? (s.parent ? s.parent.nest : 0) : s.nest) * 6 : 10;
    if (r.kind === 'hop') {
      const y = r.y + 19; mk('line', { class: 'hop', x1: x1 + 5, y1: y, x2: x2 - 6, y2: y, 'marker-end': 'url(#arr-call)' }, svg);
      const lbl = el('div', 'lbl'); lbl.style.left = (Math.min(x1, x2) + 14) + 'px'; lbl.style.top = '1px'; lbl.innerHTML = `<span class="m">send</span> <span class="sub">${esc(r.to.name)}</span><span class="tag hop">continues on another thread · +${fmtDuration(r.delay)}</span>`; div.appendChild(lbl); continue;
    }
    const failed = !!s.exception;
    if (r.kind === 'ret') {
      const y = r.y + 12;
      if (!r.to) mk('line', { class: 'ret' + (failed ? ' failed' : ''), x1: x1 - 6, y1: y, x2: 16, y2: y, 'marker-end': `url(#arr-${failed ? 'retfail' : 'ret'})` }, svg);
      else if (r.from === r.to) mk('path', { class: 'ret' + (failed ? ' failed' : ''), d: `M${x2 + 12},${y - 8} H${x2 + 28} V${y} H${x2 + 10}`, 'marker-end': `url(#arr-${failed ? 'retfail' : 'ret'})` }, svg);
      else mk('line', { class: 'ret' + (failed ? ' failed' : ''), x1: x1 + (x2 > x1 ? 5 : -5), y1: y, x2: x2 + (x2 > x1 ? -7 : 7), y2: y, 'marker-end': `url(#arr-${failed ? 'retfail' : 'ret'})` }, svg);
      const note = failed ? `<span class="tag bad">✗ ${esc(simple(s.exception.className))}</span>` : s.http && !s.layer && s.http.status != null ? `<span class="tag ${s.http.status < 400 ? 'ok' : 'bad'}">${s.http.status}</span>` : s.kind === 'SQL' && s.sql.rows != null ? `<span class="tag">${s.sql.rows} row${s.sql.rows === 1 ? '' : 's'}</span>` : s.kind === 'SQL' && s.sql.updateCount != null ? `<span class="tag">${s.sql.updateCount} updated</span>` : '';
      if (note) { const rl = el('div', 'rlbl'); rl.style.left = (Math.min(x1, x2) + 14) + 'px'; rl.innerHTML = note; div.appendChild(rl); }
      continue;
    }
    const y = r.y + 24, self = r.from === r.to, cls = (r.hop ? 'hop' : 'call') + (failed ? ' failed' : '');
    if (!r.from) mk('line', { class: cls, x1: 10, y1: y, x2: x2 - 6, y2: y, 'marker-end': `url(#arr-${failed ? 'fail' : 'call'})` }, svg);
    else if (self) mk('path', { class: cls, d: `M${x1 + 5},${y - 10} H${x1 + 30} V${y} H${x1 + 12}`, 'marker-end': `url(#arr-${failed ? 'fail' : 'call'})` }, svg);
    else mk('line', { class: cls, x1: x1 + (x2 > x1 ? 5 : -5), y1: y, x2: x2 + (x2 > x1 ? -6 : 6), y2: y, 'marker-end': `url(#arr-${failed ? 'fail' : 'call'})` }, svg);
    const lbl = el('div', 'lbl'); const lx = Math.min(x1, x2) + (self ? 40 : 14), room = W - DUR_COL - 10 - lx;
    if (room < 240) { lbl.classList.add('flip'); const right = Math.max(DUR_COL + 10, W - Math.max(x1, x2) + 14); lbl.style.right = right + 'px'; lbl.style.maxWidth = (W - right - 30) + 'px'; } else { lbl.style.left = lx + 'px'; lbl.style.maxWidth = room + 'px'; }
    const tags = [];
    if (r.group) tags.push(`<span class="tag n">×${r.group.length}</span>`);
    if (s.ejb && s.ejb.invocation === 'REMOTE') tags.push('<span class="tag">REMOTE</span>');
    if (r.hop) tags.push(`<span class="tag hop">on another thread (${r.hop.kind}) · +${fmtDuration(r.hop.delay)}</span>`);
    if (truncation(s).length) tags.push('<span class="tag trunc">truncated</span>');
    lbl.innerHTML = `<span class="m${s.kind === 'SQL' ? ' sql' : ''}">${esc(methodLabel(s))}</span>${r.resource ? `<span class="sub"> · ${esc(compName(r.resource))}.${esc(methodLabel(r.resource))}</span>` : ''}${tags.join('')}`;
    lbl.title = r.group ? `${r.group.length} consecutive calls of ${spanLabel(s)}, none calling further, none failing; drawn as one. Click for the list.` : s.kind === 'SQL' ? (s.sql.sql || '') : `${spanLabel(s)}${s.exception ? ' ✗ ' + s.exception.message : ''}`;
    div.appendChild(lbl);
    div.appendChild(el('div', 'dur', fmtNs(r.group ? r.group.reduce((a, g) => a + g.durationNs, 0) : s.durationNs)));
  }
}

function truncation(s) {
  const reasons = [];
  if (s.http) {
    if (s.http.requestBodyDropped) reasons.push(`request body: ${s.http.requestBodyDropped} bytes dropped`);
    if (s.http.responseBodyDropped) reasons.push(`response body: ${s.http.responseBodyDropped} bytes dropped`);
    if (s.http.requestBodyComplete === false) reasons.push('request body incomplete');
  }
  for (const w of s.serializationWarnings || []) if (/limit|truncat|dropped|ceiling/i.test(w.reason || '')) reasons.push(`${w.path}: ${w.reason}`);
  return reasons;
}

function render() {
  if (!state.model) return;
  fitLanes(); renderLanes(); renderBody();
}
window.addEventListener('resize', () => { if (state.view === 'recording') render(); });

// ---- selection ---------------------------------------------------------------------------

function selectSpan(traceId, spanId, focusPath) {
  state.selectedTrace = traceId; state.selectedSpan = spanId; state.selectedGroup = null;
  renderBody();
  renderDetail(focusPath);
  $('win').hidden = false;
  const t = state.traces.get(traceId), s = t && t.byId && t.byId.get(spanId);
  if (s && s.row) { const seq = $('seq'), top = s.row.y * state.scale; if (top - 100 < seq.scrollTop || top + 60 > seq.scrollTop + seq.clientHeight) seq.scrollTo({ top: Math.max(0, top - 120) }); }
  history.replaceState(null, '', `#r=${encodeURIComponent(state.recordingId)}&t=${encodeURIComponent(traceId)}&s=${encodeURIComponent(spanId || '')}${focusPath ? '&p=' + encodeURIComponent(focusPath) : ''}`);
}
function selectGroup(r) {
  state.selectedTrace = r.span.trace.trace.id; state.selectedSpan = null; state.selectedGroup = r.gkey;
  renderBody(); renderGroupDetail(r); $('win').hidden = false;
}
function jumpTo(traceId, spanId, path) { selectSpan(traceId, spanId, path); }

document.addEventListener('keydown', (e) => {
  if (e.target.tagName === 'INPUT' || state.view !== 'recording' || !state.model) return;
  const entries = state.model.entries; const i = entries.findIndex(t => t.trace.id === state.selectedTrace || (t.spans.some(s => s.spawns.some(c => c.trace.id === state.selectedTrace))));
  if (e.key === 'ArrowDown' && i + 1 < entries.length) { e.preventDefault(); const t = entries[i + 1]; selectSpan(t.trace.id, rootOf(t.trace.id)); }
  else if (e.key === 'ArrowUp' && i > 0) { e.preventDefault(); const t = entries[i - 1]; selectSpan(t.trace.id, rootOf(t.trace.id)); }
});

