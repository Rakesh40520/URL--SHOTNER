/**
 * nav.js — renders the shared navigation bar on every page.
 *
 * Call Nav.render() after the DOM is ready (or at the end of <body>).
 * It reads Auth.isLoggedIn() to decide which links to show.
 *
 * Anonymous nav:  Logo | Home | Session History   Login  Register
 * Auth nav:       Logo | Home | My Dispatches     [name] Logout
 */

const Nav = (() => {
  function render() {
    const el = document.getElementById('site-nav');
    if (!el) return;

    const loggedIn = Auth.isLoggedIn();
    const name = Auth.getName() || 'Account';

    el.innerHTML = `
      <div class="nav-inner wrap">
        <a href="/index.html" class="nav-logo" aria-label="Dispatch home">
          <svg width="28" height="28" viewBox="0 0 34 34" fill="none" aria-hidden="true">
            <circle cx="17" cy="17" r="15.5" stroke="currentColor" stroke-width="2"/>
            <path d="M9 17.5L14.5 23L25 11" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round"/>
          </svg>
          <span>DISPATCH</span>
        </a>

        <nav class="nav-links" aria-label="Primary navigation">
          <a href="/index.html" class="nav-link ${isActive('index')}">Home</a>
          ${loggedIn
            ? `<a href="/dashboard.html" class="nav-link ${isActive('dashboard')}">My Dispatches</a>`
            : `<a href="/history.html" class="nav-link ${isActive('history')}">Session History</a>`
          }
        </nav>

        <div class="nav-actions">
          ${loggedIn ? `
            <span class="nav-user">
              <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" aria-hidden="true"><circle cx="12" cy="8" r="4"/><path d="M4 20c0-4 3.6-7 8-7s8 3 8 7"/></svg>
              ${escHtml(name)}
            </span>
            <button class="nav-btn nav-logout" id="logout-btn" aria-label="Log out">
              <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" aria-hidden="true"><path d="M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4"/><polyline points="16 17 21 12 16 7"/><line x1="21" y1="12" x2="9" y2="12"/></svg>
              Logout
            </button>
          ` : `
            <a href="/login.html" class="nav-link ${isActive('login')}">Login</a>
            <a href="/register.html" class="nav-btn nav-register ${isActive('register')}">Register</a>
          `}
        </div>
      </div>
    `;

    // Wire logout
    const logoutBtn = document.getElementById('logout-btn');
    if (logoutBtn) {
      logoutBtn.addEventListener('click', () => {
        Auth.clear();
        // session history lives in sessionStorage which persists through
        // logout (it's browser-session-scoped, not account-scoped), so we
        // don't touch it here per the spec.
        window.location.href = '/index.html';
      });
    }
  }

  function isActive(page) {
    return window.location.pathname.includes(page) ? 'active' : '';
  }

  function escHtml(str) {
    return str.replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
  }

  return { render };
})();
