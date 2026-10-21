/* T.R Launcher · Android
   Crea window.TR (la misma API que el launcher de PC) hablando con la app nativa (TRNative).
   Cuentas, skins, instalación y «Jugar» los hace la app; aquí solo se reenvían. */
(() => {
  if (window.TR) return;
  const LS = {
    get: (k, d) => { try { const v = localStorage.getItem('trm.' + k); return v == null ? d : JSON.parse(v); } catch { return d; } },
    set: (k, v) => { try { localStorage.setItem('trm.' + k, JSON.stringify(v)); } catch {} }
  };
  const SERVERS = [
    { id: 'jamas', name: 'Nunca Jamás', tag: 'Survival', host: 'NuncaJamasbyTicoCR_.aternos.me', port: 64008, image: 'assets/server_jamas.jpg', gallery: [] },
    { id: 'creativo', name: 'Nunca Creativo', tag: 'Creativo', host: 'NuncaCreativoTR.aternos.me', port: 14689, image: 'assets/server_creativo.jpg',
      gallery: [1, 2, 3, 4, 5, 6, 7].map(n => `assets/creativo/${n}.jpg`) }
  ];
  const EXTERNAL = { discord: 'https://discord.gg/Fymufuwt6', namemc: 'https://es.namemc.com/minecraft-skins' };
  const defaults = { ramMinMb: 1024, ramMaxMb: 2048, cores: 0, jvmPreset: 'optimized', width: 1280, height: 720, fullscreen: false, windowCompat: false,
    maxFps: 60, renderDistance: 8, graphics: 'fast', vsync: false, perfPreset: 'max', gpu: 'auto', highPriority: false };
  const noop = () => {};
  const fail = error => ({ ok: false, error });

  /* ---- comunicación con la app ---- */
  const pending = new Map(); let seq = 0;
  const L = { log: [], closed: [], install: [], perf: [] };
  window.__trDone = (id, json) => {
    const res = pending.get(id); if (!res) return; pending.delete(id);
    try { res(JSON.parse(json)); } catch { res(fail('Respuesta no válida de la app.')); }
  };
  window.__trEmit = (name, json) => {
    let d; try { d = JSON.parse(json); } catch { d = json; }
    (L[name] || []).forEach(f => { try { f(d); } catch {} });
  };
  const call = (method, args) => new Promise(resolve => {
    const id = ++seq; pending.set(id, resolve);
    try { window.TRNative.invoke(id, method, JSON.stringify(args || {})); }
    catch { pending.delete(id); resolve(fail('No se pudo comunicar con la app.')); }
  });

  const toDataUrl = u => fetch(u).then(r => r.blob()).then(b => new Promise(res => { const f = new FileReader(); f.onload = () => res(f.result); f.readAsDataURL(b); }));
  const fileToDataUrl = f => new Promise((res, rej) => { const rd = new FileReader(); rd.onload = () => res(rd.result); rd.onerror = rej; rd.readAsDataURL(f); });
  const uid = () => Math.random().toString(16).slice(2, 10);

  window.TR = {
    info: async () => {
      const r = await call('info'); if (!r.ok) return r;
      return { ...r, settings: { ...defaults, ...LS.get('settings', {}), ramMaxMb: r.ramMaxMb || defaults.ramMaxMb },
        version: '1.21.11', discord: EXTERNAL.discord, servers: SERVERS, cores: Math.max(2, navigator.hardwareConcurrency || 4), gpus: [] };
    },
    win: { minimize: noop, close: noop },
    saveSettings: async s => { LS.set('settings', s); await call('settings.save', { ramMaxMb: s.ramMaxMb }); return { ok: true, settings: s }; },
    install: () => call('install'),
    chooseInstallDir: async () => fail('En el teléfono la carpeta de instalación no se puede cambiar.'),
    engineSettings: () => call('engineSettings'),
    staff: {
      state: () => call('staff.state'),
      install: async () => fail('El perfil Staff solo existe en el launcher de PC.'),
      cmd: text => call('staff.cmd', { text })
    },
    servers: {
      ping: async id => {
        const sv = SERVERS.find(s => s.id === id);
        try {
          const r = await fetch(`https://api.mcsrvstat.us/3/${sv.host}:${sv.port}`, { cache: 'no-store' }); const j = await r.json();
          return { ok: true, status: { reachable: true, online: !!j.online, players: j.players ? j.players.online : 0, max: j.players ? j.players.max : 0, available: !!j.online } };
        } catch { return { ok: true, status: null }; }
      },
      copy: async id => { const sv = SERVERS.find(s => s.id === id); return call('copy', { text: `${sv.host}:${sv.port}` }); }
    },
    openExternal: async key => call('open', { url: EXTERNAL[key] || '' }),
    openGameFolder: async () => fail('Los archivos del juego están dentro de la app.'),
    accounts: {
      list: () => call('accounts.list'),
      addOffline: (name, password) => call('accounts.addOffline', { name, password }),
      register: (name, password, claim) => call('accounts.register', { name, password, claim }),
      login: (name, password) => call('accounts.login', { name, password }),
      recover: (name, code, password) => call('accounts.recover', { name, code, password }),
      unlock: () => call('accounts.list'),
      setPassword: (id, oldPassword, newPassword) => call('accounts.setPassword', { id, oldPassword, newPassword }),
      addMicrosoft: () => call('accounts.addMicrosoft'),
      setActive: id => call('accounts.setActive', { id }),
      remove: id => call('accounts.remove', { id }),
      rename: (id, name) => call('accounts.rename', { id, name })
    },
    skins: {
      catalog: async () => {
        try {
          const list = await (await fetch('skins/skins.json')).json();
          const skins = list.map(s => ({ ...s, dataUrl: `skins/${s.id}.png` }));
          const cape = await toDataUrl('assets/cape_tr.png').catch(() => null);
          return { ok: true, skins, library: LS.get('library', []), cape };
        } catch { return fail('No se pudieron cargar las skins.'); }
      },
      current: id => call('skin.current', { id }),
      applyCatalog: async (accountId, skinId, variant) => {
        const dataUrl = await toDataUrl(`skins/${skinId}.png`);
        return call('skin.set', { id: accountId, dataUrl, variant: variant || 'classic' });
      },
      applyLibrary: async (accountId, id, variant) => {
        const it = LS.get('library', []).find(x => x.id === id); if (!it) return fail('Skin no encontrada.');
        const r = await call('skin.set', { id: accountId, dataUrl: it.dataUrl, variant: variant || it.variant || 'classic' });
        return { ...r, library: LS.get('library', []) };
      },
      libraryRemove: async id => { const l = LS.get('library', []).filter(x => x.id !== id); LS.set('library', l); return { ok: true, library: l }; },
      removeLibrary: async id => { const l = LS.get('library', []).filter(x => x.id !== id); LS.set('library', l); return { ok: true, library: l }; },
      savePng: dataUrl => call('savePng', { dataUrl }),
      applyUpload: (accountId, variant) => new Promise(resolve => {
        const inp = document.createElement('input'); inp.type = 'file'; inp.accept = 'image/png'; inp.multiple = true;
        inp.onchange = async () => {
          const files = Array.from(inp.files || []).slice(0, 20); if (!files.length) return resolve({ ok: true, canceled: true });
          const lib = LS.get('library', []); let first = null;
          for (const f of files) {
            try {
              const dataUrl = await fileToDataUrl(f); const id = 'lib_' + uid();
              lib.unshift({ id, name: f.name.replace(/\.png$/i, ''), category: 'Mis skins', variant: variant || 'classic', dataUrl });
              if (!first) first = { id, dataUrl };
            } catch {}
          }
          LS.set('library', lib);
          if (!first) return resolve(fail('Ninguna skin válida (deben ser PNG de 64x64).'));
          const r = await call('skin.set', { id: accountId, dataUrl: first.dataUrl, variant: variant || 'classic' });
          resolve({ ...r, library: lib });
        };
        inp.oncancel = () => resolve({ ok: true, canceled: true });
        inp.click();
      }),
      applyPlayer: (accountId, name) => call('skin.player', { id: accountId, name })
    },
    launch: (join, profile) => call('launch', { join: join || '', profile: profile || 'main' }),
    onLog: cb => L.log.push(d => cb(d && d.line ? d.line : String(d))),
    onClosed: cb => L.closed.push(cb),
    onInstallProgress: cb => L.install.push(cb),
    onPerf: cb => L.perf.push(cb)
  };

  document.addEventListener('DOMContentLoaded', () => {
    const b = document.getElementById('engineBtn');
    if (b) b.addEventListener('click', () => window.TR.engineSettings());
  });
})();
