/**
 * qr.js — "Make QR code" dialog for a short link (uses vendored js/vendor/qrcode.js,
 * MIT, no network needed). QR.open({ url, code }) from any page.
 *
 * The QR encodes the short link. By default it appends ?ref=qr so scans show up
 * as their own source in click analytics ("who sent this click").
 */
const QR = (() => {
  const INK = '#211D18', PAPER = '#FFFFFF', QUIET = 4;
  let root, lastFocus, state = { url: '', code: '' };

  function make(text) {
    const qr = qrcode(0, 'M');           // type auto-sized, medium error correction
    qr.addData(text);
    qr.make();
    return qr;
  }

  function paint(canvas, qr, targetPx) {
    const n = qr.getModuleCount(), total = n + QUIET * 2;
    const cell = Math.max(1, Math.floor(targetPx / total));
    const size = cell * total;                       // whole-pixel modules = crisp edges
    canvas.width = canvas.height = size;
    const ctx = canvas.getContext('2d');
    ctx.fillStyle = PAPER; ctx.fillRect(0, 0, size, size);
    ctx.fillStyle = INK;
    for (let r = 0; r < n; r++)
      for (let c = 0; c < n; c++)
        if (qr.isDark(r, c)) ctx.fillRect((c + QUIET) * cell, (r + QUIET) * cell, cell, cell);
  }

  function toSvg(qr) {
    const n = qr.getModuleCount(), total = n + QUIET * 2;
    let d = '';
    for (let r = 0; r < n; r++)
      for (let c = 0; c < n; c++)
        if (qr.isDark(r, c)) d += `M${c + QUIET} ${r + QUIET}h1v1h-1z`;
    return `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 ${total} ${total}" width="1024" height="1024" shape-rendering="crispEdges">` +
           `<rect width="${total}" height="${total}" fill="${PAPER}"/><path d="${d}" fill="${INK}"/></svg>`;
  }

  function finalUrl() {
    const track = root.querySelector('#qr-track').checked;
    if (!track) return state.url;
    return state.url + (state.url.includes('?') ? '&' : '?') + 'ref=qr';
  }

  function render() {
    const url = finalUrl();
    try {
      state.qr = make(url);
    } catch {
      UI.toast('That link is too long for a QR code', { type: 'error' });
      return;
    }
    paint(root.querySelector('#qr-canvas'), state.qr, 560);
    root.querySelector('#qr-url').textContent = url.replace(/^https?:\/\//, '');
  }

  function save(blob, name) {
    const a = document.createElement('a');
    a.href = URL.createObjectURL(blob);
    a.download = name;
    document.body.appendChild(a); a.click(); a.remove();
    setTimeout(() => URL.revokeObjectURL(a.href), 1500);
  }

  function ensure() {
    if (root) return;
    root = document.createElement('div');
    root.className = 'qr-overlay';
    root.hidden = true;
    root.innerHTML = `
      <div class="qr-card" role="dialog" aria-modal="true" aria-labelledby="qr-title">
        <button class="qr-close" type="button" aria-label="Close">&times;</button>
        <p class="qr-eyebrow">SCAN TO OPEN</p>
        <h2 id="qr-title" class="qr-title">QR code</h2>
        <div class="qr-frame"><canvas id="qr-canvas" width="300" height="300" aria-label="QR code for the short link"></canvas></div>
        <code class="qr-url" id="qr-url"></code>
        <label class="qr-check">
          <input type="checkbox" id="qr-track" checked />
          <span>Count scans as their own source <small>(adds <b>?ref=qr</b>)</small></span>
        </label>
        <div class="qr-actions">
          <button type="button" class="qr-btn primary" id="qr-png">Download PNG</button>
          <button type="button" class="qr-btn" id="qr-svg">Download SVG</button>
          <button type="button" class="qr-btn" id="qr-copy">Copy link</button>
        </div>
      </div>`;
    document.body.appendChild(root);

    root.addEventListener('click', e => { if (e.target === root || e.target.closest('.qr-close')) close(); });
    root.addEventListener('keydown', e => {
      if (e.key === 'Escape') return close();
      if (e.key !== 'Tab') return;
      const f = [...root.querySelectorAll('button, input')].filter(el => !el.disabled);
      const first = f[0], last = f[f.length - 1];
      if (e.shiftKey && document.activeElement === first) { e.preventDefault(); last.focus(); }
      else if (!e.shiftKey && document.activeElement === last) { e.preventDefault(); first.focus(); }
    });
    root.querySelector('#qr-track').addEventListener('change', render);

    root.querySelector('#qr-png').addEventListener('click', () => {
      const big = document.createElement('canvas');
      paint(big, state.qr, 1024);
      big.toBlob(b => { if (b) { save(b, `qr-${state.code || 'link'}.png`); UI.toast('QR code downloaded'); } }, 'image/png');
    });
    root.querySelector('#qr-svg').addEventListener('click', () => {
      save(new Blob([toSvg(state.qr)], { type: 'image/svg+xml' }), `qr-${state.code || 'link'}.svg`);
      UI.toast('QR code downloaded');
    });
    root.querySelector('#qr-copy').addEventListener('click', async e => {
      const ok = await UI.copyText(finalUrl());
      if (ok) { UI.flash(e.currentTarget, e.currentTarget, '✓ Copied'); UI.toast('Link copied'); }
      else UI.toast('Could not copy — please copy it manually', { type: 'error' });
    });
  }

  function open({ url, code }) {
    ensure();
    state.url = UI.shortUrlForCopy(url);
    state.code = code || '';
    lastFocus = document.activeElement;
    root.querySelector('#qr-title').textContent = code ? `QR code for ${code}` : 'QR code';
    render();
    root.hidden = false;
    document.body.classList.add('qr-open');
    requestAnimationFrame(() => root.classList.add('visible'));
    root.querySelector('.qr-close').focus();
  }

  function close() {
    if (!root || root.hidden) return;
    root.classList.remove('visible');
    document.body.classList.remove('qr-open');
    setTimeout(() => { root.hidden = true; }, 180);
    if (lastFocus && lastFocus.focus) lastFocus.focus({ preventScroll: true });
  }

  return { open, close };
})();
