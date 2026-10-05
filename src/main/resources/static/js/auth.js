/**
 * auth.js — shared authentication state for every page.
 *
 * The JWT is stored in localStorage under the key "dispatch_token".
 * Alongside it we keep basic user info (name, email) so pages can display
 * a greeting without re-fetching the user from the server on every load.
 *
 * SECURITY NOTE: localStorage is readable by any JS on the same origin.
 * Using httpOnly cookies is more secure but requires a same-origin server
 * and more complex backend plumbing. For a learning project this tradeoff
 * is fine — the spec explicitly asks for localStorage.
 */

const Auth = (() => {
  const TOKEN_KEY  = 'dispatch_token';
  const NAME_KEY   = 'dispatch_name';
  const EMAIL_KEY  = 'dispatch_email';

  function getToken()  { return localStorage.getItem(TOKEN_KEY); }
  function getName()   { return localStorage.getItem(NAME_KEY); }
  function getEmail()  { return localStorage.getItem(EMAIL_KEY); }
  function isLoggedIn(){ return !!getToken(); }

  function save({ token, name, email }) {
    localStorage.setItem(TOKEN_KEY,  token);
    localStorage.setItem(NAME_KEY,   name);
    localStorage.setItem(EMAIL_KEY,  email);
  }

  function clear() {
    localStorage.removeItem(TOKEN_KEY);
    localStorage.removeItem(NAME_KEY);
    localStorage.removeItem(EMAIL_KEY);
  }

  /** Adds the Authorization header to a fetch options object if logged in. */
  function authHeaders(extra = {}) {
    const headers = { 'Content-Type': 'application/json', ...extra };
    const token = getToken();
    if (token) headers['Authorization'] = `Bearer ${token}`;
    return headers;
  }

  /**
   * Fetch wrapper that automatically attaches the auth header.
   * If the server returns 401, logs the user out and redirects to /login.html.
   */
  async function apiFetch(url, options = {}) {
    const res = await fetch(url, {
      ...options,
      headers: authHeaders(options.headers),
    });
    if (res.status === 401) {
      clear();
      window.location.href = '/login.html?reason=session';
      return null; // caller should check for null if it needs to
    }
    return res;
  }

  /** Redirect to /login.html if not logged in. Call at top of protected pages. */
  function requireAuth() {
    if (!isLoggedIn()) {
      window.location.href = '/login.html?reason=protected';
    }
  }

  /** Redirect away from login/register if already logged in. */
  function redirectIfLoggedIn(to = '/dashboard.html') {
    if (isLoggedIn()) window.location.href = to;
  }

  return { getToken, getName, getEmail, isLoggedIn, save, clear, authHeaders, apiFetch, requireAuth, redirectIfLoggedIn };
})();
