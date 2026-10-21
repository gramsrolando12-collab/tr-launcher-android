// Visor 3D de skins en canvas (sin librerías): proyección ortogonal con caras texturizadas.
// Soporta: giro libre con el ratón, animación de caminar (pausable), capa y zoom.
(function () {
  const OV_HEAD = 0.5, OV = 0.25;

  // Partes del cuerpo. pv = pivote de la animación, th = giro (rad) alrededor del eje X en ese pivote.
  function parts(slim, legacy, a) {
    const aw = slim ? 3 : 4, ph = a.phase, am = a.amp;
    const sw = Math.sin(ph) * am, armA = 0.75 * sw, legA = 0.85 * sw;
    const P = [
      { b: { w: 8, h: 8, d: 8, c: [0, 28, 0], uv: [0, 0] }, o: { inf: OV_HEAD, uv: [32, 0] }, pv: [0, 24, 0], th: 0 },
      { b: { w: 8, h: 12, d: 4, c: [0, 18, 0], uv: [16, 16] }, o: legacy ? null : { inf: OV, uv: [16, 32] }, pv: [0, 18, 0], th: 0 },
      { b: { w: aw, h: 12, d: 4, c: [-(4 + aw / 2), 18, 0], uv: [40, 16] }, o: legacy ? null : { inf: OV, uv: [40, 32] }, pv: [-(4 + aw / 2), 22, 0], th: armA },
      { b: { w: aw, h: 12, d: 4, c: [4 + aw / 2, 18, 0], uv: legacy ? [40, 16] : [32, 48], mirror: legacy }, o: legacy ? null : { inf: OV, uv: [48, 48] }, pv: [4 + aw / 2, 22, 0], th: -armA },
      { b: { w: 4, h: 12, d: 4, c: [-2, 6, 0], uv: [0, 16] }, o: legacy ? null : { inf: OV, uv: [0, 32] }, pv: [-2, 12, 0], th: -legA },
      { b: { w: 4, h: 12, d: 4, c: [2, 6, 0], uv: legacy ? [0, 16] : [16, 48], mirror: legacy }, o: legacy ? null : { inf: OV, uv: [0, 48] }, pv: [2, 12, 0], th: legA }
    ];
    if (a.cape) P.push({ cape: true, b: { w: 10, h: 16, d: 1, c: [0, 16, -2.5], uv: [0, 0] }, o: null, pv: [0, 24, -2], th: 0.08 + am * (0.14 + 0.10 * Math.sin(2 * ph)) });
    return P;
  }
  function faces(b, inf, uv, mirror, swap) {
    const [cx, cy, cz] = b.c, hw = b.w / 2 + inf, hh = b.h / 2 + inf, hd = b.d / 2 + inf, W = b.w, H = b.h, D = b.d, [u, v] = uv;
    const F = [
      { n: [0, 0, 1], p: [cx - hw, cy + hh, cz + hd], U: [2 * hw, 0, 0], V: [0, -2 * hh, 0], t: [u + D, v + D, W, H] },
      { n: [0, 0, -1], p: [cx + hw, cy + hh, cz - hd], U: [-2 * hw, 0, 0], V: [0, -2 * hh, 0], t: [u + 2 * D + W, v + D, W, H] },
      { n: [-1, 0, 0], p: [cx - hw, cy + hh, cz - hd], U: [0, 0, 2 * hd], V: [0, -2 * hh, 0], t: [u, v + D, D, H] },
      { n: [1, 0, 0], p: [cx + hw, cy + hh, cz + hd], U: [0, 0, -2 * hd], V: [0, -2 * hh, 0], t: [u + D + W, v + D, D, H] },
      { n: [0, 1, 0], p: [cx - hw, cy + hh, cz - hd], U: [2 * hw, 0, 0], V: [0, 0, 2 * hd], t: [u + D, v, W, D] },
      { n: [0, -1, 0], p: [cx - hw, cy - hh, cz + hd], U: [2 * hw, 0, 0], V: [0, 0, -2 * hd], t: [u + D + W, v, W, D] }
    ];
    if (mirror) {   // extremidades izquierdas de skins antiguas (64x32): textura derecha reflejada
      F.forEach(f => { f.p = f.p.map((x, i) => x + f.U[i]); f.U = f.U.map(x => -x); });
      const t = F[2].t; F[2].t = F[3].t; F[3].t = t;
    }
    if (swap) { const t = F[0].t; F[0].t = F[1].t; F[1].t = t; }   // capa: la cara exterior mira hacia atrás
    return F;
  }

  function draw(canvas, img, opt) {
    const { yaw = -0.55, pitch = 0.12, slim = false, phase = 0, amp = 0, cape = null, zoom = 1 } = opt || {};
    const ctx = canvas.getContext('2d');
    ctx.setTransform(1, 0, 0, 1, 0, 0); ctx.filter = 'none'; ctx.clearRect(0, 0, canvas.width, canvas.height);
    if (!img) return;
    ctx.imageSmoothingEnabled = false;
    const legacy = img.height < 64, S = Math.min(canvas.width / 22, canvas.height / 37) * zoom;
    const cy = Math.cos(yaw), sy = Math.sin(yaw), cp = Math.cos(pitch), sp = Math.sin(pitch);
    const rot = p => { const x = p[0] * cy + p[2] * sy, z0 = -p[0] * sy + p[2] * cy, y = p[1] - 16; return [x, y * cp - z0 * sp, y * sp + z0 * cp]; };
    const rotN = n => { const x = n[0] * cy + n[2] * sy, z0 = -n[0] * sy + n[2] * cy; return [x, n[1] * cp - z0 * sp, n[1] * sp + z0 * cp]; };
    const rx = (p, pv, th) => { if (!th) return p; const c = Math.cos(th), s = Math.sin(th), y = p[1] - pv[1], z = p[2] - pv[2]; return [p[0], pv[1] + y * c - z * s, pv[2] + y * s + z * c]; };
    const rxN = (n, th) => { if (!th) return n; const c = Math.cos(th), s = Math.sin(th); return [n[0], n[1] * c - n[2] * s, n[1] * s + n[2] * c]; };
    const pr = p => ({ x: canvas.width / 2 + p[0] * S, y: canvas.height / 2 - p[1] * S });
    const L = [-0.35, 0.55, 0.76];
    const list = parts(slim, legacy, { phase, amp, cape: !!cape }).map(P => ({ P, z: rot(rx(P.b.c, P.pv, P.th))[2] })).sort((a, b) => a.z - b.z);
    const box = (P, inf, uv, tex, swap) => {
      for (const f of faces(P.b, inf, uv, P.b.mirror, swap)) {
        const nz = rotN(rxN(f.n, P.th)); if (nz[2] <= 0.001) continue;
        const T = p => rot(rx(p, P.pv, P.th));
        const A = pr(T(f.p)), B = pr(T(f.p.map((x, i) => x + f.U[i]))), C = pr(T(f.p.map((x, i) => x + f.V[i])));
        const [sx, sy2, sw, sh] = f.t, dot = nz[0] * L[0] + nz[1] * L[1] + nz[2] * L[2];
        ctx.setTransform((B.x - A.x) / sw, (B.y - A.y) / sw, (C.x - A.x) / sh, (C.y - A.y) / sh, A.x, A.y);
        ctx.filter = `brightness(${(0.62 + 0.38 * Math.max(0, dot)).toFixed(2)})`;
        ctx.drawImage(tex, sx, sy2, sw, sh, -0.03, -0.03, sw + 0.06, sh + 0.06);
      }
    };
    for (const { P } of list) {
      if (P.cape) { box(P, 0, P.b.uv, cape, true); continue; }
      box(P, 0, P.b.uv, img, false); if (P.o) box(P, P.o.inf, P.o.uv, img, false);
    }
    ctx.setTransform(1, 0, 0, 1, 0, 0); ctx.filter = 'none';
  }

  // Visor interactivo (como el de NameMC): arrastra para girar en cualquier dirección, rueda = zoom,
  // doble clic = reiniciar vista. Animación de caminar con pausa y capa opcional.
  function viewer(canvas, o) {
    let yaw = -0.5, pitch = 0.12, zoom = 1, drag = false, lx = 0, ly = 0, playing = true, capeOn = false, phase = 0, last = performance.now(), dirty = true;
    const still = matchMedia('(prefers-reduced-motion: reduce)').matches;
    if (still) playing = false;
    canvas.style.cursor = 'grab'; canvas.style.touchAction = 'none';
    canvas.addEventListener('pointerdown', e => { drag = true; lx = e.clientX; ly = e.clientY; canvas.setPointerCapture(e.pointerId); canvas.style.cursor = 'grabbing'; });
    canvas.addEventListener('pointermove', e => { if (!drag) return; yaw += (e.clientX - lx) * 0.012; pitch += (e.clientY - ly) * 0.012; lx = e.clientX; ly = e.clientY; dirty = true; });
    const up = () => { drag = false; canvas.style.cursor = 'grab'; };
    canvas.addEventListener('pointerup', up); canvas.addEventListener('pointercancel', up);
    canvas.addEventListener('wheel', e => { e.preventDefault(); zoom = Math.max(0.6, Math.min(2.2, zoom * (e.deltaY < 0 ? 1.08 : 0.92))); dirty = true; }, { passive: false });
    canvas.addEventListener('dblclick', () => { yaw = -0.5; pitch = 0.12; zoom = 1; dirty = true; });
    const render = () => draw(canvas, o.getImg(), { yaw, pitch, zoom, slim: o.getSlim(), phase, amp: 1, cape: capeOn ? o.getCape() : null });
    (function loop(now) {
      const dt = Math.min(0.1, (now - last) / 1000); last = now;
      if (canvas.offsetParent !== null) { if (playing) { phase += dt * 4.4; render(); } else if (dirty) { render(); } dirty = false; }
      requestAnimationFrame(loop);
    })(last);
    return {
      togglePlay() { playing = !playing; dirty = true; return playing; },
      toggleCape() { capeOn = !capeOn; dirty = true; return capeOn; },
      get playing() { return playing; }, get cape() { return capeOn; },
      redraw() { dirty = true; },
      reset() { yaw = -0.5; pitch = 0.12; zoom = 1; dirty = true; },
      snapshot() { render(); return canvas.toDataURL('image/png'); }
    };
  }
  window.Skin3D = { draw, viewer };
})();
