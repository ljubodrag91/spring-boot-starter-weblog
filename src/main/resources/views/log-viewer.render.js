    // ─────────────────────────────────────────────────────────
    //  RENDER
    // ─────────────────────────────────────────────────────────
    function render() {
        renderStats();
        renderHistogram();
        renderFacets();
        renderTable();
        renderLeaderboard();
    }

    function renderStats() {
        const total = DRAW.length;
        const cnt = {ok: 0, redir: 0, warn: 0, err: 0};
        let durSum = 0;
        const durArr = [];
        for (const r of DRAW) {
            cnt[entryClass(r)]++;
            if (typeof r.durationMs === 'number') {
                durSum += r.durationMs;
                durArr.push(r.durationMs);
            }
        }
        const isServer = MODE === 'server';
        const errPctBase = isServer ? cnt.err : (cnt.warn + cnt.err);
        const p95 = percentile(durArr, 0.95);
        const avg = durArr.length ? Math.round(durSum / durArr.length) : 0;

        $('statTotal').textContent = fmt.format(total);
        $('totalLabel').textContent = isServer ? 'Lines' : 'Requests';
        if (isServer) {
            $('errLabel').textContent = 'ERRORs';
            $('statErr').textContent = fmt.format(cnt.err);
            $('statErrPct').textContent = cnt.warn ? `+${fmt.format(cnt.warn)} WARN` : 'in range';
            $('p95Label').textContent = 'INFO';
            $('statP95').textContent = fmt.format(cnt.ok);
            $('statAvg').textContent = `${fmt.format(cnt.redir)} DEBUG`;
        } else {
            $('errLabel').textContent = 'Errors';
            $('statErr').textContent = fmt.format(errPctBase);
            $('statErrPct').textContent = total ? `${(errPctBase / total * 100).toFixed(1)}% of range` : '—';
            $('p95Label').textContent = 'p95';
            $('statP95').textContent = `${p95}ms`;
            $('statAvg').textContent = `avg ${avg}ms`;
        }
        $('statErrPct').className = `stat-hint ${errPctBase > 0 && !isServer ? 'bad' : ''}`;
        $('statLive').textContent = live ? '● tail' : '—';
        $('statLines').textContent = `${fmt.format(ALL.length)} loaded`;
        $('ctrlInfo').innerHTML = `showing <b>${fmt.format(total)}</b> of <b>${fmt.format(ALL.length)}</b>`;
        $('statRange').textContent = total ? 'in range' : 'no data';
        $('lastSync').textContent = lastSyncAt ? `last sync ${lastSyncAt.toLocaleTimeString()}` : 'never synced';
        updateEyebrow();
        const lg = legendConfig();
        document.querySelectorAll('[data-leg]').forEach(el => {
            el.style.background = lg[+el.dataset.leg].color;
        });
        document.querySelectorAll('[data-leg-lbl]').forEach(el => {
            el.textContent = lg[+el.dataset.legLbl].label;
        });
        $('lg2').textContent = fmt.format(cnt.ok);
        $('lg3').textContent = fmt.format(cnt.redir);
        $('lg4').textContent = fmt.format(cnt.warn);
        $('lg5').textContent = fmt.format(cnt.err);
    }

    function renderHistogram() {
        const wrap = $('histo');
        const axis = $('histoAxis');
        wrap.innerHTML = '';
        axis.innerHTML = '';

        if (!DRAW.length && !ALL.length) {
            wrap.innerHTML = '<div class="histo-empty">no data to chart</div>';
            return;
        }
        // Avoid Math.min/max(...largeArray) — spread pushes every element onto the call
        // stack and throws RangeError at large array sizes (~100k elements).
        const lo = dtFrom ?? ALL.reduce((m, r) => r._ms < m ? r._ms : m, Infinity);
        const hi = dtTo   ?? ALL.reduce((m, r) => r._ms > m ? r._ms : m, -Infinity);
        if (!isFinite(lo) || !isFinite(hi) || hi <= lo) {
            wrap.innerHTML = '<div class="histo-empty">single-point range</div>';
            return;
        }
        const BUCKETS = 60;
        const step = (hi - lo) / BUCKETS;
        const buckets = Array.from({length: BUCKETS}, () => ({ok: 0, redir: 0, warn: 0, err: 0}));
        for (const r of ALL) {
            if (r._ms < lo || r._ms > hi) continue;
            // Use shared non-time filter — histogram intentionally ignores
            // dtFrom/dtTo/bucketSelF (those are what the histogram controls).
            if (!rowMatchesNonTimeFilters(r)) continue;
            let idx = Math.floor((r._ms - lo) / step);
            if (idx >= BUCKETS) idx = BUCKETS - 1;
            buckets[idx][entryClass(r)]++;
        }
        const maxV = Math.max(1, ...buckets.map(b => b.ok + b.redir + b.warn + b.err));

        const yaxis = $('histoYaxis');
        if (yaxis) {
            yaxis.innerHTML = [1, 0.75, 0.5, 0.25, 0].map(p =>
                `<span>${fmt.format(Math.round(maxV * p))}</span>`
            ).join('');
        }

        [0, 0.25, 0.5, 0.75].forEach(p => {
            const g = document.createElement('div');
            g.className = 'grid';
            g.style.top = `${(1 - p) * 100}%`;
            wrap.appendChild(g);
        });

        buckets.forEach((b, i) => {
            const total = b.ok + b.redir + b.warn + b.err;
            const bStart = lo + i * step, bEnd = lo + (i + 1) * step;
            const bar = document.createElement('div');
            bar.className = 'histo-bar';
            bar.dataset.bucket = i;
            bar.dataset.start = bStart;
            bar.dataset.end = bEnd;
            if (bucketSelF) {
                bar.classList.add(bStart >= bucketSelF[0] && bEnd <= bucketSelF[1] ? 'in-range' : 'out-range');
            }
            bar.style.height = `${(total / maxV) * 100}%`;
            bar.title = `${new Date(bStart).toLocaleTimeString()} – ${new Date(bEnd).toLocaleTimeString()}\n${total} ${MODE === 'server' ? 'lines' : 'requests'} · ${b.err} errors`;
            if (total > 0) {
                if (b.ok) {
                    const e = document.createElement('div');
                    e.className = 'seg-ok';
                    e.style.height = `${b.ok / total * 100}%`;
                    bar.appendChild(e);
                }
                if (b.redir) {
                    const e = document.createElement('div');
                    e.className = 'seg-redir';
                    e.style.height = `${b.redir / total * 100}%`;
                    bar.appendChild(e);
                }
                if (b.warn) {
                    const e = document.createElement('div');
                    e.className = 'seg-warn';
                    e.style.height = `${b.warn / total * 100}%`;
                    bar.appendChild(e);
                }
                if (b.err) {
                    const e = document.createElement('div');
                    e.className = 'seg-err';
                    e.style.height = `${b.err / total * 100}%`;
                    bar.appendChild(e);
                }
            }
            wrap.appendChild(bar);
        });

        const MON_ABBR = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];
        const fmtTime = (ms) => {
            const d = new Date(ms);
            const sameDay = new Date(lo).toDateString() === new Date(hi).toDateString();
            return sameDay
                ? d.toLocaleTimeString([], {hour: '2-digit', minute: '2-digit'})
                : `${d.getDate()}-${MON_ABBR[d.getMonth()]} ${d.toLocaleTimeString([], {
                    hour: '2-digit',
                    minute: '2-digit'
                })}`;
        };
        for (let i = 0; i < 5; i++) {
            const span = document.createElement('span');
            span.textContent = fmtTime(lo + ((hi - lo) * i / 4));
            axis.appendChild(span);
        }
    }

    // ─────────────────────────────────────────────────────────
    //  HISTOGRAM DRAG-TO-SELECT
    // ─────────────────────────────────────────────────────────
    (function bindHistogramDrag() {
        const canvas = $('histo');
        let dragStartIdx = null;
        let dragMoved = false;

        function highlightRange(a, b) {
            const lo = Math.min(a, b), hi = Math.max(a, b);
            canvas.querySelectorAll('.histo-bar').forEach(el => {
                const i = +el.dataset.bucket;
                el.classList.toggle('drag-sel', i >= lo && i <= hi);
                el.classList.toggle('drag-dim', i < lo || i > hi);
            });
        }

        function clearHighlight() {
            canvas.querySelectorAll('.histo-bar').forEach(el => el.classList.remove('drag-sel', 'drag-dim'));
        }

        canvas.addEventListener('mousedown', (e) => {
            const bar = e.target.closest('.histo-bar');
            if (!bar) return;
            dragStartIdx = +bar.dataset.bucket;
            dragMoved = false;
            highlightRange(dragStartIdx, dragStartIdx);
            e.preventDefault();
        });
        canvas.addEventListener('mousemove', (e) => {
            if (dragStartIdx == null) return;
            const bar = e.target.closest('.histo-bar');
            if (!bar) return;
            const cur = +bar.dataset.bucket;
            if (cur !== dragStartIdx) dragMoved = true;
            highlightRange(dragStartIdx, cur);
        });
        window.addEventListener('mouseup', (e) => {
            if (dragStartIdx == null) return;
            const startIdx = dragStartIdx;
            dragStartIdx = null;
            clearHighlight();
            const bar = e.target.closest && e.target.closest('.histo-bar');
            const endIdx = bar ? +bar.dataset.bucket : startIdx;
            const lo = Math.min(startIdx, endIdx), hi = Math.max(startIdx, endIdx);
            if (lo === hi && !dragMoved) {
                const only = canvas.querySelector(`[data-bucket="${lo}"]`);
                if (!only) return;
                const bStart = +only.dataset.start, bEnd = +only.dataset.end;
                bucketSelF = (bucketSelF && bucketSelF[0] === bStart && bucketSelF[1] === bEnd) ? null : [bStart, bEnd];
            } else {
                const first = canvas.querySelector(`[data-bucket="${lo}"]`);
                const last = canvas.querySelector(`[data-bucket="${hi}"]`);
                if (!first || !last) return;
                bucketSelF = [+first.dataset.start, +last.dataset.end];
            }
            applyFilters();
        });
        document.addEventListener('mouseleave', () => {
            if (dragStartIdx != null) {
                dragStartIdx = null;
                clearHighlight();
            }
        });
    })();

    /**
     * Returns ALL entries that pass every active filter EXCEPT the named dimension.
     * Used for facet counts: clicking "4xx" still shows correct counts for all
     * other status codes, and all counts respect the current search/URI/bucket filters.
     *
     * @param {string} exceptDim  'status' | 'method' | 'uri' | null
     */
    function facetBase(exceptDim) {
        const searchLow = searchF.toLowerCase();
        return ALL.filter(r => {
            if (dtFrom    != null && r._ms < dtFrom)   return false;
            if (dtTo      != null && r._ms > dtTo)     return false;
            if (bucketSelF && (r._ms < bucketSelF[0] || r._ms > bucketSelF[1])) return false;
            // searchF applies to all dimensions (no excepted case for free text).
            if (searchLow) {
                const hay = MODE === 'server'
                    ? ((r.message||'')+' '+(r.logger||'')+' '+(r.thread||'')+' '+(r.level||'')+' '+(r.requestId||'')).toLowerCase()
                    : ((r.uri||'')+' '+(r.ip||'')+' '+(r.ua||'')+' '+(r.requestId||'')+' '+(r[REQ_ATTR]||'')).toLowerCase();
                if (!hay.includes(searchLow)) return false;
            }
            if (exceptDim !== 'status' && !entryMatchesClassFilter(r)) return false;
            if (exceptDim !== 'method' && methodF && r.method !== methodF) return false;
            if (exceptDim !== 'uri' && uriF) {
                const subj = MODE === 'server' ? (r.logger || '') : (r.uri || '');
                if (subj !== uriF) return false;   // exact match, same as rowMatchesNonTimeFilters
            }
            return true;
        });
    }

    function renderFacets() {
        if (MODE === 'server') renderFacetsServer();
        else renderFacetsRequest();
    }

    function renderFacetsRequest() {
        $('facetStatusTitle').textContent = 'Status';
        $('facetMethodGroup').style.display = '';
        $('facetUriTitle').textContent = 'Top URIs';
        $('facetIpTitle').textContent = 'Top IPs';

        // Each dimension counts entries passing all filters EXCEPT its own.
        // IP uses searchF (no dedicated filter var) so just use DRAW.
        const forStatus = facetBase('status');
        const forMethod = facetBase('method');
        const forUri    = facetBase('uri');

        const sCount = {'2': 0, '3': 0, '4': 0, '5': 0};
        // Aborted (nginx-style 499) requests are counted only under their own facet, never in the
        // numeric bucket of their real status — matching entryMatchesClassFilter's exclusivity.
        for (const r of forStatus) if (r.connFate !== 'X') sCount[statusBucket(r.status)] = (sCount[statusBucket(r.status)] || 0) + 1;
        const abortedCount = forStatus.reduce((n, r) => n + (r.connFate === 'X' ? 1 : 0), 0);

        const mCount = {};
        for (const r of forMethod) mCount[r.method] = (mCount[r.method] || 0) + 1;

        const uCount = {};
        for (const r of forUri) uCount[r.uri] = (uCount[r.uri] || 0) + 1;

        const ipCount = {};
        for (const r of DRAW) ipCount[r.ip] = (ipCount[r.ip] || 0) + 1;
        const statusLabels = [
            ['2', '2xx · Success', 's2', 'var(--ok)'],
            ['3', '3xx · Redirect', 's3', 'var(--blue)'],
            ['4', '4xx · Client', 's4', 'var(--amber)'],
            ['5', '5xx · Server', 's5', 'var(--signal)'],
        ];
        $('facetStatus').innerHTML = statusLabels.map(([v, label, cls, sw]) => `
      <div class="facet-item ${cls} ${statusF === v ? 'active' : ''}" data-facet="status" data-value="${v}">
        <span class="facet-left"><span class="facet-swatch" style="background:${sw}"></span><span class="facet-label">${label}</span></span>
        <span class="facet-count">${fmt.format(sCount[v] || 0)}</span>
      </div>`).join('') + `
      <div class="facet-item s4 ${statusF === 'aborted' ? 'active' : ''}" data-facet="status" data-value="aborted"
           title="Client closed the connection before the response finished — nginx-style 499. Matched on the abort marker; these are excluded from the numeric buckets (a 499 with a real status of 200 is not counted under 2xx).">
        <span class="facet-left"><span class="facet-swatch" style="background:var(--amber)"></span><span class="facet-label">499 · Aborted</span></span>
        <span class="facet-count">${fmt.format(abortedCount)}</span>
      </div>`;

        const methods = Object.entries(mCount).sort((a, b) => b[1] - a[1]).slice(0, 6);
        $('facetMethod').innerHTML = methods.map(([m, c]) => `
      <div class="facet-item m ${methodF === m ? 'active' : ''}" data-facet="method" data-value="${h(m)}">
        <span class="facet-left"><span class="facet-label ${methodClass(m)}">${h(m)}</span></span>
        <span class="facet-count">${fmt.format(c)}</span>
      </div>`).join('') || '<div style="color:var(--faint);font-size:11px">(none)</div>';

        const uris = Object.entries(uCount).sort((a, b) => b[1] - a[1]).slice(0, 8);
        $('facetUri').innerHTML = uris.map(([u, c]) => `
      <div class="facet-item ${uriF === u ? 'active' : ''}" data-facet="uri" data-value="${h(u)}" title="${h(u)}">
        <span class="facet-left"><span class="facet-label mono" style="font-size:11.5px">${h(u)}</span></span>
        <span class="facet-count">${fmt.format(c)}</span>
      </div>`).join('') || '<div style="color:var(--faint);font-size:11px">(none)</div>';

        const ips = Object.entries(ipCount).sort((a, b) => b[1] - a[1]).slice(0, 6);
        $('facetIp').innerHTML = ips.map(([ip, c]) => `
      <div class="facet-item ${searchF === ip ? 'active' : ''}" data-facet="ip" data-value="${h(ip)}" title="${h(ip)}">
        <span class="facet-left"><span class="facet-label mono" style="font-size:11.5px">${h(ip)}</span></span>
        <span class="facet-count">${fmt.format(c)}</span>
      </div>`).join('') || '<div style="color:var(--faint);font-size:11px">(none)</div>';
    }

    function renderFacetsServer() {
        $('facetStatusTitle').textContent = 'Level';
        $('facetMethodGroup').style.display = 'none';
        $('facetUriTitle').textContent = 'Top loggers';
        $('facetIpTitle').textContent = 'Top threads';

        // level uses statusF, logger uses uriF, thread uses searchF → use DRAW
        const forLevel  = facetBase('status');
        const forLogger = facetBase('uri');

        const lCount = {};
        for (const r of forLevel) {
            const lv = String(r.level || '').toUpperCase();
            if (lv) lCount[lv] = (lCount[lv] || 0) + 1;
        }
        const logCount = {};
        for (const r of forLogger) logCount[r.logger || '-'] = (logCount[r.logger || '-'] || 0) + 1;
        const thrCount = {};
        for (const r of DRAW)     thrCount[r.thread || '-'] = (thrCount[r.thread || '-'] || 0) + 1;
        const levelOrder = ['ERROR', 'WARN', 'INFO', 'DEBUG', 'TRACE', 'FATAL', 'SEVERE', 'WARNING', 'NOTICE'];
        const colorMap = {
            ERROR: 'var(--signal)', FATAL: 'var(--signal)', SEVERE: 'var(--signal)',
            WARN: 'var(--amber)', WARNING: 'var(--amber)',
            INFO: 'var(--ok)', NOTICE: 'var(--ok)',
            DEBUG: 'var(--blue)', TRACE: 'var(--blue)'
        };
        const cssClass = (lv) => lv === 'ERROR' || lv === 'FATAL' || lv === 'SEVERE' ? 's5'
            : lv === 'WARN' || lv === 'WARNING' ? 's4'
                : lv === 'INFO' || lv === 'NOTICE' ? 's2' : 's3';
        const seen = Object.keys(lCount);
        const ordered = levelOrder.filter(l => seen.includes(l)).concat(seen.filter(l => !levelOrder.includes(l)));
        $('facetStatus').innerHTML = ordered.map(lv => `
      <div class="facet-item ${cssClass(lv)} ${statusF === lv ? 'active' : ''}" data-facet="status" data-value="${h(lv)}">
        <span class="facet-left"><span class="facet-swatch" style="background:${colorMap[lv] || 'var(--dim)'}"></span><span class="facet-label">${h(lv)}</span></span>
        <span class="facet-count">${fmt.format(lCount[lv] || 0)}</span>
      </div>`).join('') || '<div style="color:var(--faint);font-size:11px">(none)</div>';

        const loggers = Object.entries(logCount).sort((a, b) => b[1] - a[1]).slice(0, 8);
        $('facetUri').innerHTML = loggers.map(([u, c]) => {
            const short = String(u).replace(/^.*\.([^.]+)$/, '$1');
            return `<div class="facet-item ${uriF === u ? 'active' : ''}" data-facet="uri" data-value="${h(u)}" title="${h(u)}">
        <span class="facet-left"><span class="facet-label mono" style="font-size:11px">${h(short)}</span></span>
        <span class="facet-count">${fmt.format(c)}</span>
      </div>`;
        }).join('') || '<div style="color:var(--faint);font-size:11px">(none)</div>';

        const threads = Object.entries(thrCount).sort((a, b) => b[1] - a[1]).slice(0, 8);
        $('facetIp').innerHTML = threads.map(([t, c]) => `
      <div class="facet-item ${searchF === t ? 'active' : ''}" data-facet="ip" data-value="${h(t)}" title="${h(t)}">
        <span class="facet-left"><span class="facet-label mono" style="font-size:11px">${h(t)}</span></span>
        <span class="facet-count">${fmt.format(c)}</span>
      </div>`).join('') || '<div style="color:var(--faint);font-size:11px">(none)</div>';
    }

    function renderTable() {
        const total = DRAW.length;
        const tp = Math.max(1, Math.ceil(total / pageSize));
        if (page >= tp) page = tp - 1;
        const start = page * pageSize;
        const end = Math.min(start + pageSize, total);
        PAGE_ROWS = DRAW.slice(start, end);

        $('tableSubText').textContent = `${fmt.format(total)} ${total === 1 ? 'entry' : 'entries'}`;
        const sortLabels = {
            timestamp: 'time', method: 'verb', uri: 'path', status: 'status', durationMs: 'duration',
            level: 'level', logger: 'logger', thread: 'thread', message: 'message'
        };
        $('tableSortLabel').textContent = sortLabels[sortField] || sortField;
        $('tableSortDir').textContent = sortDir === 'desc' ? '↓' : '↑';

        const arr = (k) => sortField === k ? (sortDir === 'desc' ? '↓' : '↑') : '';
        const sc = (k) => sortField === k ? 'active' : '';

        if (MODE === 'server') {
            $('colHead').innerHTML = `
        <span class="cell-rownum">#</span>
        <span class="sortable ${sc('timestamp')}" data-sort="timestamp" title="When this line was logged">Time<span class="arr">${arr('timestamp')}</span></span>
        <span class="sortable ${sc('level')}" data-sort="level" title="Log level (INFO / WARN / ERROR / ...)">Level<span class="arr">${arr('level')}</span></span>
        <span class="sortable ${sc('message')}" data-sort="message" title="Log message">Message<span class="arr">${arr('message')}</span></span>
        <span class="sortable ${sc('logger')}" data-sort="logger" title="Logger name that emitted this line">Logger<span class="arr">${arr('logger')}</span></span>
        <span class="sortable ${sc('thread')}" data-sort="thread" title="JVM thread that emitted this line">Thread<span class="arr">${arr('thread')}</span></span>`;
        } else {
            $('colHead').innerHTML = `
        <span class="cell-rownum">#</span>
        <span class="sortable ${sc('timestamp')}" data-sort="timestamp" title="Request timestamp">Time<span class="arr">${arr('timestamp')}</span></span>
        <span class="sortable ${sc('method')}" data-sort="method" title="HTTP method">Verb<span class="arr">${arr('method')}</span></span>
        <span class="sortable ${sc('uri')}" data-sort="uri" title="Request URI">Path<span class="arr">${arr('uri')}</span></span>
        <span class="col-req" title="X-Request-Id — correlates this request across all four log files">Req&nbsp;ID</span>
        <span class="sortable col-num ${sc('status')}" data-sort="status" title="HTTP response status code">Stat<span class="arr">${arr('status')}</span></span>
        <span class="sortable col-num ${sc('durationMs')}" data-sort="durationMs" style="padding-left:10px" title="Total request duration (ms)">Dur<span class="arr">${arr('durationMs')}</span></span>
        <span class="col-ip col-num" title="Resolved client IP (prefers a validated proxy header over the raw connection address, if configured)">From</span>
        <span class="sortable col-user ${sc('user')}" data-sort="user" title="Authenticated principal, when Spring Security had one for this request">User<span class="arr">${arr('user')}</span></span>`;
        }

        if (!total) {
            $('rows').innerHTML = `<div class="empty-state"><div class="empty-mark">∅</div><div class="empty-msg">${
                ALL.length === 0 ? 'no log entries' : 'no entries match the current filters'
            }</div></div>`;
        } else if (MODE === 'server') {
            $('rows').innerHTML = PAGE_ROWS.map((r, i) => {
                const lv  = String(r.level || '').toUpperCase();
                const lvc = lv.toLowerCase();
                const shortLogger = String(r.logger || '').replace(/^.*\.([^.]+)$/, '$1');
                const isNew = newRowSet.has(r);
                return `<div class="row level-${lvc}${isNew ? ' row-new' : ''}" data-idx="${start + i}">
          <span class="cell-rownum">${start + i + 1}</span>
          <span class="cell-time">${timeHtml(r.timestamp)}</span>
          <span class="cell-level"><span class="level-chip lv-${lvc}">${h(lv)}</span></span>
          <span class="cell-message" title="${h(r.message || '')}">${h(r.message || '')}${r.throwable ? ' <i style="color:var(--signal);font-style:normal;font-size:10px;font-weight:700">+stack</i>' : ''}</span>
          <span class="cell-logger" title="${h(r.logger || '')}">${h(shortLogger)}</span>
          <span class="cell-thread" title="${h(r.thread || '')}">${h(r.thread || '')}</span>
        </div>`;
            }).join('');
        } else {
            $('rows').innerHTML = PAGE_ROWS.map((r, i) => {
                const sb    = statusBucket(r.status);
                const reqId = (r.requestId || '').slice(0, 8);
                const isNew = newRowSet.has(r);
                // Per-user tint: the accent colours the User cell, the (very faint) bg tints the
                // whole row so runs of requests by one principal read as a block while scanning.
                const pal      = r.user ? userPalette(r.user) : null;
                const userDisp = r.user ? r.user.replace(/@.*$/, '') : '';
                // Skip the row tint on 5xx: .flagged carries its own background/border and must
                // stay visually dominant — an error row matters more than whose request it was.
                const rowStyle = pal && sb !== '5'
                    ? ` style="background:${pal.bg};border-left-color:${pal.accent}"`
                    : '';
                return `<div class="row ${sb === '5' ? 'flagged' : ''}${isNew ? ' row-new' : ''}" data-idx="${start + i}"${rowStyle}>
          <span class="cell-rownum">${start + i + 1}</span>
          <span class="cell-time">${timeHtml(r.timestamp)}</span>
          <span class="cell-method ${methodClass(r.method)}">${h(r.method)}</span>
          <span class="cell-uri" title="${h(r.uri)}">${h(r.uri)}</span>
          <span class="cell-req col-req" title="${h(r.requestId || '')}">${reqId}${r.requestId ? '…' : ''}</span>
          <span class="cell-status ${statusClass(r)}">${statusDisplay(r)}</span>
          <span class="cell-dur ${durClass(r.durationMs)}">${fmt.format(r.durationMs)}ms</span>
          <span class="cell-ip col-ip" title="${h(r.ip)}">${h(r.ip)}</span>
          <span class="cell-user col-user" title="${h(r.user || '')}"${pal ? ` style="color:${pal.accent}"` : ''}>${h(userDisp)}</span>
        </div>`;
            }).join('');
        }

        $('pgInfo').innerHTML = `Page <b>${page + 1}</b> of <b>${tp}</b> · rows ${total ? start + 1 : 0}–${end}`;
        $('pgPrev').disabled = page === 0;
        $('pgNext').disabled = page >= tp - 1;
    }

    function renderLeaderboard() {
        if (!isLeaderboardActive()) {
            $('leaderboardList').innerHTML = '';
            return;
        }
        const groups = {};
        for (const r of DRAW) {
            const k = r.uri || '-';
            if (!groups[k]) groups[k] = {uri: k, calls: 0, errs: 0, maxDur: 0, durs: null};
            groups[k].calls++;
            if (r.status >= 400) groups[k].errs++;
            // Skip 0ms entries: they represent missing/aborted durations (%D logged as "-")
            // and would drag p95 downward, making slow endpoints appear faster than they are.
            const d = r.durationMs;
            if (typeof d === 'number' && d > 0) {
                if (d > groups[k].maxDur) groups[k].maxDur = d;
            }
        }

        // Two-pass: first sort URIs by their max duration descending (cheap proxy for
        // p95 ranking), take top-20 candidates, then compute exact p95 only for those.
        // This avoids sorting every URI's full duration array when there are many distinct paths.
        const candidates = Object.values(groups)
            .filter(g => g.maxDur > 0)
            .sort((a, b) => b.maxDur - a.maxDur)
            .slice(0, 20);

        // Populate durs only for the candidate set.
        const candidateSet = new Set(candidates.map(g => g.uri));
        for (const r of DRAW) {
            const k = r.uri || '-';
            if (!candidateSet.has(k)) continue;
            const d = r.durationMs;
            if (typeof d === 'number' && d > 0) {
                const g = groups[k];
                if (!g.durs) g.durs = [];
                g.durs.push(d);
            }
        }

        const items = candidates
            .map(g => ({
                uri: g.uri,
                calls: g.calls,
                errRate: g.calls ? g.errs / g.calls * 100 : 0,
                p95: percentile(g.durs || [], 0.95)
            }))
            .sort((a, b) => b.p95 - a.p95)
            .slice(0, 8);

        if (!items.length) {
            $('leaderboardList').innerHTML = '<div style="color:var(--faint);font-size:12px">(no data in range)</div>';
            return;
        }
        const maxP95 = items[0].p95 || 1;
        $('leaderboardList').innerHTML = items.map((t, i) => {
            const bad = t.errRate > 5;
            const warn = t.p95 > 500;
            const cls = bad ? 'bad' : (warn ? 'warn' : '');
            const w = (t.p95 / maxP95) * 100;
            return `<div class="lb-item ${uriF === t.uri ? 'active' : ''}" data-uri="${h(t.uri)}" title="Click to filter the log to this endpoint">
        <div class="lb-row1">
          <span class="lb-rank">${i + 1}</span>
          <span class="lb-uri" title="${h(t.uri)}">${h(t.uri)}</span>
        </div>
        <div class="lb-row2">
          <span class="lb-p95 ${cls}">${fmt.format(t.p95)}<span class="ms">ms</span></span>
          <span class="lb-bar"><i class="${cls}" style="width:${w}%"></i></span>
        </div>
        <div class="lb-meta">${fmt.format(t.calls)} call${t.calls === 1 ? '' : 's'} · <span class="${bad ? 'bad' : ''}">${t.errRate.toFixed(1)}% err</span></div>
      </div>`;
        }).join('');
    }

