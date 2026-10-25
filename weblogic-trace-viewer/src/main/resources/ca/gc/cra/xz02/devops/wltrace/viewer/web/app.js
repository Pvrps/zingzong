'use strict';

// ---- state --------------------------------------------------------------------------------

const state = {
  domain: null,            // last /api/state
  view: 'home',            // 'home' | 'recording'
  recordingId: null,
  index: null,             // /api/recordings/{id}
  traces: new Map(),       // trace id -> trace JSON (kept across index refreshes)
  model: null,             // what the diagram is built from (buildModel)
  selectedTrace: null,
  selectedSpan: null,      // span id within selectedTrace
  selectedGroup: null,     // a grouped run's key when its row is selected
  expanded: new Map(),     // group key -> how many of its calls are drawn one by one
  scale: 1,
  confirmDelete: null,
  busy: false,
};

const $ = id => document.getElementById(id);

async function api(path, options) {
  const r = await fetch(path, Object.assign({ cache: 'no-store' }, options || {}));
  const text = await r.text();
  let body = null;
  try { body = text ? JSON.parse(text) : null; } catch (e) { body = { error: text }; }
  if (!r.ok) throw new Error(body && body.error ? body.error : `${r.status} ${path}`);
  return body;
}

// ---- header: state, servers, start/stop --------------------------------------------------

async function pollState() {
  try {
    state.domain = await api('/api/state');
    renderTop();
  } catch (e) {
    $('state-message').textContent = `page server unreachable: ${e.message}`;
    $('state-message').className = 'pill bad';
    $('btn-start').disabled = true;
    $('btn-stop').disabled = true;
  }
}

function renderTop() {
  const d = state.domain;
  const msg = $('state-message');
  msg.textContent = d.message;
  msg.className = 'pill ' + (d.kind === 'READY' ? 'ok' : d.kind === 'ENABLED_SERVER_DOWN' ? 'warn' : 'bad');
  $('domain-meta').textContent = `${d.domain} · WebLogic ${d.webLogicVersion} · recordings in ${d.outDir}`;
  const servers = $('servers');
  servers.innerHTML = '';
  if (!d.servers.length) servers.appendChild(el('span', 'server none', 'no server JVM of this domain is running'));
  for (const s of d.servers) {
    const span = el('span', 'server ' + (s.agentLoaded ? 'agent' : 'noagent'));
    span.textContent = `${s.serverName} · pid ${s.pid} · ` + (s.agentLoaded
      ? (s.recording ? `recording ${s.traceCount} trace${s.traceCount === 1 ? '' : 's'}${s.droppedTraces ? ', ' + s.droppedTraces + ' dropped' : ''}` : 'agent idle')
      : 'no agent, restart it');
    if (s.agentLoaded && !s.agentCurrent) span.textContent += ' · older agent build; restart to update';
    span.title = s.agentBuild ? `agent ${s.agentBuild}` : '';
    servers.appendChild(span);
  }
  const active = d.activeRecordingId;
  const start = $('btn-start');
  const stop = $('btn-stop');
  start.disabled = state.busy || !d.ready || !!active;
  stop.disabled = state.busy || !active;
  $('start-reason').textContent = start.disabled && !state.busy && !active ? d.message : '';
  $('name').disabled = start.disabled;
  $('name').hidden = !!active;
  const link = $('active-link');
  if (active) {
    link.hidden = false;
    link.innerHTML = `<span class="rec">REC</span> ${esc(d.activeRecordingName || active)}`;
    link.href = `#r=${encodeURIComponent(active)}`;
  } else {
    link.hidden = true;
  }
}

async function startCapture() {
  state.busy = true; renderTop();
  try {
    const r = await api('/api/control/start', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ name: $('name').value || null }) });
    $('name').value = '';
    await pollState();
    location.hash = `#r=${encodeURIComponent(r.id)}`;
  } catch (e) {
    alert(`could not start: ${e.message}`);
  } finally {
    state.busy = false; renderTop();
  }
}

async function stopCapture() {
  state.busy = true; renderTop();
  try {
    await api('/api/control/stop', { method: 'POST' });
    await pollState();
    if (state.view === 'recording') await refreshRecording();
    else await renderHome();
  } catch (e) {
    alert(`could not stop: ${e.message}`);
  } finally {
    state.busy = false; renderTop();
  }
}

async function deleteRecording(id) {
  try {
    await api(`/api/recordings/${encodeURIComponent(id)}`, { method: 'DELETE' });
  } catch (e) {
    alert(`could not delete: ${e.message}`);
  }
  state.confirmDelete = null;
  if (state.view === 'recording' && state.recordingId === id) location.hash = '';
  else await renderHome();
}

/** A delete control with an inline confirmation, no dialog. */
function deleteControl(r, host) {
  host.innerHTML = '';
  if (r.active) return;
  if (state.confirmDelete === r.id) {
    const box = el('span', 'confirm');
    box.appendChild(document.createTextNode(`delete ${r.traceCount} trace${r.traceCount === 1 ? '' : 's'}?`));
    const yes = el('button', 'btn small danger', 'Delete');
    yes.addEventListener('click', (e) => { e.stopPropagation(); deleteRecording(r.id); });
    const no = el('button', 'btn small', 'Keep');
    no.addEventListener('click', (e) => { e.stopPropagation(); state.confirmDelete = null; deleteControl(r, host); });
    box.appendChild(yes); box.appendChild(no);
    host.appendChild(box);
  } else {
    const b = el('button', 'btn small', 'Delete');
    b.addEventListener('click', (e) => { e.stopPropagation(); state.confirmDelete = r.id; deleteControl(r, host); });
    host.appendChild(b);
  }
}

// ---- home: all recordings -------------------------------------------------------------------

async function renderHome() {
  const list = await api('/api/recordings');
  const body = $('home-body');
  body.innerHTML = '';
  body.className = '';
  if (!list.length) {
    body.appendChild(el('div', 'empty', 'No recordings yet. Start one above, then drive the application; Stop when done.'));
    return;
  }
  const table = el('table', 'recordings');
  table.innerHTML = '<thead><tr><th>recording</th><th>started</th><th>duration</th><th>traces</th><th>servers</th><th></th></tr></thead>';
  const tbody = el('tbody');
  for (const r of list) {
    const tr = el('tr', r.active ? 'active' : '');
    const duration = r.startedAt && r.endedAt ? fmtDuration(Date.parse(r.endedAt) - Date.parse(r.startedAt)) : (r.active ? 'running' : r.startedAt ? 'unfinished' : '');
    tr.innerHTML = `<td><a class="name" href="#r=${encodeURIComponent(r.id)}">${esc(r.name || r.id)}</a> ${r.active ? '<span class="rec">REC</span>' : ''}<div class="id">${esc(r.id)}</div></td>` +
      `<td>${esc(fmtTime(r.startedAt))}</td><td>${esc(duration)}</td><td>${r.traceCount}</td><td>${esc((r.servers || []).join(', '))}</td><td class="actions"></td>`;
    deleteControl(r, tr.querySelector('.actions'));
    tbody.appendChild(tr);
  }
  table.appendChild(tbody);
  body.appendChild(table);
}

// ---- recording view: loading ---------------------------------------------------------------------

async function openRecording(id, hash) {
  state.view = 'recording';
  if (state.recordingId !== id) {
    state.recordingId = id;
    state.index = null;
    state.traces = new Map();
    state.model = null;
    state.selectedTrace = null;
    state.selectedSpan = null;
    state.selectedGroup = null;
    state.expanded = new Map();
    $('win').hidden = true;
  }
  showView();
  try {
    await refreshRecording();
  } catch (e) {
    $('body').className = '';
    $('body').innerHTML = `<div class="err-box">${esc(String(e))}</div>`;
    return;
  }
  if (hash.get('t') && state.traces.has(hash.get('t'))) {
    if (hash.get('p')) jumpTo(hash.get('t'), hash.get('s') || rootOf(hash.get('t')), hash.get('p'));
    else selectSpan(hash.get('t'), hash.get('s') || rootOf(hash.get('t')));
  }
}

/** Reloads the index, fetches traces not seen yet, rebuilds the diagram when something changed. */
async function refreshRecording() {
  const id = state.recordingId;
  const index = await api(`/api/recordings/${encodeURIComponent(id)}`);
  if (state.recordingId !== id) return;
  const missing = index.traces.filter(t => !state.traces.has(t.id));
  const t0 = performance.now();
  await Promise.all(missing.map(async t => {
    const trace = await api(`/api/recordings/${encodeURIComponent(id)}/traces/${encodeURIComponent(t.file)}`);
    state.traces.set(t.id, trace);
  }));
  if (missing.length) console.info(`[weblogic-trace] ${missing.length} trace(s), ${missing.reduce((n, t) => n + (t.spanCount || 0), 0)} spans fetched and parsed in ${(performance.now() - t0).toFixed(0)} ms`);
  if (state.recordingId !== id) return;
  const changed = !state.index || state.index.traces.length !== index.traces.length || missing.length > 0;
  state.index = index;
  const r = index.recording || {};
  const active = state.domain && state.domain.activeRecordingId === id;
  const entries = index.traces.filter(t => !t.parentTraceId || !state.traces.has(t.parentTraceId)).length;
  $('recording-meta').innerHTML = `<b>${esc(r.name || r.id || id)}</b> · ${esc(r.id || id)} · ${entries} entry point${entries === 1 ? '' : 's'} · ${index.traces.length} thread${index.traces.length === 1 ? '' : 's'} of work` +
    ` · ${esc((r.servers || [r.serverName]).filter(Boolean).join(', ') || '?')}` +
    (active ? ' · <span class="rec">REC</span> new work appears as it completes' : r.endedAt ? '' : ' · unfinished (no end recorded)');
  deleteControl({ id, active, traceCount: index.traces.length }, $('rec-delete'));
  if (changed) {
    const t1 = performance.now();
    state.model = buildModel();
    render();
    console.info(`[weblogic-trace] diagram of ${state.model.rows.length} rows built and rendered in ${(performance.now() - t1).toFixed(0)} ms`);
    if (state.selectedTrace) renderDetail();
  }
}

// ---- routing & polling --------------------------------------------------------------------------

function showView() {
  $('home').hidden = state.view !== 'home';
  $('recording').hidden = state.view !== 'recording';
  if (state.view !== 'recording') $('win').hidden = true;
}

async function route() {
  const h = new URLSearchParams(location.hash.replace(/^#/, ''));
  if (h.get('r')) {
    await openRecording(h.get('r'), h);
  } else {
    state.view = 'home';
    showView();
    await renderHome();
  }
}

async function tick() {
  await pollState();
  try {
    if (state.view === 'home') await renderHome();
    else if (state.view === 'recording' && state.domain && state.domain.activeRecordingId === state.recordingId) await refreshRecording();
  } catch (e) {
    // transient while files are being written; the next tick retries
  }
}

window.addEventListener('hashchange', route);
$('btn-start').addEventListener('click', startCapture);
$('btn-stop').addEventListener('click', stopCapture);
$('name').addEventListener('keydown', e => { if (e.key === 'Enter' && !$('btn-start').disabled) startCapture(); });

function fmtTime(iso) { return iso ? iso.replace('T', ' ').replace(/\.\d+Z$/, 'Z') : ''; }
function fmtDuration(ms) { return ms < 1000 ? `${ms} ms` : ms < 60000 ? `${(ms / 1000).toFixed(1)} s` : `${Math.floor(ms / 60000)} min ${Math.round((ms % 60000) / 1000)} s`; }
function fmtNs(ns) { const ms = ns / 1e6; return ms < 10 ? `${ms.toFixed(2)} ms` : ms < 1000 ? `${ms.toFixed(1)} ms` : `${(ms / 1000).toFixed(2)} s`; }

(async () => { await pollState(); await route(); setInterval(tick, 1000); })();


// ---- the details window ------------------------------------------------------------------------

const win = $('win');
let winDrag = null;
$('win-title').addEventListener('mousedown', ev => { if (ev.target.tagName === 'BUTTON') return; const r = win.getBoundingClientRect(); winDrag = { dx: ev.clientX - r.left, dy: ev.clientY - r.top }; ev.preventDefault(); });
window.addEventListener('mousemove', ev => { if (!winDrag) return; win.style.left = Math.max(0, Math.min(window.innerWidth - 60, ev.clientX - winDrag.dx)) + 'px'; win.style.top = Math.max(0, Math.min(window.innerHeight - 40, ev.clientY - winDrag.dy)) + 'px'; win.style.right = 'auto'; });
window.addEventListener('mouseup', () => { winDrag = null; });
$('win-close').addEventListener('click', () => { win.hidden = true; });
$('btn-details').addEventListener('click', () => { win.hidden = !win.hidden; });

function chainOf(s) { const p = []; for (let x = s; x;) { p.unshift({ span: x }); if (x.parent) x = x.parent; else if (x.trace.cause) { p.unshift({ hop: x.comp.kind === 'MDB' ? 'JMS' : '@Asynchronous' }); x = x.trace.cause; } else x = null; } return p; }
function entryOf(s) { let t = s.trace; while (t.cause) t = t.cause.trace; return t; }

function renderGroupDetail(r) {
  const body = $('detail-body'); body.className = ''; body.innerHTML = '';
  const run = r.group, s = run[0], total = run.reduce((a, g) => a + g.durationNs, 0), d = run.map(g => g.durationNs);
  $('win-name').textContent = `${spanLabel(s)} ×${run.length}`;
  const chips = el('div', 'chips'); chips.innerHTML = `<span class="chip">${run.length} consecutive identical calls</span><span class="chip">${esc(KIND_TEXT[s.comp.kind])}</span>${s.comp.app ? `<span class="chip">${esc(s.comp.app)}</span>` : ''}`; body.appendChild(chips);
  const facts = el('dl', 'facts'); facts.innerHTML = `<dt>total</dt><dd>${fmtNs(total)}</dd><dt>min / avg / max</dt><dd>${fmtNs(Math.min(...d))} / ${fmtNs(total / run.length)} / ${fmtNs(Math.max(...d))}</dd><dt>calls</dt><dd>${esc(s.id)} … ${esc(run[run.length - 1].id)}</dd>`; body.appendChild(facts);
  const p = el('p', 'hint', 'Every call is recorded in full. Pick one below, or draw them one by one in the diagram.'); body.appendChild(p);
  const a = el('a', '', 'draw them one by one →'); a.style.cursor = 'pointer'; a.addEventListener('click', () => { state.expanded.set(r.gkey, PAGE); state.model = buildModel(); render(); }); body.appendChild(a);
  const table = el('table', 'group-table'); table.innerHTML = '<thead><tr><th>#</th><th>call</th><th>input</th><th>output</th><th>duration</th></tr></thead>';
  const tbody = el('tbody'); const show = Math.min(run.length, Math.max(PAGE, state.expanded.get(r.gkey + '/detail') || 0));
  run.slice(0, show).forEach((g, i) => {
    const tr = el('tr', g.id === state.selectedSpan ? 'selected' : '');
    const input = g.kind === 'SQL' ? (g.sql.parameters ? JSON.stringify(g.sql.parameters) : '') : JSON.stringify((g.args || []).map(x => x.value), (k, v) => k === '@type' ? undefined : v);
    const output = g.kind === 'SQL' ? (g.sql.rows != null ? g.sql.rows + ' rows' : g.sql.updateCount != null ? g.sql.updateCount + ' updated' : '') : g.result ? JSON.stringify(g.result.value, (k, v) => k === '@type' ? undefined : v) : '';
    tr.innerHTML = `<td>${i + 1}</td><td>${esc(g.id)}</td><td>${esc(shorten(input, 60))}</td><td>${esc(shorten(output || '', 60))}</td><td>${fmtNs(g.durationNs)}</td>`;
    tr.addEventListener('click', () => selectSpan(g.trace.trace.id, g.id)); tbody.appendChild(tr);
  });
  table.appendChild(tbody); body.appendChild(table);
  if (show < run.length) { const more = el('button', 'btn small', `show ${Math.min(PAGE * 5, run.length - show)} more of ${run.length}`); more.style.marginTop = '8px'; more.addEventListener('click', () => { state.expanded.set(r.gkey + '/detail', show + PAGE * 5); renderGroupDetail(r); }); body.appendChild(more); }
}

function renderDetail(focusPath) {
  const body = $('detail-body');
  const trace = state.traces.get(state.selectedTrace);
  const span = trace && trace.byId && trace.byId.get(state.selectedSpan);
  if (!span) { body.className = 'empty'; body.textContent = 'Select a call.'; $('win-name').textContent = 'Details'; return; }
  body.className = ''; body.innerHTML = '';
  const root = trace.spans.find(s => s.parentId == null) || trace.spans[0];
  const isReq = span.comp.kind === 'CLIENT', res = span.row && span.row.resource;
  $('win-name').textContent = spanLabel(span);

  const chips = [];
  const k = isReq ? (res ? res.comp.kind : 'CLIENT') : span.comp.kind;
  chips.push(`<span class="chip">${esc(k === 'DATASOURCE' ? dataSourceText(span.lane) : KIND_TEXT[k])}</span>`);
  if (span.comp.app && span.comp.app !== 'data sources') chips.push(`<span class="chip">${esc(span.comp.app)}${span.module ? ' / ' + esc(span.module) : ''}</span>`); else if (res && res.comp.app) chips.push(`<span class="chip">${esc(res.comp.app)}</span>`);
  if (span.ejb && span.ejb.invocation) chips.push(`<span class="chip">${esc(span.ejb.invocation)}</span>`);
  if (span.ejb && span.ejb.transactionAttribute) chips.push(`<span class="chip">tx ${esc(span.ejb.transactionAttribute)}</span>`);
  if (span.kind === 'SQL') chips.push(`<span class="chip">${esc(span.sql.statementType || '')} · ${esc(span.sql.operation)}</span>`);
  if (span.http && span.http.status != null) chips.push(`<span class="chip ${span.http.status >= 400 ? 'err' : ''}">HTTP ${span.http.status}</span>`);
  if (span.exception) chips.push(`<span class="chip err">✗ ${esc(simple(span.exception.className))}</span>`);
  const trunc = truncation(span);
  if (trunc.length) chips.push(`<span class="chip trunc" title="${esc(trunc.join('\n'))}">truncated</span>`);
  const ch = el('div', 'chips'); ch.innerHTML = chips.join(''); body.appendChild(ch);

  body.appendChild(el('h2', '', 'How we got here'));
  const path = el('div', 'path'); let depth = 0; const e = entryOf(span);
  path.innerHTML = `<div class="hint">entry point #${e.seq}</div>`;
  for (const step of chainOf(span)) {
    if (step.hop) { path.innerHTML += `<div style="padding-left:${depth * 14}px"><span class="hopl">↓ another thread (${step.hop})</span></div>`; continue; }
    const a = `<a data-t="${esc(step.span.trace.trace.id)}" data-s="${esc(step.span.id)}" style="cursor:pointer;color:inherit;text-decoration:none">${step.span === span ? '<b>' + esc(spanLabel(step.span)) + '</b>' : esc(spanLabel(step.span))}</a>`;
    path.innerHTML += `<div style="padding-left:${depth * 14}px">${depth ? '↳ ' : ''}${a}</div>`; depth++;
  }
  path.querySelectorAll('a[data-s]').forEach(a => a.addEventListener('click', () => selectSpan(a.dataset.t, a.dataset.s)));
  body.appendChild(path);

  body.appendChild(el('h2', '', 'Facts'));
  const facts = el('dl', 'facts'); const f = []; const fact = (kk, v) => { if (v != null && v !== '') f.push(`<dt>${esc(kk)}</dt><dd>${esc(v)}</dd>`); };
  fact('duration', `${fmtNs(span.durationNs)} · started ${fmtNs(span.startNs)} into ${trace.cause ? 'its thread' : 'the request'}`);
  fact('thread', span.thread);
  if (span.kind === 'SQL') { fact('data source', span.sql.dataSource + (span.sql.jndiNames ? ' · ' + span.sql.jndiNames.join(', ') : '')); fact('database', span.sql.database); fact('url', span.sql.url); }
  if (span.ejb) { fact('signature', span.ejb.signature); fact('interface', span.ejb.interface); fact('bean', span.ejb.beanClass); fact('type', span.ejb.beanType ? span.ejb.beanType.toLowerCase().replace('_', '-') + (span.ejb.ejb2x ? ' · EJB 2.x component interface' : '') : null); fact('destination', span.ejb.destination); if (span.ejb.jndiNames && span.ejb.jndiNames.length) fact('jndi', span.ejb.jndiNames.join('\n')); }
  if (span.http && !span.layer) { fact('request', `${span.http.method || ''} ${span.http.path || ''}${span.http.query ? '?' + span.http.query : ''}`); fact('from', span.http.remoteAddr); if (res) fact('resource method', res.http.resourceSignature || `${compName(res)}.${methodLabel(res)}`); }
  if (span.http && span.layer) fact('resource', span.http.resourceSignature || `${span.http.resourceClass}.${span.http.resourceMethod}`);
  fact('id', `${trace.trace.id} / ${span.id}`);
  facts.innerHTML = f.join(''); body.appendChild(facts);

  if (span.spawns.length) {
    const box = el('div', 'handoff-box'); box.appendChild(document.createTextNode('↓ continued on another thread: '));
    span.spawns.forEach((c, i) => { if (i) box.appendChild(document.createTextNode(' · ')); const cr = c.spans.find(x => x.parentId == null) || c.spans[0]; const a = el('a', '', `${spanLabel(cr)} (${cr.queue ? 'JMS' : '@Asynchronous'}, +${fmtDuration(c.startMs - trace.startMs)})`); a.addEventListener('click', () => selectSpan(c.trace.id, cr.id)); box.appendChild(a); });
    body.appendChild(box);
  }

  const badges = lineageBadges(trace.trace.id, span.id);
  const section = (title, count, open, fill) => {
    const d = el('details', 'sec'); if (open) d.open = true;
    const sum = el('summary'); sum.innerHTML = `${esc(title)}${count != null ? ` <span class="cnt">${esc(count)}</span>` : ''}`; d.appendChild(sum);
    const json = el('div', 'json'); fill(json); d.appendChild(json); body.appendChild(d); return json;
  };
  const shown = new Set(['args', 'result', 'exception', 'http', 'sql', 'serializationWarnings']);
  const resBadges = res ? lineageBadges(trace.trace.id, res.id) : null;
  // Input and Output, the same way for every kind of call
  section('Input', span.kind === 'SQL' ? (span.sql.parameters ? `${span.sql.parameters.length} parameter${span.sql.parameters.length === 1 ? '' : 's'}` : null) : isReq ? (span.http.requestBodyLength != null ? `${span.http.requestBodyLength} bytes` : null) : span.args ? `${span.args.length} argument${span.args.length === 1 ? '' : 's'}` : null, true, j => {
    if (span.kind === 'SQL') {
      j.appendChild(el('pre', 'sqltext', span.sql.sql || `(${span.sql.operation})`));
      if (span.sql.parameters) span.sql.parameters.forEach((v, i) => renderValue(j, v, `sql.parameters[${i}]`, 0, badges, i + 1));
      if (span.sql.batches) renderValue(j, span.sql.batches, 'sql.batches', 0, badges, 'batches');
      if (!span.sql.parameters && !span.sql.batches) j.appendChild(el('span', 'hint', 'no parameters'));
    } else if (isReq) {
      if (span.http.requestBody != null) renderValue(j, span.http.requestBody, 'http.requestBody', 0, badges, 'requestBody'); else j.appendChild(el('span', 'hint', 'no request body'));
      if (res && res.args) { j.appendChild(el('div', 'hint', `arguments of ${spanLabel(res)}:`)); renderValue(j, res.args, 'args', 0, resBadges, 'args'); }
    } else if (span.args) renderValue(j, span.args, 'args', 0, badges, 'args'); else j.appendChild(el('span', 'hint', 'no arguments'));
  });
  section('Output', span.kind === 'SQL' ? (span.sql.rows != null ? `${span.sql.rows} row${span.sql.rows === 1 ? '' : 's'} read` : span.sql.updateCount != null ? `${span.sql.updateCount} updated` : null) : isReq && span.http.status != null ? `HTTP ${span.http.status}` : null, true, j => {
    if (span.exception) { j.appendChild(el('span', 'hint', 'none: the call threw')); return; }
    if (span.kind === 'SQL') {
      if (span.sql.rows != null) renderValue(j, span.sql.rows, 'sql.rows', 0, badges, 'rows');
      if (span.sql.updateCount != null) renderValue(j, span.sql.updateCount, 'sql.updateCount', 0, badges, 'updateCount');
      if (span.sql.updateCounts) renderValue(j, span.sql.updateCounts, 'sql.updateCounts', 0, badges, 'updateCounts');
      if (span.sql.resultSet === false && span.sql.updateCount == null) j.appendChild(el('span', 'hint', 'no result set'));
    } else if (isReq) {
      if (span.http.responseBody != null) renderValue(j, span.http.responseBody, 'http.responseBody', 0, badges, 'responseBody'); else j.appendChild(el('span', 'hint', span.http.responseBodyNote || 'no response body'));
      if (span.http.sentError) renderValue(j, span.http.sentError, 'http.sentError', 0, badges, 'sentError');
      if (res && res.result) { j.appendChild(el('div', 'hint', `result of ${spanLabel(res)}:`)); renderValue(j, res.result, 'result', 0, resBadges, 'result'); }
    } else if (span.result) renderValue(j, span.result, 'result', 0, badges, 'result'); else j.appendChild(el('span', 'hint', 'void'));
  });
  if (span.exception) section('Exception', simple(span.exception.className), true, j => renderValue(j, span.exception, 'exception', 0, badges, 'exception'));
  if (res && res.exception) section(`Exception in ${spanLabel(res)}`, simple(res.exception.className), true, j => renderValue(j, res.exception, 'exception', 0, resBadges, 'exception'));
  if (span.http) section(isReq ? 'HTTP request and response' : 'HTTP', null, false, j => { const h = {}; for (const kk of Object.keys(span.http)) if (kk !== 'requestBody' && kk !== 'responseBody') h[kk] = span.http[kk]; renderValue(j, h, 'http', 0, badges, 'http'); });
  if (span.kind === 'SQL') section('Data source', null, false, j => { const d = {}; for (const kk of Object.keys(span.sql)) if (!['sql', 'parameters', 'batches', 'rows', 'updateCount', 'updateCounts', 'resultSet'].includes(kk)) d[kk] = span.sql[kk]; renderValue(j, d, 'sql', 0, badges, 'sql'); });
  if ((span.serializationWarnings || []).length) section('Notes from the capture', `${span.serializationWarnings.length}`, false, j => renderValue(j, span.serializationWarnings, 'serializationWarnings', 0, badges, 'serializationWarnings'));
  section('Everything else on this call', null, false, j => { for (const kk of ORIGINAL.get(span) || Object.keys(span)) { if (shown.has(kk)) continue; renderValue(j, span[kk], kk, 0, badges, kk); } });
  const unplaced = Object.entries(badges).filter(([, v]) => !v.placed);
  if (unplaced.length) { const box = el('div', 'json'); box.appendChild(el('div', 'k', 'lineage on paths not rendered above:')); for (const [p, v] of unplaced) { const r = el('div', 'row'); r.textContent = p + ' '; v.badges.forEach(b => r.appendChild(b)); box.appendChild(r); } body.appendChild(box); }
  if (focusPath) { const target = findRow(body, focusPath); if (target) { const d = target.closest('details'); if (d) d.open = true; target.scrollIntoView({ block: 'center' }); target.classList.remove('flash'); void target.offsetWidth; target.classList.add('flash'); } }
}

// Markers the agent writes for structural necessities; each is highlighted, never hidden. Red
// means data is missing at this point; the others only tell what the value is.
const MARKERS = { '@type': 'type', '@ref': 'ref', '@id': 'ref', '@lazy': 'lazy', '@handle': 'handle', '@skipped': 'skipped', '@truncated': 'truncated', '@unserializable': 'unserializable', '@serializable': 'unserializable', '@bytes': 'type', '@text': 'truncated', '@jms': 'type', '@entries': 'type' };
const MISSING = new Set(['@lazy', '@handle', '@truncated', '@unserializable']);
const MARKER_HELP = {
  '@type': 'the runtime class of this value',
  '@ref': 'the same object again (a cycle or a shared reference); it is written in full where @id says so',
  '@id': 'this object is referred to elsewhere by @ref',
  '@lazy': 'a lazy collection or holder that was not loaded when the call crossed the boundary; loading it would have changed the call, so its content is not here',
  '@handle': 'a runtime handle (thread, class loader, socket, connection, lock); not payload, so only its identity and toString are recorded',
  '@skipped': 'fields the walker leaves out because they describe the JVM, not the value (for example Throwable.backtrace)',
  '@truncated': 'a safety ceiling was hit: what and how much was dropped',
  '@unserializable': 'the walker could not read this value; the reason and its toString are recorded',
  '@serializable': 'false: the object does not implement Serializable. It was captured in full all the same; this only matters for replaying it',
  '@bytes': 'a byte array, base64',
  '@text': 'a string over the ceiling: what was kept, and how much was dropped',
  '@jms': 'a JMS message, recorded as headers, properties and body',
  '@entries': 'a map with non-string keys, as key/value pairs',
};

function renderValue(container, value, path, depth, badges, key) {
  const row = el('span', 'row');
  row.style.setProperty('--indent', depth ? '1.2em' : '0');  // rows nest, so each level adds one step
  row.dataset.path = path;
  if (key !== undefined) {
    const k = el('span', 'k', typeof key === 'number' ? `[${key}]` : JSON.stringify(key));
    const marker = typeof key === 'string' && Object.prototype.hasOwnProperty.call(MARKERS, key) ? MARKERS[key] : null;
    if (marker) { k.classList.add('m', marker); k.title = MARKER_HELP[key] || ''; }
    row.appendChild(k);
    row.appendChild(document.createTextNode(': '));
    if (MISSING.has(key) && value !== false) row.classList.add('flagged');
  }
  if (value === null || value === undefined) {
    row.appendChild(el('span', 'z', 'null'));
  } else if (Array.isArray(value)) {
    if (!value.length) row.appendChild(document.createTextNode('[]'));
    else {
      const fold = el('span', 'fold', `[ ${value.length} ]`);
      const box = el('span', 'block');
      fold.addEventListener('click', () => { box.hidden = !box.hidden; });
      row.appendChild(fold);
      row.appendChild(box);
      value.forEach((v, i) => renderValue(box, v, `${path}[${i}]`, depth + 1, badges, i));
    }
  } else if (typeof value === 'object') {
    const keys = Object.keys(value);
    if (!keys.length) row.appendChild(document.createTextNode('{}'));
    else {
      const fold = el('span', 'fold', `{ ${keys.length} }`);
      const box = el('span', 'block');
      fold.addEventListener('click', () => { box.hidden = !box.hidden; });
      row.appendChild(fold);
      row.appendChild(box);
      for (const k of keys) renderValue(box, value[k], path ? `${path}.${k}` : k, depth + 1, badges, k);
    }
  } else if (typeof value === 'string') {
    if (value.length > 120 || value.includes('\n')) {
      const block = el('span', 'text-block');
      block.textContent = value;
      row.appendChild(el('span', 'z', `string, ${value.length} chars`));
      row.appendChild(block);
    } else {
      row.appendChild(el('span', 's', JSON.stringify(value)));
    }
  } else if (typeof value === 'number') {
    row.appendChild(el('span', 'n', String(value)));
  } else {
    row.appendChild(el('span', 'b', String(value)));
  }
  const b = badges[path];
  if (b) { b.badges.forEach(x => row.appendChild(x)); b.placed = true; }
  container.appendChild(row);
}

function findRow(scope, path) {
  // exact first, then the longest rendered prefix (a value inside a body string, a path segment)
  let p = path;
  while (p) {
    const hit = scope.querySelector(`.row[data-path="${CSS.escape(p)}"]`);
    if (hit) return hit;
    p = parentPath(p);
  }
  return null;
}
function parentPath(p) { const i = Math.max(p.lastIndexOf('.'), p.lastIndexOf('[')); return i <= 0 ? '' : p.substring(0, i); }

// ---- lineage ----------------------------------------------------------------------------

// A lineage path names the captured value ("args[0].orderId", "result.id", "http.requestBody.x",
// "http.path[4]"); in the span JSON an arg or result wraps its value (args[0].value.orderId), and a
// value inside a body string or the URL is addressed from the field holding it, remainder shown.
function locate(lineagePath) {
  let m;
  if ((m = /^(args\[\d+\])(.*)$/.exec(lineagePath))) return { json: m[1] + '.value' + m[2], remainder: '' };
  if ((m = /^result(.*)$/.exec(lineagePath))) return { json: 'result.value' + m[1], remainder: '' };
  if ((m = /^(http\.(requestBody|responseBody|path|query))(.*)$/.exec(lineagePath))) return { json: m[1], remainder: m[3] };
  return { json: lineagePath, remainder: '' };
}
function jsonPath(lineagePath) { return locate(lineagePath).json; }

function lineageBadges(traceId, spanId) {
  const out = {};
  const put = (lineagePath, badgeEl) => { const jp = jsonPath(lineagePath); if (!out[jp]) out[jp] = { badges: [], placed: false }; out[jp].badges.push(badgeEl); };
  const seqOf = id => { const t = state.traces.get(id); return t && t.seq ? '#' + t.seq : (t && t.cause ? '#' + entryOf(t.cause).seq + '↓' : id); };
  const labelOf = (ref) => { const t = state.traces.get(ref.trace || traceId); const s = t && t.byId && t.byId.get(ref.span); return s ? spanLabel(s) : ref.span; };
  const link = (ref, text) => {
    const a = el('a', '', text);
    a.title = `${ref.trace || traceId} / ${ref.span} / ${ref.path}`;
    a.addEventListener('click', (e) => { e.stopPropagation(); jumpTo(ref.trace || traceId, ref.span, jsonPath(ref.path)); });
    return a;
  };
  for (const entry of state.index.lineage) {
    const to = entry.to || {};
    const toTrace = to.trace || traceId;
    if (toTrace === traceId && to.span === spanId) {
      const b = el('span', 'lineage');
      const remainder = locate(to.path).remainder;
      if (entry.from) {
        b.appendChild(document.createTextNode((remainder ? remainder + ' ' : '') + '← '));
        b.appendChild(link(entry.from, `${seqOf(entry.from.trace)} ${labelOf(entry.from)} / ${entry.from.path}`));
        for (const alt of entry.alternatives || []) {
          b.appendChild(document.createTextNode(' · '));
          const altLink = link(alt, `${seqOf(alt.trace)} ${labelOf(alt)} / ${alt.path}`);
          altLink.classList.add('alt');
          b.appendChild(document.createTextNode('also: '));
          b.appendChild(altLink);
        }
        if (entry.alsoProducedHere) b.appendChild(document.createTextNode(' · also produced in this trace'));
      } else {
        b.classList.add('class-' + entry.classification);
        b.textContent = (remainder ? remainder + ' ' : '') + entry.classification;
      }
      b.title = `value ${entry.value}`;
      put(to.path, b);
    }
    if (entry.from && (entry.from.trace || traceId) === traceId && entry.from.span === spanId) {
      const b = el('span', 'lineage uses');
      const remainder = locate(entry.from.path).remainder;
      b.appendChild(document.createTextNode((remainder ? remainder + ' ' : '') + '→ '));
      b.appendChild(link({ trace: toTrace, span: to.span, path: to.path }, `${seqOf(toTrace)} ${labelOf({ trace: toTrace, span: to.span })} / ${to.path}`));
      b.title = `value ${entry.value}`;
      put(entry.from.path, b);
    }
  }
  return out;
}

// ---- helpers ------------------------------------------------------------------------------

function el(tag, cls, text) { const e = document.createElement(tag); if (cls) e.className = cls; if (text !== undefined) e.textContent = text; return e; }
function esc(s) { return String(s).replace(/[&<>"]/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c])); }
function shorten(s, n) { s = s || ''; return s.length > n ? s.substring(0, n) + '…' : s; }

