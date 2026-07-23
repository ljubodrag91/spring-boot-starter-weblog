    // ─────────────────────────────────────────────────────────
    //  SIDEBAR TOGGLES
    // ─────────────────────────────────────────────────────────
    const LS_KEY = 'logviewer.layout.v1';

    function loadLayout() {
        try {
            return JSON.parse(localStorage.getItem(LS_KEY) || '{}');
        } catch (e) {
            return {};
        }
    }

    function saveLayout(patch) {
        const cur = loadLayout();
        Object.assign(cur, patch);
        try {
            localStorage.setItem(LS_KEY, JSON.stringify(cur));
        } catch (e) {
        }
    }

    // At ≤720 the leaderboard is moved into the facets drawer (the right panel is hidden on
    // mobile); above that it lives in its own aside. Nodes are moved, not cloned, so the
    // #leaderboardList render target and its click listener survive the relocation.
    function placeLeaderboard() {
        const content = $('lbContent');
        if (!content) return;
        const target = window.innerWidth <= 720
            ? $('lbMobileMount')
            : document.querySelector('.side.leaderboard');
        if (target && content.parentElement !== target) target.appendChild(content);
    }

    function applyLayout() {
        const l = loadLayout();
        placeLeaderboard();
        const grid = document.querySelector('.body-grid');
        grid.classList.toggle('no-facets', !!l.facetsHidden);
        const lbSuppressed = !isLeaderboardActive();
        grid.classList.toggle('no-lb', lbSuppressed || !!l.lbHidden);
        const lbEl = document.querySelector('.side.leaderboard');
        if (lbEl) lbEl.style.display = lbSuppressed ? 'none' : '';
        const lbMount = $('lbMobileMount');
        if (lbMount) lbMount.style.display = lbSuppressed ? 'none' : '';
        const fh = $('toggleFacets2'), lh = $('toggleLb2');
        if (fh) fh.textContent = l.facetsHidden ? '›' : '‹';
        if (lh) lh.textContent = (lbSuppressed || l.lbHidden) ? '‹' : '›';
        renderReopenRails(!!l.facetsHidden, lbSuppressed || !!l.lbHidden, lbSuppressed);
        // mobile backdrop
        const bd = $('mobileBackdrop');
        if (bd) bd.classList.toggle('visible', window.innerWidth <= 720 && !l.facetsHidden);
    }

    function renderReopenRails(facetsHidden, lbHidden, lbSuppressed) {
        let lr = document.getElementById('reopenLeft');
        let rr = document.getElementById('reopenRight');
        const pane = document.querySelector('.table-pane');
        if (facetsHidden && !lr) {
            lr = document.createElement('div');
            lr.id = 'reopenLeft';
            lr.className = 'reopen-rail left';
            lr.textContent = 'Filters ›';
            lr.onclick = () => {
                saveLayout({facetsHidden: false});
                applyLayout();
            };
            pane.appendChild(lr);
        } else if (!facetsHidden && lr) lr.remove();
        if (lbHidden && !lbSuppressed && !rr) {
            rr = document.createElement('div');
            rr.id = 'reopenRight';
            rr.className = 'reopen-rail right';
            rr.textContent = '‹ Slow lane';
            rr.onclick = () => {
                saveLayout({lbHidden: false});
                applyLayout();
            };
            pane.appendChild(rr);
        } else if ((!lbHidden || lbSuppressed) && rr) rr.remove();
    }

    function bindToggle(id, key) {
        const el = $(id);
        if (!el) return;
        el.onclick = () => {
            saveLayout({[key]: !loadLayout()[key]});
            applyLayout();
        };
    }

    bindToggle('toggleFacets2', 'facetsHidden');
    bindToggle('toggleLb2', 'lbHidden');

    // Mobile-only Filters button (in the table header) — opens/closes the off-canvas facets
    // drawer, since the desktop side-handle/reopen rail is hidden at phone widths.
    const mfb = $('mobileFacetsBtn');
    if (mfb) mfb.onclick = () => {
        saveLayout({facetsHidden: !loadLayout().facetsHidden});
        applyLayout();
    };

    const mbd = $('mobileBackdrop');
    if (mbd) mbd.onclick = () => {
        saveLayout({facetsHidden: true});
        applyLayout();
    };

    // Re-run layout when crossing the mobile breakpoint (relocate leaderboard, backdrop state).
    let _resizeTimer = null;
    window.addEventListener('resize', () => {
        clearTimeout(_resizeTimer);
        _resizeTimer = setTimeout(applyLayout, 150);
    });

    // ─────────────────────────────────────────────────────────
    //  LIVE IN-FLIGHT STRIP  (GET /admin/logs/inflight)
    //  Purely observational: shows requests running right now. Only on the access view,
    //  and paused while in history mode or when the browser tab is hidden.
    // ─────────────────────────────────────────────────────────
    let inflightTimer = null;
    let inflightExpanded = false;   // rows collapsed by default; the bar toggles them open
    const INFLIGHT_REFRESH_SECS = Math.max(1, Math.round(INFLIGHT_REFRESH_MS / 1000));
    let inflightLeft = INFLIGHT_REFRESH_SECS;

    function inflightActive() {
        return INFLIGHT_ENABLED && currentTab === 'access' && !rangeMode
            && document.visibilityState !== 'hidden';
    }

    function startInflightPoll() {
        if (!INFLIGHT_ENABLED || inflightTimer) return;
        // Expand/collapse the rows when the bar is clicked. Delegated to the persistent strip
        // element — its innerHTML is rebuilt each refresh, so a listener on the inner bar wouldn't
        // survive. Toggling a CSS class makes it instant, independent of the next data refresh.
        const strip = $('inflightStrip');
        if (strip) strip.addEventListener('click', (e) => {
            if (!e.target.closest('.if-bar') || strip.classList.contains('zero')) return;
            inflightExpanded = !inflightExpanded;
            strip.classList.toggle('expanded', inflightExpanded);
            const caret = document.getElementById('ifCaret');
            if (caret) caret.textContent = inflightExpanded ? '▾' : '▸';
        });
        inflightLeft = INFLIGHT_REFRESH_SECS;
        pollInflight();
        inflightTimer = setInterval(inflightTick, 1000);  // 1s heartbeat drives the countdown
    }

    // Every second: tick the countdown down; fetch fresh data when it reaches zero.
    function inflightTick() {
        if (!inflightActive()) { renderInflightStrip(null); return; }
        inflightLeft--;
        if (inflightLeft <= 0) { inflightLeft = INFLIGHT_REFRESH_SECS; pollInflight(); }
        else { updateInflightCountdown(); }
    }

    function updateInflightCountdown() {
        const el = document.getElementById('ifCountdown');
        if (el) el.textContent = inflightLeft + 's';
    }

    function pollInflight() {
        if (!inflightActive()) { renderInflightStrip(null); return; }
        fetch(`${CTX}/admin/logs/inflight`, {credentials: 'same-origin'})
            // Throw on a non-OK status so it lands in .catch alongside network errors: a transient
            // blip (auth/session hiccup, or a stumble while the API is being hammered) must keep the
            // last render, NOT hide the whole strip. Passing null here would set el.hidden=true and
            // make the panel vanish mid-tail. renderInflightStrip(null) is reserved for the genuinely
            // inactive case (wrong tab / range mode / hidden document), signalled by the guards above.
            .then(r => { if (!r.ok) throw new Error(r.status); return r.json(); })
            .then(v => renderInflightStrip(v))
            .catch(() => { /* transient network/auth blip — keep last render, try again next tick */ });
    }

    function renderInflightStrip(view) {
        const el = $('inflightStrip');
        if (!el) return;
        if (!inflightActive() || !view) { el.hidden = true; el.innerHTML = ''; return; }
        const reqs = Array.isArray(view.requests) ? view.requests : [];
        const count = view.count != null ? view.count : reqs.length;
        el.hidden = false;
        const countdown = `<span class="if-hint">next in <span id="ifCountdown">${inflightLeft}s</span></span>`;
        if (count === 0) {
            el.className = 'inflight-strip zero';
            el.innerHTML = `<div class="if-bar"><span class="if-dot"></span><strong>In-flight: 0</strong>${countdown}</div>`;
            return;
        }
        const slowN = reqs.filter(r => r && r.slow).length;
        el.className = 'inflight-strip' + (slowN ? ' has-slow' : '') + (inflightExpanded ? ' expanded' : '');
        const caret = `<span class="if-caret" id="ifCaret">${inflightExpanded ? '▾' : '▸'}</span>`;
        const head = `<div class="if-bar">${caret}<span class="if-dot live"></span>`
            + `<strong>In-flight: ${count}</strong>`
            + (slowN ? ` · <span class="if-slowcount">${slowN} slow</span>` : '')
            + countdown + `</div>`;
        // Header row — same grid as the rows so the columns line up. Order: timing · origin · request.
        const headRow = `<div class="if-head">`
            + `<span class="if-age">age</span>`
            + `<span class="if-started">started</span>`
            + `<span class="if-ip">ip</span>`
            + `<span class="if-thread">thread</span>`
            + `<span class="if-method">method</span>`
            + `<span class="if-uri">uri</span>`
            + `</div>`;
        const rows = reqs.map(r => {
            const secs = ((r.inFlightMs || 0) / 1000).toFixed(1);
            // startedAt is "yyyy-MM-dd HH:mm:ss.SSS"; show the time-of-day, keep the full value in the tooltip.
            const started = (r.startedAt || '').split(' ')[1] || (r.startedAt || '');
            return `<div class="if-row${r.slow ? ' slow' : ''}">`
                + `<span class="if-age">${secs}s</span>`
                + `<span class="if-started" title="${h(r.startedAt || '')}">${h(started)}</span>`
                + `<span class="if-ip">${h(r.ip || '')}</span>`
                + `<span class="if-thread" title="${h(r.thread || '')}">${h(r.thread || '')}</span>`
                + `<span class="if-method">${h(r.method || '')}</span>`
                + `<span class="if-uri" title="${h(r.uri || '')}">${h(r.uri || '')}</span>`
                + `</div>`;
        }).join('');
        el.innerHTML = head + headRow + `<div class="if-rows">${rows}</div>`;
    }

    document.addEventListener('visibilitychange', () => { if (!document.hidden) pollInflight(); });

    // ─────────────────────────────────────────────────────────
    //  INIT
    // ─────────────────────────────────────────────────────────
    (function init() {
        const layout = loadLayout();
        if (layout.type && !INIT_TYPE) currentTab = layout.type;
        if (window.innerWidth <= 720 && layout.mobileInit !== true) {
            saveLayout({facetsHidden: true, lbHidden: true, mobileInit: true});
        }
        renderTabs();
        updateEyebrow();
        applyLayout();
        searchF = getDefaultSearch();
        $('searchInput').value = searchF;
        loadInitial();
        startInflightPoll();
    })();
