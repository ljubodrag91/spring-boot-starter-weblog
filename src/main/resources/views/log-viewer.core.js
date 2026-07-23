    // ─────────────────────────────────────────────────────────
    //  INJECTED BY SERVER
    // ─────────────────────────────────────────────────────────
    const CTX = window.__INIT.ctx;
    const INIT_TYPE = window.__INIT.type;
    const INIT_LINES = window.__INIT.lines;
    const INIT_DATA = window.__INIT.data;
    // Name of the configured per-request attribute (log-viewer.request-attribute); the access
    // log JSON carries its value under this key. Empty string ⇒ feature disabled, hide it.
    const REQ_ATTR = window.__INIT.reqAttr;
    // Human label for the attribute column/row (Title Case of the key).
    const REQ_ATTR_LABEL = REQ_ATTR
        ? REQ_ATTR.replace(/[_.-]+/g, ' ').replace(/\b\w/g, c => c.toUpperCase())
        : '';
    // Live in-flight strip config (GET /admin/logs/inflight). Enabled unless the server said false.
    const INFLIGHT_ENABLED = window.__INIT.inflightEnabled !== false;
    const INFLIGHT_REFRESH_MS = Number(window.__INIT.inflightRefreshMs) || 3000;

    // ─────────────────────────────────────────────────────────
    //  STATE
    // ─────────────────────────────────────────────────────────
    let ALL = [];
    let DRAW = [];
    let _globalSeq = 0;    // ever-increasing; stamps each normalized row for stable sort tiebreaking
    let PAGE_ROWS = [];
    let _allSortedByTime = null;  // cached chronological copy of ALL; nulled when ALL changes

    const currentSource = 'tomcat';
    let currentTab = INIT_TYPE || 'access';
    let MODE = 'request';  // 'request' | 'server'

    let dtFrom = null;
    let dtTo = null;
    let statusF = '';
    let methodF = '';
    let uriF = '';
    let searchF = '';
    let bucketSelF = null;

    let sortField = 'timestamp';
    let sortDir = 'desc';
    let page = 0;
    let pageSize = 50;

    let live          = false;
    let liveTickTimer = null;
    let liveCountdown = 0;

    // History (server-side paged) mode — see /admin/logs/page. When on, ALL holds one page and
    // ◀ Older / Newer ▶ navigate via the cursors returned with each page. Mutually exclusive
    // with live tail. Cursors are {file, index}; null until the first page loads.
    let rangeMode    = false;
    let pageOldest   = null;
    let pageNewest   = null;
    let pageHasOlder = false;
    let pageHasNewer = false;
    let newRowSet     = new WeakSet();
    let lastSyncAt    = null;
    // Content fingerprints of rows that have already flashed — never flash the same row twice.
    // Cleared on full data reload (fetchData / loadInitial) so new page loads start clean.
    let flashedKeys   = new Set();

    // Stable fingerprint for a log row — content-based so it survives re-normalization.
    function rowKey(r) {
        return r.timestamp + '\x00' + (r.method || r.level || '') + '\x00' + (r.uri || r.logger || '');
    }

    // ─────────────────────────────────────────────────────────
    //  UTIL
    // ─────────────────────────────────────────────────────────
    const $ = (id) => document.getElementById(id);
    const fmt = new Intl.NumberFormat('en-US');
    const h = (s) => String(s == null ? '' : s).replace(/[&<>"]/g, c => ({
        '&': '&amp;',
        '<': '&lt;',
        '>': '&gt;',
        '"': '&quot;'
    }[c]));

    function tsToMs(ts) {
        if (!ts || ts === '-') return 0;
        // Primary: dd-MMM-yyyy HH:mm:ss.SSS  e.g. "22-May-2026 14:30:45.123"
        const m1 = ts.match(/^(\d{2})-([A-Za-z]{3})-(\d{4})\s(\d{2}):(\d{2}):(\d{2})(?:\.(\d{1,3}))?/);
        if (m1) {
            const mon = {
                Jan: 0,
                Feb: 1,
                Mar: 2,
                Apr: 3,
                May: 4,
                Jun: 5,
                Jul: 6,
                Aug: 7,
                Sep: 8,
                Oct: 9,
                Nov: 10,
                Dec: 11
            };
            return new Date(+m1[3], mon[m1[2]] ?? 0, +m1[1], +m1[4], +m1[5], +m1[6], +(m1[7] || 0)).getTime();
        }
        // Fallback: yyyy-MM-dd HH:mm:ss
        const m2 = ts.match(/^(\d{4})-(\d{2})-(\d{2})[ T](\d{2}):(\d{2}):(\d{2})(?:\.(\d{1,3}))?/);
        if (m2) return new Date(+m2[1], +m2[2] - 1, +m2[3], +m2[4], +m2[5], +m2[6], +(m2[7] || 0)).getTime();
        return Date.parse(ts) || 0;
    }

    function msToInput(ms) {
        if (!ms) return '';
        const d = new Date(ms);
        const p = (n) => String(n).padStart(2, '0');
        return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}T${p(d.getHours())}:${p(d.getMinutes())}`;
    }

    function inputToMs(v) {
        return v ? new Date(v).getTime() : null;
    }

    function statusBucket(s) {
        return s >= 500 ? '5' : s >= 400 ? '4' : s >= 300 ? '3' : s >= 200 ? '2' : '0';
    }

    // Colour class for a status cell/badge. Client-aborted requests (connFate 'X') are their
    // own nginx-style 499 class (amber) rather than inheriting the real status's colour — this
    // keeps them visually consistent with the "499 · Aborted" facet and out of the 2xx colour.
    function statusClass(r) {
        return r.connFate === 'X' ? 's-aborted' : 's' + statusBucket(r.status);
    }

    // Display-only status text. Tomcat commits the real status (often 200) before a client
    // aborts, so an abandoned request still logs its intended status; connFate 'X' is the abort
    // marker. Surface it as nginx-style 499 ("client closed request") as the primary code, with
    // the real logged status shown secondarily — e.g. 499(200). The logged status is unchanged;
    // for filtering/counting these rows live only in the "499 · Aborted" facet (see
    // entryMatchesClassFilter / renderFacetsRequest), not in the 2xx bucket.
    function statusDisplay(r) {
        const real = h(r.status);
        if (r.connFate !== 'X') return real;
        return `499<span class="status-aborted" title="Client closed the connection before the response finished — nginx-style 499 (Client Closed Request). Real logged status was ${real}.">(${real})</span>`;
    }

    function methodClass(m) {
        return ['GET', 'POST', 'PUT', 'PATCH', 'DELETE'].includes(m) ? `m-${m}` : 'm-OTHER';
    }

    function durClass(d) {
        return d > 1000 ? 'd-slow' : d > 500 ? 'd-warn' : 'd-ok';
    }

    // Stable per-user colouring: the same principal always gets the same swatch across
    // reloads (hash of the name, not its position in the batch), so a user is recognisable
    // by colour while scanning. Muted on purpose — this is a scanning aid, not a status signal,
    // and must not compete with the status/duration colours that do carry meaning.
    const USER_PALETTES = [
        {bg: 'rgba(58,74,122,.09)',  accent: '#3a4a7a'},
        {bg: 'rgba(63,107,74,.09)',  accent: '#3f6b4a'},
        {bg: 'rgba(107,74,138,.09)', accent: '#6b4a8a'},
        {bg: 'rgba(141,114,72,.09)', accent: '#8d7248'},
        {bg: 'rgba(72,110,110,.09)', accent: '#486e6e'},
        {bg: 'rgba(110,72,72,.09)',  accent: '#6e4848'},
        {bg: 'rgba(72,90,110,.09)',  accent: '#485a6e'},
        {bg: 'rgba(90,100,72,.09)',  accent: '#5a6448'},
    ];

    function userPalette(user) {
        let hash = 0;
        for (let i = 0; i < user.length; i++) hash = (Math.imul(31, hash) + user.charCodeAt(i)) | 0;
        return USER_PALETTES[Math.abs(hash) % USER_PALETTES.length];
    }

    function fmtBytes(n) {
        const num = +n;
        if (!n || n === '-' || isNaN(num)) return '—';
        if (num === 0) return '0 B';
        const raw = `<span style="color:var(--faint);font-size:10.5px"> (${fmt.format(num)} B)</span>`;
        if (num < 1024) return `${fmt.format(num)} B`;
        if (num < 1024 * 1024) return `${(num / 1024).toFixed(1)} KB${raw}`;
        return `${(num / (1024 * 1024)).toFixed(2)} MB${raw}`;
    }

    function percentile(arr, p) {
        if (!arr.length) return 0;
        const sorted = arr.slice().sort((a, b) => a - b);
        return sorted[Math.min(sorted.length - 1, Math.floor(sorted.length * p))];
    }

    function timeHtml(ts) {
        const full = timeShort(ts);
        if (!full) return '';
        // Split into date / time-of-day / millis so the date can be hidden on mobile
        // (.t-date is display:none at ≤720 — time-of-day alone is enough on a phone).
        const sp = full.indexOf(' ');
        const datePart = sp >= 0 ? full.slice(0, sp) : '';
        const rest = sp >= 0 ? full.slice(sp + 1) : full;
        const dot = rest.lastIndexOf('.');
        const timeMain = dot < 0 ? rest : rest.slice(0, dot);
        const ms = dot < 0 ? '' : rest.slice(dot);
        const dateHtml = datePart ? `<span class="t-date">${h(datePart)} </span>` : '';
        const msHtml = ms ? `<span class="t-ms" style="color:var(--faint);font-size:10px;letter-spacing:0">${h(ms)}</span>` : '';
        return dateHtml + h(timeMain) + msHtml;
    }

    function timeShort(ts) {
        if (!ts) return '';
        const MON = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];
        const sp = ts.indexOf(' ');
        const timePart = sp >= 0 ? ts.slice(sp + 1, sp + 13) : ts.slice(11, 23);
        const datePart = sp >= 0 ? ts.slice(0, sp) : ts.slice(0, 10);
        // "22-May-2026" → "22-May-26"
        const m1 = datePart.match(/^(\d{1,2})-([A-Za-z]{3})-(\d{2,4})/);
        if (m1) return `${m1[1]}-${m1[2]}-${m1[3].slice(-2)} ${timePart}`;
        // "2026-05-22" → "22-May-26"
        const m2 = datePart.match(/^(\d{4})-(\d{2})-(\d{2})/);
        if (m2) return `${+m2[3]}-${MON[+m2[2] - 1] ?? m2[2]}-${m2[1].slice(-2)} ${timePart}`;
        return timePart;
    }

    // ─────────────────────────────────────────────────────────
    //  NORMALIZE
    // ─────────────────────────────────────────────────────────
    function normalize(rows) {
        return rows.map(r => ({...r, _ms: tsToMs(r.timestamp), _seq: _globalSeq++}));
    }

    /**
     * Returns ALL sorted chronologically (oldest first).  The result is cached
     * and reused across modal opens; it is invalidated whenever ALL is replaced.
     * Avoids the O(n log n) sort on every row click.
     */
    function getAllSortedByTime() {
        if (!_allSortedByTime) {
            _allSortedByTime = ALL.slice().sort((a, b) => a._ms - b._ms);
        }
        return _allSortedByTime;
    }

    function invalidateAllSorted() { _allSortedByTime = null; }

    // ─────────────────────────────────────────────────────────
    //  MODE / SOURCE / TABS
    // ─────────────────────────────────────────────────────────
    const APP_LABEL = (CTX.replace(/^\//, '') || 'Application') + ' logs';
    const FILE_LABELS = {
        'tomcat:access':     {title: APP_LABEL, sub: 'Tomcat', file: 'access.log'},
        'tomcat:catalina':   {title: APP_LABEL, sub: 'Tomcat', file: 'catalina.log'},
        'tomcat:error':      {title: APP_LABEL, sub: 'Tomcat', file: 'error.log'},
        'tomcat:exclusions': {title: APP_LABEL, sub: 'Tomcat', file: 'exclusions.log'},
        'tomcat:slow':       {title: APP_LABEL, sub: 'Tomcat', file: 'slow.log'},
    };

    function tabsConfig() {
        return [
            {type: 'access',     label: 'Access'},
            {type: 'catalina',   label: 'Catalina'},
            {type: 'error',      label: '⚠ Errors'},
            {type: 'exclusions', label: 'Excluded'},
            {type: 'slow',       label: '🐌 Slow'},
        ];
    }

    function renderTabs() {
        const cont = $('mhTabs');
        cont.innerHTML = tabsConfig().map(t =>
            `<button data-type="${t.type}" class="${t.type === currentTab ? 'active' : ''}">${t.label}</button>`
        ).join('');
    }

    function updateEyebrow() {
        const lbl = FILE_LABELS[`${currentSource}:${currentTab}`]
            || {title: currentSource, file: currentTab};
        $('ebrow').innerHTML =
            `<span class="mh-eyebrow-title">${h(lbl.title)}</span>` +
            (lbl.sub ? `<span class="mh-eyebrow-sub">${h(lbl.sub)}</span>` : '') +
            `<span class="mh-eyebrow-file">${h(lbl.file)}</span>`;
    }

    function detectMode() {
        const sample = ALL.find(r => r && r.level != null);
        MODE = sample ? 'server' : 'request';
        document.body.classList.toggle('mode-server', MODE === 'server');
    }

    function isLeaderboardActive() {
        return MODE !== 'server' && currentTab !== 'error';
    }

    function getDefaultSearch() {
        return currentTab === 'access' ? CTX : '';
    }

    function entryClass(r) {
        if (r.level != null) {
            const lv = String(r.level).toUpperCase();
            if (lv === 'ERROR' || lv === 'FATAL' || lv === 'SEVERE') return 'err';
            if (lv === 'WARN' || lv === 'WARNING') return 'warn';
            if (lv === 'INFO' || lv === 'NOTICE') return 'ok';
            return 'redir';
        }
        const s = r.status || 0;
        if (s >= 500) return 'err';
        if (s >= 400) return 'warn';
        if (s >= 300) return 'redir';
        // status <= 0 = no real response yet (Slow-tab entries logged while in flight): flag amber,
        // not green — these are unfinished/slow requests, never a completed 2xx.
        if (s <= 0) return 'warn';
        return 'ok';
    }

    function legendConfig() {
        if (MODE === 'server') return [
            {key: 'ok', label: 'INFO', color: 'var(--ok)'},
            {key: 'redir', label: 'DEBUG', color: 'var(--blue)'},
            {key: 'warn', label: 'WARN', color: 'var(--amber)'},
            {key: 'err', label: 'ERROR', color: 'var(--signal)'},
        ];
        return [
            {key: 'ok', label: '2xx', color: 'var(--ok)'},
            {key: 'redir', label: '3xx', color: 'var(--blue)'},
            {key: 'warn', label: '4xx', color: 'var(--amber)'},
            {key: 'err', label: '5xx', color: 'var(--signal)'},
        ];
    }

    function entryMatchesClassFilter(r) {
        if (!statusF) return true;
        if (MODE === 'server') return String(r.level || '').toUpperCase() === statusF;
        // 'aborted' is a dedicated bucket: client-closed (connFate 'X'), surfaced as nginx-style 499.
        // Matched off connFate, and mutually exclusive with the numeric buckets — an aborted request
        // with a real status of 200 shows only under "499 · Aborted", never under 2xx.
        if (statusF === 'aborted') return r.connFate === 'X';
        return r.connFate !== 'X' && statusBucket(r.status) === statusF;
    }

    // ─────────────────────────────────────────────────────────
    //  FETCH
    // ─────────────────────────────────────────────────────────
    function loadInitial() {
        flashedKeys = new Set();   // fresh session — nothing pre-flashed
        if (Array.isArray(INIT_DATA)) {
            ALL = normalize(INIT_DATA);
            invalidateAllSorted();
            lastSyncAt = new Date();
            onDataReady(true);
        } else {
            fetchData();
        }
    }

    // Full refresh — replaces ALL, resets range, shows spinner.
    // Called on tab switch, lines change, manual Refresh button.
    function fetchData() {
        flashedKeys = new Set();   // new dataset — allow fresh flashes from the next live tick
        setLoading(true);
        $('errBanner').hidden = true;
        const lines = $('linesEl').value;
        fetch(`${CTX}/admin/logs/data?type=${encodeURIComponent(currentTab)}&lines=${lines}`, {credentials: 'same-origin'})
            .then(r => { if (!r.ok) throw new Error(r.status); return r.json(); })
            .then(data => {
                ALL = normalize(data);
                invalidateAllSorted();
                lastSyncAt = new Date();
                setLoading(false);
                onDataReady(true);
            })
            .catch(() => {
                setLoading(false);
                $('errBanner').hidden = false;
            });
    }

    // Live-tail tick — smart merge: only prepends genuinely new rows,
    // highlights them, silently ignores errors.
    function fetchLive() {
        const prevMax = ALL.reduce((m, r) => r._ms > m ? r._ms : m, 0);
        const lines   = +$('linesEl').value || 5000;   // guard: .value=="" if option not found
        fetch(`${CTX}/admin/logs/data?type=${encodeURIComponent(currentTab)}&lines=${lines}`, {credentials: 'same-origin'})
            .then(r => { if (!r.ok) throw new Error(r.status); return r.json(); })
            .then(data => {
                lastSyncAt = new Date();
                $('lastSync').textContent = `last sync ${lastSyncAt.toLocaleTimeString()}`;

                // Data arrives newest-first.  Walk forward and stop as soon as we hit a
                // row that is NOT newer than prevMax — everything after is already in ALL.
                // This avoids normalizing the entire response (tsToMs + _globalSeq++) on
                // every tick even when there are no new rows.
                const freshRows = [];
                if (prevMax > 0) {
                    for (const row of data) {
                        const ms = tsToMs(row.timestamp);
                        if (ms <= prevMax) break;          // all remaining rows already known
                        const key = rowKey(row);
                        if (!flashedKeys.has(key)) {
                            freshRows.push({...row, _ms: ms, _seq: _globalSeq++});
                        }
                    }
                }
                // prevMax=0 means ALL is empty — bootstrap only, no flash needed.

                if (freshRows.length === 0) return; // nothing new — leave display untouched

                // Prepend new rows, cap to avoid unbounded growth
                ALL = [...freshRows, ...ALL].slice(0, lines);
                invalidateAllSorted();

                // Mark for highlight; stamp flashedKeys so these rows never flash again.
                // Clear after animation finishes (2.1 s matches the CSS transition).
                newRowSet = new WeakSet();
                freshRows.forEach(r => { newRowSet.add(r); flashedKeys.add(rowKey(r)); });
                // Bound flashedKeys: ALL is capped to `lines`, but this Set would otherwise grow
                // for every row ever seen, leaking memory across a long live-tail session. Once it
                // drifts well past what we still hold, rebuild it from the rows currently in ALL.
                if (flashedKeys.size > ALL.length * 2) {
                    flashedKeys = new Set(ALL.map(rowKey));
                }
                setTimeout(() => { newRowSet = new WeakSet(); renderTable(); }, 2100);

                // Advance sliding time window so new rows stay in view.
                // Skip when a histogram bucket is selected: advancing dtTo shifts all bucket
                // boundaries and makes the selected bucket appear to drift even though the
                // user hasn't changed anything. The bucket filter (bucketSelF) supersedes
                // the preset window while a selection is active.
                if (!bucketSelF) {
                    const activePreset = $('presetSeg').querySelector('button.active')?.dataset?.preset;
                    if (activePreset && activePreset !== 'all') {
                        const ms = {'15m': 15 * 60e3, '1h': 3600e3, '6h': 6 * 3600e3, '24h': 24 * 3600e3}[activePreset];
                        if (ms) {
                            const now = Date.now();
                            dtFrom = now - ms;
                            dtTo   = now;
                            $('dtFrom').value = msToInput(dtFrom);
                            $('dtTo').value   = msToInput(dtTo);
                        }
                    }
                }

                applyFilters();
            })
            .catch(() => { /* live errors are transient — silently skip */ });
    }

    // ── History mode: server-side paged / date-ranged reads (/admin/logs/page) ──

    function msToDate(ms) {
        if (!ms) return '';
        const d = new Date(ms);
        const p = n => String(n).padStart(2, '0');
        return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}`;
    }

    function enterHistory() {
        if (rangeMode) return;
        rangeMode = true;
        if (live) stopLive();
        $('liveBtn').disabled = true;
        $('liveBtn').style.opacity = '.5';
        $('histBtn').style.fontWeight = '700';
        $('histControls').hidden = false;
        const tg = $('timeRangeGroup'); if (tg) tg.hidden = true;
        $('batchNote').hidden = false;
        // Default window: today (server selects that day's file[s]). Clearing a bound = unbounded.
        if (!$('histTo').value)   $('histTo').value   = msToDate(Date.now());
        if (!$('histFrom').value) $('histFrom').value = msToDate(Date.now());
        pageOldest = pageNewest = null;
        fetchPage('initial');
    }

    function exitHistory() {
        if (!rangeMode) return;
        rangeMode = false;
        pageOldest = pageNewest = null;
        $('liveBtn').disabled = false;
        $('liveBtn').style.opacity = '';
        $('histBtn').style.fontWeight = '';
        $('histControls').hidden = true;
        const tg = $('timeRangeGroup'); if (tg) tg.hidden = false;
        $('batchNote').hidden = true;
        fetchData(); // back to the live/tail view
    }

    /**
     * Fetches one page from /admin/logs/page and replaces ALL with it (navigation, not
     * accumulation — keeps both server and browser memory bounded to one page). dir is
     * 'older' | 'newer' | 'initial'; 'initial' (or a missing cursor) requests the newest page
     * of the current date window.
     */
    function fetchPage(dir) {
        setLoading(true);
        $('errBanner').hidden = true;
        flashedKeys = new Set();
        const lines = +$('linesEl').value || 5000;
        const params = new URLSearchParams({type: currentTab, lines: String(lines)});
        const from = $('histFrom').value, to = $('histTo').value;
        if (from) params.set('from', from);
        if (to)   params.set('to', to);
        if (dir === 'older' && pageOldest) {
            params.set('dir', 'older');
            params.set('cursorFile', pageOldest.file);
            params.set('cursorIndex', String(pageOldest.index));
        } else if (dir === 'newer' && pageNewest) {
            params.set('dir', 'newer');
            params.set('cursorFile', pageNewest.file);
            params.set('cursorIndex', String(pageNewest.index));
        } else {
            params.set('dir', 'older'); // initial: newest page of the window
        }
        fetch(`${CTX}/admin/logs/page?${params.toString()}`, {credentials: 'same-origin'})
            .then(r => { if (!r.ok) throw new Error(r.status); return r.json(); })
            .then(pageResp => {
                ALL = normalize(pageResp.entries || []);
                invalidateAllSorted();
                pageOldest   = pageResp.oldest;
                pageNewest   = pageResp.newest;
                pageHasOlder = !!pageResp.hasOlder;
                pageHasNewer = !!pageResp.hasNewer;
                $('olderBtn').disabled = !pageHasOlder;
                $('newerBtn').disabled = !pageHasNewer;
                lastSyncAt = new Date();
                setLoading(false);
                // Range mode shows the whole loaded page: disable the client time-window so the
                // sidebar preset/brush don't hide rows the user explicitly navigated to.
                dtFrom = dtTo = null;
                bucketSelF = null;
                onDataReady(false);
            })
            .catch(() => {
                setLoading(false);
                $('errBanner').hidden = false;
            });
    }

    function setLoading(on) {
        $('loadOverlay').hidden = !on;
        $('refreshBtn').style.opacity = on ? '.5' : '';
    }

    function onDataReady(resetRange) {
        detectMode();
        applyLayout();
        if (resetRange) applyPreset('24h', true);
        applyFilters();
    }

    // ─────────────────────────────────────────────────────────
    //  FILTERS
    // ─────────────────────────────────────────────────────────

    /**
     * Returns true if the row passes every active filter EXCEPT the time-range
     * filters (dtFrom / dtTo / bucketSelF).  Used by both applyFilters() and
     * renderHistogram() so the two code paths can never silently diverge.
     */
    function rowMatchesNonTimeFilters(r) {
        if (!entryMatchesClassFilter(r)) return false;
        if (methodF && r.method !== methodF) return false;
        if (uriF) {
            const subj = MODE === 'server' ? (r.logger || '') : (r.uri || '');
            // uriF is always set by a facet/leaderboard click — exact match only.
            // A substring match would let e.g. /orders bleed into /orders_history.
            if (subj !== uriF) return false;
        }
        if (searchF) {
            const hay = MODE === 'server'
                ? ((r.message || '') + ' ' + (r.logger || '') + ' ' + (r.thread || '') + ' ' + (r.level || '') + ' ' + (r.requestId || '')).toLowerCase()
                : ((r.uri || '') + ' ' + (r.ip || '') + ' ' + (r.ua || '') + ' ' + (r.requestId || '') + ' ' + (r.user || '') + ' ' + (r[REQ_ATTR] || '')).toLowerCase();
            if (!hay.includes(searchF.toLowerCase())) return false;
        }
        return true;
    }

    function applyFilters() {
        DRAW = ALL.filter(r => {
            if (!rowMatchesNonTimeFilters(r)) return false;
            if (dtFrom != null && r._ms < dtFrom) return false;
            if (dtTo != null && r._ms > dtTo) return false;
            if (bucketSelF && (r._ms < bucketSelF[0] || r._ms > bucketSelF[1])) return false;
            return true;
        });
        doSort();
        page = 0;
        syncResetBtn();
        render();
    }

    function doSort() {
        DRAW.sort((a, b) => {
            let av = a[sortField], bv = b[sortField];
            if (sortField === 'timestamp') {
                av = a._ms;
                bv = b._ms;
            } else if (typeof av === 'string' || typeof bv === 'string') {
                // Coerce BOTH sides: nullable columns (e.g. user, which is absent on every
                // unauthenticated request) otherwise hit .toLowerCase() on null. Missing values
                // normalize to '' so they group together at one end of the sort.
                av = (av ?? '').toString().toLowerCase();
                bv = (bv ?? '').toString().toLowerCase();
            }
            if (av < bv) return sortDir === 'asc' ? -1 : 1;
            if (av > bv) return sortDir === 'asc' ? 1 : -1;
            // Stable tiebreaker: _seq is a global counter stamped at normalize() time.
            // Live-tail freshRows get higher _seq than old rows, so in descending sort
            // (newest first) they correctly appear before old rows within the same ms.
            return sortDir === 'asc' ? a._seq - b._seq : b._seq - a._seq;
        });
    }

