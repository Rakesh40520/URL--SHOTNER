/**
 * plans.js — subscription helpers shared by pages.
 *
 *   Plans.list()              → all tiers (public endpoint, cached)
 *   Plans.mine()              → signed-in user's plan + this month's usage, or null
 *   Plans.price(cents)        → "Free" / "$4.99"
 *   Plans.promptUpgrade(err)  → upgrade dialog for a PLAN_* error from the API
 */
const Plans = (() => {
  const CURRENCY = '$';      // change here to show another currency symbol
  let listCache;

  const price = c => c === 0 ? 'Free' : CURRENCY + (c / 100).toFixed(c % 100 ? 2 : 0);
  const planName = key => ({ FREE: 'Free', PRO: 'Pro', BUSINESS: 'Business' }[key] || key);

  async function list() {
    if (!listCache) {
      const res = await fetch('/api/v1/plans');
      if (!res.ok) throw new Error('plans');
      listCache = await res.json();
    }
    return listCache;
  }

  async function mine() {
    if (!Auth.isLoggedIn()) return null;
    try {
      const res = await Auth.apiFetch('/api/v1/subscription');
      return res && res.ok ? await res.json() : null;
    } catch { return null; }
  }

  async function change(planKey) {
    const res = await Auth.apiFetch('/api/v1/subscription', {
      method: 'POST', body: JSON.stringify({ plan: planKey }),
    });
    if (!res) return null;
    const data = await res.json().catch(() => ({}));
    if (!res.ok) throw new Error(data.message || 'Could not change plan.');
    return data;
  }

  function promptUpgrade(err) {
    const need = err && err.requiredPlan ? planName(err.requiredPlan) : 'a higher';
    const wrap = document.createElement('div');
    wrap.className = 'qr-overlay visible';
    wrap.innerHTML = `
      <div class="qr-card upgrade-card" role="dialog" aria-modal="true" aria-labelledby="up-title">
        <button class="qr-close" type="button" aria-label="Close">&times;</button>
        <p class="qr-eyebrow">PLAN LIMIT</p>
        <h2 id="up-title" class="qr-title">Upgrade to unlock this</h2>
        <p class="upgrade-msg"></p>
        <div class="qr-actions">
          <a class="qr-btn primary" href="/pricing.html">See ${need === 'a higher' ? 'plans' : need + ' plan'}</a>
          <button type="button" class="qr-btn" data-close>Not now</button>
        </div>
      </div>`;
    wrap.querySelector('.upgrade-msg').textContent = (err && err.message) || 'This action isn\'t available on your current plan.';
    const prev = document.activeElement;
    const close = () => { wrap.remove(); if (prev && prev.focus) prev.focus({ preventScroll: true }); };
    wrap.addEventListener('click', e => { if (e.target === wrap || e.target.closest('.qr-close,[data-close]')) close(); });
    wrap.addEventListener('keydown', e => { if (e.key === 'Escape') close(); });
    document.body.appendChild(wrap);
    wrap.querySelector('.qr-btn.primary').focus();
  }

  const isPlanError = data => !!(data && typeof data.code === 'string' && data.code.startsWith('PLAN_'));

  return { list, mine, change, price, planName, promptUpgrade, isPlanError };
})();
