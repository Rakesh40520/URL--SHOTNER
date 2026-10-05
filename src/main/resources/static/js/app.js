/**
 * app.js — home page logic
 *
 * Responsibilities:
 *  1. Dispatch (shorten) a URL, with JWT if logged in
 *  2. Stamping animation tied to real request state
 *  3. Save dispatched URL to sessionStorage (anonymous session history)
 *  4. Track / stats lookup
 *  5. Copy button
 */

const SESSION_KEY = 'dispatch_session_history';

/* ── Session history helpers ── */
function getSessionHistory() {
  try { return JSON.parse(sessionStorage.getItem(SESSION_KEY) || '[]'); }
  catch { return []; }
}
function addToSessionHistory(entry) {
  const list = getSessionHistory();
  list.unshift(entry); // newest first
  sessionStorage.setItem(SESSION_KEY, JSON.stringify(list));
}

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
      showFormError(data.message || 'Could not dispatch that link.');
      btnLabel.textContent = 'DISPATCH FAILED';
      setTimeout(Dispatch.reset, 1600);
      return;
    }

    // Save to session history regardless of login state, so anonymous users
    // always see it in /history.html, and logged-in users can also see the
    // current-session list there (distinct from /dashboard.html). The
    // management key is saved here too, since sessionStorage is the only
    // place it's recoverable within this session - the server never returns
    // it again after this response.
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

/* ── Track button on the dispatched result card ── */
document.getElementById('result-track-btn').addEventListener('click', () => {
  // trackCode/trackKey are already pre-filled by renderResultCard(), so this
  // just runs the same lookup and scrolls the (separate) results into view.
  document.getElementById('track-section').scrollIntoView({ behavior: 'smooth', block: 'start' });
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

/* ── Track / stats ── */
document.getElementById('track-btn').addEventListener('click', async () => {
  const rawCode = document.getElementById('trackCode').value;
  const code    = extractShortCode(rawCode);
  const key     = document.getElementById('trackKey').value.trim();
  const errorEl = document.getElementById('track-error');
  const result  = document.getElementById('track-result');

  errorEl.hidden = true;
  result.hidden  = true;
  if (!code) {
    errorEl.textContent = 'Please enter a short code or paste a link.';
    errorEl.hidden = false;
    return;
  }

  try {
    // Auth.apiFetch attaches the JWT if logged in, so an owner can view
    // their own link's stats without needing the management key at all.
    // The management key itself goes in a header rather than the query
    // string - a query param would end up in server access logs and
    // browser history, which we don't want for something that acts like a
    // bearer secret for this one link.
    const headers = key ? { 'X-Management-Key': key } : {};
    const res = await Auth.apiFetch(`/api/v1/urls/${encodeURIComponent(code)}/stats`, { headers });
    if (!res) return; // Auth.apiFetch already redirected on 401
    const data = await res.json();

    if (!res.ok) {
      errorEl.textContent = res.status === 403
        ? 'That management key doesn\'t match this link (or you\'re not its owner).'
        : (data.message || 'No parcel found with that code.');
      errorEl.hidden = false;
      return;
    }

    document.getElementById('track-clicks').textContent  = data.clickCount;
    document.getElementById('track-created').textContent = formatDate(data.createdAt) || '—';
    document.getElementById('track-last').textContent    = data.lastAccessedAt
      ? formatDate(data.lastAccessedAt) : 'Not yet clicked';
    // data.status comes straight from the API ("Active"/"Flagged"/
    // "Disabled") - expired is still checked client-side first since a
    // link can be both expired and otherwise Active/Flagged/Disabled, and
    // "Expired" is the more useful thing to show here.
    document.getElementById('track-status').textContent  = data.expired ? 'Expired' : (data.status || 'Active');
    document.getElementById('track-dest-url').textContent = data.longUrl;
    result.hidden = false;
  } catch {
    errorEl.textContent = 'Network error — could not look up that code.';
    errorEl.hidden = false;
  }
});

// Allow Enter key on track inputs
['trackCode', 'trackKey'].forEach(id =>
  document.getElementById(id).addEventListener('keydown', e => {
    if (e.key === 'Enter') document.getElementById('track-btn').click();
  })
);

/* ── Report abuse ── */
// Deliberately asks for nothing but a short code - no login, no management
// key - since the whole point is letting someone who was sent a malicious
// short link flag it even though they have no account for it. Rate-limited
// server-side per IP (see RateLimitFilter/app.report-rate-limit).
document.getElementById('report-btn').addEventListener('click', async () => {
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

  const btn = document.getElementById('report-btn');
  btn.disabled = true;

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
  }
});

/* ── Init ── */
Nav.render();

// Arriving from history.html's "Track" button (?trackCode=...&trackKey=...):
// pre-fill both fields, scroll to the tracking section, and run the lookup
// automatically so it's a one-click hop from "Session History" to results.
(function autoTrackFromLink() {
  const params = new URLSearchParams(window.location.search);
  const code = params.get('trackCode');
  if (!code) return;

  document.getElementById('trackCode').value = code;
  document.getElementById('trackKey').value = params.get('trackKey') || '';

  // Clean the URL so refreshing the page doesn't re-trigger this.
  window.history.replaceState({}, '', window.location.pathname);

  document.getElementById('track-section').scrollIntoView({ behavior: 'smooth', block: 'start' });
  document.getElementById('track-btn').click();
})();
