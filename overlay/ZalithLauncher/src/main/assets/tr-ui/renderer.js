const $ = id => document.getElementById(id);
const T = window.TR;
let info = null, settings = null, acc = { active: null, accounts: [] }, catalog = [], library = [], bigImg = null;
const skinCache = new Map();   // accountId -> dataUrl | null
const imgCache = new Map();    // dataUrl -> HTMLImageElement
let running = false;

function log(m) { $('log').textContent = `${new Date().toLocaleTimeString()}  ${String(m).trim()}\n` + $('log').textContent.slice(0, 6000); }
const activeAcc = () => acc.accounts.find(a => a.id === acc.active) || null;
const ROLE_LABEL = { player: 'Jugador', staff: 'Staff', admin: 'Admin', owner: 'Dueño' };
const typeLabel = a => a.type === 'microsoft' ? 'cuenta Microsoft' : a.type === 'tr' ? 'cuenta T.R' : 'cuenta local';
// Guarda la lista de cuentas que devuelve el proceso principal y, si trae el estado Staff (el rol puede haber cambiado), lo aplica.
function takeAcc(r) { acc = r; if (r.staff) { info.staff = r.staff; renderStaff(); } }
function h(tag, props = {}, ...kids) {
  const el = document.createElement(tag);
  for (const [k, v] of Object.entries(props)) { if (k === 'class') el.className = v; else if (k.startsWith('on')) el.addEventListener(k.slice(2), v); else el[k] = v; }
  el.append(...kids.filter(k => k != null)); return el;
}

/* ---------- Ventana de texto (Electron no tiene prompt()) ---------- */
function ask({ title, label, type = 'text', ok = 'Aceptar', validate }) {
  return new Promise(resolve => {
    const ov = $('askOverlay'), inp = $('askInput');
    $('askTitle').textContent = title; $('askLabel').textContent = label || ''; $('askErr').textContent = ''; $('askOk').textContent = ok;
    inp.type = type; inp.value = ''; ov.classList.remove('hidden'); inp.focus();
    const done = v => { ov.classList.add('hidden'); $('askOk').onclick = $('askCancel').onclick = inp.onkeydown = null; resolve(v); };
    const submit = async () => { const v = inp.value; if (!v) return; if (validate) { const err = await validate(v); if (err) { $('askErr').textContent = err; return; } } done(v); };
    $('askOk').onclick = submit; $('askCancel').onclick = () => done(null);
    inp.onkeydown = e => { if (e.key === 'Enter') submit(); if (e.key === 'Escape') done(null); };
  });
}
// Si la cuenta local tiene contraseña y aún no se ha desbloqueado en esta sesión, la pide.
async function ensureUnlocked(a) {
  if (!a || !a.locked) return true;
  const pw = await ask({ title: 'Cuenta ' + a.name, label: 'Escribe tu contraseña para usar esta cuenta.', type: 'password', ok: 'Entrar',
    validate: async v => { const r = await T.accounts.unlock(a.id, v); if (!r.ok) return r.error; acc = r; return ''; } });
  if (pw === null) return false;
  await refreshAll(); return true;
}

/* ---------- Dibujo de skins ---------- */
function loadImg(url) {
  if (imgCache.has(url)) return Promise.resolve(imgCache.get(url));
  return new Promise(res => { const i = new Image(); i.onload = () => { imgCache.set(url, i); res(i); }; i.onerror = () => res(null); i.src = url; });
}
function part(ctx, img, sx, sy, sw, sh, dx, dy, s) { ctx.drawImage(img, sx, sy, sw, sh, dx * s, dy * s, sw * s, sh * s); }
function drawHead(canvas, img) {
  const c = canvas.getContext('2d'); c.imageSmoothingEnabled = false; c.clearRect(0, 0, canvas.width, canvas.height);
  if (!img) return; c.drawImage(img, 8, 8, 8, 8, 0, 0, canvas.width, canvas.height); c.drawImage(img, 40, 8, 8, 8, 0, 0, canvas.width, canvas.height);
}
const defaultSkinUrl = () => (catalog[0] && catalog[0].dataUrl) || null;
async function skinOf(id, force) {
  if (force || !skinCache.has(id)) { const r = await T.skins.current(id); skinCache.set(id, r.ok ? r.skin : null); }
  return skinCache.get(id) || defaultSkinUrl();
}
async function paintHead(canvas, id) { const u = await skinOf(id); drawHead(canvas, u ? await loadImg(u) : null); }

/* ---------- Navegación ---------- */
const PAGES = {
  home: ['Bienvenido a NuncaJamas', 'Tu launcher oficial de Minecraft'],
  versions: ['Elige tu versión', 'Selecciona con qué versión quieres jugar.'],
  discord: ['Únete a nuestro Discord', 'Únete a la comunidad, recibe noticias, eventos y mucho más.'],
  skins: ['Skins de Minecraft', 'Explora y elige tu skin favorita.'],
  profile: ['Tu perfil de jugador', 'Administra tu cuenta, nombre y contraseña.'],
  settings: ['Configuración', 'Ajusta el rendimiento y la experiencia de juego.'],
  servers: ['Servidores disponibles', 'Elige a qué mundo quieres entrar.'],
  panel: ['Panel de comandos', 'Roles y cuentas del servidor, sin contraseñas de acceso.']
};
const NAV_OF = { versions: 'home', discord: 'home' };
function showPage(p) {
  document.querySelectorAll('.page').forEach(el => el.classList.toggle('hidden', el.id !== p));
  document.querySelectorAll('.nav').forEach(el => el.classList.toggle('active', el.dataset.page === (NAV_OF[p] || p)));
  $('pageTitle').textContent = PAGES[p][0]; $('pageSub').textContent = PAGES[p][1];
  $('pageTitles').classList.toggle('hidden', p === 'home');
  $('main') && $('main').scrollTo(0, 0);
  if (p === 'skins') renderSkins(); if (p === 'profile') renderProfile(); if (p === 'servers') renderServers(); if (p === 'panel') $('panelInput').focus();
}
document.querySelectorAll('.nav').forEach(b => b.addEventListener('click', () => showPage(b.dataset.page)));

/* ---------- Cuenta activa (cabecera / inicio) ---------- */
async function renderAccountBits() {
  const a = activeAcc();
  $('chipName').textContent = a ? a.name + (a.locked ? ' 🔒' : '') : 'Sin cuenta';
  $('homeAccount').textContent = a ? `Jugando como ${a.name}` : 'Crea o elige una cuenta para jugar';
  $('accountLabel').textContent = a ? `Jugando como ${a.name} · ${typeLabel(a)}${a.type === 'tr' && a.role !== 'player' ? ' · ' + ROLE_LABEL[a.role] : ''}` : 'Crea o elige una cuenta para jugar';
  if (a) await paintHead($('chipHead'), a.id); else drawHead($('chipHead'), null);
  if ($('skins') && !$('skins').classList.contains('hidden')) renderSkins();
  if ($('profile') && !$('profile').classList.contains('hidden')) renderProfile();
}

/* ---------- Modal de cuentas ---------- */
function badgeText(a) {
  if (a.type === 'microsoft') return 'Microsoft';
  if (a.type === 'tr') return a.expired ? 'T.R · sesión caducada' : 'T.R' + (a.role && a.role !== 'player' ? ' · ' + ROLE_LABEL[a.role] : '');
  return info && info.apiEnabled ? 'Local (antigua)' : 'Local';
}
async function renderAccountList() {
  const box = $('accountList'); box.replaceChildren();
  if (!acc.accounts.length) box.append(h('p', { class: 'note', textContent: 'Todavía no tienes cuentas. Crea tu cuenta T.R o añade tu cuenta Microsoft.' }));
  for (const a of acc.accounts) {
    const cv = h('canvas', { width: 40, height: 40 }); paintHead(cv, a.id);
    const row = h('div', { class: 'accRow' + (a.id === acc.active ? ' active' : '') }, cv,
      h('div', { class: 'info' }, h('strong', { textContent: a.name + (a.locked ? ' 🔒' : '') }), h('span', { class: 'badge' + (a.type === 'offline' || a.expired ? ' local' : ''), textContent: badgeText(a) })),
      a.id === acc.active ? h('small', { textContent: 'En uso' }) : h('button', { class: 'secondary', textContent: 'Usar', onclick: async () => { const r = await T.accounts.setActive(a.id); if (!r.ok) return; takeAcc(r); await refreshAll(); await ensureUnlocked(activeAcc()); } }),
      h('button', { class: 'danger', textContent: 'Quitar', onclick: async () => { if (!await ensureUnlocked(a)) return; if (!confirm(`¿Quitar la cuenta ${a.name} del launcher?`)) return; const r = await T.accounts.remove(a.id); if (r.ok) { takeAcc(r); await refreshAll(); } else $('accountsMsg').textContent = r.error; } }));
    box.append(row);
  }
}
async function refreshAll() { await renderAccountList(); await renderAccountBits(); }
function openAccounts() {
  $('accountsMsg').textContent = ''; $('accountsOverlay').classList.remove('hidden');
  const on = !!(info && info.apiEnabled); $('trBox').classList.toggle('hidden', !on); $('localBox').classList.toggle('hidden', on);
  if (on) setTrMode(trMode); renderAccountList();
}
// Cuentas T.R (con servidor): iniciar sesión / crear cuenta / recuperar contraseña
let trMode = 'login';
const TR_HELP = {
  login: 'Entra con tu cuenta T.R. Funciona en cualquier PC.',
  register: 'Tu nombre será único: nadie podrá registrar el mismo nombre ni uno casi igual (por ejemplo T1co_CR).',
  recover: 'Pide a un admin el comando /resetear con tu nombre y escribe aquí el código que te dé (vale 24 horas).'
};
function setTrMode(m) {
  trMode = m; $('tabLogin').classList.toggle('on', m !== 'register'); $('tabReg').classList.toggle('on', m === 'register');
  $('trPass2').classList.toggle('hidden', m !== 'register'); $('trCode').classList.toggle('hidden', m !== 'recover');
  if (m !== 'register') $('trClaim').classList.add('hidden');
  $('trForgot').classList.toggle('hidden', m !== 'login');
  $('trPass').placeholder = m === 'recover' ? 'Contraseña nueva (mínimo 6)' : m === 'register' ? 'Contraseña (mínimo 6)' : 'Contraseña';
  $('trGo').textContent = m === 'login' ? 'Iniciar sesión' : m === 'register' ? 'Crear cuenta' : 'Cambiar contraseña y entrar'; $('trHelp').textContent = TR_HELP[m];
}
$('tabLogin').addEventListener('click', () => { setTrMode('login'); $('accountsMsg').textContent = ''; });
$('tabReg').addEventListener('click', () => { setTrMode('register'); $('accountsMsg').textContent = ''; });
$('trForgot').addEventListener('click', () => { setTrMode('recover'); $('accountsMsg').textContent = ''; });
async function trSubmit() {
  const name = $('trName').value.trim(), pass = $('trPass').value, msg = $('accountsMsg'); msg.textContent = '';
  if (!name || !pass) { msg.textContent = 'Escribe tu nombre y tu contraseña.'; return; }
  if (trMode === 'register' && pass !== $('trPass2').value) { msg.textContent = 'Las contraseñas no coinciden.'; return; }
  const b = $('trGo'), label = b.textContent; b.disabled = true; b.textContent = 'Un momento…';
  let r;
  if (trMode === 'register') r = await T.accounts.register(name, pass, $('trClaim').classList.contains('hidden') ? '' : $('trClaim').value);
  else if (trMode === 'recover') r = await T.accounts.recover(name, $('trCode').value, pass);
  else r = await T.accounts.login(name, pass);
  b.disabled = false; b.textContent = label;
  if (!r.ok) {
    msg.textContent = r.error;
    if (r.code === 'needs_claim') { $('trClaim').classList.remove('hidden'); msg.textContent = r.error + ' Si eres del equipo, escribe la clave de dueño que te dieron.'; }
    return;
  }
  skinCache.delete(r.account.id); takeAcc(r);
  $('trName').value = $('trPass').value = $('trPass2').value = $('trCode').value = $('trClaim').value = '';
  log((trMode === 'register' ? 'Cuenta T.R creada: ' : 'Sesión iniciada: ') + r.account.name);
  setTrMode('login'); await refreshAll(); $('accountsOverlay').classList.add('hidden');
}
$('trGo').addEventListener('click', trSubmit);
['trName', 'trPass', 'trPass2', 'trCode', 'trClaim'].forEach(id => $(id).addEventListener('keydown', e => { if (e.key === 'Enter') trSubmit(); }));
$('accountChip').addEventListener('click', openAccounts);
$('manageAccounts').addEventListener('click', openAccounts);
$('accountsClose').addEventListener('click', () => $('accountsOverlay').classList.add('hidden'));
$('newOfflineBtn').addEventListener('click', async () => {
  if ($('newPass').value !== $('newPass2').value) { $('accountsMsg').textContent = 'Las contraseñas no coinciden.'; return; }
  const r = await T.accounts.addOffline($('newOffline').value, $('newPass').value);
  if (!r.ok) { $('accountsMsg').textContent = r.error; return; }
  takeAcc(r); $('newOffline').value = $('newPass').value = $('newPass2').value = ''; $('accountsMsg').textContent = ''; log('Cuenta local creada: ' + r.account.name); await refreshAll();
});
$('addMicrosoft').addEventListener('click', async () => {
  const b = $('addMicrosoft'); b.disabled = true; b.textContent = 'Esperando a Microsoft…'; $('accountsMsg').textContent = '';
  const r = await T.accounts.addMicrosoft();
  b.disabled = false; b.textContent = 'Añadir cuenta Microsoft';
  if (!r.ok) { $('accountsMsg').textContent = r.error; log('Error Microsoft: ' + r.error); return; }
  takeAcc(r); skinCache.delete(r.account.id); log('Sesión Microsoft guardada: ' + r.account.name); await refreshAll();
});
$('removeActive').addEventListener('click', async () => {
  const a = activeAcc(); if (!a) return; if (!await ensureUnlocked(a)) return; if (!confirm(`¿Quitar la cuenta ${a.name} del launcher?`)) return;
  const r = await T.accounts.remove(a.id); if (r.ok) { takeAcc(r); await refreshAll(); } else alert(r.error);
});

/* ---------- Perfil ---------- */
async function renderProfile() {
  const a = activeAcc();
  $('profileName').textContent = a ? a.name : 'Sin cuenta';
  $('profileType').textContent = a ? badgeText(a).replace(/^T\.R/, 'Cuenta T.R').replace(/^Local/, 'Cuenta local').replace(/^Microsoft/, 'Cuenta Microsoft') : '—';
  $('profileType').className = 'badge' + (a && (a.type === 'offline' || a.expired) ? ' local' : '');
  $('profileUuid').textContent = a ? 'UUID: ' + a.uuid.replace(/^(.{8})(.{4})(.{4})(.{4})(.{12})$/, '$1-$2-$3-$4-$5') : '';
  $('renameHelp').textContent = a && a.type === 'microsoft'
    ? 'Cambia el nombre oficial de tu cuenta Minecraft. Mojang solo permite un cambio cada 30 días y el nombre debe estar libre.'
    : a && a.type === 'tr' ? 'Tu nombre es único en todo el servidor: si otro jugador ya lo tiene (o uno casi igual), no te lo dejará usar. Al cambiarlo, tu identificador en el juego también cambia.'
    : 'Elige un nombre de 3 a 16 caracteres: letras, números o guion bajo. Al cambiarlo, tu identificador en el servidor también cambia.';
  $('renameInput').disabled = $('renameBtn').disabled = !a;
  const local = !!a && (a.type === 'offline' || a.type === 'tr');
  $('passwordCard').classList.toggle('hidden', !local);
  if (local) { $('pwOldWrap').classList.toggle('hidden', a.type === 'offline' && !a.hasPassword); $('pwHelp').textContent = a.type === 'tr' ? 'Cambia la contraseña con la que entras a tu cuenta T.R. Se cerrará la sesión en tus otros PC.' : a.hasPassword ? 'Cambia la contraseña con la que entras a esta cuenta en el launcher.' : 'Esta cuenta aún no tiene contraseña. Ponle una para protegerla.'; }
  if (a) await paintHead($('profileHead'), a.id); else drawHead($('profileHead'), null);
  const u = a ? await skinOf(a.id) : null;
  window.Skin3D.draw($('profileSkin'), u ? await loadImg(u) : null, { yaw: -0.5, pitch: 0.1, slim: !!a && a.variant === 'slim' });
}
$('renameBtn').addEventListener('click', async () => {
  const a = activeAcc(); if (!a) return; if (!await ensureUnlocked(a)) return; const name = $('renameInput').value.trim();
  if (a.type === 'microsoft' && !confirm(`¿Cambiar tu nombre de Minecraft a "${name}"? Mojang solo permite un cambio cada 30 días.`)) return;
  $('renameBtn').disabled = true;
  const r = await T.accounts.rename(a.id, name); $('renameBtn').disabled = false;
  if (!r.ok) { alert(r.error); return; }
  takeAcc(r); $('renameInput').value = ''; log('Nombre cambiado a ' + name); await refreshAll();
});

/* ---------- Skins ---------- */
let busySkin = false;
const REDUCED = matchMedia('(prefers-reduced-motion: reduce)').matches;
// Visor de la skin principal: se gira arrastrando, camina (pausable) y admite una capa de prueba
let capeImg = null;   // la textura de la capa llega como data URL desde el proceso principal (evita canvas "contaminado")
const viewerCtl = window.Skin3D.viewer($('skinBig'), { getImg: () => bigImg, getSlim: () => $('skinVariant').value === 'slim', getCape: () => capeImg });
const PAUSE_SVG = '<svg viewBox="0 0 24 24"><rect x="6" y="5" width="4" height="14" rx="1"/><rect x="14" y="5" width="4" height="14" rx="1"/></svg>';
const PLAY_SVG = '<svg viewBox="0 0 24 24"><path d="M7 4.5v15a1 1 0 0 0 1.5.86l12-7.5a1 1 0 0 0 0-1.72l-12-7.5A1 1 0 0 0 7 4.5z"/></svg>';
$('vPlay').addEventListener('click', () => { const p = viewerCtl.togglePlay(); $('vPlay').innerHTML = p ? PAUSE_SVG : PLAY_SVG; $('vPlay').classList.toggle('on', !p); });
$('vCape').addEventListener('click', () => { $('vCape').classList.toggle('on', viewerCtl.toggleCape()); });
$('vShot').addEventListener('click', async () => { const r = await T.skins.savePng(viewerCtl.snapshot()); $('skinNote').textContent = r.ok ? (r.canceled ? '' : 'Imagen guardada.') : r.error; });
$('skinVariant').addEventListener('change', () => viewerCtl.redraw());
let skinFilter = 'all';
const favs = (() => { try { return new Set(JSON.parse(localStorage.getItem('tr.favs') || '[]')); } catch { return new Set(); } })();
const saveFavs = () => { try { localStorage.setItem('tr.favs', JSON.stringify([...favs])); } catch {} };
async function renderSkins() {
  const a = activeAcc();
  $('skinOwner').textContent = a ? a.name : 'Crea una cuenta primero';
  $('skinLocalNote').classList.toggle('hidden', !(a && a.type === 'offline'));
  if (a) { $('skinVariant').value = a.variant || 'classic'; const u = await skinOf(a.id); bigImg = u ? await loadImg(u) : null; } else bigImg = null;
  viewerCtl.redraw();
  const cats = [...new Set(catalog.map(x => x.category))];
  const defs = [['all', 'Todos'], ['fav', 'Favoritas'], ...(library.length ? [['Mis skins', 'Mis skins']] : []), ...cats.map(c => [c, c])];
  if (!defs.some(([k]) => k === skinFilter)) skinFilter = 'all';
  $('skinChips').replaceChildren(...defs.map(([k, label]) => h('button', { class: 'chipBtn' + (skinFilter === k ? ' on' : ''), textContent: label, onclick: () => { skinFilter = k; renderSkins(); } })));
  let items = [...library, ...catalog].filter(x => skinFilter === 'all' ? true : skinFilter === 'fav' ? favs.has(x.id) : x.category === skinFilter);
  const q = $('skinSearch').value.trim().toLowerCase(); if (q) items = items.filter(x => x.name.toLowerCase().includes(q));
  if ($('skinSort').value === 'az') items.sort((x, y) => x.name.localeCompare(y.name, 'es'));
  const g = $('skinGallery'); g.replaceChildren(); $('skinEmpty').classList.toggle('hidden', items.length > 0);
  for (const sk of items) {
    const isLib = sk.id.startsWith('lib_'), slim = sk.variant === 'slim';
    const cv = h('canvas', { width: 110, height: 170 });
    let im = null, yaw = -0.55, raf = 0; const paint = () => window.Skin3D.draw(cv, im, { yaw, pitch: 0.12, slim });
    loadImg(sk.dataUrl).then(i => { im = i; paint(); });
    const pick = h('button', { class: 'skinItem' + (isLib ? ' lib' : '') + (a && a.skinId === sk.id ? ' selected' : ''), title: sk.name,
      onclick: () => { const cur = activeAcc(); if (!cur) return openAccounts(); applySkin(() => isLib ? T.skins.applyLibrary(cur.id, sk.id, sk.variant) : T.skins.applyCatalog(cur.id, sk.id, $('skinVariant').value)); } },
      h('div', { class: 'skHead', textContent: sk.name }), h('div', { class: 'skBody' }, cv), h('div', { class: 'skFoot', textContent: slim ? 'Fino' : 'Clásico' }));
    if (!REDUCED) {   // al pasar el ratón, la skin gira
      pick.addEventListener('mouseenter', () => { const t0 = performance.now(), y0 = yaw; const tick = t => { yaw = y0 + (t - t0) / 1000 * 2.4; paint(); raf = requestAnimationFrame(tick); }; raf = requestAnimationFrame(tick); });
      pick.addEventListener('mouseleave', () => { cancelAnimationFrame(raf); yaw = -0.55; paint(); });
    }
    const heart = h('button', { class: 'heart' + (favs.has(sk.id) ? ' on' : ''), title: 'Favorita', textContent: favs.has(sk.id) ? '♥' : '♡', onclick: e => { e.stopPropagation(); favs.has(sk.id) ? favs.delete(sk.id) : favs.add(sk.id); saveFavs(); renderSkins(); } });
    const del = isLib ? h('button', { class: 'skinDel', title: 'Quitar de Mis skins', textContent: '✕', onclick: async e => { e.stopPropagation(); const r = await T.skins.removeLibrary(sk.id); if (r.ok) { library = r.library; renderSkins(); } } }) : null;
    g.append(h('div', { class: 'skinCell' }, pick, heart, del, a && a.skinId === sk.id ? h('span', { class: 'inUse', textContent: 'En uso' }) : null));
  }
}
$('skinSearch').addEventListener('input', () => renderSkins());
$('skinSort').addEventListener('change', () => renderSkins());
async function applySkin(fn) {
  const a = activeAcc(); if (!a) { openAccounts(); return; } if (busySkin) return; if (!await ensureUnlocked(a)) return; busySkin = true;
  $('skinNote').textContent = 'Aplicando skin…';
  const r = await fn(); busySkin = false;
  if (!r.ok) { $('skinNote').textContent = r.error; return; }
  if (r.canceled) { $('skinNote').textContent = ''; return; }
  if (r.library) library = r.library;
  skinCache.set(a.id, r.skin); acc.accounts = acc.accounts.map(x => x.id === a.id ? { ...r.account, locked: false } : x);
  $('skinNote').textContent = r.note; log('Skin cambiada para ' + a.name); await refreshAll(); await renderSkins();
}
$('skinUpload').addEventListener('click', () => { const a = activeAcc(); if (a) applySkin(() => T.skins.applyUpload(a.id, $('skinVariant').value)); else openAccounts(); });
$('skinPlayerBtn').addEventListener('click', () => { const a = activeAcc(); const n = $('skinPlayer').value.trim(); if (!a) return openAccounts(); if (n) applySkin(() => T.skins.applyPlayer(a.id, n)); });
$('skinNameMC').addEventListener('click', () => T.openExternal('namemc'));

/* ---------- Servidores ---------- */
function setStatus(pill, playersEl, r) {
  if (r && r.available) { pill.className = 'statusPill on'; pill.textContent = 'En línea'; playersEl.textContent = `${r.online} / ${r.max} jugadores`; }
  else if (r && r.reachable) { pill.className = 'statusPill off'; pill.textContent = 'Apagado'; playersEl.textContent = '—'; }
  else { pill.className = 'statusPill off'; pill.textContent = 'Sin respuesta'; playersEl.textContent = '—'; }
}
async function pingInto(sv, pill, playersEl) {
  pill.className = 'statusPill wait'; pill.textContent = 'Comprobando…';
  const r = await T.servers.ping(sv.id); setStatus(pill, playersEl, r.ok ? r.status : null);
}
// Galería de capturas de un servidor: flechas, miniaturas y cambio automático (se detiene al pasar el ratón)
function buildGallery(list, name) {
  let i = 0, timer = null;
  const img = h('img', { class: 'galImg', alt: name, draggable: false });
  const count = h('span', { class: 'galCount' });
  const thumbs = list.map((src, k) => h('button', { class: 'galThumb', ariaLabel: `Imagen ${k + 1}`, onclick: () => { go(k); restart(); } }, h('img', { src, alt: '', draggable: false })));
  const go = k => { i = (k + list.length) % list.length; img.src = list[i]; count.textContent = `${i + 1} / ${list.length}`; thumbs.forEach((t, j) => t.classList.toggle('on', j === i)); };
  const restart = () => { clearInterval(timer); if (!REDUCED) timer = setInterval(() => { if (!document.body.contains(root)) clearInterval(timer); else go(i + 1); }, 4500); };
  const prev = h('button', { class: 'galNav prev', textContent: '‹', ariaLabel: 'Anterior', onclick: () => { go(i - 1); restart(); } });
  const next = h('button', { class: 'galNav next', textContent: '›', ariaLabel: 'Siguiente', onclick: () => { go(i + 1); restart(); } });
  const root = h('div', { class: 'gallery' }, h('div', { class: 'galStage' }, img, prev, next, count), h('div', { class: 'galThumbs' }, ...thumbs));
  root.addEventListener('mouseenter', () => clearInterval(timer)); root.addEventListener('mouseleave', restart);
  go(0); restart(); return root;
}
const pingers = [];
function renderServers() {
  const list = $('serverList'); list.replaceChildren(); pingers.length = 0;
  const st = info.staff || {};
  for (const sv of info.servers) {
    const pill = h('span', { class: 'statusPill wait', textContent: 'Comprobando…' }), players = h('dd', { textContent: '—' });
    const copyBtn = h('button', { class: 'secondary', textContent: 'Copiar dirección', onclick: async () => { await T.servers.copy(sv.id); copyBtn.textContent = '¡Copiada!'; setTimeout(() => { copyBtn.textContent = 'Copiar dirección'; }, 1500); } });
    const rows = [['IP', sv.host], ['Puerto', String(sv.port)], ['Dirección', `${sv.host}:${sv.port}`], ['Versión', info.version]];
    const dl = h('dl', { class: 'serverInfo' }); for (const [k, v] of rows) dl.append(h('dt', { textContent: k }), h('dd', { textContent: v }));
    dl.append(h('dt', { textContent: 'Jugadores' }), players);
    const actions = h('div', { class: 'serverActions' }, copyBtn, h('button', { class: 'primary launchBtn', textContent: '▶　Entrar', onclick: e => play(sv.id, 'main', e.currentTarget) }),
      st.allowed && st.installed ? h('button', { class: 'primary launchBtn', textContent: '▶　Entrar (Staff)', onclick: e => play(sv.id, 'staff', e.currentTarget) }) : null);
    list.append(h('div', { class: 'card serverCard hoverable' }, h('img', { class: 'serverImg', src: sv.image, alt: sv.name, draggable: false }),
      h('div', { class: 'serverBody' }, h('div', { class: 'serverTop' }, h('h3', { textContent: sv.name }), h('span', { class: 'tag', textContent: sv.tag }), pill), dl, actions),
      sv.gallery && sv.gallery.length ? buildGallery(sv.gallery, sv.name) : null));
    pingers.push(() => pingInto(sv, pill, players)); pingInto(sv, pill, players);
  }
  document.querySelectorAll('.launchBtn').forEach(b => { b.disabled = running; });
}
$('serversRefresh').addEventListener('click', () => pingers.forEach(f => f()));

/* ---------- Ajustes ---------- */
const RES = [[925, 530], [1280, 720], [1366, 768], [1600, 900], [1920, 1080], [2560, 1440]];
const gb = mb => (mb / 1024).toFixed(1).replace('.0', '') + ' GB';
function bindRange(id, out, fmt) { const el = $(id); const upd = () => { $(out).textContent = fmt(Number(el.value)); }; el.addEventListener('input', upd); return upd; }
function fillSettings() {
  const maxCap = Math.max(1024, info.totalMemMb - 1024);
  $('ramMax').min = 1024; $('ramMax').max = maxCap; $('ramMin').min = 512; $('ramMin').max = maxCap;
  $('ramMax').value = settings.ramMaxMb; $('ramMin').value = settings.ramMinMb;
  $('ramHint').textContent = `Tu equipo tiene ${gb(info.totalMemMb)} de RAM. Deja al menos 1 GB libre para Android. Con 4 a 6 GB basta para la mayoría del tiempo.`;
  $('cores').max = info.cores; $('cores').value = settings.cores;
  $('jvmPreset').value = settings.jvmPreset; $('fullscreen').checked = settings.fullscreen; $('windowCompat').checked = settings.windowCompat;
  $('perfPreset').value = settings.perfPreset; $('gpu').value = settings.gpu; $('highPriority').checked = settings.highPriority;
  $('gpuInfo').textContent = info.gpus && info.gpus.length ? 'Gráficos detectados: ' + info.gpus.join(' + ') + '. En portátiles con dos tarjetas, «Alto rendimiento» fuerza la dedicada.' : 'No se pudo detectar la tarjeta gráfica.';
  syncPreset2();
  $('maxFps').value = settings.maxFps; $('renderDistance').value = settings.renderDistance; $('graphics').value = settings.graphics; $('vsync').checked = settings.vsync;
  $('resW').value = settings.width; $('resH').value = settings.height;
  const sel = $('resPreset'); sel.replaceChildren(h('option', { value: 'custom', textContent: 'Personalizada' }), ...RES.map(([w, hh]) => h('option', { value: `${w}x${hh}`, textContent: `${w} × ${hh}` })));
  syncPreset(); updRanges();
}
function syncPreset2() { const max = $('perfPreset').value === 'max'; $('renderDistance').disabled = $('graphics').disabled = max; }
$('perfPreset').addEventListener('change', syncPreset2);
function syncPreset() { const k = `${$('resW').value}x${$('resH').value}`; $('resPreset').value = RES.some(([w, hh]) => `${w}x${hh}` === k) ? k : 'custom'; }
const updRamMin = bindRange('ramMin', 'ramMinOut', gb), updRamMax = bindRange('ramMax', 'ramMaxOut', gb);
const updCores = bindRange('cores', 'coresOut', v => v === 0 ? 'Automático' : v + (v === 1 ? ' núcleo' : ' núcleos'));
const updFps = bindRange('maxFps', 'fpsOut', v => v >= 260 ? 'Sin límite' : v + ' FPS'), updRd = bindRange('renderDistance', 'rdOut', v => v + ' chunks');
function updRanges() { updRamMin(); updRamMax(); updCores(); updFps(); updRd(); }
$('ramMax').addEventListener('input', () => { if (Number($('ramMin').value) > Number($('ramMax').value)) { $('ramMin').value = $('ramMax').value; updRamMin(); } });
$('ramMin').addEventListener('input', () => { if (Number($('ramMin').value) > Number($('ramMax').value)) { $('ramMax').value = $('ramMin').value; updRamMax(); } });
$('resPreset').addEventListener('change', () => { if ($('resPreset').value !== 'custom') { const [w, hh] = $('resPreset').value.split('x'); $('resW').value = w; $('resH').value = hh; } });
$('resW').addEventListener('input', syncPreset); $('resH').addEventListener('input', syncPreset);
$('saveSettings').addEventListener('click', async () => {
  const r = await T.saveSettings({
    ramMinMb: +$('ramMin').value, ramMaxMb: +$('ramMax').value, cores: +$('cores').value, jvmPreset: $('jvmPreset').value,
    width: +$('resW').value, height: +$('resH').value, fullscreen: $('fullscreen').checked, windowCompat: $('windowCompat').checked,
    maxFps: +$('maxFps').value, renderDistance: +$('renderDistance').value, graphics: $('graphics').value, vsync: $('vsync').checked, autoJoin: true,
    perfPreset: $('perfPreset').value, gpu: $('gpu').value, highPriority: $('highPriority').checked
  });
  if (r.ok) { settings = r.settings; fillSettings(); $('saveMsg').textContent = 'Ajustes guardados. Se aplicarán la próxima vez que inicies Minecraft.'; log('Ajustes guardados.'); }
  else $('saveMsg').textContent = r.error;
});
$('pwSave').addEventListener('click', async () => {
  const a = activeAcc(); if (!a) return; if (!await ensureUnlocked(a)) return;
  const r = await T.accounts.setPassword(a.id, $('pwOld').value, $('pwNew').value);
  if (!r.ok) { $('pwMsg').textContent = r.error; return; }
  takeAcc(r); $('pwOld').value = $('pwNew').value = ''; $('pwMsg').textContent = 'Contraseña guardada.'; log('Contraseña actualizada.'); await refreshAll();
});
$('openFolder').addEventListener('click', () => T.openGameFolder());

/* ---------- Rendimiento en vivo ---------- */
const fmtTime = s => `${Math.floor(s / 3600) ? Math.floor(s / 3600) + ' h ' : ''}${Math.floor(s % 3600 / 60)} min ${s % 60} s`;
T.onPerf(p => { $('mRam').textContent = gb(p.ramMb); $('mCpu').textContent = p.cpuPct == null ? '…' : p.cpuPct + ' %'; $('mTime').textContent = fmtTime(p.uptimeSec); });
function setRunning(v) {
  running = v; $('statusDot').classList.toggle('busy', v); $('statusText').textContent = v ? 'Minecraft en ejecución' : 'Launcher preparado';
  if (!v) { $('mRam').textContent = $('mCpu').textContent = $('mTime').textContent = '—'; }
  document.querySelectorAll('.launchBtn').forEach(b => { b.disabled = v; });
}

/* ---------- Jugar ---------- */
async function play(join, profile, btn) {
  if (!activeAcc()) { openAccounts(); return; }
  if (!await ensureUnlocked(activeAcc())) return;
  const label = btn.textContent; btn.disabled = true; btn.textContent = 'Iniciando…';
  const r = await T.launch(join, profile);
  btn.textContent = label; btn.disabled = false;
  if (!r.ok) { log('Error al iniciar: ' + r.error); alert('No se pudo iniciar Minecraft: ' + r.error); return; }
  setRunning(true); log('Minecraft se está iniciando.');
}
$('play').addEventListener('click', () => play(false, 'main', $('play')));
T.onLog(l => log(l));
T.onClosed(c => { setRunning(false); log('Minecraft se cerró (código ' + c + ').'); });

/* ---------- Descarga inicial ---------- */
let progressSink = null;
T.onInstallProgress(p => {
  if (progressSink) return progressSink(p);
  $('installStage').textContent = p.stage + (p.total > 1 ? `  ${p.done} / ${p.total}` : '');
  if (p.total > 0) $('installFill').style.width = Math.round(p.done / p.total * 100) + '%';
});
function showInstallDir() { $('installDirLabel').textContent = info.installDir; $('installPath').textContent = info.installDir; }
$('installDirBtn').addEventListener('click', async () => {
  const r = await T.chooseInstallDir();
  if (!r.ok) { $('installStage').textContent = r.error; return; }
  if (r.canceled) return;
  info.installDir = r.dir; showInstallDir(); $('installStage').textContent = '';
  if (r.installed) { info.installed = true; $('installOverlay').classList.add('hidden'); log('Instalación encontrada en ' + r.dir); if (!acc.accounts.length) openAccounts(); }
});
$('installBtn').addEventListener('click', async () => {
  $('installBtn').disabled = $('installDirBtn').disabled = true; $('installBtn').textContent = 'Descargando…'; $('installBar').classList.remove('hidden'); $('statusDot').classList.add('busy'); $('statusText').textContent = 'Descargando Minecraft';
  const r = await T.install();
  $('statusDot').classList.remove('busy'); $('statusText').textContent = 'Launcher preparado';
  if (!r.ok) { $('installStage').textContent = 'Error: ' + r.error + ' — pulsa de nuevo para reintentar (continúa donde se quedó).'; $('installBtn').disabled = $('installDirBtn').disabled = false; $('installBtn').textContent = 'Reintentar descarga'; return; }
  info.installed = true; $('installOverlay').classList.add('hidden'); log('Minecraft ' + info.version + ' instalado.');
  if (!acc.accounts.length) openAccounts();
});

$('repairBtn').addEventListener('click', async () => {
  const b = $('repairBtn'); b.disabled = true; b.textContent = 'Verificando…'; $('repairBar').classList.remove('hidden'); $('statusDot').classList.add('busy'); $('statusText').textContent = 'Verificando archivos';
  progressSink = p => { $('repairStage').textContent = p.stage + (p.total > 1 ? `  ${p.done} / ${p.total}` : ''); if (p.total > 0) $('repairFill').style.width = Math.round(p.done / p.total * 100) + '%'; };
  const r = await T.install(); progressSink = null;
  b.disabled = false; b.textContent = 'Verificar y reparar archivos'; $('statusDot').classList.remove('busy'); $('statusText').textContent = 'Launcher preparado';
  $('repairStage').textContent = r.ok ? 'Todo en orden: los archivos están completos.' : 'Error: ' + r.error;
  if (r.ok) setTimeout(() => $('repairBar').classList.add('hidden'), 1500);
});

/* ---------- Perfil Staff y panel de comandos (solo cuentas con rol staff / admin / dueño) ---------- */
function renderStaff() {
  const st = info.staff || {}, ok = !!st.allowed;
  $('staffCard').classList.toggle('hidden', !ok); $('logCard').classList.toggle('hidden', !ok); $('navPanel').classList.toggle('hidden', !ok);
  if (!ok) { if (!$('panel').classList.contains('hidden')) showPage('home'); return; }
  $('staffRepair').classList.toggle('hidden', !st.installed);
  $('staffTitle').textContent = 'T.R Staff · ' + info.version;
  $('staffStatus').textContent = st.installed ? 'Instalado' : 'Falta instalar';
  $('staffPlay').textContent = st.installed ? '▶　Jugar' : '⇩　Instalar';
  $('panelRole').textContent = ROLE_LABEL[st.role] || '';
  if (!$('servers').classList.contains('hidden')) renderServers();
  if (!st.installed) { $('staffPlay').onclick = installStaff; } else { $('staffPlay').onclick = () => play(false, 'staff', $('staffPlay')); }
}
async function installStaff() {
  $('staffPlay').disabled = true; $('staffStatus').textContent = 'Instalando…'; $('statusDot').classList.add('busy');
  const off = p => { $('staffStatus').textContent = p.stage + (p.total > 1 ? ` ${p.done}/${p.total}` : ''); };
  progressSink = off;
  const r = await T.staff.install(); progressSink = null;
  $('statusDot').classList.remove('busy'); $('staffPlay').disabled = false;
  if (!r.ok) { $('staffStatus').textContent = 'Error: ' + r.error; log('Error: ' + r.error); return; }
  info.staff = r.staff; renderStaff(); log('Perfil adicional instalado.');
  if (r.skinMod && !r.skinMod.ok) { $('staffStatus').textContent = 'Instalado (sin mod de skins: ' + r.skinMod.error + ')'; log('Mod de skins no instalado: ' + r.skinMod.error); }
}
$('staffRepair').addEventListener('click', installStaff);

// Panel de comandos: /hacer staff a rolando grams, /quitar staff a …, /lista, /ayuda …
const panelHist = []; let histPos = 0;
function panelPrint(text, isErr) { const l = h('div', { class: isErr ? 'err' : '', textContent: text }); $('panelOut').append(l); $('panelOut').scrollTop = $('panelOut').scrollHeight; }
async function panelRun(text) {
  text = String(text || '').trim(); if (!text) return;
  panelHist.push(text); histPos = panelHist.length; $('panelInput').value = '';
  panelPrint('> ' + text);
  $('panelSend').disabled = true;
  const r = await T.staff.cmd(text);
  $('panelSend').disabled = false;
  if (!r.ok) panelPrint('✖ ' + r.error, true);
  else if (r.failed) panelPrint('✖ ' + (r.error || 'No se pudo.'), true);
  else r.lines.forEach(l => panelPrint(l));
  const st = await T.staff.state(); if (st.ok) { info.staff = st.staff; renderStaff(); }   // por si cambió tu propio rol
  $('panelInput').focus();
}
$('panelOut').replaceChildren(h('div', { class: 'note', textContent: 'Escribe un comando y pulsa Enter. Con /ayuda ves todos.' }));
$('panelSend').addEventListener('click', () => panelRun($('panelInput').value));
$('panelInput').addEventListener('keydown', e => {
  if (e.key === 'Enter') panelRun($('panelInput').value);
  else if (e.key === 'ArrowUp' && panelHist.length) { histPos = Math.max(0, histPos - 1); $('panelInput').value = panelHist[histPos]; e.preventDefault(); }
  else if (e.key === 'ArrowDown') { histPos = Math.min(panelHist.length, histPos + 1); $('panelInput').value = panelHist[histPos] || ''; e.preventDefault(); }
});
$('panelQuick').replaceChildren(...[['/hacer staff a …', '/hacer staff a '], ['/lista', '/lista'], ['/cuentas', '/cuentas'], ['/ayuda', '/ayuda']].map(([label, cmd]) =>
  h('button', { class: 'chipBtn', textContent: label, onclick: () => { if (cmd.endsWith(' ')) { $('panelInput').value = cmd; $('panelInput').focus(); } else panelRun(cmd); } })));

/* ---------- Otros botones ---------- */
$('discordBtn').addEventListener('click', () => T.openExternal('discord'));
$('discordBanner').addEventListener('click', () => T.openExternal('discord'));
$('homePlay').addEventListener('click', () => showPage('versions'));
$('homeDiscord').addEventListener('click', () => showPage('discord'));
$('playBack').addEventListener('click', () => showPage('home'));
$('discordBack').addEventListener('click', () => showPage('home'));
$('editProfile').addEventListener('click', () => { $('renameInput').focus(); $('renameInput').scrollIntoView({ block: 'center', behavior: REDUCED ? 'auto' : 'smooth' }); });
$('changeSkin').addEventListener('click', () => showPage('skins'));
$('winMin').addEventListener('click', () => T.win.minimize());
$('winClose').addEventListener('click', () => T.win.close());

/* ---------- Arranque ---------- */
(async () => {
  const r = await T.info(); if (!r.ok) { log('Error: ' + r.error); return; }
  info = r; settings = r.settings;
  $('heroVer').textContent = 'v' + info.appVersion; showPage('home');
  $('versionTitle').textContent = 'Minecraft Java ' + info.version; $('installVersion').textContent = 'Minecraft Java ' + info.version;
  const c = await T.skins.catalog(); if (c.ok) { catalog = c.skins; library = c.library || []; if (c.cape) loadImg(c.cape).then(i => { capeImg = i; viewerCtl.redraw(); }); }
  const l = await T.accounts.list(); if (l.ok) acc = l;
  fillSettings(); showInstallDir(); await refreshAll(); setRunning(info.running); renderStaff();
  if (!info.installed) $('installOverlay').classList.remove('hidden');
  else if (!acc.accounts.length) openAccounts();
  log('T.R Launcher listo.');
})();
