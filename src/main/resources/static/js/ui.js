/**
 * ui.js — small shared UI helpers (loaded on every page, before nav.js)
 *
 *  UI.copyText(text)      → Promise<boolean>  clipboard with a fallback for
 *                           non-secure contexts / older mobile browsers
 *  UI.toast(msg, opts)    → small bottom toast ("Copied …")
 *  UI.shortUrlForCopy(u)  → makes sure the copied link uses the host/protocol
 *                           the user is actually on (Render sits behind a
 *                           proxy, so the backend can report http://)
 */
const UI = (() => {
  let host;

  function ensureHost() {
    if (host) return host;
    host = document.createElement('div');
    host.className = 'toast-host';
    host.setAttribute('role', 'status');
    host.setAttribute('aria-live', 'polite');
    document.body.appendChild(host);
    return host;
  }

  function toast(message, { type = 'success', duration = 1900 } = {}) {
    const el = document.createElement('div');
    el.className = `toast ${type}`;
    const icon = type === 'error' ? '!' : '✓';
    el.innerHTML = `<b aria-hidden="true">${icon}</b><span></span>`;
    el.lastChild.textContent = message;
    const h = ensureHost();
    while (h.children.length >= 2) h.firstChild.remove();
    h.appendChild(el);
    setTimeout(() => {
      el.classList.add('out');
      setTimeout(() => el.remove(), 220);
    }, duration);
  }

  async function copyText(text) {
    try {
      if (navigator.clipboard && window.isSecureContext) {
        await navigator.clipboard.writeText(text);
        return true;
      }
    } catch { /* fall through to legacy path */ }
    try {
      const ta = document.createElement('textarea');
      ta.value = text;
      ta.setAttribute('readonly', '');
      ta.style.cssText = 'position:fixed;top:0;left:0;opacity:0;pointer-events:none;';
      document.body.appendChild(ta);
      ta.select();
      ta.setSelectionRange(0, text.length);
      const ok = document.execCommand('copy');
      ta.remove();
      return ok;
    } catch {
      return false;
    }
  }

  function shortUrlForCopy(url) {
    try {
      const u = new URL(url, location.origin);
      if (u.hostname === location.hostname) return location.origin + u.pathname + u.search;
      return u.toString();
    } catch {
      return url;
    }
  }

  /** Briefly swaps a label to `doneText` and marks `el` as .copied. */
  function flash(el, labelEl, doneText, ms = 1500) {
    clearTimeout(el._flashT);
    if (labelEl) {
      if (el._orig == null) el._orig = labelEl.textContent;
      labelEl.textContent = doneText;
    }
    el.classList.add('copied');
    el._flashT = setTimeout(() => {
      el.classList.remove('copied');
      if (labelEl) { labelEl.textContent = el._orig; el._orig = null; }
    }, ms);
  }

  /**
   * Link history for this browser, including management keys, so keys aren't lost
   * when a tab closes. Lives in localStorage (this device only, never sent to the
   * server). Entries from the older per-tab sessionStorage list are merged in once.
   */
  const HISTORY_KEY = 'dispatch_session_history', HISTORY_MAX = 200;
  const history = {
    get() {
      let list = [];
      try { list = JSON.parse(localStorage.getItem(HISTORY_KEY) || '[]'); } catch { /* corrupted -> start fresh */ }
      try {
        const old = JSON.parse(sessionStorage.getItem(HISTORY_KEY) || '[]');
        if (old.length) {
          const have = new Set(list.map(u => u.shortCode));
          list = list.concat(old.filter(u => !have.has(u.shortCode)));
          localStorage.setItem(HISTORY_KEY, JSON.stringify(list.slice(0, HISTORY_MAX)));
          sessionStorage.removeItem(HISTORY_KEY);
        }
      } catch { /* storage blocked: fall through with what we have */ }
      return list;
    },
    set(list) {
      try { localStorage.setItem(HISTORY_KEY, JSON.stringify(list.slice(0, HISTORY_MAX))); } catch { /* storage full/blocked */ }
    },
    add(entry) {
      const list = history.get().filter(u => u.shortCode !== entry.shortCode);
      list.unshift(entry);                       // newest first
      history.set(list);
    },
    clear() { try { localStorage.removeItem(HISTORY_KEY); } catch {} },
  };

  return { copyText, toast, shortUrlForCopy, flash, history };
})();
