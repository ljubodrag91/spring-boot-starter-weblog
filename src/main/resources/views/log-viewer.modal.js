    // ─────────────────────────────────────────────────────────
    //  MODAL
    // ─────────────────────────────────────────────────────────
    let modalCurrent = null;
    let modalRoot = null;
    let modalDrawIdx = -1;

    // ── Stack-trace rendering + on-demand lookup by requestId ─────────────────
    function stackSectionHtml(title, trace, sub) {
        const firstLine = sub != null ? sub : (String(trace).split(/\r?\n/)[0] || '');
        return `
        <div class="stack-section">
          <div class="stack-title">${h(title)}</div>
          <div class="stack-sub">${h(firstLine)}</div>
          <pre class="stack-body">${h(trace)}</pre>
          <div class="stack-actions">
            <button class="copy-stack" data-copy-stack>⎎ Copy stack</button>
          </div>
        </div>`;
    }

    function wireCopyStack(container, trace) {
        const btn = container.querySelector('[data-copy-stack]');
        if (!btn) return;
        btn.onclick = () => {
            navigator.clipboard.writeText(String(trace)).then(() => {
                btn.textContent = '✓ Copied';
                setTimeout(() => btn.textContent = '⎎ Copy stack', 1500);
            });
        };
    }

    // For a 5xx access-log entry, offer to pull the stack trace logged for the SAME
    // request id from error.log and render it inline in the same modal.
    function renderTraceLookup(el, requestId, dateStr) {
        el.innerHTML = `
        <div class="stack-section">
          <div class="stack-title">Server error (5xx).</div>
          <div class="stack-sub">A stack trace may have been logged for request ${h(String(requestId).slice(0, 8))}…</div>
          <div class="stack-actions">
            <button class="copy-stack" id="loadTraceBtn">⎈ View stack trace</button>
          </div>
        </div>`;
        $('loadTraceBtn').onclick = () => {
            const btn = $('loadTraceBtn');
            btn.disabled = true;
            btn.textContent = 'Loading…';
            const traceUrl = `${CTX}/admin/logs/trace?requestId=${encodeURIComponent(requestId)}`
                + (dateStr ? `&date=${encodeURIComponent(dateStr)}` : '');
            fetch(traceUrl, {credentials: 'same-origin'})
                .then(res => { if (!res.ok) throw new Error('HTTP ' + res.status); return res.json(); })
                .then(list => {
                    const traces = (list || []).filter(e => e && e.throwable && String(e.throwable).trim());
                    if (traces.length === 0) {
                        el.innerHTML = `
        <div class="stack-section">
          <div class="stack-title">No stack trace found.</div>
          <div class="stack-sub">Nothing in error.log matches request ${h(requestId)}. It may have rotated to .gz, or the error was handled before it was logged.</div>
        </div>`;
                        return;
                    }
                    const combined = traces.map(e => {
                        const head = [e.timestamp, e.level, e.logger].filter(Boolean).join('  ');
                        return (head ? head + '\n' : '') + String(e.throwable);
                    }).join('\n\n──────────\n\n');
                    el.innerHTML = stackSectionHtml('Stack trace.', combined,
                        `from error.log · same request id ${requestId}`);
                    wireCopyStack(el, combined);
                })
                .catch(err => {
                    el.innerHTML = `
        <div class="stack-section">
          <div class="stack-title">Lookup failed.</div>
          <div class="stack-sub">Could not load the stack trace (${h(String(err.message || err))}).</div>
        </div>`;
                });
        };
    }

    function openModal(entry, isNav) {
        const r = entry;
        if (!r || typeof r !== 'object') return;
        if (!isNav) modalRoot = r;
        modalCurrent = r;
        modalDrawIdx = DRAW.indexOf(r);
        $('modalPrev').disabled = modalDrawIdx <= 0;
        $('modalNext').disabled = modalDrawIdx < 0 || modalDrawIdx >= DRAW.length - 1;
        $('modalCounter').textContent = modalDrawIdx >= 0
            ? `${fmt.format(modalDrawIdx + 1)} / ${fmt.format(DRAW.length)}`
            : `— / ${fmt.format(DRAW.length)}`;
        const isServer = (r.level != null);

        if (isServer) {
            const lv = String(r.level || '').toUpperCase();
            const lvc = lv.toLowerCase();
            $('modalEbrow').textContent = `Log entry · ${r.timestamp}`;
            $('modalTitle').innerHTML = `
        <span class="level-chip lv-${lvc}" style="font-size:13px;padding:3px 10px">${h(lv)}</span>
        <span class="path" style="font-size:18px">${h(r.logger || '')}</span>`;
            {
                const tiles = [
                    ['When', h(r.timestamp || '—')],
                    r.thread ? ['Thread', h(r.thread)] : null,
                    r.message ? ['Message', `<span style="font-size:11px;color:var(--dim);font-weight:400">${h(String(r.message).slice(0, 100))}</span>`] : null,
                ].filter(Boolean);
                $('modalMeta').innerHTML = tiles.map(([lbl, val]) =>
                    `<div class="modal-meta-tile"><span class="modal-meta-lbl">${lbl}</span><span class="modal-meta-val">${val}</span></div>`
                ).join('');
            }
            $('modalFields').innerHTML = [
                ['Level', `<span class="level-chip lv-${lvc}">${h(lv)}</span>`, '', 'Log severity for this line'],
                ['Timestamp', `<span style="color:var(--ink)">${h(r.timestamp || '')}</span>`, '', 'When this line was logged'],
                ['Logger', `<span class="long">${h(r.logger || '')}</span>`, 'long', 'Logger name that emitted this line'],
                ['Thread', h(r.thread || ''), '', 'JVM thread that emitted this line'],
                ['Request Id', `<span class="long">${h(r.requestId || '—')}</span>`, 'long', 'Correlation ID — copy it and search the Access tab to find the matching request (present only when the line was logged within a request; Tomcat-internal lines have none)'],
                ['Message', `<span class="long">${h(r.message || '')}</span>`, 'long', 'Full log message'],
            ].map(([k, v, cls, tip]) => `<span class="k" title="${h(tip || '')}">${h(k)}</span><span class="v${cls ? ' ' + cls : ''}">${v}</span>`).join('');

        } else {
            $('modalEbrow').textContent = `Request · ${r.timestamp}`;
            $('modalTitle').innerHTML = `
        <span class="badge ${statusClass(r)}">${statusDisplay(r)}</span>
        <span class="verb ${methodClass(r.method)}">${h(r.method)}</span>
        <span class="path">${h(r.uri)}</span>`;
            {
                const tiles = [
                    ['When', h(r.timestamp || '—')],
                    ['Duration', `${fmt.format(r.durationMs)}<span class="ma">ms</span>`],
                    r.ip ? ['IP', h(r.ip)] : null,
                    r.responseBytes && r.responseBytes !== '-' && r.responseBytes !== '0' ? ['Sent', fmtBytes(r.responseBytes)] : null,
                    r.protocol ? ['Protocol', h(r.protocol)] : null,
                ].filter(Boolean);
                $('modalMeta').innerHTML = tiles.map(([lbl, val]) =>
                    `<div class="modal-meta-tile"><span class="modal-meta-lbl">${lbl}</span><span class="modal-meta-val">${val}</span></div>`
                ).join('');
            }
            {
                const F = [];
                let curSec = 'sec-facts';
                const row = (k, v, tip, cls) => F.push(['r', k, v, cls || '', tip || '', curSec]);
                const sec = (label, cls) => { curSec = cls || ''; F.push(['s', label, cls || '']); };

                // ── Core / Facts ────────────────────────────────────
                row('Method', `<span class="${methodClass(r.method)}">${h(r.method)}</span>`, 'HTTP method used for this request');
                row('URI', `<span class="long">${h(r.uri)}</span>`, 'Request path, with the context path stripped', 'long');
                row('Status', `<span class="${statusClass(r)}" style="font-weight:700;font-size:14px">${statusDisplay(r)}</span>`, 'HTTP response status code');
                row('Duration', `${fmt.format(r.durationMs)}<span class="fv-accent">ms</span>`, 'Total time from request received to response fully sent');
                if (r.ttfbMs != null) row('First byte', `${fmt.format(r.ttfbMs)}<span class="fv-accent">ms</span>`, 'Time to first byte — how long until the response started sending');
                row('Timestamp', h(r.timestamp), 'When this request completed — Tomcat logs the end time by default, not the start time');
                row('Protocol', h(r.protocol), 'HTTP protocol version');

                // ── Network ───────────────────────────────────────
                sec('Network', 'sec-network');
                row('IP', h(r.ip), 'Resolved client IP — prefers a validated proxy header over the raw connection address, if configured');
                if (r.xForwardedFor) row('X-Forwarded-For', `<span class="long">${h(r.xForwardedFor)}</span>`, 'Client IP reported by a proxy — not validated unless this connection is explicitly trusted', 'long');
                if (r.xRealIp) row('X-Real-IP', h(r.xRealIp), 'Alternate single-IP proxy header, used as a fallback');
                if (r.remoteIp && r.remoteIp !== r.ip) row('Remote IP', h(r.remoteIp), 'Raw direct connection address, before any proxy header is applied');
                if (r.vhost) row('Virtual host', h(r.vhost), 'Server host:port that received the connection');
                if (r.thread) row('Thread', h(r.thread), 'Server worker thread that handled this request');
                if (r.connFate) {
                    const fl = {'+': 'Keep-alive', '-': 'Close', 'X': 'Aborted'}[r.connFate] || r.connFate;
                    row('Conn. state', fl, 'Whether the connection was kept alive, closed, or aborted after this response');
                }

                // ── Request ───────────────────────────────────────
                sec('Request', 'sec-request');
                if (r.host) row('Host', h(r.host), 'Host header sent by the client');
                if (REQ_ATTR && r[REQ_ATTR]) row(REQ_ATTR_LABEL, `<span class="long">${h(r[REQ_ATTR])}</span>`, `Value of the configured "${REQ_ATTR}" request attribute (log-viewer.request-attribute). Logged verbatim — see the deployment security notes`, 'long');
                if (r.reqContentType) row('Content-Type', `<span class="long">${h(r.reqContentType)}</span>`, 'MIME type of the request body, if any', 'long');
                if (r.reqContentLength) row('Size', fmtBytes(r.reqContentLength), 'Declared size of the request body');
                if (r.accept) row('Accept', `<span class="long">${h(r.accept)}</span>`, 'MIME types the client says it can accept in the response', 'long');
                if (r.acceptEncoding) row('Accept-Encoding', h(r.acceptEncoding), 'Compression formats the client accepts, e.g. gzip');
                if (r.acceptLanguage) row('Accept-Language', h(r.acceptLanguage), "Client's preferred response language(s)");
                if (r.connection) row('Connection', h(r.connection), 'Connection header sent by the client, e.g. keep-alive');
                if (r.reqCacheControl) row('Cache-Control', h(r.reqCacheControl), 'Caching directives sent by the client');
                row('X-Request-Id', `<span class="long">${h(r.requestId || '—')}</span>`, 'Correlation ID — the same value appears in the app log and every other log file for this request', 'long');
                if (r.user) row('Authenticated user', `<span class="long">${h(r.user)}</span>`, 'Principal Spring Security had authenticated when this request completed', 'long');
                if (r.auth) {
                    const authCls = /expired/i.test(r.auth) ? ' s4' : '';
                    row('Authorization', `<span class="long${authCls}"${authCls ? ' style="font-weight:600"' : ''}>${h(r.auth)}</span>`,
                        'Safe summary of the Authorization header — scheme, and for a JWT its sub/type/expiry. The raw credential is never logged', 'long');
                } else if (r.status === 401 || r.status === 403) {
                    row('Authorization', `<span class="s4" style="font-weight:600">(no Authorization header)</span>`,
                        'This request was rejected and carried no Authorization header at all');
                }
                row('Referer', h(r.referer || '—'), 'Page or client that reported sending this request');
                row('User-Agent', `<span class="long">${h(r.ua || '—')}</span>`, 'Client software that made this request', 'long');

                // ── Response ──────────────────────────────────────
                sec('Response', 'sec-response');
                if (r.deny) row('Denied because', `<span class="s4" style="font-weight:700">${h(r.deny)}</span>`,
                    'Short reason the application rejected this request, set by the app via AuthInfoFilter.DENY_REQ_ATTR');
                row('Bytes sent', fmtBytes(r.responseBytes), 'Size of the response body actually sent');
                if (r.respContentType) row('Content-Type', `<span class="long">${h(r.respContentType)}</span>`, 'MIME type of the response body', 'long');
                if (r.respContentLength) row('Content-Length', fmtBytes(r.respContentLength), 'Declared response body size, if set');
                if (r.respEncoding) row('Encoding', h(r.respEncoding), 'Compression applied to the response body');
                if (r.respCacheControl) row('Cache-Control', `<span class="long">${h(r.respCacheControl)}</span>`, 'Caching directives sent back to the client', 'long');
                if (r.rateLimitLimit) row('X-RateLimit-Limit', h(r.rateLimitLimit), "Maximum calls allowed for this caller's quota window");
                if (r.rateLimitRemaining) row('X-RateLimit-Remaining', h(r.rateLimitRemaining), "Calls left in this caller's quota window after this request");

                $('modalFields').innerHTML = F.map(f =>
                    f[0] === 's'
                        ? `<span class="fg-section${f[2] ? ' ' + f[2] : ''}">${h(f[1])}</span>`
                        : `<span class="k${f[5] ? ' ' + f[5] : ''}" title="${h(f[4] || '')}">${h(f[1])}</span><span class="v${f[3] ? ' ' + f[3] : ''}">${f[2]}</span>`
                ).join('');
            }

        }

        // ── AROUND THIS (unified across all log types) ───────────
        {
            const allSorted = getAllSortedByTime();   // cached; O(1) on second+ modal open
            const centerIdx = allSorted.indexOf(modalRoot);
            const sliceStart = Math.max(0, centerIdx - 10);
            const sliceEnd = Math.min(allSorted.length, centerIdx + 11);
            const related = allSorted.slice(sliceStart, sliceEnd).reverse();
            const before = centerIdx - sliceStart;
            const after = sliceEnd - centerIdx - 1;
            $('relatedSub').textContent = `${after} after · ${before} before`;
            $('relatedList').innerHTML = related.length === 0
                ? '<div style="color:var(--faint);font-size:12px">(none)</div>'
                : `<div class="related-timeline">${related.map(x => {
                    const isTarget = x === r;
                    const isRoot = x === modalRoot && modalRoot !== r;
                    const allIdx = ALL.indexOf(x);
                    const entryCls = isTarget ? 'is-target' : (isRoot ? 'is-root' : '');
                    const itemCls = isTarget ? 'target' : (isRoot ? 'root-origin' : '');
                    const suffix = isTarget ? ' · viewing now' : (isRoot ? ' · opened from' : '');
                    if (isServer) {
                        const xlv = String(x.level || '').toUpperCase();
                        const xlvc = xlv.toLowerCase();
                        return `<div class="related-entry ${entryCls}">
                <div class="related-item ${itemCls}" data-all-idx="${allIdx}">
                  <div class="ri-row1">
                    <span class="ri-uri">${h(String(x.logger || '').replace(/^.*\.([^.]+)$/, '$1'))} <span style="color:var(--dim)">${h(String(x.message || '').slice(0, 100))}</span></span>
                    <span class="level-chip lv-${xlvc}" style="font-size:9.5px;padding:1px 5px">${h(xlv)}</span>
                  </div>
                  <div class="ri-row2">${timeHtml(x.timestamp)}${suffix}</div>
                </div>
              </div>`;
                    } else {
                        return `<div class="related-entry ${entryCls}">
                <div class="related-item ${itemCls}" data-all-idx="${allIdx}">
                  <div class="ri-row1">
                    <span class="ri-uri"><span class="${methodClass(x.method)}">${h(x.method)}</span> ${h(x.uri)}</span>
                    <span class="${statusClass(x)}" style="font-weight:600">${statusDisplay(x)}</span>
                  </div>
                  <div class="ri-row2">${timeHtml(x.timestamp)} · ${fmt.format(x.durationMs)}ms${suffix}</div>
                </div>
              </div>`;
                    }
                }).join('')}</div>`;
        }

        const stackEl = $('modalStack');
        const trace = r.throwable;
        if (trace && trace !== '-' && String(trace).trim()) {
            // Server/error-log entry that already carries its own stack trace.
            stackEl.innerHTML = stackSectionHtml('Stack trace.', trace);
            wireCopyStack(stackEl, trace);
        } else if (!isServer && Number(r.status) >= 500 && r.requestId && r.requestId !== '-') {
            // Access-log 5xx: no trace on this entry, but one may exist in error.log
            // under the same request id — offer an on-demand lookup, scoped to this row's
            // date so the server can find it even in a compressed archive.
            renderTraceLookup(stackEl, r.requestId, msToDate(r._ms));
        } else {
            stackEl.innerHTML = '';
        }

        $('modal').hidden = false;
        document.body.style.overflow = 'hidden';
    }

    function closeModal() {
        $('modal').hidden = true;
        modalCurrent = null;
        modalRoot = null;
        modalDrawIdx = -1;
        document.body.style.overflow = '';
        $('modalMeta').innerHTML = '';
    }

    // ─────────────────────────────────────────────────────────
    //  EVENTS
    // ─────────────────────────────────────────────────────────
    function activateTab(t) {
        currentTab = t;
        statusF = '';
        methodF = '';
        uriF = '';
        bucketSelF = null;
        searchF = getDefaultSearch();
        $('searchInput').value = searchF;
        renderTabs();
        updateEyebrow();
        applyLayout();
        // In history mode, reload the current date window for the newly selected type
        // (restarting from its newest page) instead of dropping back to the live tail.
        if (rangeMode) { pageOldest = pageNewest = null; fetchPage('initial'); }
        else fetchData();
        pollInflight();   // immediately show the strip on 'access', hide it elsewhere
    }

    $('mhTabs').addEventListener('click', (e) => {
        const btn = e.target.closest('button[data-type]');
        if (!btn) return;
        activateTab(btn.dataset.type);
        saveLayout({type: currentTab});
    });

    $('linesEl').value = String(INIT_LINES);
    // Guard: a stale INIT_LINES (e.g. a bookmarked 50000, an option since removed) leaves the
    // select with no matching option and value===''. Fall back to the default so requests still
    // carry a valid line count.
    if (!$('linesEl').value) $('linesEl').value = '5000';
    $('linesEl').onchange = () => rangeMode ? fetchPage('initial') : fetchData();
    $('refreshBtn').onclick = () => rangeMode ? fetchPage('initial') : fetchData();
    $('retryBtn').onclick = () => rangeMode ? fetchPage('initial') : fetchData();

    // ── History controls ─────────────────────────────────────────────────────
    $('histBtn').onclick   = () => rangeMode ? exitHistory() : enterHistory();
    $('histExit').onclick  = () => exitHistory();
    $('olderBtn').onclick  = () => fetchPage('older');
    $('newerBtn').onclick  = () => fetchPage('newer');
    // Changing the window restarts paging from its newest page.
    $('histFrom').onchange = () => { pageOldest = pageNewest = null; fetchPage('initial'); };
    $('histTo').onchange   = () => { pageOldest = pageNewest = null; fetchPage('initial'); };
    $('dismissBtn').onclick = () => {
        $('errBanner').hidden = true;
    };

    // ── Live tail ────────────────────────────────────────────────────────────
    function updateLiveLabel() {
        $('liveLabel').textContent = `Live · ${liveCountdown}s`;
    }

    function startLive() {
        live = true;
        $('liveBtn').classList.replace('live-off', 'live-on');
        liveCountdown = 10;
        updateLiveLabel();
        liveTickTimer = setInterval(() => {
            liveCountdown--;
            updateLiveLabel();
            if (liveCountdown <= 0) {
                liveCountdown = 10;
                fetchLive();
            }
        }, 1000);
    }

    function stopLive() {
        live = false;
        clearInterval(liveTickTimer);
        liveTickTimer = null;
        $('liveBtn').classList.replace('live-on', 'live-off');
        $('liveLabel').textContent = 'Live tail';
    }

    $('liveBtn').onclick = () => {
        if (rangeMode) exitHistory(); // live tail and history are mutually exclusive
        live ? stopLive() : startLive();
    };

    // date range
    $('dtFrom').onchange = () => {
        dtFrom = inputToMs($('dtFrom').value);
        bucketSelF = null;
        setPreset(null);
        applyFilters();
    };
    $('dtTo').onchange = () => {
        dtTo = inputToMs($('dtTo').value);
        bucketSelF = null;
        setPreset(null);
        applyFilters();
    };

    function setPreset(p) {
        $('presetSeg').querySelectorAll('button').forEach(b => b.classList.toggle('active', b.dataset.preset === p));
    }

    function applyPreset(p, silent) {
        setPreset(p);
        bucketSelF = null;
        if (p === 'all' || !p) {
            if (ALL.length) {
                dtFrom = ALL.reduce((m, r) => r._ms < m ? r._ms : m, Infinity);
                dtTo   = ALL.reduce((m, r) => r._ms > m ? r._ms : m, -Infinity);
            } else {
                dtFrom = dtTo = null;
            }
        } else {
            // Timed presets are anchored to wall-clock now, not the newest loaded row, so
            // "15m" means the last 15 real minutes (matching fetchLive's live-tail window).
            // If there has been no traffic in the window the table is empty — use the "all"
            // preset to span the full loaded range regardless of when it occurred.
            const now = Date.now();
            const ms = {'15m': 15 * 60e3, '1h': 3600e3, '6h': 6 * 3600e3, '24h': 24 * 3600e3}[p];
            if (!ms) return;
            dtFrom = now - ms;
            dtTo = now;
        }
        $('dtFrom').value = msToInput(dtFrom);
        $('dtTo').value = msToInput(dtTo);
        if (!silent) applyFilters();
    }

    $('presetSeg').onclick = (e) => {
        const btn = e.target.closest('button[data-preset]');
        if (!btn) return;
        applyPreset(btn.dataset.preset);
    };

    let searchDebounce;
    $('searchInput').oninput = () => {
        clearTimeout(searchDebounce);
        searchDebounce = setTimeout(() => {
            searchF = $('searchInput').value;
            applyFilters();
        }, 200);
    };

    function resetFilters() {
        statusF = '';
        methodF = '';
        uriF = '';
        bucketSelF = null;
        searchF = getDefaultSearch();
        $('searchInput').value = searchF;
        applyFilters();
    }

    $('clearBtn').onclick = resetFilters;

    document.querySelectorAll('.facets').forEach(el => {
        el.addEventListener('click', (e) => {
            const clr = e.target.closest('.facet-clear');
            if (clr) {
                const k = clr.dataset.clear;
                if (k === 'status') statusF = '';
                if (k === 'method') methodF = '';
                if (k === 'uri') uriF = '';
                if (k === 'ip') {
                    searchF = '';
                    $('searchInput').value = '';
                }
                applyFilters();
                return;
            }
            const item = e.target.closest('.facet-item');
            if (!item) return;
            const f = item.dataset.facet, v = item.dataset.value;
            if (f === 'status') statusF = statusF === v ? '' : v;
            if (f === 'method') methodF = methodF === v ? '' : v;
            if (f === 'uri') uriF = uriF === v ? '' : v;
            if (f === 'ip') {
                searchF = searchF === v ? '' : v;
                $('searchInput').value = searchF;
            }
            applyFilters();
        });
    });

    $('resetFiltersBtn').onclick = resetFilters;

    function syncResetBtn() {
        const def = getDefaultSearch();
        const dirty = statusF || methodF || uriF || bucketSelF || searchF !== def;
        $('resetFiltersBtn').style.display = dirty ? '' : 'none';
    }

    $('psEl').onchange = () => {
        pageSize = +$('psEl').value;
        page = 0;
        render();
    };
    $('pgPrev').onclick = () => {
        if (page > 0) {
            page--;
            render();
        }
    };
    $('pgNext').onclick = () => {
        page++;
        render();
    };

    document.querySelector('.rows-wrap').addEventListener('click', (e) => {
        const el = e.target.closest('.col-head .sortable');
        if (!el) return;
        const f = el.dataset.sort;
        if (sortField === f) sortDir = sortDir === 'asc' ? 'desc' : 'asc';
        else {
            sortField = f;
            sortDir = (f === 'durationMs' || f === 'timestamp') ? 'desc' : 'asc';
        }
        doSort();
        render();
    });

    $('rows').addEventListener('click', (e) => {
        const row = e.target.closest('.row');
        if (!row) return;
        openModal(DRAW[+row.dataset.idx]);
    });

    $('relatedList').addEventListener('click', (e) => {
        const item = e.target.closest('.related-item');
        if (!item) return;
        const entry = ALL[+item.dataset.allIdx];
        if (entry) openModal(entry, true);
    });

    $('modalClose').onclick = closeModal;
    $('modal').addEventListener('click', (e) => {
        if (e.target === $('modal')) closeModal();
    });
    $('modalCopy').onclick = () => {
        if (!modalCurrent) return;
        const clean = {...modalCurrent};
        delete clean._ms;
        delete clean._seq;
        navigator.clipboard.writeText(JSON.stringify(clean, null, 2)).then(() => {
            $('modalCopy').textContent = '✓ Copied';
            setTimeout(() => {
                $('modalCopy').textContent = '⎘ Copy JSON';
            }, 1500);
        });
    };
    $('modalPrev').onclick = () => {
        const idx = DRAW.indexOf(modalCurrent);
        if (idx > 0) openModal(DRAW[idx - 1], true);
    };
    $('modalNext').onclick = () => {
        const idx = DRAW.indexOf(modalCurrent);
        if (idx >= 0 && idx < DRAW.length - 1) openModal(DRAW[idx + 1], true);
    };
    document.addEventListener('keydown', (e) => {
        if ($('modal').hidden) return;
        if (e.key === 'Escape') {
            closeModal();
            return;
        }
        const idx = (e.key === 'ArrowLeft' || e.key === 'ArrowRight') ? DRAW.indexOf(modalCurrent) : -1;
        if (e.key === 'ArrowLeft' && idx > 0) openModal(DRAW[idx - 1], true);
        if (e.key === 'ArrowRight' && idx >= 0 && idx < DRAW.length - 1) openModal(DRAW[idx + 1], true);
    });

    $('leaderboardList').addEventListener('click', (e) => {
        const item = e.target.closest('.lb-item');
        if (!item) return;
        const uri = item.dataset.uri;
        uriF = (uriF === uri) ? '' : uri;
        applyFilters();
        document.querySelectorAll('#leaderboardList .lb-item').forEach(el => {
            el.classList.toggle('active', el.dataset.uri === uriF);
        });
    });

