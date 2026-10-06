/**
 * app.js — home page logic
 *
 * Responsibilities:
 *  1. Dispatch (shorten) a URL, with JWT if logged in
 *  2. Stamping animation tied to real request state
 *  3. Save dispatched URL + management key on this device (localStorage)
 *  4. Track / stats lookup
 *  5. Copy button
 */

/* ── Link history (saved on this device, keys included) ── */
function addToSessionHistory(entry) { UI.history.add(entry); }

/* ── Date formatter ── */
function formatDate(iso) {
  if (!iso) return null;
  return new Date(iso).toLocaleDateString(undefined, {
    day: '2-digit', month: 'short', year: 'numeric'
  });
}

/* ── Dispatch progress animation ──────────────────────────────────────
 * Stages advance while the real request is in flight. Progress eases toward
 * ~90% and only reaches 100% once the server actually answers, so it never
 * "finishes" before the work is done. If the server is slow (Render's free
 * tier sleeps when idle) a note explains the wait instead of looking frozen.
 */
const overlay      = document.getElementById('stamp-overlay');
const sheet        = document.getElementById('dispatch-sheet');
const seal         = document.getElementById('stamp-seal');
const sealCode     = document.getElementById('seal-code');
const stageItems   = [...document.querySelectorAll('#stage-list li')];
const sheetBar     = document.getElementById('sheet-progress');
const sheetFill    = document.getElementById('sheet-progress-fill');
const sheetNote    = document.getElementById('sheet-note');
const submitBtn    = document.getElementById('submit-btn');
const progressFill = document.getElementById('stamp-progress-fill');
const btnLabel     = document.getElementById('btn-label');

const sleep = ms => new Promise(r => setTimeout(r, ms));
const MIN_VISIBLE_MS = 1300;          // avoid a flash on very fast responses
const STAGE_LABELS = ['CHECKING…', 'SCANNING…', 'ASSIGNING…', 'STAMPING…'];
// While waiting, advance through the first three stages; "stamp" only on response.
const WAIT_STAGE_AT = [0, 750, 1600];

const Dispatch = (() => {
  let startedAt = 0, raf = 0, timers = [], stage = -1, lastFocus = null;

  const setProgress = pct => {
    sheetFill.style.width = pct + '%';
    progressFill.style.width = pct + '%';
  };

  function setStage(i) {
    stage = i;
    stageItems.forEach((li, idx) => {
      li.classList.toggle('done', idx < i);
      li.classList.toggle('active', idx === i);
    });
    btnLabel.textContent = STAGE_LABELS[i] || btnLabel.textContent;
  }

  function tick(now) {
    const t = now - startedAt;
    setProgress(+(90 * (1 - Math.exp(-t / 2400))).toFixed(1)); // eases, never hits 100
    raf = requestAnimationFrame(tick);
  }

  function clearTimers() { timers.forEach(clearTimeout); timers = []; cancelAnimationFrame(raf); }

  function start() {
    clearTimers();
    lastFocus = document.activeElement;
    startedAt = performance.now();
    submitBtn.classList.add('stamping');
    sheetBar.classList.remove('done');
    sheetNote.textContent = '';
    seal.classList.remove('slam');
    sheet.classList.remove('thud');
    sealCode.textContent = '——';
    stageItems.forEach(li => li.classList.remove('active', 'done'));
    setProgress(0);
    setStage(0);

    overlay.classList.add('visible');
    overlay.setAttribute('aria-hidden', 'false');

    WAIT_STAGE_AT.slice(1).forEach((at, i) => timers.push(setTimeout(() => setStage(i + 1), at)));
    timers.push(setTimeout(() => { sheetNote.textContent = 'Still working — the server may be waking up…'; }, 3500));
    timers.push(setTimeout(() => { sheetNote.textContent = 'Free hosting sleeps when idle; the first request can take up to ~30s. Hang tight.'; }, 9000));
    raf = requestAnimationFrame(tick);
  }

  /** Server answered OK: finish remaining stages, slam the seal, resolve when done. */
  async function succeed(code) {
    clearTimers();
    const wait = MIN_VISIBLE_MS - (performance.now() - startedAt);
    if (wait > 0) await sleep(wait);
    sheetNote.textContent = '';
    setProgress(100);
    sheetBar.classList.add('done');

    for (let i = Math.max(stage, 0); i < 3; i++) { setStage(i); await sleep(170); }
    setStage(3);                                   // "Stamping the parcel"
    await sleep(260);

    sealCode.textContent = code;
    seal.classList.add('slam');
    setTimeout(() => sheet.classList.add('thud'), 200);
    stageItems.forEach(li => { li.classList.remove('active'); li.classList.add('done'); });
    btnLabel.textContent = 'STAMPED ✓';
    await sleep(1000);
  }

  function hide() {
    clearTimers();
    overlay.classList.remove('visible');
    overlay.setAttribute('aria-hidden', 'true');
    if (lastFocus && lastFocus.focus) lastFocus.focus({ preventScroll: true });
    setTimeout(() => { seal.classList.remove('slam'); sheet.classList.remove('thud'); }, 300);
  }

  function reset() {
    submitBtn.classList.remove('stamping');
    btnLabel.textContent = 'DISPATCH';
    progressFill.style.width = '0%';
    submitBtn.disabled = false;
  }

  return { start, succeed, hide, reset };
})();

/* ── Subscription-aware form ───────────────────────────────────────────
 * Signed-in users get their plan's rules reflected in the form (locked alias on
 * Free, expiry choices capped to what the plan allows, usage hint). The server
 * enforces every rule regardless - this just avoids offering things that will
 * be rejected. Anonymous visitors are unaffected.
 */
async function applyPlanToForm() {
  if (!Auth.isLoggedIn()) return;
  const sub = await Plans.mine();
  if (!sub) return;

  const plan = sub.plan;
  const alias = document.getElementById('customAlias');
  const lock  = document.getElementById('alias-lock');
  const sel   = document.getElementById('expiresInDays');
  const hint  = document.getElementById('plan-hint');

  // Custom codes: Pro and Business only.
  alias.disabled = !plan.customAlias;
  alias.placeholder = plan.customAlias ? 'my-launch' : 'Upgrade to choose your own code';
  lock.hidden = plan.customAlias;
  if (!plan.customAlias) alias.value = '';

  // Expiry choices capped to the plan.
  const keep = sel.value;
  const options = [[1, '1 day'], [7, '7 days'], [30, '30 days'], [90, '90 days'], [365, '1 year']]
    .filter(([d]) => plan.maxExpiryDays == null || d <= plan.maxExpiryDays);
  if (plan.maxExpiryDays == null) options.push(['', 'Never']);
  sel.innerHTML = options.map(([v, l]) => `<option value="${v}">${l}</option>`).join('');
  sel.value = options.some(([v]) => String(v) === keep) ? keep : String(options.find(([v]) => v === 7)?.[0] ?? options[0][0]);

  // Usage hint.
  if (sub.monthlyLinkLimit < 0) {
    hint.innerHTML = `${plan.name} plan · unlimited links`;
  } else {
    const left = sub.linksRemaining;
    hint.innerHTML = `${plan.name} plan · <b>${sub.linksUsedThisMonth} of ${sub.monthlyLinkLimit}</b> links used this month` +
      (left <= 3 ? ` — <a href="/pricing.html">${left === 0 ? 'upgrade for more' : left + ' left, upgrade?'}</a>` : '');
  }
  hint.hidden = false;
}
applyPlanToForm();

/* ── Dispatch form ── */
function showFormError(msg) {
  const errorEl = document.getElementById('form-error');
  errorEl.textContent = msg;
  errorEl.hidden = false;
  const card = document.getElementById('shipForm');
  card.classList.remove('shake'); void card.offsetWidth; card.classList.add('shake');
}

async function dispatchLink() {
  const longUrl       = document.getElementById('longUrl').value.trim();
  const customAlias   = document.getElementById('customAlias').value.trim();
  const expiresInDays = document.getElementById('expiresInDays').value;
  const errorEl       = document.getElementById('form-error');
  const btn           = document.getElementById('submit-btn');

  if (btn.disabled) return;
  errorEl.hidden = true;
  document.getElementById('result-card').hidden = true;

  if (!longUrl) return showFormError('Please enter a URL.');
  if (!/^https?:\/\//i.test(longUrl)) return showFormError('URL must start with http:// or https://');

  btn.disabled = true;
  Dispatch.start();

  const payload = { longUrl };
  if (customAlias) payload.customAlias = customAlias;
  if (expiresInDays) payload.expiresInDays = Number(expiresInDays);

  // Give up after 60s so a dead server can't leave the overlay up forever.
  const controller = new AbortController();
  const abortTimer = setTimeout(() => controller.abort(), 60000);

  try {
    // Auth.apiFetch automatically attaches the Bearer token if logged in,
    // so the backend can associate this URL with the user's account.
    const res = await Auth.apiFetch('/api/v1/urls', {
      method: 'POST',
      body: JSON.stringify(payload),
      signal: controller.signal,
    });

    if (!res) { Dispatch.hide(); Dispatch.reset(); return; }   // 401 -> redirecting to login

    const data = await res.json().catch(() => ({}));

    if (!res.ok) {
      Dispatch.hide();
      if (Plans.isPlanError(data)) {
        // Not a failure: the plan doesn't allow this. Show an upgrade prompt
        // naming the cheapest plan that does.
        Dispatch.reset();
        Plans.promptUpgrade(data);
        applyPlanToForm();
        return;
      }
      showFormError(data.message || 'Could not dispatch that link.');
      btnLabel.textContent = 'DISPATCH FAILED';
      setTimeout(Dispatch.reset, 1600);
      return;
    }

    // Save to session history regardless of login state, so anonymous users
    // always see it in /history.html, and logged-in users can also see the
    // current-session list there (distinct from /dashboard.html). The
    // management key is saved here too (localStorage, this device only) - for
    // anonymous links the server never returns it again after this response.
    addToSessionHistory({
      longUrl:    data.longUrl,
      shortUrl:   data.shortUrl,
      shortCode:  data.shortCode,
      createdAt:  data.createdAt,
      expiresAt:  data.expiresAt,
      managementKey: data.managementKey,
    });

    await Dispatch.succeed(data.shortCode);   // stages complete, seal slams
    Dispatch.hide();
    renderResultCard(data);
    Dispatch.reset();
    applyPlanToForm();

  } catch (err) {
    Dispatch.hide();
    showFormError(err.name === 'AbortError'
      ? 'The server took too long to respond. Please try again.'
      : 'Network error — could not reach the server.');
    Dispatch.reset();
  } finally {
    clearTimeout(abortTimer);
  }
}

document.getElementById('submit-btn').addEventListener('click', dispatchLink);
['longUrl', 'customAlias'].forEach(id =>
  document.getElementById(id).addEventListener('keydown', e => { if (e.key === 'Enter') dispatchLink(); })
);

/* ── Result card ── */
function renderResultCard(data) {
  document.getElementById('result-url').textContent = data.shortUrl;
  document.getElementById('result-url').href        = data.shortUrl;
  document.getElementById('open-btn').href          = data.shortUrl;
  document.getElementById('result-key').textContent = data.managementKey;

  const longEl = document.getElementById('result-long');
  longEl.textContent = data.longUrl;
  longEl.title       = data.longUrl;

  const expiresWrap = document.getElementById('result-expires-wrap');
  if (data.expiresAt) {
    document.getElementById('result-expires').textContent = formatDate(data.expiresAt);
    expiresWrap.hidden = false;
  } else {
    expiresWrap.hidden = true;
  }

  // Pre-fill the tracking form below with this link's code + key, so
  // trying it out is one click away instead of retyping both.
  document.getElementById('trackCode').value = data.shortCode;
  document.getElementById('trackKey').value  = data.managementKey;

  document.getElementById('result-card').hidden = false;
  document.getElementById('result-card')
    .scrollIntoView({ behavior: 'smooth', block: 'nearest' });
}

/* ── Copy buttons ── */
async function copyFrom(btn, text, toastMsg) {
  const ok = await UI.copyText(text);
  if (!ok) { UI.toast('Could not copy — please copy it manually', { type: 'error' }); return; }
  UI.flash(btn, btn, '✓ Copied');
  UI.toast(toastMsg);
}
document.getElementById('copy-btn').addEventListener('click', e =>
  copyFrom(e.currentTarget, UI.shortUrlForCopy(document.getElementById('result-url').href), 'Short link copied'));
document.getElementById('copy-key-btn').addEventListener('click', e =>
  copyFrom(e.currentTarget, document.getElementById('result-key').textContent, 'Management key copied'));

/* ── QR code for the dispatched link ── */
document.getElementById('result-qr-btn').addEventListener('click', () => {
  QR.open({
    url: document.getElementById('result-url').href,
    code: document.getElementById('trackCode').value,
  });
});

/* ── Track button on the dispatched result card ── */
document.getElementById('result-track-btn').addEventListener('click', () => {
  // trackCode/trackKey are already pre-filled by renderResultCard(), so this
  // smoothly scrolls the tracking section into view, highlights it, and triggers lookup.
  const trackSec = document.getElementById('track-section');
  if (trackSec) {
    trackSec.scrollIntoView({ behavior: 'smooth', block: 'start' });
    trackSec.classList.add('highlight-section');
    setTimeout(() => trackSec.classList.remove('highlight-section'), 1500);
  }
  document.getElementById('track-btn').click();
});

/* ── Short code extractor helper ── */
function extractShortCode(input) {
  if (!input) return '';
  input = input.trim();
  try {
    if (input.includes('/') || input.startsWith('http://') || input.startsWith('https://')) {
      const url = new URL(input.startsWith('http') ? input : 'https://' + input);
      const segments = url.pathname.split('/').filter(Boolean);
      if (segments.length > 0) {
        return segments[segments.length - 1];
      }
    }
  } catch {}
  return input.replace(/^[/#]+|[/#]+$/g, '').split(/[?#]/)[0].trim();
}

/* ── Click analytics: location + who sent the click ── */
function renderBreakdown(el, rows, emptyText) {
  if (!rows || !rows.length) { el.innerHTML = `<p class="an-empty">${emptyText}</p>`; return; }
  const max = Math.max(...rows.map(r => r.count));
  el.innerHTML = rows.map(r => `
    <div class="an-row"><span title="${escapeHtml(r.label)}">${escapeHtml(r.label)}</span><b>${r.count}</b>
      <div class="an-bar"><i style="width:${Math.max(4, Math.round(r.count / max * 100))}%"></i></div>
    </div>`).join('');
}

// True for addresses that can never be geolocated (the server was behind a proxy and
// recorded the proxy's internal address instead of the visitor's).
function isPrivateIp(ip) {
  if (!ip) return false;
  return /^(10\.|127\.|192\.168\.|169\.254\.|172\.(1[6-9]|2\d|3[01])\.|100\.(6[4-9]|[7-9]\d|1[01]\d|12[0-7])\.)/.test(ip)
      || /^(::1|f[cd][0-9a-f]{2}:|fe80:)/i.test(ip);
}

function escapeHtml(str) {
  return String(str).replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
}

async function loadAnalytics(code, key) {
  const box = document.getElementById('analytics');
  box.hidden = true;
  try {
    const headers = key ? { 'X-Management-Key': key } : {};
    const [aRes, cRes] = await Promise.all([
      Auth.apiFetch(`/api/v1/urls/${encodeURIComponent(code)}/analytics`, { headers }),
      Auth.apiFetch(`/api/v1/urls/${encodeURIComponent(code)}/clicks?size=8`, { headers }),
    ]);
    if (!aRes || !aRes.ok) return;
    const a = await aRes.json();

    document.getElementById('analytics-sub').textContent = a.totalClicks
      ? `${a.clicksWithLocation} of ${a.totalClicks} click${a.totalClicks === 1 ? '' : 's'} could be placed on a map.`
      : 'No clicks yet — open the short link and refresh to see it appear here.';
    renderBreakdown(document.getElementById('an-countries'), a.topCountries.filter(r => a.totalClicks), 'No location data yet.');
    renderBreakdown(document.getElementById('an-cities'), a.topCities, 'No city data yet.');
    renderBreakdown(document.getElementById('an-sources'), a.topSources.filter(r => a.totalClicks), 'No clicks yet.');
    renderBreakdown(document.getElementById('an-tags'), a.topReferralTags, 'No tagged clicks yet.');
    document.getElementById('an-example').textContent = `/${code}?ref=alice`;

    const recent = document.getElementById('recent');
    if (cRes && cRes.ok) {
      const page = await cRes.json();
      const items = page.content || [];
      recent.hidden = !items.length;
      document.getElementById('recent-list').innerHTML = items.map(c => {
        const known = [c.city, c.region, c.country].filter(Boolean).filter((v, i, arr) => arr.indexOf(v) === i).join(', ');
        const fresh = Date.now() - new Date(c.clickedAt).getTime() < 2 * 60 * 1000;
        const where = known || (isPrivateIp(c.ipAddress) ? 'Location unknown (server saw a private IP)'
                              : fresh ? 'Locating…' : 'Location unknown');
        const from = c.referralTag ? `via ${c.referralTag}` : (c.referrerHost ? `from ${c.referrerHost}` : 'direct');
        return `<div class="recent-item"><time>${escapeHtml(new Date(c.clickedAt).toLocaleString(undefined, { day: '2-digit', month: 'short', hour: '2-digit', minute: '2-digit' }))}</time><span title="${escapeHtml(where)} · ${escapeHtml(from)}">${escapeHtml(where)} · ${escapeHtml(from)}</span></div>`;
      }).join('');
    }
    box.hidden = false;
  } catch { /* analytics are a bonus - never break the stats card */ }
}

/* ── Track / stats ── */
document.getElementById('track-btn').addEventListener('click', async () => {
  const trackBtn = document.getElementById('track-btn');
  const rawCode  = document.getElementById('trackCode').value;
  const code     = extractShortCode(rawCode);
  const key      = document.getElementById('trackKey').value.trim();
  const errorEl  = document.getElementById('track-error');
  const result   = document.getElementById('track-result');

  errorEl.hidden = true;
  result.hidden  = true;
  if (!code) {
    errorEl.textContent = 'Please enter a short code or paste a link.';
    errorEl.hidden = false;
    return;
  }

  // Keep loading button state while data is in flight
  const origText = trackBtn.textContent;
  trackBtn.disabled = true;
  trackBtn.classList.add('loading');
  trackBtn.textContent = 'Tracking…';

  try {
    const headers = key ? { 'X-Management-Key': key } : {};
    const res = await Auth.apiFetch(`/api/v1/urls/${encodeURIComponent(code)}/stats`, { headers });
    if (!res) return; // Auth.apiFetch already redirected on 401
    const data = await res.json().catch(() => ({}));

    if (!res.ok) {
      errorEl.textContent = res.status === 403
        ? 'That management key doesn\'t match this link (or you\'re not its owner).'
        : (data.message || 'No parcel found with that code.');
      errorEl.hidden = false;
      result.hidden = true;
      return;
    }

    document.getElementById('track-clicks').textContent  = data.clickCount;
    document.getElementById('track-created').textContent = formatDate(data.createdAt) || '—';
    document.getElementById('track-last').textContent    = data.lastAccessedAt
      ? formatDate(data.lastAccessedAt) : 'Not yet clicked';
    document.getElementById('track-status').textContent  = data.expired ? 'Expired' : (data.status || 'Active');
    document.getElementById('track-dest-url').textContent = data.longUrl;

    // Smooth transition animation
    result.classList.add('anim-entering');
    result.hidden = false;
    void result.offsetHeight; // trigger reflow
    result.classList.remove('anim-entering');
    result.scrollIntoView({ behavior: 'smooth', block: 'nearest' });

    loadAnalytics(code, key);
  } catch {
    // If data did not come, show error
    errorEl.textContent = 'Network error — could not reach the server to look up that code.';
    errorEl.hidden = false;
    result.hidden = true;
  } finally {
    trackBtn.disabled = false;
    trackBtn.classList.remove('loading');
    trackBtn.textContent = origText;
  }
});

// Allow Enter key on track inputs
['trackCode', 'trackKey'].forEach(id =>
  document.getElementById(id).addEventListener('keydown', e => {
    if (e.key === 'Enter') document.getElementById('track-btn').click();
  })
);

/* ── Report abuse ── */
document.getElementById('report-btn').addEventListener('click', async () => {
  const btn     = document.getElementById('report-btn');
  const rawCode = document.getElementById('reportCode').value;
  const code    = extractShortCode(rawCode);
  const reason  = document.getElementById('reportReason').value.trim();
  const errorEl = document.getElementById('report-error');
  const okEl    = document.getElementById('report-success');

  errorEl.hidden = true;
  okEl.hidden = true;
  if (!code) {
    errorEl.textContent = 'Please enter a short code or paste the link you wish to report.';
    errorEl.hidden = false;
    return;
  }

  const origBtnText = btn.textContent;
  btn.disabled = true;
  btn.classList.add('loading');
  btn.textContent = 'Submitting…';

  try {
    const res = await fetch(`/api/v1/urls/${encodeURIComponent(code)}/report`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(reason ? { reason } : {}),
    });
    const data = await res.json().catch(() => ({}));

    if (!res.ok) {
      errorEl.textContent = res.status === 404
        ? 'No parcel found with that code.'
        : (res.status === 429
            ? 'Too many reports from this connection - please try again shortly.'
            : (data.message || 'Could not submit that report.'));
      errorEl.hidden = false;
      return;
    }

    okEl.textContent = data.autoFlagged
      ? 'Reported & Verified: Threat or abuse detected. This link has been immediately flagged and disabled from resolving.'
      : `Reported (Recorded ${data.reportCount} report${data.reportCount > 1 ? 's' : ''}). Thank you for keeping links safe.`;
    okEl.hidden = false;
    document.getElementById('reportCode').value = '';
    document.getElementById('reportReason').value = '';
  } catch {
    errorEl.textContent = 'Network error — could not submit that report.';
    errorEl.hidden = false;
  } finally {
    btn.disabled = false;
    btn.classList.remove('loading');
    btn.textContent = origBtnText;
  }
});

/* ── Init ── */
Nav.render();

// Arriving from history.html or dashboard.html "Track" button:
(function autoTrackFromLink() {
  const params = new URLSearchParams(window.location.search);
  const code = params.get('trackCode');
  if (!code) return;

  document.getElementById('trackCode').value = code;
  document.getElementById('trackKey').value = params.get('trackKey') || '';

  // Clean the URL so refreshing the page doesn't re-trigger this.
  window.history.replaceState({}, '', window.location.pathname);

  const trackSec = document.getElementById('track-section');
  if (trackSec) {
    trackSec.scrollIntoView({ behavior: 'smooth', block: 'start' });
    trackSec.classList.add('highlight-section');
    setTimeout(() => trackSec.classList.remove('highlight-section'), 1500);
  }
  document.getElementById('track-btn').click();
})();
