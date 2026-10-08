'use strict';
// Glassbook UI: no framework, no build step. Market data over WebSocket, trading over REST.
const $ = (id) => document.getElementById(id);
const state = {
  symbol: localStorage.getItem('symbol') || 'GLSS',
  tickers: {}, book: null, trades: [], candles: {5: []}, interval: 5,
  token: sessionStorage.getItem('token'), user: sessionStorage.getItem('user'),
  side: 'BUY', type: 'LIMIT', ws: null, retry: 0, dirty: true,
};

const scale = () => (state.tickers[state.symbol] ? state.tickers[state.symbol].priceScale : 2);
const fmtPrice = (p) => (p / 10 ** scale()).toFixed(scale());
const fmtInt = (n) => Number(n).toLocaleString('en-US');
const time = (ms) => new Date(ms).toLocaleTimeString('en-GB');

// ---------- REST ----------
async function api(path, options = {}) {
  const headers = {'Content-Type': 'application/json'};
  if (state.token) headers.Authorization = 'Bearer ' + state.token;
  const res = await fetch(path, {...options, headers});
  const text = await res.text();
  const body = text ? JSON.parse(text) : null;
  if (res.status === 401 && state.token) { logout(); }
  return {status: res.status, ok: res.ok, body};
}

// ---------- WebSocket ----------
function connect() {
  const proto = location.protocol === 'https:' ? 'wss' : 'ws';
  const ws = new WebSocket(`${proto}://${location.host}/ws/market`);
  state.ws = ws;
  ws.onopen = () => { state.retry = 0; subscribe(); };
  ws.onmessage = (e) => onMessage(JSON.parse(e.data));
  ws.onclose = () => { setTimeout(connect, Math.min(5000, 300 * 2 ** state.retry++)); };
}
function subscribe() {
  if (state.ws && state.ws.readyState === 1) {
    state.ws.send(JSON.stringify({op: 'subscribe', symbol: state.symbol}));
  }
}
function onMessage(m) {
  if (m.symbol !== state.symbol) return;
  if (m.ticker) state.tickers[m.symbol] = m.ticker;
  if (m.book) state.book = m.book;
  if (m.type === 'snapshot') {
    state.trades = m.trades || [];
    state.candles = m.candles || {};
  } else {
    if (m.trades) state.trades = m.trades.slice().reverse().concat(state.trades).slice(0, 60);
    if (m.candles) for (const [iv, c] of Object.entries(m.candles)) mergeCandle(iv, c);
  }
  state.dirty = true;
}
function mergeCandle(iv, c) {
  const list = state.candles[iv] || (state.candles[iv] = []);
  const last = list[list.length - 1];
  if (last && last.time === c.time) list[list.length - 1] = c;
  else if (!last || c.time > last.time) { list.push(c); if (list.length > 300) list.shift(); }
}

// ---------- rendering (one frame at a time, only when something changed) ----------
function renderBook() {
  const b = state.book;
  if (!b) return;
  const asks = b.asks.slice(0, 12), bids = b.bids.slice(0, 12);
  const max = Math.max(1, ...asks.map((l) => l.quantity), ...bids.map((l) => l.quantity));
  const row = (l, cls) => `<div class="lvl ${cls}" data-p="${l.price}"><i style="width:${(l.quantity / max) * 100}%"></i>` +
      `<span>${fmtPrice(l.price)}</span><span>${fmtInt(l.quantity)}</span><span>${fmtInt(l.quantity * l.price / 10 ** scale())}</span></div>`;
  $('asks').innerHTML = asks.map((l) => row(l, 'ask')).join('');
  $('bids').innerHTML = bids.map((l) => row(l, 'bid')).join('');
  const t = state.tickers[state.symbol];
  if (t) {
    const up = t.last >= t.sessionOpen;
    $('mid').innerHTML = `<span class="${up ? 'up' : 'down'}">${fmtPrice(t.last)}</span>`;
    if (b.asks.length && b.bids.length) {
      $('spread').textContent = 'spread ' + fmtPrice(b.asks[0].price - b.bids[0].price);
    }
  }
}
function renderTape() {
  $('tape').innerHTML = state.trades.slice(0, 40).map((t) =>
      `<div class="trade"><span class="${t.takerSide === 'BUY' ? 'up' : 'down'}">${fmtPrice(t.price)}</span>` +
      `<span>${fmtInt(t.quantity)}</span><span>${time(t.timestamp)}</span></div>`).join('');
}
function renderTicker() {
  const t = state.tickers[state.symbol];
  if (!t) return;
  const change = t.sessionOpen ? ((t.last - t.sessionOpen) / t.sessionOpen) * 100 : 0;
  $('ticker').innerHTML = `<span>Last <b>${fmtPrice(t.last)}</b></span>` +
      `<span class="${change >= 0 ? 'up' : 'down'}">${change >= 0 ? '+' : ''}${change.toFixed(2)}%</span>` +
      `<span>Vol <b>${fmtInt(t.volume)}</b></span>` + (t.halted ? '<span class="down">HALTED</span>' : '');
}
function renderChart() {
  const canvas = $('chart');
  const dpr = window.devicePixelRatio || 1;
  const w = canvas.clientWidth, h = canvas.clientHeight;
  if (!w || !h) return;
  if (canvas.width !== w * dpr || canvas.height !== h * dpr) { canvas.width = w * dpr; canvas.height = h * dpr; }
  const g = canvas.getContext('2d');
  g.setTransform(dpr, 0, 0, dpr, 0, 0);
  g.clearRect(0, 0, w, h);
  const data = (state.candles[state.interval] || []).slice(-Math.floor((w - 60) / 7));
  if (data.length < 2) return;
  const lo = Math.min(...data.map((c) => c.low)), hi = Math.max(...data.map((c) => c.high));
  const pad = Math.max(1, (hi - lo) * 0.08), min = lo - pad, max = hi + pad;
  const plotW = w - 60, plotH = h - 20, step = plotW / data.length;
  const y = (p) => plotH - ((p - min) / (max - min)) * plotH + 4;
  const css = getComputedStyle(document.documentElement);
  const up = css.getPropertyValue('--buy'), down = css.getPropertyValue('--sell'), line = css.getPropertyValue('--line'),
      muted = css.getPropertyValue('--muted');
  g.font = '11px ui-monospace, monospace'; g.fillStyle = muted; g.strokeStyle = line; g.lineWidth = 1;
  for (let i = 0; i <= 4; i++) {
    const p = min + ((max - min) * i) / 4, yy = Math.round(y(p)) + 0.5;
    g.beginPath(); g.moveTo(0, yy); g.lineTo(plotW, yy); g.stroke();
    g.fillText(fmtPrice(p), plotW + 6, yy + 4);
  }
  data.forEach((c, i) => {
    const x = i * step + step / 2, col = c.close >= c.open ? up : down;
    g.strokeStyle = col; g.fillStyle = col;
    g.beginPath(); g.moveTo(x, y(c.high)); g.lineTo(x, y(c.low)); g.stroke();
    const top = y(Math.max(c.open, c.close)), bh = Math.max(1, Math.abs(y(c.open) - y(c.close)));
    g.fillRect(x - step * 0.3, top, step * 0.6, bh);
  });
}
function renderAll() { renderBook(); renderTape(); renderTicker(); renderChart(); }
function frame() {
  if (state.dirty) { state.dirty = false; renderAll(); }
  requestAnimationFrame(frame);
}

// ---------- symbols, order form ----------
async function loadSymbols() {
  const r = await api('/api/instruments');
  if (!r.ok) return;
  r.body.forEach((t) => (state.tickers[t.symbol] = t));
  if (!state.tickers[state.symbol]) state.symbol = r.body[0].symbol;
  $('symbols').innerHTML = r.body.map((t) =>
      `<button data-s="${t.symbol}" class="${t.symbol === state.symbol ? 'on' : ''}">${t.symbol}</button>`).join('');
}
$('symbols').addEventListener('click', (e) => {
  const s = e.target.dataset.s;
  if (!s || s === state.symbol) return;
  if (state.ws && state.ws.readyState === 1) state.ws.send(JSON.stringify({op: 'unsubscribe', symbol: state.symbol}));
  state.symbol = s; localStorage.setItem('symbol', s);
  state.book = null; state.trades = []; state.candles = {};
  document.querySelectorAll('#symbols button').forEach((b) => b.classList.toggle('on', b.dataset.s === s));
  $('asks').innerHTML = $('bids').innerHTML = $('tape').innerHTML = '';
  subscribe(); refreshPrivate(); state.dirty = true;
});
$('asks').addEventListener('click', (e) => pickPrice(e));
$('bids').addEventListener('click', (e) => pickPrice(e));
function pickPrice(e) {
  const row = e.target.closest('.lvl');
  if (row) { state.type = 'LIMIT'; syncForm(); $('price').value = fmtPrice(Number(row.dataset.p)); }
}
function segment(id, key) {
  $(id).addEventListener('click', (e) => {
    if (!e.target.dataset.v) return;
    state[key] = e.target.dataset.v; syncForm();
  });
}
segment('side', 'side'); segment('type', 'type');
function syncForm() {
  document.querySelectorAll('#side button').forEach((b) => b.classList.toggle('on', b.dataset.v === state.side));
  document.querySelectorAll('#type button').forEach((b) => b.classList.toggle('on', b.dataset.v === state.type));
  const sell = state.side === 'SELL';
  document.querySelectorAll('#side button').forEach((b) => b.classList.toggle('buy', b.dataset.v === 'BUY'));
  $('submit').textContent = (sell ? 'Sell ' : 'Buy ') + state.symbol;
  $('submit').classList.toggle('sell', sell);
  $('price').disabled = state.type === 'MARKET';
  $('price').placeholder = state.type === 'MARKET' ? 'market' : '';
  if (state.type === 'MARKET' && !['IOC', 'FOK'].includes($('tif').value)) $('tif').value = 'IOC';
}
$('order-form').addEventListener('submit', async (e) => {
  e.preventDefault();
  if (!state.token) { openLogin(); return; }
  const price = state.type === 'MARKET' ? 0 : Math.round(parseFloat($('price').value) * 10 ** scale());
  if (state.type === 'LIMIT' && !(price > 0)) return msg('Enter a price', 'err');
  const body = {symbol: state.symbol, side: state.side, type: state.type, timeInForce: $('tif').value,
    price, quantity: parseInt($('qty').value, 10), clientOrderId: crypto.randomUUID()};
  $('submit').disabled = true;
  try {
    const r = await api('/api/orders', {method: 'POST', body: JSON.stringify(body)});
    const o = r.body || {};
    if (r.status === 422) msg('Rejected: ' + o.rejectReason, 'err');
    else if (!r.ok) msg(o.message || o.error || ('HTTP ' + r.status), 'err');
    else msg(`${o.status}: filled ${o.filledQuantity}` + (o.averagePrice ? ' @ ' + fmtPrice(o.averagePrice) : '') +
        (o.restingQuantity ? `, resting ${o.restingQuantity}` : ''), 'ok');
    refreshPrivate();
  } finally { $('submit').disabled = false; }
});
function msg(text, cls) { const m = $('order-msg'); m.textContent = text; m.className = 'msg ' + (cls || ''); }

// ---------- account and orders ----------
async function refreshPrivate() {
  if (!state.token) { $('account').innerHTML = ''; $('orders').innerHTML = '<p class="muted">Sign in to trade.</p>'; return; }
  const [acc, ord] = await Promise.all([api('/api/account'), api('/api/orders?filter=all&limit=30')]);
  if (acc.ok) {
    const a = acc.body;
    $('account').innerHTML = `<div><span>Cash</span><b>${(a.cashFree / 100).toLocaleString('en-US', {minimumFractionDigits: 2})}</b></div>` +
        `<div><span>Reserved</span><span>${(a.cashHeld / 100).toLocaleString('en-US', {minimumFractionDigits: 2})}</span></div>` +
        a.positions.map((p) => `<div><span>${p.symbol}</span><span>${fmtInt(p.free)}${p.held ? ' (+' + fmtInt(p.held) + ' held)' : ''}</span></div>`).join('');
  }
  if (ord.ok) {
    const rows = ord.body.map((o) => {
      const live = o.status === 'OPEN' || o.status === 'PARTIALLY_FILLED';
      const p = o.price ? (o.price / 100).toFixed(2) : 'mkt';
      return `<tr><td>${o.id}</td><td>${o.symbol}</td><td class="${o.side === 'BUY' ? 'up' : 'down'}">${o.side}</td>` +
          `<td>${p}</td><td>${o.filledQuantity}/${o.quantity}</td><td>${o.status}</td>` +
          `<td>${live ? `<button data-cancel="${o.id}">cancel</button>` : ''}</td></tr>`;
    }).join('');
    $('orders').innerHTML = `<table><thead><tr><th>Id</th><th>Symbol</th><th>Side</th><th>Price</th><th>Filled</th><th>Status</th><th></th></tr></thead><tbody>${rows}</tbody></table>`;
  }
}
$('orders').addEventListener('click', async (e) => {
  const id = e.target.dataset.cancel;
  if (!id) return;
  await api('/api/orders/' + id, {method: 'DELETE'});
  refreshPrivate();
});

// ---------- auth ----------
function renderAuth() {
  $('auth').innerHTML = state.token
      ? `<span class="muted">${state.user}</span> <button id="logout">Sign out</button>`
      : '<button id="signin">Sign in</button>';
  const lo = $('logout'); if (lo) lo.onclick = logout;
  const si = $('signin'); if (si) si.onclick = openLogin;
}
function openLogin() { $('login-msg').textContent = ''; $('login').showModal(); }
async function authenticate(path) {
  const r = await api(path, {method: 'POST', body: JSON.stringify({username: $('u').value, password: $('p').value})});
  if (!r.ok) { $('login-msg').textContent = (r.body && r.body.message) || 'failed'; $('login-msg').className = 'msg err'; return; }
  state.token = r.body.token; state.user = r.body.username;
  sessionStorage.setItem('token', state.token); sessionStorage.setItem('user', state.user);
  $('login').close(); renderAuth(); refreshPrivate();
}
$('do-login').onclick = (e) => { e.preventDefault(); authenticate('/api/auth/login'); };
$('do-register').onclick = (e) => { e.preventDefault(); authenticate('/api/auth/register'); };
function logout() {
  state.token = null; state.user = null; sessionStorage.clear(); renderAuth(); refreshPrivate();
}

// ---------- boot ----------
(async function boot() {
  await loadSymbols();
  syncForm(); renderAuth(); connect(); refreshPrivate();
  setInterval(refreshPrivate, 3000);
  window.addEventListener('resize', () => (state.dirty = true));
  requestAnimationFrame(frame);
})();
