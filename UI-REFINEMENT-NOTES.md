# UI refinement — what changed (all inside src/main/resources/static)

Phase 1 – Foundation: js/ui.js (NEW: clipboard w/ fallback, toast, copy flash), mobile hamburger nav (js/nav.js, css/nav.css), toasts.
Phase 2 – My Dispatches + Session History: click short code copies full link (+ toast), delegated handlers survive re-render/delete/paging,
          mobile card layout (no hidden columns), icon-only actions on desktop, 5-col stats grid.
Phase 3 – Dispatch animation: 4 real stages (check → scan → assign → stamp), progress eases to ~90% and only hits 100% on the real response,
          seal slams down with the code, slow-server notes for Render free-tier cold starts, 60s timeout, Enter-to-submit.
Phase 4 – Home responsiveness: full-width tap-friendly buttons, 16px inputs (no iOS zoom), stacked result/track rows, reduced-motion support.

No backend / pom / Dockerfile changes. Redeploy to Render as usual (git push).
