'use strict';

const API = '/api/v1';
const THEME_KEY = 'guardiania.theme';
const ROUTES = ['resumen', 'vincular', 'dispositivos', 'alertas'];

const ICONS = {
  overview: '<rect x="3" y="3" width="7" height="7" rx="1.5"/><rect x="14" y="3" width="7" height="7" rx="1.5"/><rect x="3" y="14" width="7" height="7" rx="1.5"/><rect x="14" y="14" width="7" height="7" rx="1.5"/>',
  link: '<path d="M10.5 13.5a4.5 4.5 0 0 0 6.4 0l2.1-2.1a4.5 4.5 0 0 0-6.4-6.4l-1.2 1.2"/><path d="M13.5 10.5a4.5 4.5 0 0 0-6.4 0l-2.1 2.1a4.5 4.5 0 0 0 6.4 6.4l1.2-1.2"/>',
  device: '<rect x="6.5" y="2.5" width="11" height="19" rx="2.5"/><path d="M10.5 5.5h3M11 18.5h2"/>',
  bell: '<path d="M18 8a6 6 0 0 0-12 0c0 7-3 7-3 9h18c0-2-3-2-3-9"/><path d="M10.3 21a2 2 0 0 0 3.4 0"/>',
  lock: '<rect x="4.5" y="10" width="15" height="10.5" rx="2.5"/><path d="M8 10V7a4 4 0 0 1 8 0v3"/>',
  plus: '<path d="M12 5v14M5 12h14"/>',
  moon: '<path d="M20 14.5A8.5 8.5 0 0 1 9.5 4 8.5 8.5 0 1 0 20 14.5Z"/>',
  sun: '<circle cx="12" cy="12" r="4"/><path d="M12 2v2M12 20v2M4.9 4.9l1.4 1.4M17.7 17.7l1.4 1.4M2 12h2M20 12h2M4.9 19.1l1.4-1.4M17.7 6.3l1.4-1.4"/>',
  logout: '<path d="M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4"/><path d="m16 17 5-5-5-5M21 12H9"/>',
  check: '<path d="m5 12 4.5 4.5L19 7"/>',
  copy: '<rect x="8" y="8" width="12" height="12" rx="2"/><path d="M16 8V6a2 2 0 0 0-2-2H6a2 2 0 0 0-2 2v8a2 2 0 0 0 2 2h2"/>',
  clock: '<circle cx="12" cy="12" r="9"/><path d="M12 7v5l3 2"/>',
  arrow: '<path d="M5 12h14M13 6l6 6-6 6"/>',
  shield: '<path d="M12 3 5 6v6c0 4.5 3 7.5 7 9 4-1.5 7-4.5 7-9V6l-7-3Z"/><path d="m9 12 2 2 4-4"/>',
  alert: '<path d="M12 3 2.5 20h19L12 3Z"/><path d="M12 9v5M12 17.2v.1"/>',
  refresh: '<path d="M20 7v5h-5"/><path d="M4 17v-5h5"/><path d="M6.1 9a7 7 0 0 1 11.4-2L20 9M4 15l2.5 2A7 7 0 0 0 18 15"/>',
};

const state = {
  tutor: null,
  route: ROUTES[0],
  overview: null,
  alerts: [],
  devices: [],
  pairing: null,
  socket: null,
  stream: null,
  streamTimer: null,
  streamRetries: 0,
  autoTimer: null,
  signature: '',
  lastRefreshAt: 0,
  streamConnecting: false,
  streamError: null,
  poll: null,
  ticker: null,
  filter: 'TODAS',
  toastTimer: null,
  booting: true,
};

const $ = (selector, root = document) => root.querySelector(selector);
const $$ = (selector, root = document) => Array.from(root.querySelectorAll(selector));

function icon(name, size = 20) {
  return `<svg viewBox="0 0 24 24" width="${size}" height="${size}" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">${ICONS[name] || ''}</svg>`;
}

function escapeHtml(value) {
  return String(value ?? '').replace(/[&<>"']/g, (character) => (
    { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[character]
  ));
}

function escapeAttr(value) {
  return escapeHtml(value).replace(/`/g, '&#96;');
}

function readCookie(name) {
  const prefix = `${name}=`;
  return document.cookie.split(';').map((part) => part.trim()).find((part) => part.startsWith(prefix))?.slice(prefix.length) || '';
}

function csrfToken() {
  return decodeURIComponent(readCookie('guardiania_csrf'));
}

let refreshPromise = null;

async function refreshOnce() {
  try {
    const response = await fetch(`${API}/auth/refresh`, {
      method: 'POST',
      credentials: 'same-origin',
      headers: { Accept: 'application/json', 'X-CSRF-Token': csrfToken() },
    });
    if (response.ok) return 'ok';
    if (response.status === 401 || response.status === 403) return 'expired';
    return 'error';
  } catch {
    return 'error';
  }
}

async function refreshSession() {
  if (refreshPromise) return refreshPromise;
  // Serialize refreshes within the tab and, when available, across tabs.
  // Refresh tokens rotate, so two simultaneous requests would invalidate one.
  const task = navigator.locks?.request
    ? navigator.locks.request('guardiania-session-refresh', refreshOnce)
    : refreshOnce();
  refreshPromise = task.finally(() => {
    refreshPromise = null;
  });
  return refreshPromise;
}

async function api(path, { method = 'GET', body, auth = true, retry = true } = {}) {
  const headers = { Accept: 'application/json' };
  if (body !== undefined) headers['Content-Type'] = 'application/json';
  const unsafe = !['GET', 'HEAD', 'OPTIONS'].includes(method);
  if (unsafe && csrfToken()) headers['X-CSRF-Token'] = csrfToken();

  const response = await fetch(`${API}${path}`, {
    method,
    headers,
    credentials: 'same-origin',
    body: body === undefined ? undefined : JSON.stringify(body),
  });

  if (response.status === 401 && auth && retry) {
    const refreshed = await refreshSession();
    if (refreshed === 'ok') {
      return api(path, { method, body, auth, retry: false });
    }
    if (refreshed === 'expired') {
      await logout(false, false);
      throw new Error('Tu sesión expiró. Vuelve a entrar.');
    }
    throw new Error('No pudimos renovar la sesión. Revisa tu conexión e inténtalo otra vez.');
  }
  if (response.status === 401 && auth) {
    await logout(false, false);
    throw new Error('Tu sesión expiró. Vuelve a entrar.');
  }

  const data = response.status === 204 ? null : await response.json().catch(() => null);
  if (!response.ok) {
    let message = 'No pudimos completar la acción.';
    if (typeof data?.detail === 'string') message = data.detail;
    else if (Array.isArray(data?.detail) && data.detail[0]?.msg) message = data.detail[0].msg;
    const error = new Error(message);
    error.status = response.status;
    throw error;
  }
  return data;
}

function toast(message, tone = 'default') {
  const node = $('#toast');
  node.textContent = message;
  node.dataset.tone = tone;
  node.classList.add('is-visible');
  clearTimeout(state.toastTimer);
  state.toastTimer = setTimeout(() => node.classList.remove('is-visible'), 3200);
}

function firstName(alias) {
  return String(alias || '').trim().split(/\s+/)[0] || 'tutor';
}

function initials(alias) {
  return String(alias || 'IA')
    .trim()
    .split(/\s+/)
    .slice(0, 2)
    .map((part) => part[0]?.toUpperCase() || '')
    .join('') || 'IA';
}

const DATE_FORMAT = new Intl.DateTimeFormat('es-CL', { weekday: 'long', day: 'numeric', month: 'long' });
const TIME_FORMAT = new Intl.DateTimeFormat('es-CL', { hour: '2-digit', minute: '2-digit', hour12: false });
const NUMBER_FORMAT = new Intl.NumberFormat('es-CL');

function todayLabel() {
  const label = DATE_FORMAT.format(new Date());
  return label.charAt(0).toUpperCase() + label.slice(1);
}

function timeLabel(iso) {
  if (!iso) return '—';
  return TIME_FORMAT.format(new Date(iso));
}

function relativeLabel(iso) {
  if (!iso) return 'sin sincronizar';
  const seconds = Math.max(0, (Date.now() - new Date(iso).getTime()) / 1000);
  if (seconds < 60) return 'hace un momento';
  if (seconds < 3600) return `hace ${Math.floor(seconds / 60)} min`;
  if (seconds < 86400) return `hace ${Math.floor(seconds / 3600)} h`;
  if (seconds < 172800) return 'ayer';
  const days = Math.floor(seconds / 86400);
  return `hace ${days} días`;
}

function severityLabel(severity) {
  return { CRITICA: 'Crítica', ALTA: 'Alta', MEDIA: 'Media', BAJA: 'Baja' }[severity] || severity;
}

function alertRuleLabel(alert) {
  const rule = alert.reglas?.[0];
  const code = String(alert.categoria || '').trim().toLowerCase();
  // Una alerta de IA guarda la señal concreta en `categoria`; se prefiere ese
  // nombre ("Sextorsión") antes que la categoría genérica del modelo.
  if (rule?.source === 'ai' && code && !code.startsWith('ai_')) {
    const label = prettyAlertLabel(code);
    if (label && label !== code) return label.charAt(0).toUpperCase() + label.slice(1);
  }
  return rule?.label || alert.categoria;
}

function alertStateLabel(estado) {
  return {
    NUEVA: 'Sin revisar',
    VISTA: 'Vista',
    RECONOCIDA: 'Reconocida',
    DESCARTADA: 'Descartada',
  }[estado] || estado;
}

function statusBadgeLabel(tone) {
  return { calma: 'En calma', atencion: 'Requiere atención', critico: 'Crítico' }[tone] || 'Estado';
}

function themeIsDark() {
  return document.documentElement.dataset.theme === 'dark';
}

function applyTheme(theme, persist = true) {
  document.documentElement.dataset.theme = theme;
  if (persist) localStorage.setItem(THEME_KEY, theme);
  const toggle = $('#theme-toggle');
  if (toggle) toggle.innerHTML = icon(theme === 'dark' ? 'sun' : 'moon');
}

function initTheme() {
  const saved = localStorage.getItem(THEME_KEY);
  const preferred = window.matchMedia?.('(prefers-color-scheme: dark)').matches ? 'dark' : 'light';
  applyTheme(saved || preferred, false);
}

/* ---------- Auth ---------- */

let authMode = 'login';

function showAuth(mode = 'login') {
  authMode = mode;
  $('#auth').hidden = false;
  $('#shell').hidden = true;
  setAuthMode(mode);
}

function setAuthMode(mode) {
  authMode = mode;
  const registering = mode === 'register';
  $('#auth-title').textContent = registering ? 'Crear cuenta' : 'Entrar';
  $('#auth-sub').textContent = registering
    ? 'Crea tu cuenta de tutor para vincular el primer dispositivo.'
    : 'Tu panel de tutor. Sin contenido de conversaciones.';
  $('#alias-field').hidden = !registering;
  $('#consent-field').hidden = !registering;
  $('#auth-submit').textContent = registering ? 'Crear cuenta' : 'Entrar';
  const errorBox = $('#auth-error');
  errorBox.textContent = '';
  delete errorBox.dataset.tone;
  $$('.segmented button').forEach((button) => {
    button.setAttribute('aria-selected', String(button.dataset.authMode === mode));
  });
  const password = $('#auth-form [name="password"]');
  password.autocomplete = registering ? 'new-password' : 'current-password';
}

function showApp() {
  $('#auth').hidden = true;
  $('#shell').hidden = false;
  const tutor = state.tutor;
  $('#tutor-name').textContent = tutor.alias;
  $('#tutor-email').textContent = tutor.email;
  $('#tutor-avatar').textContent = initials(tutor.alias);
}

async function logout(notify = true, notifyServer = true) {
  closePairingLive();
  closeTutorStream();
  stopAutoRefresh();
  if (notifyServer) {
    try {
      await fetch(`${API}/auth/logout`, {
        method: 'POST',
        credentials: 'same-origin',
        headers: { Accept: 'application/json', 'X-CSRF-Token': csrfToken() },
      });
    } catch {
      // The local session is cleared even when the network is unavailable.
    }
  }
  state.tutor = null;
  state.overview = null;
  state.alerts = [];
  state.devices = [];
  state.pairing = null;
  showAuth('login');
  if (notify) toast('Sesión cerrada');
}

async function submitAuth(event) {
  event.preventDefault();
  const form = event.target;
  const errorBox = $('#auth-error');
  const submit = $('#auth-submit');
  errorBox.textContent = '';
  const data = new FormData(form);
  const email = String(data.get('email') || '').trim();
  const password = String(data.get('password') || '');
  const alias = String(data.get('alias') || '').trim();
  const consent = Boolean(data.get('consent'));

  if (!email.includes('@')) {
    errorBox.textContent = 'Escribe un correo válido.';
    return;
  }
  if (password.length < 12) {
    errorBox.textContent = 'La contraseña debe tener al menos 12 caracteres.';
    return;
  }
  if (authMode === 'register' && alias.length < 2) {
    errorBox.textContent = 'Escribe cómo quieres aparecer.';
    return;
  }
  if (authMode === 'register' && !consent) {
    errorBox.textContent = 'Necesitamos tu consentimiento para crear la cuenta.';
    return;
  }

  submit.disabled = true;
  submit.textContent = authMode === 'register' ? 'Creando…' : 'Entrando…';
  try {
    const payload = authMode === 'register'
      ? { email, password, alias, consent }
      : { email, password };
    const response = await api(`/auth/${authMode === 'register' ? 'register' : 'login'}`, {
      method: 'POST',
      body: payload,
      auth: false,
    });
    if (response?.status === 'VERIFICAR_CORREO') {
      form.reset();
      setAuthMode('login');
      errorBox.dataset.tone = 'success';
      errorBox.textContent = response.message || 'Revisa tu correo para activar la cuenta.';
      return;
    }
    state.tutor = response.tutor;
    form.reset();
    if (!location.hash) location.hash = '#/resumen';
    showApp();
    await enterApp();
  } catch (error) {
    errorBox.textContent = error.message;
  } finally {
    submit.disabled = false;
    submit.textContent = authMode === 'register' ? 'Crear cuenta' : 'Entrar';
  }
}

async function demoLogin() {
  const button = $('#demo-login');
  button.disabled = true;
  button.textContent = 'Entrando…';
  try {
    const response = await api('/auth/login', {
      method: 'POST',
      body: { email: 'demo@guardiania.cl', password: 'GuardianIA2026' },
      auth: false,
    });
    state.tutor = response.tutor;
    showApp();
    await enterApp();
  } catch (error) {
    toast(error.message);
  } finally {
    button.disabled = false;
    button.textContent = 'Entrar con la cuenta demo';
  }
}

/* ---------- Routing ---------- */

function routeFromHash() {
  const route = location.hash.replace(/^#\/?/, '');
  return ROUTES.includes(route) ? route : 'resumen';
}

function markNavigation(route) {
  $$('.nav a, .tabbar a').forEach((link) => {
    if (!link.dataset.route) return;
    if (link.dataset.route === route) link.setAttribute('aria-current', 'page');
    else link.removeAttribute('aria-current');
  });
}

function updateTopbar(route) {
  const date = $('#top-eyebrow');
  const title = $('#top-title');
  const shortcut = $('#pair-shortcut');
  const tutor = state.tutor;
  shortcut.hidden = route === 'vincular';
  if (route === 'resumen') {
    date.textContent = todayLabel();
    title.textContent = `Hola, ${firstName(tutor?.alias)}.`;
  } else if (route === 'vincular') {
    date.textContent = 'Vinculación';
    title.textContent = 'Vincular un dispositivo';
  } else if (route === 'dispositivos') {
    date.textContent = 'Inventario';
    title.textContent = 'Dispositivos';
  } else {
    date.textContent = 'Revisión';
    title.textContent = 'Alertas';
  }
  updateSyncChip();
}

function updateSyncChip() {
  const chip = $('#sync-chip');
  const devices = state.devices.filter((device) => device.estado === 'ACTIVO');
  if (!devices.length) {
    chip.classList.add('is-stale');
    chip.lastElementChild.textContent = 'Sin dispositivos';
    return;
  }
  const latest = devices
    .map((device) => device.ultima_conexion)
    .filter(Boolean)
    .sort()
    .pop();
  if (!latest) {
    chip.classList.add('is-stale');
    chip.lastElementChild.textContent = 'Sin sincronizar';
    return;
  }
  const stale = Date.now() - new Date(latest).getTime() > 2 * 60 * 60 * 1000;
  chip.classList.toggle('is-stale', stale);
  chip.lastElementChild.textContent = `Sincronizado ${relativeLabel(latest)}`;
}

async function navigate() {
  if (!state.tutor) return;
  const route = routeFromHash();
  const changed = route !== state.route;
  state.route = route;
  markNavigation(route);
  updateTopbar(route);
  if (route !== 'vincular') closePairingLive();

  const view = $('#view');
  if (changed) view.focus({ preventScroll: true });

  if (route === 'resumen') {
    if (!state.overview) {
      view.innerHTML = loadingBlock('Cargando el resumen…');
      await loadCore();
    }
    renderOverview();
  } else if (route === 'vincular') {
    renderPairing();
  } else if (route === 'dispositivos') {
    view.innerHTML = loadingBlock('Cargando dispositivos…');
    await loadDevices();
    renderDevices();
  } else if (route === 'alertas') {
    view.innerHTML = loadingBlock('Cargando alertas…');
    await loadAlerts();
    renderAlerts();
  }
  state.signature = viewSignature();
}

function loadingBlock(label) {
  return `<div class="card loading-block"><span class="pulse"></span><p>${escapeHtml(label)}</p></div>`;
}

async function enterApp() {
  await loadCore();
  if (!location.hash) location.hash = '#/resumen';
  await navigate();
  state.signature = viewSignature();
  startAutoRefresh();
  connectTutorStream();
}

async function loadCore() {
  const [overview, devices] = await Promise.all([api('/overview'), api('/devices')]);
  state.overview = overview;
  state.devices = devices;
  updateAlertBadges(overview.open_alerts);
  updateSyncChip();
}

async function loadDevices() {
  state.devices = await api('/devices');
  updateSyncChip();
}

async function loadAlerts() {
  state.alerts = await api('/alerts?limit=200');
  if (state.overview) updateAlertBadges(state.overview.open_alerts);
}

async function refreshCore() {
  await loadCore();
  if (state.route === 'alertas') state.alerts = await api('/alerts?limit=200');
}

/* ---------- Actualización automática ---------- */

const AUTO_REFRESH_MS = 15000;
const STREAM_RETRY_MAX_MS = 30000;

function viewSignature() {
  if (state.route === 'alertas') {
    // El resumen general cambia cada 15 s aunque las alertas no; no debe
    // provocar un repintado que cierre los detalles abiertos.
    return JSON.stringify(
      state.alerts.map((alert) => [alert.id, alert.estado, alert.severidad, alert.categoria, alert.resumen]),
    );
  }
  if (state.route === 'dispositivos') {
    return JSON.stringify(state.devices);
  }
  return JSON.stringify({
    overview: state.overview,
    alerts: state.alerts.map((alert) => [alert.id, alert.estado]),
    devices: state.devices.map((device) => [device.id, device.estado, device.ultima_conexion]),
  });
}

function openAlertIds() {
  return new Set(
    Array.from(document.querySelectorAll('.alert-why[open]'))
      .map((node) => node.dataset.alertId)
      .filter(Boolean),
  );
}

function restoreOpenAlertIds(ids) {
  if (!ids.size) return;
  document.querySelectorAll('.alert-why').forEach((node) => {
    if (ids.has(node.dataset.alertId)) node.open = true;
  });
}

function expandedSummaryIds() {
  return new Set(
    Array.from(document.querySelectorAll('#view .alert-summary.is-expanded'))
      .map((node) => node.closest('.alert-summary-block')?.dataset.alertId)
      .filter(Boolean),
  );
}

function restoreExpandedSummaries(ids) {
  if (!ids.size) return;
  document.querySelectorAll('#view .alert-summary-block').forEach((block) => {
    if (!ids.has(block.dataset.alertId)) return;
    const summary = block.querySelector('.alert-summary');
    const button = block.querySelector('[data-action="expand-alert-summary"]');
    if (summary) summary.classList.add('is-expanded');
    if (button) button.textContent = 'Ver menos';
  });
}

function enhanceCompactSummaries() {
  document.querySelectorAll('#view .alert-summary-block').forEach((block) => {
    const summary = block.querySelector('.alert-summary');
    const button = block.querySelector('[data-action="expand-alert-summary"]');
    if (!summary || !button) return;
    const expanded = summary.classList.contains('is-expanded');
    const overflowing = summary.scrollHeight > summary.clientHeight + 1;
    button.hidden = !overflowing && !expanded;
    button.textContent = expanded ? 'Ver menos' : 'Ver mensaje completo';
    if (!overflowing && !expanded) summary.classList.remove('is-expanded');
  });
}

function rerenderCurrent() {
  const view = $('#view');
  if (!view || document.hidden) return;
  if (document.querySelector('dialog[open]')) return;

  const scroll = window.scrollY;
  if (state.route === 'resumen' && state.overview) renderOverview();
  else if (state.route === 'alertas') renderAlerts();
  else if (state.route === 'dispositivos') renderDevices();
  else return;
  window.scrollTo(0, scroll);
}

let refreshing = false;

async function refreshFromServer({ force = false } = {}) {
  if (!state.tutor || refreshing) return;
  const now = Date.now();
  if (!force && now - state.lastRefreshAt < 4000) return;
  state.lastRefreshAt = now;
  refreshing = true;
  try {
    await refreshCore();
    const signature = viewSignature();
    if (force || signature !== state.signature) {
      state.signature = signature;
      rerenderCurrent();
    }
  } catch {
    // Un fallo puntual no debe romper la vista: el próximo ciclo reintenta.
  } finally {
    refreshing = false;
  }
}

function startAutoRefresh() {
  stopAutoRefresh();
  state.autoTimer = setInterval(() => {
    if (document.hidden) return;
    // Autocuración: si el socket quedó caído, se reintenta en cada ciclo.
    if (!state.stream) connectTutorStream();
    refreshFromServer();
  }, AUTO_REFRESH_MS);
  document.addEventListener('visibilitychange', onVisibilityChange);
  window.addEventListener('focus', onWindowFocus);
  window.addEventListener('resize', onWindowResize);
}

function stopAutoRefresh() {
  clearInterval(state.autoTimer);
  state.autoTimer = null;
  document.removeEventListener('visibilitychange', onVisibilityChange);
  window.removeEventListener('focus', onWindowFocus);
  window.removeEventListener('resize', onWindowResize);
}

function onVisibilityChange() {
  if (document.hidden) return;
  if (!state.stream && state.tutor) connectTutorStream();
  refreshFromServer();
}

function onWindowFocus() {
  if (!document.hidden) refreshFromServer();
}

function onWindowResize() {
  if (!document.hidden && state.route === 'resumen') enhanceCompactSummaries();
}

async function connectTutorStream() {
  if (!state.tutor || state.stream || state.streamConnecting || document.hidden) return;
  state.streamConnecting = true;
  updateLiveChip('retry');
  try {
    const ticket = await api('/realtime/ticket', { method: 'POST' });
    const scheme = location.protocol === 'https:' ? 'wss' : 'ws';
    const socket = new WebSocket(
      `${scheme}://${location.host}${ticket.ws_url}`,
      ['guardiania', ticket.ws_ticket],
    );
    state.stream = socket;
    socket.onopen = () => {
      state.streamRetries = 0;
      state.streamError = null;
      updateLiveChip('live');
      // Al (re)conectar puede haber alertas de mientras estaba caído.
      refreshFromServer({ force: true });
    };
    socket.onmessage = (event) => {
      let message;
      try { message = JSON.parse(event.data); } catch { return; }
      if (message.type === 'risk_alert') handleLiveAlert(message.data);
      else if (message.type === 'device_reconnected') handleReconnected(message.data);
      else if (message.type === 'device_status') handleDeviceStatus(message.data);
    };
    socket.onclose = () => {
      if (state.stream !== socket) return;
      state.stream = null;
      updateLiveChip(state.tutor ? 'retry' : 'off');
      if (state.tutor) scheduleTutorReconnect();
    };
    socket.onerror = () => {
      // onclose programa el reintento.
    };
  } catch (error) {
    state.streamError = String(error?.message || error);
    updateLiveChip('retry');
    scheduleTutorReconnect();
  } finally {
    state.streamConnecting = false;
  }
}

function scheduleTutorReconnect() {
  clearTimeout(state.streamTimer);
  const attempt = Math.min(state.streamRetries + 1, 5);
  state.streamRetries = attempt;
  const delay = Math.min(STREAM_RETRY_MAX_MS, 2000 * 2 ** (attempt - 1));
  state.streamTimer = setTimeout(() => {
    if (!document.hidden) connectTutorStream();
  }, delay);
}

function closeTutorStream() {
  clearTimeout(state.streamTimer);
  state.streamTimer = null;
  state.streamRetries = 0;
  updateLiveChip('off');
  if (state.stream) {
    const socket = state.stream;
    state.stream = null;
    try { socket.close(); } catch { /* noop */ }
  }
}

function updateLiveChip(status) {
  const chip = $('#live-chip');
  if (!chip) return;
  chip.dataset.state = status;
  chip.classList.toggle('is-stale', status !== 'live');
  chip.lastElementChild.textContent = status === 'live'
    ? 'En vivo'
    : status === 'retry'
      ? 'Reconectando'
      : 'Sin conexión';
}

function handleLiveAlert(data) {
  if (!data) return;
  const who = data.etiqueta_menor || data.device_alias || 'el teléfono';
  const tone = ['ALTA', 'CRITICA'].includes(data.severidad) ? 'danger' : 'default';
  toast(`Nueva señal de ${who}: ${prettyAlertLabel(data.categoria)}`, tone);
  // El evento no trae las reglas completas: se recarga para pintar la alerta
  // igual que por REST, sin inventar campos.
  refreshFromServer({ force: true });
  if (state.overview) {
    state.overview.open_alerts += 1;
    if (data.severidad === 'CRITICA') state.overview.critical_alerts += 1;
    updateAlertBadges(state.overview.open_alerts);
  }
}

function handleReconnected(data) {
  if (!data) return;
  const who = data.etiqueta_menor || data.device_alias || 'El teléfono';
  toast(`${who} volvió a conectarse`);
  refreshFromServer({ force: true });
}

function handleDeviceStatus(data) {
  if (!data) return;
  const who = data.etiqueta_menor || data.device_alias || 'El teléfono';
  if (data.notificaciones_activas) {
    toast(`${who}: acceso a notificaciones restaurado`);
  }
  refreshFromServer({ force: true });
}

function prettyAlertLabel(code) {
  const labels = {
    sin_senal: 'teléfono sin señal',
    acceso_desactivado: 'acceso a notificaciones desactivado',
    self_harm: 'posible autolesión',
    grooming: 'posible grooming',
    sextortion: 'sextorsión',
    abuse: 'abuso o maltrato',
    trafficking: 'trata o explotación',
    radicalization: 'radicalización',
    sexual_content: 'contenido sexual',
    sexual_harassment: 'acoso sexual',
    violence: 'violencia o amenazas',
    threat: 'amenaza',
    weapons: 'armas',
    coercion: 'presión o chantaje',
    bullying: 'acoso escolar',
    hate_speech: 'discurso de odio',
    insult_severe: 'insulto grave',
    insult_moderate: 'insulto moderado',
    insult_mild: 'insulto leve',
    eating_disorder: 'trastorno alimentario',
    doxxing: 'datos expuestos',
    stalking: 'acecho',
    runaway: 'riesgo de fuga',
    dangerous_challenge: 'reto peligroso',
    drugs: 'drogas o alcohol',
    gambling: 'apuestas',
    malestar: 'malestar emocional',
    scam_points: 'canje de puntos',
    scam_casino: 'invitación a casino',
    scam_financial: 'inversión sospechosa',
    scam_account: 'phishing o suplantación',
    scam_prize: 'premio sospechoso',
    scam_delivery: 'envío sospechoso',
    scam_job: 'trabajo sospechoso',
    scam_romance: 'estafa romántica',
    scam: 'posible estafa',
    unknown_contact: 'contacto desconocido',
    uso_nocturno: 'uso nocturno',
    uso_prolongado: 'uso prolongado',
    contacto_recurrente: 'contacto recurrente',
  };
  return labels[code] || String(code || '').replace(/^ai_/, '').replaceAll('_', ' ');
}

function updateAlertBadges(count) {
  const nav = $('#nav-alert-count');
  const tab = $('#tab-alert-count');
  const label = count > 99 ? '99+' : String(count);
  nav.textContent = label;
  tab.textContent = label;
  nav.hidden = !count;
  tab.hidden = !count;
}

/* ---------- Overview ---------- */

function renderOverview() {
  const overview = state.overview;
  if (!overview) return;
  const devices = overview.devices.filter((device) => device.estado === 'ACTIVO');
  const names = devices.map((device) => device.etiqueta_menor || device.alias);
  const noDevices = devices.length === 0;
  const latest = devices.map((device) => device.ultima_conexion).filter(Boolean).sort().pop();

  const summary = noDevices
    ? 'Aún no hay teléfonos vinculados. El primero toma menos de un minuto.'
    : `<strong>${escapeHtml(names.join(' y '))}</strong> ${devices.length === 1 ? 'está vinculado' : 'están vinculados'} · última sincronización ${escapeHtml(relativeLabel(latest))}.`;

  const heroTitle = noDevices ? 'Vincula el primer teléfono.' : overview.status_line;
  const heroTone = noDevices ? 'calma' : overview.status_tone;

  const statMinutes = statCard({
    label: 'Minutos hoy',
    value: NUMBER_FORMAT.format(overview.minutes_today),
    unit: 'min',
    note: `Últimos 7 días: ${Math.round(overview.week.reduce((total, day) => total + day.minutes, 0) / 60)} h`,
    series: overview.week.map((day) => day.minutes),
  });

  const statAlerts = statCard({
    label: 'Señales abiertas',
    value: NUMBER_FORMAT.format(overview.open_alerts),
    unit: overview.open_alerts === 1 ? 'señal' : 'señales',
    note: overview.critical_alerts
      ? `${overview.critical_alerts} crítica${overview.critical_alerts === 1 ? '' : 's'} sin revisar`
      : 'Ninguna crítica sin revisar',
    series: overview.week.map((day) => day.alerts),
    alert: overview.open_alerts > 0,
    seriesAlert: true,
  });

  const statDevices = `
    <article class="stat card">
      <p class="stat-label">Dispositivos activos</p>
      <div class="stat-line"><span class="stat-value">${overview.devices_active}</span><span class="stat-unit">de ${overview.devices.length}</span></div>
      <div class="device-mini">${
        devices.length
          ? devices.slice(0, 3).map((device) => `<span><i class="dot"></i>${escapeHtml(device.etiqueta_menor || device.alias)}</span>`).join('')
          : '<span>Sin teléfonos vinculados</span>'
      }</div>
    </article>`;

  const recentAlerts = [...overview.alerts]
    .sort((a, b) => {
      const openA = ['NUEVA', 'VISTA'].includes(a.estado) ? 0 : 1;
      const openB = ['NUEVA', 'VISTA'].includes(b.estado) ? 0 : 1;
      return openA - openB || new Date(b.creado_en) - new Date(a.creado_en);
    })
    .slice(0, 4)
    .map((alert) => alertRow(alert, { compact: true }))
    .join('');
  const deviceRows = devices.length
    ? devices.map(compactDeviceRow).join('')
    : `<div class="alert-empty"><strong>Sin dispositivos</strong>Vincula el teléfono de tu hijo o hija para empezar.</div>`;

  const openDetails = openAlertIds();
  const expandedSummaries = expandedSummaryIds();
  $('#view').innerHTML = `
    <section class="hero">
      <div class="hero-copy">
        <span class="status-badge status-badge--${heroTone}"><span class="dot"></span>${statusBadgeLabel(heroTone)}</span>
        <h2>${escapeHtml(heroTitle)}</h2>
        <p class="hero-sub">${summary}</p>
      </div>
      <div class="hero-action">
        ${noDevices ? `<button class="btn btn-primary" type="button" data-action="go-pair">${icon('link', 17)} Vincular el primer teléfono</button>` : ''}
      </div>
    </section>

    ${guardStrip(overview)}

    <div class="stats">${statMinutes}${statAlerts}${statDevices}</div>

    <div class="board">
      <section class="panel card" aria-labelledby="recent-alerts-title">
        <header class="card-head">
          <h2 id="recent-alerts-title">Señales recientes</h2>
          <a class="link-btn" href="#/alertas">Ver todas</a>
        </header>
        <div class="panel-body">
          ${recentAlerts || `<div class="alert-empty"><strong>Todo en calma</strong>No hay señales que revisar en este momento.</div>`}
        </div>
      </section>

      <aside class="panel card" aria-labelledby="devices-title">
        <header class="card-head">
          <h2 id="devices-title">Dispositivos</h2>
          <a class="link-btn" href="#/dispositivos">Gestionar</a>
        </header>
        <div class="device-list">${deviceRows}</div>
      </aside>
    </div>
  `;
  restoreOpenAlertIds(openDetails);
  restoreExpandedSummaries(expandedSummaries);
  enhanceCompactSummaries();
  requestAnimationFrame(enhanceCompactSummaries);
}

function statCard({ label, value, unit, note, series, alert = false, seriesAlert = false }) {
  const max = Math.max(...series, 1);
  const bars = series
    .map((item, index) => {
      const height = Math.max(3, Math.round((item / max) * 26));
      const classes = [index === series.length - 1 ? 'is-today' : '', seriesAlert && item > 0 ? 'is-alert' : '']
        .filter(Boolean)
        .join(' ');
      return `<i class="${classes}" style="height:${height}px"></i>`;
    })
    .join('');
  return `
    <article class="stat card">
      <p class="stat-label">${escapeHtml(label)}</p>
      <div class="stat-line">
        <span class="stat-value ${alert ? 'is-alert' : ''}">${escapeHtml(value)}</span>
        <span class="stat-unit">${escapeHtml(unit)}</span>
      </div>
      <p class="stat-note">${escapeHtml(note)}</p>
      <div class="spark" aria-hidden="true">${bars}</div>
    </article>`;
}

function guardStrip(overview) {
  const marks = overview.guard_marks || [];
  const buckets = Array.from({ length: 24 }, (_, hour) => ({
    hour,
    activity: 0,
    attention: 0,
    alert: 0,
  }));
  const counts = { activity: 0, attention: 0, alert: 0 };

  for (const mark of marks) {
    const hour = new Date(mark.at).getHours();
    if (!Number.isFinite(hour)) continue;
    const bucket = buckets[hour];
    if (mark.kind !== 'alerta') {
      bucket.activity += 1;
      counts.activity += 1;
    } else if (['MEDIA', 'BAJA'].includes(mark.severity)) {
      bucket.attention += 1;
      counts.attention += 1;
    } else {
      bucket.alert += 1;
      counts.alert += 1;
    }
  }

  const maxHour = Math.max(1, ...buckets.map((bucket) => bucket.activity + bucket.attention + bucket.alert));
  const columns = buckets
    .map((bucket) => {
      const total = bucket.activity + bucket.attention + bucket.alert;
      const height = total ? Math.max(7, (total / maxHour) * 100) : 0;
      const label = `${String(bucket.hour).padStart(2, '0')}:00 · ${bucket.activity} actividad · ${bucket.attention} atención · ${bucket.alert} alerta`;
      return `<div class="guard-col${bucket.hour < 6 ? ' is-night' : ''}" title="${escapeAttr(label)}" aria-label="${escapeAttr(label)}">
        ${total ? `<div class="guard-col-bars" style="height:${height.toFixed(1)}%">
          ${bucket.activity ? `<span class="guard-bar guard-bar--activity" style="flex:${bucket.activity}"></span>` : ''}
          ${bucket.attention ? `<span class="guard-bar guard-bar--attention" style="flex:${bucket.attention}"></span>` : ''}
          ${bucket.alert ? `<span class="guard-bar guard-bar--alert" style="flex:${bucket.alert}"></span>` : ''}
        </div>` : ''}
      </div>`;
    })
    .join('');

  const total = counts.activity + counts.attention + counts.alert;
  const summary = `Actividad: ${counts.activity}. Atención: ${counts.attention}. Alertas: ${counts.alert}.`;

  return `
    <section class="guard card" aria-labelledby="guard-title">
      <div class="guard-head">
        <div class="guard-title">
          <h2 id="guard-title">Guardia de hoy</h2>
          <span>${total} ${total === 1 ? 'marca' : 'marcas'} · últimas 24 h</span>
        </div>
        <div class="guard-stats" role="list" aria-label="Resumen de la guardia">
          <div class="guard-stat guard-stat--activity" role="listitem">
            <i class="guard-stat-dot" aria-hidden="true"></i>
            <strong>${counts.activity}</strong>
            <span>Actividad</span>
          </div>
          <div class="guard-stat guard-stat--attention" role="listitem">
            <i class="guard-stat-dot" aria-hidden="true"></i>
            <strong>${counts.attention}</strong>
            <span>Atención</span>
          </div>
          <div class="guard-stat guard-stat--alert" role="listitem">
            <i class="guard-stat-dot" aria-hidden="true"></i>
            <strong>${counts.alert}</strong>
            <span>Alerta</span>
          </div>
        </div>
      </div>
      <div class="guard-chart">
        <div class="guard-bars" role="img" aria-label="${escapeAttr(summary)}">
          ${columns}
        </div>
        ${total ? '' : '<p class="guard-empty">Sin marcas en las últimas 24 horas.</p>'}
        <div class="guard-scale" aria-hidden="true">
          <span>00</span><span>06</span><span>12</span><span>18</span><span>24</span>
        </div>
      </div>
    </section>`;
}

function compactDeviceRow(device) {
  return `
    <div class="device-row device-row--compact">
      <span class="device-icon">${icon('device', 18)}</span>
      <div class="device-info">
        <strong>${escapeHtml(device.etiqueta_menor || device.alias)}</strong>
        <span>${escapeHtml(device.model_family || 'Android')} · ${relativeLabel(device.ultima_conexion)}</span>
      </div>
      <span class="device-state ${device.estado === 'ACTIVO' ? '' : 'is-revoked'}"><i class="dot"></i>${device.estado === 'ACTIVO' ? 'Activo' : 'Revocado'}</span>
    </div>`;
}

function alertRow(alert, { compact = false } = {}) {
  const evidence = (alert.reglas || [])
    .map((rule) => {
      const entries = rule.evidence && typeof rule.evidence === 'object'
        ? Object.entries(rule.evidence).map(([key, value]) => `${key}: ${value}`).join(', ')
        : '';
      return entries ? `${rule.label} (${entries})` : rule.label;
    })
    .join(' · ');
  const advice = (alert.reglas || [])
    .map((rule) => rule.consejo_educativo)
    .find((value) => typeof value === 'string' && value.trim());
  const title = alertRuleLabel(alert);
  const showWhy = !compact && (Boolean(advice) || (evidence && evidence !== title));
  const canAct = ['NUEVA', 'VISTA'].includes(alert.estado);
  return `
    <article class="alert-row">
      <div class="alert-rail alert-rail--${alert.severidad}" aria-hidden="true"></div>
      <div class="alert-content">
        <div class="alert-meta">
          <strong>${escapeHtml(alert.etiqueta_menor || alert.device_alias)}</strong>
          <span aria-hidden="true">·</span>
          <span>${relativeLabel(alert.creado_en)}</span>
          <span class="sev sev--${alert.severidad}">${severityLabel(alert.severidad)}</span>
          ${compact && alert.estado !== 'NUEVA' ? `<span>${alertStateLabel(alert.estado)}</span>` : ''}
        </div>
        <h3 class="alert-title">${escapeHtml(title)}</h3>
        ${compact ? `
          <div class="alert-summary-block" data-alert-id="${escapeAttr(alert.id)}">
            <p class="alert-summary">${escapeHtml(alert.resumen)}</p>
            <button class="alert-expand" type="button" data-action="expand-alert-summary" hidden>Ver mensaje completo</button>
          </div>` : `
          <div class="alert-summary-block" data-alert-id="${escapeAttr(alert.id)}">
            <p class="alert-summary">${escapeHtml(alert.resumen)}</p>
            <button class="alert-expand" type="button" data-action="expand-alert-summary" hidden>Ver mensaje completo</button>
          </div>
          ${showWhy ? `<details class="alert-why" data-alert-id="${escapeAttr(alert.id)}">
            <summary>Por qué se generó</summary>
            ${evidence && evidence !== title ? `<p class="alert-evidence">${escapeHtml(evidence)}</p>` : ''}
            ${advice ? `<p class="alert-why-advice">${escapeHtml(advice)}</p>` : ''}
            <p class="alert-note">Sin contenido del mensaje: solo señales derivadas.</p>
          </details>` : ''}`}
        ${compact ? '' : `
          <div class="alert-actions">
            ${alert.estado === 'NUEVA' ? `<button class="btn btn-sm btn-secondary" type="button" data-action="alert-state" data-id="${alert.id}" data-state="VISTA">Marcar vista</button>` : ''}
            ${canAct ? `<button class="btn btn-sm btn-ghost" type="button" data-action="alert-state" data-id="${alert.id}" data-state="DESCARTADA">Descartar</button>` : ''}
          </div>`}
      </div>
      <div class="alert-side">
        <time class="alert-time" datetime="${escapeAttr(alert.creado_en)}">${timeLabel(alert.creado_en)}</time>
        <span class="alert-state">${alertStateLabel(alert.estado)}</span>
      </div>
    </article>`;
}

/* ---------- Pairing ---------- */

function renderPairing() {
  const pairing = state.pairing && state.pairing.sessionId ? state.pairing : null;
  if (!pairing) {
    $('#view').innerHTML = `
      <section class="page-head">
        <div>
          <p class="eyebrow">Paso previo</p>
          <h2>Un código, dos teléfonos.</h2>
          <p>Genera un código y muéstralo en tu teléfono. El teléfono de tu hijo o hija lo escanea o lo escribe. La vinculación se confirma en vivo.</p>
        </div>
      </section>
      <section class="card generator">
        <div class="generator-copy">
          <p class="eyebrow">Nueva vinculación</p>
          <h2>¿A quién vas a vincular?</h2>
          <p>Usa un alias, no el nombre legal. Puedes cambiarlo después. El código vence en 10 minutos y se usa una sola vez.</p>
          <div class="panel-address">
            <span class="panel-address-label">Dirección del panel</span>
            <code>${escapeHtml(location.origin)}</code>
            <button class="btn btn-sm btn-secondary" type="button" data-action="copy-panel-url">Copiar</button>
          </div>
          <p class="panel-address-note">Escríbela en la app si no aparece por defecto.</p>
          <form class="pair-form" id="pair-form">
            <label class="field">
              <span>Alias del teléfono</span>
              <input type="text" name="alias" maxlength="40" placeholder="Ej: Sofía" autocomplete="off">
            </label>
            <label class="check">
              <input type="checkbox" name="tutor_declara_tutoria" required>
              <span>Declaro que ejerzo la patria potestad, tutela o cuidado personal del menor y que le expliqué cómo funciona la vinculación.</span>
            </label>
            <button class="btn btn-primary" type="submit">${icon('link', 17)} Generar código de vinculación</button>
          </form>
          <div class="privacy-line">${icon('shield', 15)}<span>El teléfono analiza en local. En este paso no se envía ningún dato del menor.</span></div>
        </div>
        <div class="generator-art" aria-hidden="true">
          ${qrGlyph()}
          <p>Vinculación cifrada · 10 minutos · un uso</p>
        </div>
      </section>`;
    return;
  }

  if (pairing.status === 'success') {
    renderPairingSuccess(pairing);
    return;
  }

  const expired = pairing.status === 'expired';
  $('#view').innerHTML = `
    <section class="page-head">
      <div>
        <p class="eyebrow">Vinculación en curso</p>
        <h2>${expired ? 'El código expiró.' : 'Muestra este código.'}</h2>
        <p>${expired ? 'Genera uno nuevo para intentarlo otra vez. Los códigos duran 10 minutos.' : 'Escanea el QR con la app IA Guardian del teléfono, o escribe el código manual. Se confirma aquí, en vivo. Si el teléfono no lo detecta, acércalo al QR o usa el código manual.'}</p>
      </div>
      ${expired ? '<button class="btn btn-primary" type="button" data-action="reset-pairing">Generar otro código</button>' : ''}
    </section>
    <div class="pair-grid">
      <section class="pair-code ${expired ? 'is-expired' : ''}" aria-label="Código de vinculación">
        <p class="eyebrow">${pairing.etiqueta ? `Para ${escapeHtml(pairing.etiqueta)}` : 'Paso 1 · muestra'}</p>
        <div class="qr-shell" ${expired ? 'aria-hidden="true"' : ''}>${pairing.qrSvg}</div>
        <div class="manual-code">
          <div>
            <span>Código manual</span>
            <strong>${escapeHtml(pairing.manualCode)}</strong>
          </div>
          <button class="icon-btn" type="button" data-action="copy-code" aria-label="Copiar código manual" title="Copiar código">${icon('copy', 18)}</button>
        </div>
        <div class="countdown">
          <svg class="ring" viewBox="0 0 34 34" aria-hidden="true">
            <circle class="track" cx="17" cy="17" r="14"></circle>
            <circle class="progress" id="countdown-ring" cx="17" cy="17" r="14"></circle>
          </svg>
          <span id="countdown-text">${expired ? 'Código expirado' : 'Calculando…'}</span>
        </div>
        <p class="pair-hint">El código es de un solo uso. Si alguien más lo ve, cancélalo y genera otro.</p>
      </section>

      <section class="card pair-steps">
        <p class="eyebrow">En el teléfono del menor</p>
        <h2>Cómo vincular</h2>
        <p>La app se instala con el tutor presente. El menor ve qué se comparte y qué no.</p>
        <ol class="steps">
          <li><div><strong>Abre IA Guardian</strong><p>En el teléfono, elige “Vincular con un código”.</p></div></li>
          <li><div><strong>Escanea el QR o escribe el código</strong><p>El QR está a la izquierda. El código manual sirve si la cámara falla.</p></div></li>
          <li><div><strong>Confirma en este panel</strong><p>La pantalla cambia sola cuando la vinculación queda activa.</p></div></li>
        </ol>
        <div class="live" id="pair-live" role="status" aria-live="polite">
          <span class="pulse"></span>
          <span>${expired ? 'Sin vinculación activa. Genera un código nuevo.' : 'Esperando al teléfono…'}</span>
        </div>
        <div class="privacy-line">${icon('lock', 15)}<span>Solo se comparten señales derivadas: categoría, minutos de uso y conteos. Nunca el texto de un mensaje.</span></div>
        ${expired ? '' : '<button class="btn btn-ghost btn-sm" type="button" style="margin-top:14px" data-action="cancel-pairing">Cancelar vinculación</button>'}
      </section>
    </div>`;

  if (!expired) startCountdown();
}

function renderPairingSuccess(pairing) {
  const alias = pairing.deviceAlias || pairing.etiqueta || 'el dispositivo';
  $('#view').innerHTML = `
    <section class="page-head">
      <div>
        <p class="eyebrow">Vinculación confirmada</p>
        <h2>${escapeHtml(alias)} ya está en el panel.</h2>
        <p>El panel recibirá señales derivadas desde ahora. Puedes revocar el acceso en cualquier momento desde Dispositivos.</p>
      </div>
    </section>
    <section class="card pair-success">
      <div class="stamp" aria-hidden="true">
        <div class="stamp-inner">${icon('check', 30)}<span>Vinculado</span></div>
      </div>
      <h2>Vinculación activa · ${escapeHtml(alias)}</h2>
      <p>La primera sincronización puede tardar unos minutos. La guardia se llenará sola.</p>
      <div class="success-actions">
        <a class="btn btn-primary" href="#/resumen">Ir al resumen ${icon('arrow', 17)}</a>
        <button class="btn btn-secondary" type="button" data-action="reset-pairing">Vincular otro dispositivo</button>
      </div>
      <ul class="success-facts">
        <li><span class="fact-k">Llega al panel</span><span class="fact-v">Categorías, minutos y conteos</span></li>
        <li><span class="fact-k">No llega</span><span class="fact-v">Textos, fotos ni contactos</span></li>
        <li><span class="fact-k">Control</span><span class="fact-v">Revocable desde Dispositivos</span></li>
      </ul>
    </section>`;
}

function qrGlyph() {
  return `
    <svg viewBox="0 0 160 160" fill="none" aria-hidden="true">
      <rect x="1" y="1" width="158" height="158" rx="16" stroke="currentColor" stroke-opacity=".18"/>
      <path d="M46 54V42h12M114 54V42h-12M46 106v12h12M114 106v12h-12" stroke="currentColor" stroke-width="4" stroke-linecap="round"/>
      <rect x="58" y="62" width="18" height="18" rx="3" stroke="currentColor" stroke-width="4"/>
      <rect x="88" y="62" width="14" height="14" rx="3" stroke="currentColor" stroke-width="4"/>
      <rect x="62" y="92" width="14" height="14" rx="3" stroke="currentColor" stroke-width="4"/>
      <path d="M92 94h10v10H92zM106 82h8v8h-8z" fill="currentColor" opacity=".55"/>
    </svg>`;
}

async function generatePairing(event) {
  event.preventDefault();
  const form = event.target;
  const input = form.querySelector('[name="alias"]');
  const declaration = form.querySelector('[name="tutor_declara_tutoria"]');
  const button = form.querySelector('button[type="submit"]');
  const etiqueta = input.value.trim();
  if (!declaration?.checked) {
    toast('Debes declarar tu vínculo de tutela antes de generar la vinculación.', 'danger');
    declaration?.focus();
    return;
  }
  button.disabled = true;
  button.textContent = 'Generando…';
  try {
    const response = await api('/pairing/generate', {
      method: 'POST',
      body: { etiqueta_menor: etiqueta || null, ttl_seconds: 600, tutor_declara_tutoria: true },
    });
    state.pairing = {
      sessionId: response.session_id,
      qrSvg: response.qr_svg,
      manualCode: response.manual_code,
      expiresAt: response.expires_at,
      wsUrl: response.ws_url,
      wsTicket: response.ws_ticket,
      etiqueta,
      status: 'pending',
      totalSeconds: 600,
      deviceAlias: null,
    };
    renderPairing();
    connectPairingSocket();
    startPairingPolling();
  } catch (error) {
    toast(error.message);
  } finally {
    if (button) {
      button.disabled = false;
      button.innerHTML = `${icon('link', 17)} Generar código de vinculación`;
    }
  }
}

function startCountdown() {
  clearInterval(state.ticker);
  updateCountdown();
  state.ticker = setInterval(updateCountdown, 1000);
}

function updateCountdown() {
  const pairing = state.pairing;
  if (!pairing || pairing.status !== 'pending') {
    clearInterval(state.ticker);
    return;
  }
  const remaining = Math.max(0, Math.round((new Date(pairing.expiresAt).getTime() - Date.now()) / 1000));
  const text = $('#countdown-text');
  const ring = $('#countdown-ring');
  if (text) {
    const minutes = Math.floor(remaining / 60);
    const seconds = String(remaining % 60).padStart(2, '0');
    text.textContent = remaining > 0 ? `Vence en ${minutes}:${seconds}` : 'Código expirado';
  }
  if (ring) {
    const progress = Math.min(1, Math.max(0, remaining / pairing.totalSeconds));
    ring.style.strokeDashoffset = String(87.96 * (1 - progress));
  }
  if (remaining <= 0) {
    pairing.status = 'expired';
    clearInterval(state.ticker);
    closePairingLive();
    const live = $('#pair-live');
    if (live) {
      live.querySelector('.pulse')?.remove();
      live.lastElementChild.textContent = 'El código expiró. Genera uno nuevo.';
    }
    $$('[data-action="cancel-pairing"]').forEach((button) => { button.hidden = true; });
  }
}

function connectPairingSocket() {
  closeSocket();
  const pairing = state.pairing;
  if (!pairing?.wsUrl || !pairing.wsTicket) return;
  const scheme = location.protocol === 'https:' ? 'wss' : 'ws';
  try {
    // The short-lived ticket travels in a subprotocol, never in the URL.
    const socket = new WebSocket(
      `${scheme}://${location.host}${pairing.wsUrl}`,
      ['guardiania', pairing.wsTicket],
    );
    state.socket = socket;
    socket.onmessage = (event) => {
      let message;
      try { message = JSON.parse(event.data); } catch { return; }
      if (message.type === 'pairing_success') {
        handlePairingSuccess(message.data?.device_alias);
      } else if (message.type === 'pairing_expired') {
        handlePairingExpired();
      }
    };
    socket.onerror = () => {
      // Polling already runs as a fallback; no noisy toast for a transient socket failure.
    };
  } catch {
    // Browser without WebSocket: polling covers it.
  }
}

function startPairingPolling() {
  clearInterval(state.poll);
  state.poll = setInterval(async () => {
    const pairing = state.pairing;
    if (!pairing || pairing.status !== 'pending') return;
    try {
      const status = await api(`/pairing/${pairing.sessionId}`);
      if (status.vinculacion_estado === 'ACTIVO') handlePairingSuccess(status.device_alias);
      else if (['EXPIRADO', 'REVOCADO'].includes(status.vinculacion_estado) || ['EXPIRADA', 'CANCELADA'].includes(status.estado)) handlePairingExpired();
    } catch {
      // Keep waiting; transient errors should not end the pairing.
    }
  }, 4000);
}

function handlePairingSuccess(deviceAlias) {
  const pairing = state.pairing;
  if (!pairing || pairing.status === 'success') return;
  pairing.status = 'success';
  pairing.deviceAlias = deviceAlias || pairing.etiqueta || 'Dispositivo';
  closePairingLive();
  renderPairing();
  toast(`Vinculación activa: ${pairing.deviceAlias}`);
  refreshFromServer({ force: true });
}

function handlePairingExpired() {
  const pairing = state.pairing;
  if (!pairing || pairing.status !== 'pending') return;
  pairing.status = 'expired';
  closePairingLive();
  renderPairing();
}

function closeSocket() {
  if (state.socket) {
    try { state.socket.close(); } catch { /* noop */ }
    state.socket = null;
  }
}

function closePairingLive() {
  closeSocket();
  clearInterval(state.poll);
  clearInterval(state.ticker);
  state.poll = null;
  state.ticker = null;
}

function resetPairing() {
  closePairingLive();
  state.pairing = null;
  renderPairing();
}

async function copyManualCode() {
  const pairing = state.pairing;
  if (!pairing) return;
  const code = pairing.manualCode.replace(/\s/g, '');
  try {
    await navigator.clipboard.writeText(code);
    toast('Código copiado');
  } catch {
    toast(`Escribe este código: ${pairing.manualCode}`);
  }
}

async function cancelPairing() {
  const pairing = state.pairing;
  if (!pairing) return;
  try {
    await api(`/pairing/${pairing.sessionId}/cancel`, { method: 'POST' });
    resetPairing();
    toast('Vinculación cancelada');
  } catch (error) {
    toast(error.message);
  }
}

/* ---------- Devices ---------- */

async function renderDevicesPage() {
  await loadDevices();
  renderDevices();
}

function renderDevices() {
  const isActive = (device) => device.estado === 'ACTIVO' && device.vinculacion_estado === 'ACTIVO';
  const active = state.devices.filter(isActive);
  const revoked = state.devices.filter((device) => !isActive(device));

  const activeRows = active
    .map((device) => {
      const name = device.etiqueta_menor || device.alias;
      const sinAcceso = device.notificaciones_activas === false;
      const statusBits = [
        device.notificaciones_activas === true ? 'Notificaciones activas' : null,
        device.dnd === true ? 'No molestar activo' : null,
        device.notificaciones_activas === true
          ? (device.ultima_notificacion_en
            ? `última notificación ${relativeLabel(device.ultima_notificacion_en)}`
            : 'sin notificaciones registradas')
          : null,
      ].filter(Boolean);
      const statusText = sinAcceso
        ? 'Acceso a notificaciones desactivado'
        : statusBits.join(' · ');
      return `
        <article class="device-row">
          <span class="device-icon">${icon('device', 18)}</span>
          <div class="device-info">
            <strong>${escapeHtml(name)}</strong>
            <span>${escapeHtml(device.model_family || 'Android')} · Android API ${device.api_level} · sincronizó ${relativeLabel(device.ultima_conexion)}</span>
            ${statusText ? `<span class="${sinAcceso ? 'device-alert' : 'device-status-line'}">${escapeHtml(statusText)}</span>` : ''}
          </div>
          <span class="device-state"><i class="dot"></i>Activo</span>
          <button class="btn btn-sm btn-danger" type="button" data-action="revoke" data-id="${device.vinculacion_id}" data-name="${escapeAttr(name)}">Revocar</button>
        </article>
        <div class="device-code">
          <div class="device-code-copy">
            <strong>Código de desvinculación</strong>
            <span>${device.unlink_code_set
              ? 'Activo: el teléfono lo pedirá para desvincularse.'
              : 'Sin código: el teléfono puede desvincularse desde la app.'}</span>
          </div>
          <div class="device-code-actions">
            <button class="btn btn-sm btn-secondary" type="button" data-action="set-unlink-code" data-id="${device.vinculacion_id}" data-name="${escapeAttr(name)}">${device.unlink_code_set ? 'Cambiar' : 'Definir código'}</button>
            ${device.unlink_code_set ? `<button class="btn btn-sm btn-secondary" type="button" data-action="remove-unlink-code" data-id="${device.vinculacion_id}" data-name="${escapeAttr(name)}">Quitar</button>` : ''}
          </div>
          <p class="device-code-note">Este código impide desvincular <em>desde la app</em>. No impide desinstalar la app, borrar sus datos desde Ajustes ni restablecer el teléfono. Si el teléfono deja de sincronizar, el panel lo mostrará como sin señal.</p>
        </div>`;
    })
    .join('');

  const revokedRows = revoked
    .map((device) => {
      const name = device.etiqueta_menor || device.alias;
      return `
        <article class="device-row">
          <span class="device-icon">${icon('device', 18)}</span>
          <div class="device-info">
            <strong>${escapeHtml(name)}</strong>
            <span>${escapeHtml(device.model_family || 'Android')} · vinculado ${relativeLabel(device.creado_en)}</span>
          </div>
          <span class="device-state is-revoked"><i class="dot"></i>Desvinculado</span>
          <button class="btn btn-sm btn-secondary" type="button" data-action="hide-device" data-id="${device.id}" data-name="${escapeAttr(name)}">Quitar del panel</button>
        </article>`;
    })
    .join('');

  $('#view').innerHTML = `
    <section class="page-head">
      <div>
        <h2>Dispositivos vinculados.</h2>
        <p>Cada teléfono tiene su propia vinculación. Revocar corta el envío de señales de inmediato y borra el token del dispositivo.</p>
      </div>
    </section>
    <section class="card">
      <header class="card-head" style="padding:16px 18px;border-bottom:1px solid var(--line)">
        <h2>${active.length} ${active.length === 1 ? 'dispositivo activo' : 'dispositivos activos'}</h2>
        <span class="eyebrow">Retención 90 días</span>
      </header>
      <div class="device-list">
        ${activeRows || `<div class="alert-empty"><strong>No hay dispositivos activos</strong>Vincula uno nuevo para empezar a recibir señales.</div>`}
      </div>
    </section>
    ${revoked.length ? `
    <section class="card" style="margin-top:16px">
      <header class="card-head" style="padding:16px 18px;border-bottom:1px solid var(--line)">
        <h2>Desvinculados (${revoked.length})</h2>
        <button class="btn btn-sm btn-secondary" type="button" data-action="hide-revoked">Quitar todos</button>
      </header>
      <div class="device-list">${revokedRows}</div>
      <div class="privacy-line" style="margin:14px 16px 16px">${icon('shield', 15)}<span>Se quitan de la lista; sus alertas y el historial agregado se conservan.</span></div>
    </section>` : ''}
    <div class="privacy-line" style="margin-top:20px">${icon('shield', 15)}<span>Revocar no borra el historial agregado de alertas, pero el teléfono deja de enviar datos en el siguiente intento de sincronización.</span></div>`;
}

async function revokeDevice(bindingId, name) {
  const confirmed = await confirmAction({
    title: '¿Revocar este dispositivo?',
    text: `${name} dejará de enviar señales. El teléfono tendrá que vincularse de nuevo para volver a aparecer en el panel.`,
    ok: 'Revocar acceso',
  });
  if (!confirmed) return;
  try {
    await api(`/devices/${bindingId}/revoke`, { method: 'POST' });
    toast('Acceso revocado');
    await refreshCore();
    if (state.route === 'dispositivos') renderDevices();
    if (state.route === 'resumen') renderOverview();
  } catch (error) {
    toast(error.message);
  }
}

async function setUnlinkCode(bindingId, name) {
  const code = await promptAction({
    title: 'Código de desvinculación',
    text: `Define un código de 6 dígitos para ${name}. El teléfono lo pedirá para desvincularse; tú siempre puedes revocar desde aquí.`,
    ok: 'Guardar código',
  });
  if (code === null) return;
  if (!/^\d{6}$/.test(code)) {
    toast('El código debe tener exactamente 6 dígitos.');
    return;
  }
  try {
    await api(`/devices/${bindingId}/unlink-code`, { method: 'PUT', body: { codigo: code } });
    toast('Código guardado');
    await refreshCore();
    if (state.route === 'dispositivos') renderDevices();
  } catch (error) {
    toast(error.message);
  }
}

async function removeUnlinkCode(bindingId, name) {
  const confirmed = await confirmAction({
    title: '¿Quitar el código?',
    text: `${name} podrá desvincularse desde la app sin pedir código.`,
    ok: 'Quitar código',
  });
  if (!confirmed) return;
  try {
    await api(`/devices/${bindingId}/unlink-code`, { method: 'DELETE' });
    toast('Código eliminado');
    await refreshCore();
    if (state.route === 'dispositivos') renderDevices();
  } catch (error) {
    toast(error.message);
  }
}

async function hideDevice(deviceId, name) {
  const confirmed = await confirmAction({
    title: '¿Quitar este teléfono del panel?',
    text: `${name} desaparecerá de la lista. Sus alertas y el historial se conservan.`,
    ok: 'Quitar del panel',
  });
  if (!confirmed) return;
  try {
    await api(`/devices/${deviceId}/hide`, { method: 'POST' });
    toast('Teléfono quitado del panel');
    await refreshFromServer({ force: true });
  } catch (error) {
    toast(error.message);
  }
}

async function hideRevokedDevices() {
  const confirmed = await confirmAction({
    title: '¿Quitar todos los desvinculados?',
    text: 'Desaparecen de la lista. Sus alertas y el historial se conservan.',
    ok: 'Quitar todos',
  });
  if (!confirmed) return;
  try {
    const result = await api('/devices/hide-revoked', { method: 'POST' });
    toast(`${result.ocultos} teléfono(s) quitados del panel`);
    await refreshFromServer({ force: true });
  } catch (error) {
    toast(error.message);
  }
}

/* ---------- Alerts ---------- */

function renderAlerts() {
  const alerts = state.alerts;
  const counts = {
    TODAS: alerts.length,
    SIN_REVISAR: alerts.filter((alert) => ['NUEVA', 'VISTA'].includes(alert.estado)).length,
    ALTAS: alerts.filter((alert) => ['ALTA', 'CRITICA'].includes(alert.severidad)).length,
    RESUELTAS: alerts.filter((alert) => ['RECONOCIDA', 'DESCARTADA'].includes(alert.estado)).length,
  };
  const filtered = alerts.filter((alert) => {
    if (state.filter === 'SIN_REVISAR') return ['NUEVA', 'VISTA'].includes(alert.estado);
    if (state.filter === 'ALTAS') return ['ALTA', 'CRITICA'].includes(alert.severidad);
    if (state.filter === 'RESUELTAS') return ['RECONOCIDA', 'DESCARTADA'].includes(alert.estado);
    return true;
  });

  const chips = [
    ['TODAS', 'Todas'],
    ['SIN_REVISAR', 'Sin revisar'],
    ['ALTAS', 'Alta prioridad'],
    ['RESUELTAS', 'Resueltas'],
  ]
    .map(([key, label]) => `<button class="chip" type="button" aria-pressed="${state.filter === key}" data-action="filter" data-filter="${key}">${label}<span class="chip-count">${counts[key]}</span></button>`)
    .join('');

  const openDetails = openAlertIds();
  const expandedSummaries = expandedSummaryIds();
  $('#view').innerHTML = `
    <section class="page-head">
      <div>
        <h2>Señales explicadas.</h2>
        <p>Cada alerta muestra qué regla se activó y con qué evidencia agregada. Nunca verás el texto de una conversación.</p>
      </div>
    </section>
    <div class="filters" role="group" aria-label="Filtrar alertas">${chips}</div>
    <section class="card">
      ${filtered.length
        ? filtered.map((alert) => alertRow(alert)).join('')
        : `<div class="empty-state">
            <span class="empty-mark">${icon('shield', 24)}</span>
            <h3>No hay señales en este filtro</h3>
            <p>Cuando el teléfono detecte una señal, aparecerá aquí con la regla que la originó.</p>
          </div>`}
    </section>
    <div class="privacy-line" style="margin-top:20px">${icon('lock', 15)}<span>El motor de riesgo es heurístico y explicable. No es un diagnóstico ni reemplaza una conversación.</span></div>`;
  restoreOpenAlertIds(openDetails);
  restoreExpandedSummaries(expandedSummaries);
  enhanceCompactSummaries();
  requestAnimationFrame(enhanceCompactSummaries);
}

async function setAlertState(id, newState) {
  try {
    await api(`/alerts/${id}`, { method: 'PATCH', body: { estado: newState } });
    toast(newState === 'DESCARTADA' ? 'Alerta descartada' : 'Alerta marcada como vista');
    await refreshCore();
    if (state.route === 'alertas') renderAlerts();
    if (state.route === 'resumen') renderOverview();
  } catch (error) {
    toast(error.message);
  }
}

/* ---------- Confirm dialog ---------- */

function promptAction({ title, text, ok, placeholder = '000000' }) {
  const dialog = document.createElement('dialog');
  dialog.className = 'dialog';
  dialog.innerHTML = `
    <form method="dialog">
      <h2>${escapeHtml(title)}</h2>
      <p class="muted">${escapeHtml(text)}</p>
      <label class="field">
        <span>Código de 6 dígitos</span>
        <input name="codigo" inputmode="numeric" autocomplete="off" pattern="\\d{6}" maxlength="6" placeholder="${escapeAttr(placeholder)}" required>
      </label>
      <div class="dialog-actions">
        <button class="btn btn-secondary" value="cancel" type="submit">Cancelar</button>
        <button class="btn btn-primary" value="confirm" type="submit">${escapeHtml(ok || 'Guardar')}</button>
      </div>
    </form>`;
  document.body.appendChild(dialog);
  dialog.showModal();
  const input = dialog.querySelector('input');
  input.focus();
  return new Promise((resolve) => {
    dialog.addEventListener('close', () => {
      const value = dialog.returnValue === 'confirm' ? input.value.trim() : null;
      dialog.remove();
      resolve(value);
    });
  });
}


function confirmAction({ title, text, ok }) {
  const dialog = $('#confirm-dialog');
  $('#confirm-title').textContent = title;
  $('#confirm-text').textContent = text;
  $('#confirm-ok').textContent = ok || 'Confirmar';
  dialog.returnValue = '';
  dialog.showModal();
  return new Promise((resolve) => {
    const onClose = () => {
      dialog.removeEventListener('close', onClose);
      resolve(dialog.returnValue === 'confirm');
    };
    dialog.addEventListener('close', onClose);
  });
}

/* ---------- Privacy and account ---------- */

function openPrivacy() {
  const dialog = $('#privacy-dialog');
  if (!dialog || !state.tutor) return;
  $('#privacy-email').textContent = state.tutor.email;
  $('#privacy-verified').textContent = state.tutor.email_verified ? 'Correo verificado' : 'Correo pendiente de verificación';
  $('#privacy-verified').dataset.tone = state.tutor.email_verified ? 'ok' : 'warn';
  $('#consent-list').innerHTML = '';
  $('#delete-error').textContent = '';
  dialog.showModal();
}

async function exportMyData() {
  const button = $('#export-data');
  button.disabled = true;
  try {
    const data = await api('/account/export');
    const blob = new Blob([JSON.stringify(data, null, 2)], { type: 'application/json' });
    const url = URL.createObjectURL(blob);
    const link = document.createElement('a');
    link.href = url;
    link.download = 'ia-guardian-mis-datos.json';
    link.click();
    URL.revokeObjectURL(url);
    toast('Descarga preparada');
  } catch (error) {
    toast(error.message);
  } finally {
    button.disabled = false;
  }
}

async function loadConsents() {
  const box = $('#consent-list');
  box.innerHTML = '<p class="muted">Cargando…</p>';
  try {
    const rows = await api('/account/consents');
    box.innerHTML = rows.length
      ? rows.map((row) => `<div class="consent-row"><strong>${escapeHtml(row.tipo)}</strong><span>Versión ${escapeHtml(row.version)}</span><time>${new Date(row.creado_en).toLocaleDateString('es-CL')}</time></div>`).join('')
      : '<p class="muted">No hay consentimientos registrados.</p>';
  } catch (error) {
    box.innerHTML = `<p class="form-error">${escapeHtml(error.message)}</p>`;
  }
}

async function changePassword(event) {
  event.preventDefault();
  const form = event.target;
  const data = new FormData(form);
  const errorBox = $('#password-error');
  errorBox.textContent = '';
  try {
    await api('/auth/password/change', {
      method: 'POST',
      body: {
        current_password: String(data.get('current_password') || ''),
        new_password: String(data.get('new_password') || ''),
      },
    });
    form.reset();
    toast('Contraseña actualizada. Vuelve a entrar.');
    $('#privacy-dialog').close();
    await logout(false, false);
  } catch (error) {
    errorBox.textContent = error.message;
  }
}

async function deleteMyAccount(event) {
  event.preventDefault();
  const form = event.target;
  const data = new FormData(form);
  const errorBox = $('#delete-error');
  errorBox.textContent = '';
  const confirmation = String(data.get('confirmation') || '').trim();
  if (confirmation.toUpperCase() !== 'ELIMINAR MI CUENTA') {
    errorBox.textContent = 'Escribe exactamente ELIMINAR MI CUENTA.';
    return;
  }
  const accepted = await confirmAction({
    title: '¿Eliminar la cuenta y todos los datos?',
    text: 'Se revocan los dispositivos, se borran alertas, capturas y consentimientos. Esta acción no se puede deshacer.',
    ok: 'Eliminar definitivamente',
  });
  if (!accepted) return;
  try {
    await api('/account/delete', {
      method: 'POST',
      body: {
        password: String(data.get('password') || ''),
        confirmation,
      },
    });
    $('#privacy-dialog').close();
    await logout(false, false);
    toast('Cuenta eliminada');
  } catch (error) {
    errorBox.textContent = error.message;
  }
}

/* ---------- Global events ---------- */

function bindEvents() {
  document.addEventListener('click', async (event) => {
    const trigger = event.target.closest('[data-action]');
    if (!trigger) return;
    const action = trigger.dataset.action;
    if (action === 'go-pair') {
      location.hash = '#/vincular';
    } else if (action === 'go-alerts') {
      location.hash = '#/alertas';
    } else if (action === 'copy-code') {
      await copyManualCode();
    } else if (action === 'copy-panel-url') {
      try {
        await navigator.clipboard.writeText(location.origin);
        toast('Dirección del panel copiada');
      } catch {
        toast(location.origin);
      }
    } else if (action === 'reset-pairing') {
      resetPairing();
    } else if (action === 'cancel-pairing') {
      await cancelPairing();
    } else if (action === 'revoke') {
      await revokeDevice(trigger.dataset.id, trigger.dataset.name);
    } else if (action === 'set-unlink-code') {
      await setUnlinkCode(trigger.dataset.id, trigger.dataset.name);
    } else if (action === 'remove-unlink-code') {
      await removeUnlinkCode(trigger.dataset.id, trigger.dataset.name);
    } else if (action === 'hide-device') {
      await hideDevice(trigger.dataset.id, trigger.dataset.name);
    } else if (action === 'hide-revoked') {
      await hideRevokedDevices();
    } else if (action === 'expand-alert-summary') {
      const block = trigger.closest('.alert-summary-block');
      const summary = block?.querySelector('.alert-summary');
      if (!summary) return;
      const expanded = summary.classList.toggle('is-expanded');
      trigger.textContent = expanded ? 'Ver menos' : 'Ver mensaje completo';
      enhanceCompactSummaries();
    } else if (action === 'alert-state') {
      trigger.disabled = true;
      await setAlertState(trigger.dataset.id, trigger.dataset.state);
    } else if (action === 'filter') {
      state.filter = trigger.dataset.filter;
      renderAlerts();
    } else if (action === 'open-privacy') {
      openPrivacy();
    }
  });

  document.addEventListener('submit', async (event) => {
    if (event.target.id === 'auth-form') await submitAuth(event);
    if (event.target.id === 'pair-form') await generatePairing(event);
    if (event.target.id === 'password-form') await changePassword(event);
    if (event.target.id === 'delete-form') await deleteMyAccount(event);
  });

  $('#demo-login').addEventListener('click', demoLogin);
  $('#logout').addEventListener('click', () => logout());
  $('#privacy-open').addEventListener('click', openPrivacy);
  $('#export-data').addEventListener('click', exportMyData);
  $('#view-consents').addEventListener('click', loadConsents);
  $('#theme-toggle').addEventListener('click', () => applyTheme(themeIsDark() ? 'light' : 'dark'));
  $('#pair-shortcut').addEventListener('click', () => { location.hash = '#/vincular'; });

  $$('.segmented button').forEach((button) => {
    button.addEventListener('click', () => setAuthMode(button.dataset.authMode));
  });

  window.addEventListener('hashchange', () => {
    navigate().catch((error) => toast(error.message));
  });
}

async function boot() {
  initTheme();
  $$('[data-icon]').forEach((node) => { node.innerHTML = icon(node.dataset.icon, 20); });
  bindEvents();

  try {
    await api('/auth/csrf', { auth: false });
    state.tutor = await api('/auth/me');
    showApp();
    await enterApp();
  } catch {
    await logout(false, false);
    showAuth('login');
  }
  state.booting = false;
}

document.addEventListener('DOMContentLoaded', boot);
