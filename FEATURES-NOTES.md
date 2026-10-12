# New features (on top of the UI refinement)

## Subscriptions (Free / Pro / Business)
- Plans live in ONE place: entity/SubscriptionPlan.java (limits, price, features). Edit a number there and the pricing page, usage meter and server checks all follow.
- Free: 20 links/month, random codes only, links up to 30 days, no CSV. Pro: 500/month, custom codes, up to 365 days, CSV. Business: unlimited, links can never expire.
- Enforced on the server (SubscriptionService.applyPlanRules) for signed-in users; over-limit requests get HTTP 403 with code PLAN_LIMIT_REACHED / PLAN_FEATURE_LOCKED and the plan that unlocks it.
- Endpoints: GET /api/v1/plans (public), GET/POST /api/v1/subscription, GET /api/v1/urls/my/export (CSV).
- UI: pricing.html, plan + usage card and Export CSV on My Dispatches, locked custom-code field and capped expiry choices on the home form, upgrade dialog.
- NO PAYMENTS: POST /api/v1/subscription switches plan instantly (demo). Before charging real money set app.subscription.self-service-enabled=false and call changePlan() from a verified Stripe/Razorpay webhook.
- New nullable columns on users (plan, plan_changed_at) are added by ddl-auto=update; existing accounts count as Free.

## QR codes
- "QR code" button on the result card and every row in My Dispatches / Session History. Download PNG or SVG, copy link.
- Generated in the browser (js/vendor/qrcode.js, MIT, no CDN, no backend change). Optional ?ref=qr tag so scans show up as their own source.

## Click location + who sent it
- Each click now stores: referring website (host only), optional ?ref=name tag, and country/region/city.
- Location comes from the visitor IP via ipwho.is, looked up in the BACKGROUND after the redirect (never slows a redirect). Disable with app.geo.enabled=false.
- "Who suggested it": the website the visitor came from (Referer header) and a ?ref= tag you add yourself, e.g. https://yoursite/abc1234?ref=alice.
- GET /api/v1/urls/{code}/analytics (top countries, cities, sources, tags) and richer /clicks. Shown under "Track a parcel".
- render.yaml sets APP_GEO_TRUST_FORWARDED_FOR=true so Render's real visitor IP is used (analytics only; rate limiting is unaffected).
- New columns on url_click_event are added by ddl-auto=update.

## Tests added
SubscriptionServiceTest, ClickSourceTest, GeoLocationServiceTest, plus new cases in ControllerIntegrationTest and ClientIpResolverTest. Run: mvn test

# Update: location fixes + stored management keys

## Location
- Providers: ipwho.is, then get.geojs.io, then ipapi.co - the next one is tried if one fails or blocks the server.
- A failed lookup is only remembered for 5 minutes (before, one hiccup could leave an IP "unknown" for good).
- Retry job (ClickGeoRetryJob): every 5 min, re-tries clicks from the last 3 days that still have no location, at most once an hour each.
- Failures are now logged (WARN, at most once a minute) so the Render logs say why.
- The recent-clicks list says "server saw a private IP" when the real visitor IP wasn't forwarded.
- ON RENDER: set the environment variable APP_GEO_TRUST_FORWARDED_FOR=true in the service's Environment tab (render.yaml only applies when the blueprint is synced), then redeploy. Without it every click shows Render's internal IP and can't be located.
- Test with a different network (mobile data), not from the same machine as the server: private/localhost IPs are never looked up.

## Management keys
- Saved on this device: link history (with keys) now lives in localStorage instead of per-tab sessionStorage, so keys survive closing the tab. Old per-tab entries are merged in automatically. Page renamed "Link History", with a Clear all button.
- Saved on the server for signed-in owners: the key is stored ENCRYPTED (AES-256-GCM, secret from app.key-vault.secret, defaulting to JWT_SECRET). My Dispatches has a Key button to copy it. Links created before this update have no stored key (they were only ever kept as a hash) so they show no Key button; ownership still lets you view their stats.
- Anonymous links are not stored on the server (no account to attach them to); the browser copy is the only one.
- If you change JWT_SECRET (or app.key-vault.secret), stored keys can no longer be decrypted and the Key button disappears for those links. Set app.key-vault.secret separately if you plan to rotate JWT_SECRET.

# Update: email verification + forgot password (emailed 6-digit codes)

## How it works
- Register no longer logs you in. It creates the account (unverified), emails a 6-digit code and sends you to verify-email.html. Entering the right code verifies the address and logs you in.
- Login is refused for unverified accounts (only after the password is correct, so it can't be used to find out who is registered). The login page then sends you to verify-email.html, which asks for a fresh code.
- Forgot password: login page -> "Forgot password?" -> enter email -> enter the code + a new password. The answer is the same whether or not the email is registered.
- Codes: 6 digits, valid 10 minutes, single use, stored only as a bcrypt hash, 5 wrong guesses burn the code, a new code replaces the old one, max 1 email per 60 s and 5 per hour per address.
- Existing accounts are treated as verified (the new column is empty for them), so nobody is locked out by the upgrade.
- Re-registering an address that never finished verifying just updates it and sends a new code, so nobody can squat on an address.
- Endpoints: POST /api/v1/auth/{register, verify-email, resend-code, login, forgot-password, reset-password}.

## Email setup (REQUIRED for real delivery)
Render's free web services BLOCK outbound SMTP, so mail is sent over an HTTPS API. Easiest: Brevo (free, 300 emails/day, no domain needed).
1. Create a free account at brevo.com, go to Senders and add + verify the email address you want codes to come from.
2. Create an API key (SMTP & API -> API Keys).
3. On Render -> your service -> Environment add:  MAIL_PROVIDER=brevo   MAIL_FROM_EMAIL=<the verified sender>   BREVO_API_KEY=<key>   (optional MAIL_FROM_NAME)
4. Redeploy. (Resend also works: MAIL_PROVIDER=resend + RESEND_API_KEY, but it only delivers to your own address until you verify a domain.)
- With no MAIL_PROVIDER set the app uses "log" mode: emails are NOT sent, the code is printed in the server log. Fine for local development, not for production.
- If the key is missing/wrong, register shows "couldn't send the email" and the log says why; the app itself still starts.

## Limits worth knowing
- Existing login tokens stay valid after a password reset (tokens are stateless, up to 24 h).
- Rate limiting is per email address, not per IP.
- Changing an account's email isn't part of this.
