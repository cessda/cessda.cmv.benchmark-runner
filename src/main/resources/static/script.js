"use strict";

// ── Tenant config (fetched from /api/config) ──────────────────────────────
// Both maps start empty; init() populates them before any rendering occurs.
let SET_NAMES = {};
let FAIR_MAP = {};


// ── Maturity badge helper ─────────────────────────────────────────────────
function maturityBadgeHTML(level) {
    const labels = ['None', 'Level 1', 'Level 2', 'Level 3'];
    return `<span class="maturity-badge level-${level}">${labels[level]}</span>`;
}

// ── State ─────────────────────────────────────────────────────────────────
let set = '';
let pageCount = 0;    // total pages for this set (from summary.json)
let currentPage = 1;   // 1-based, current page displayed
let searchMode = false; // true when filtering across all pages
let searchCache = [];   // all records loaded for search

// ── Sort state for records table ──────────────────────────────────────────
let sortCol = null;   // 'identifier' | 'score' | 'maturity'
let sortDir = 'asc';
let currentRecords = []; // the currently displayed records (pre-sort)
const FAIR_DIMENSIONS = ['F', 'A', 'I', 'R'];

// ── Utilities ─────────────────────────────────────────────────────────────
function pct(n, d) {
    return d ? Math.round((n / d) * 100) : 0;
}

function fairCategory(testId) {
    if (FAIR_MAP[testId]) {
        const cat = String(FAIR_MAP[testId]).trim().toUpperCase();
        return FAIR_DIMENSIONS.includes(cat) ? cat : null;
    }
    const t = testId.trim();
    if (FAIR_MAP[t]) {
        const cat = String(FAIR_MAP[t]).trim().toUpperCase();
        return FAIR_DIMENSIONS.includes(cat) ? cat : null;
    }
    const fallback = (t[0] || '').toUpperCase();
    return FAIR_DIMENSIONS.includes(fallback) ? fallback : null;
}

function normaliseResultValue(result) {
    if (result === 'pass' || result === 'fail' || result === 'indeterminate') {
        return result;
    }
    return null;
}

function buildFairSummary(results) {
    const summary = {};
    FAIR_DIMENSIONS.forEach(cat => {
        summary[cat] = {pass: 0, fail: 0, indeterminate: 0, total: 0};
    });
    for (const [tid, value] of Object.entries(results || {})) {
        const cat = fairCategory(tid);
        const result = normaliseResultValue(value && value.result);
        if (!cat || !result) continue;
        summary[cat][result] += 1;
        summary[cat].total += 1;
    }
    return summary;
}

function fairSummaryText(summary) {
    return FAIR_DIMENSIONS
        .filter(cat => (summary[cat] && summary[cat].total > 0))
        .map(cat => {
            const row = summary[cat];
            return `${cat}: ${row.pass} pass, ${row.fail} fail, `
                + `${row.indeterminate} indeterminate`;
        })
        .join('; ');
}

function fairSummaryChipsHtml(summary) {
    return FAIR_DIMENSIONS
        .filter(cat => (summary[cat] && summary[cat].total > 0))
        .map(cat => {
            const row = summary[cat];
            return `<span class="chip fair fair-${cat}" title="${cat}: ${row.pass} pass, ${row.fail} fail, ${row.indeterminate} indeterminate">
            <span class="chip-key">${cat}</span>P:${row.pass} F:${row.fail} I:${row.indeterminate}
          </span>`;
        })
        .join('');
}

function fairSummaryRowsHtml(summary) {
    return FAIR_DIMENSIONS
        .filter(cat => (summary[cat] && summary[cat].total > 0))
        .map(cat => {
            const row = summary[cat];
            return `<div class="fair-summary-row">
            <span class="fair-summary-letter ${cat}" aria-hidden="true">${cat}</span>
            <span>${row.pass} pass · ${row.fail} fail · ${row.indeterminate} indeterminate</span>
            <span class="fair-summary-counts">${row.total} tests</span>
          </div>`;
        })
        .join('');
}

function pageUrl(p) {
    return `/api/results/guids_${set}/pages/page-${String(p).padStart(3, '0')}.json`;
}

// ── Bootstrap: load summary.json ─────────────────────────────────────────
async function loadData() {
    let summary;
    try {
        const r = await apiFetch('/api/results/summary.json');
        if (!r.ok) throw new Error(`HTTP ${r.status}`);
        summary = await r.json();
    } catch (e) {
        showError('Could not load /api/results/summary.json — check your API key and manifest. ' + e.message);
        return;
    }

    const ls = summary.sets && summary.sets[set];
    if (!ls) {
        showError(`No data found for set "${set}" in summary.json.`);
        return;
    }

    pageCount = ls.pageCount || 0;
    renderStats(ls);
    renderTestBreakdown(ls.tests || {});

    document.getElementById('hdr-count').textContent = `${ls.records.toLocaleString()} records`;
    document.getElementById('loading').style.display = 'none';
    document.getElementById('content').style.display = 'block';

    if (pageCount > 0) {
        await goPage(1);
    } else {
        document.getElementById('rec-count').textContent = '0 records';
        document.getElementById('pagination').style.display = 'none';
    }
}

// Per-level record counts for the set, e.g. "6×L3 599×L2" (same style as the overall dashboard).
// Falls back to the single best-level badge if the summary has no distribution.
function maturityDistributionHTML(ls) {
    const dist = ls.maturityDistribution;
    if (!dist) return maturityBadgeHTML(ls.maturityLevel != null ? ls.maturityLevel : 0);
    const parts = [
        [dist.level3, 'L3', 'var(--pass)'],
        [dist.level2, 'L2', 'var(--accent)'],
        [dist.level1, 'L1', 'var(--indet)'],
        [dist.none, 'L0', 'var(--fail)']
    ].filter(([n]) => n)
        .map(([n, label, color]) => `<span style="color:${color}">${n.toLocaleString()}×${label}</span>`);
    return parts.join(' ') || '—';
}

// ── Render summary KPIs from pre-aggregated stats ─────────────────────────
function renderStats(ls) {
    const tot = ls.pass + ls.fail + ls.indet;
    document.getElementById('sum-records').textContent = ls.records.toLocaleString();
    document.getElementById('sum-pass').textContent = pct(ls.pass, tot) + '%';
    document.getElementById('sum-fail').textContent = pct(ls.fail, tot) + '%';
    document.getElementById('sum-indet').textContent = pct(ls.indet, tot) + '%';

    document.getElementById('sum-maturity').innerHTML = maturityDistributionHTML(ls);

    for (const cat of ['F', 'A', 'I', 'R']) {
        const d = ls.fair[cat] || {pass: 0, total: 0};
        const p = pct(d.pass, d.total);
        const cL = cat.toLowerCase();
        document.getElementById(`lf-${cL}`).textContent = p + '%';
        document.getElementById(`lf-${cL}-sub`).textContent = `${d.pass} / ${d.total} tests`;
        setTimeout(() => {
            document.getElementById(`lf-${cL}-bar`).style.width = p + '%';
        }, 100);
    }
}

// ── Render per-test breakdown from pre-aggregated stats ───────────────────
function renderTestBreakdown(tests) {
    const grid = document.getElementById('test-grid');
    grid.innerHTML = '';
    for (const [tid, st] of Object.entries(tests)) {
        const t = st.pass + st.fail + st.indet;
        const pp = pct(st.pass, t);
        const fp = pct(st.fail, t);
        const ip = pct(st.indet, t);
        const cat = fairCategory(tid);
        const cell = document.createElement('div');
        cell.className = 'test-cell';
        cell.setAttribute('role', 'group');
        cell.setAttribute('aria-label', `${tid.trim()}: Pass ${pp}%, Fail ${fp}%, Indeterminate ${ip}%`);
        cell.innerHTML = `
        <div class="test-id">
          ${tid.trim()}
          ${cat ? `<span class="test-fair-badge badge-${cat}">${cat}</span>` : ''}
        </div>
        <div class="test-bar-row">
          <span class="test-bar-label pass">P</span>
          <div class="test-bar-bg" aria-hidden="true"><div class="test-bar-fill fill-pass" style="width:${pp}%"></div></div>
          <span class="test-pct pass">${pp}%</span>
        </div>
        <div class="test-bar-row">
          <span class="test-bar-label fail">F</span>
          <div class="test-bar-bg" aria-hidden="true"><div class="test-bar-fill fill-fail" style="width:${fp}%"></div></div>
          <span class="test-pct fail">${fp}%</span>
        </div>
        <div class="test-bar-row">
          <span class="test-bar-label indet">I</span>
          <div class="test-bar-bg" aria-hidden="true"><div class="test-bar-fill fill-indet" style="width:${ip}%"></div></div>
          <span class="test-pct indet">${ip}%</span>
        </div>
        <p class="test-sub">${t.toLocaleString()} records</p>`;
        grid.appendChild(cell);
    }
}

// ── Pagination ────────────────────────────────────────────────────────────
async function goPage(p) {
    if (p < 1 || p > pageCount) return;

    // Exit search mode when the user navigates pages explicitly
    if (searchMode) exitSearchMode();

    setPageLoading(true);
    let records;
    try {
        const r = await apiFetch(pageUrl(p));
        if (!r.ok) throw new Error(`HTTP ${r.status}`);
        records = await r.json();
    } catch (e) {
        setPageLoading(false);
        showTableError('Failed to load page ' + p + ': ' + e.message);
        return;
    }

    currentPage = p;
    currentRecords = records;
    sortCol = null; // reset sort on page change
    updateSortHeaders();
    renderTable(getSortedRecords());
    updatePagination();
    setPageLoading(false);
}

function updatePagination() {
    document.getElementById('btn-prev').disabled = (currentPage <= 1);
    document.getElementById('btn-next').disabled = (currentPage >= pageCount);
    document.getElementById('page-info').textContent =
        pageCount > 1 ? `Page ${currentPage} of ${pageCount}` : '';
}

function setPageLoading(on) {
    document.getElementById('page-loading').textContent = on ? 'Loading…' : '';
    document.getElementById('page-loading').style.display = on ? '' : 'none';
    document.getElementById('btn-prev').disabled = on || (currentPage <= 1);
    document.getElementById('btn-next').disabled = on || (currentPage >= pageCount);
}

// ── Search (loads all pages, then filters) ────────────────────────────────
let searchDebounce = null;

function onSearchInput() {
    clearTimeout(searchDebounce);
    const q = document.getElementById('search-input').value.trim();
    const rf = document.getElementById('result-filter').value;
    if (!q && !rf) {
        exitSearchMode();
        goPage(currentPage);
        return;
    }
    // Debounce 400 ms before hitting the network
    searchDebounce = setTimeout(() => runSearch(q, rf), 400);
}

async function runSearch(q, rf) {
    // If we don't have all records cached, load them now
    if (searchCache.length === 0) {
        await loadAllPagesIntoCache();
    }
    const qLower = q.toLowerCase();
    const filtered = searchCache.filter(rec => {
        if (q && !(rec.identifier || '').toLowerCase().includes(qLower) &&
            !(rec.testedguid || '').toLowerCase().includes(qLower)) return false;
        if (rf) {
            const vals = Object.values(rec.test_results || {}).map(v => v.result);
            if (!vals.includes(rf)) return false;
        }
        return true;
    });

    searchMode = true;
    document.getElementById('search-banner').style.display = '';
    document.getElementById('search-banner').textContent =
        `Search results: ${filtered.length.toLocaleString()} record${filtered.length !== 1 ? 's' : ''} matched`;
    document.getElementById('pagination').style.display = 'none';
    currentRecords = filtered;
    renderTable(getSortedRecords());
    document.getElementById('rec-count').textContent =
        `${filtered.length.toLocaleString()} match${filtered.length !== 1 ? 'es' : ''}`;
}

async function loadAllPagesIntoCache() {
    setPageLoading(true);
    document.getElementById('page-loading').textContent =
        `Loading all ${pageCount} page${pageCount !== 1 ? 's' : ''} for search…`;
    searchCache = [];
    for (let p = 1; p <= pageCount; p++) {
        try {
            document.getElementById('page-loading').textContent =
                `Loading page ${p} of ${pageCount}…`;
            const r = await apiFetch(pageUrl(p));
            if (!r.ok) continue;
            const recs = await r.json();
            searchCache.push(...recs);
        } catch { /* skip */
        }
    }
    setPageLoading(false);
}

function exitSearchMode() {
    searchMode = false;
    searchCache = [];
    document.getElementById('search-banner').style.display = 'none';
    document.getElementById('pagination').style.display = '';
    updatePagination();
}

// ── Sorting helpers ───────────────────────────────────────────────────────
function getSortedRecords() {
    if (!sortCol) return currentRecords;
    const arr = [...currentRecords];
    arr.sort((a, b) => {
        let av, bv;
        if (sortCol === 'identifier') {
            av = (a.identifier || extractIdentifier(a.testedguid || '')).toLowerCase();
            bv = (b.identifier || extractIdentifier(b.testedguid || '')).toLowerCase();
        } else if (sortCol === 'score') {
            av = a.netScore != null ? a.netScore : Object.values(a.test_results || {}).reduce((s, v) => s + (v.weight || 0), 0);
            bv = b.netScore != null ? b.netScore : Object.values(b.test_results || {}).reduce((s, v) => s + (v.weight || 0), 0);
        } else if (sortCol === 'maturity') {
            av = a.maturity != null ? a.maturity : 0;
            bv = b.maturity != null ? b.maturity : 0;
        }
        if (av < bv) return sortDir === 'asc' ? -1 : 1;
        if (av > bv) return sortDir === 'asc' ? 1 : -1;
        return 0;
    });
    return arr;
}

function updateSortHeaders() {
    document.querySelectorAll('#records-thead th.sortable').forEach(th => {
        th.classList.remove('sort-asc', 'sort-desc');
        if (th.dataset.col === sortCol) {
            th.classList.add(sortDir === 'asc' ? 'sort-asc' : 'sort-desc');
        }
    });
}

function onHeaderClick(th) {
    const col = th.dataset.col;
    if (sortCol === col) {
        sortDir = sortDir === 'asc' ? 'desc' : 'asc';
    } else {
        sortCol = col;
        sortDir = col === 'identifier' ? 'asc' : 'desc';
    }
    updateSortHeaders();
    renderTable(getSortedRecords());
}

// Wire up sort headers once DOM is ready
document.querySelectorAll('#records-thead th.sortable').forEach(th => {
    th.addEventListener('click', () => onHeaderClick(th));
});

// ── Table rendering ───────────────────────────────────────────────────────
function renderTable(records) {
    const tbody = document.getElementById('records-tbody');
    tbody.innerHTML = '';

    if (!searchMode) {
        document.getElementById('rec-count').textContent =
            `${records.length} record${records.length !== 1 ? 's' : ''} on this page`;
    }

    for (const rec of records) {
        const results = rec.test_results || {};
        const fairSummary = buildFairSummary(results);
        const fairSummaryTextValue = fairSummaryText(fairSummary);
        const netScore = rec.netScore != null
            ? rec.netScore
            : Object.values(results).reduce((s, v) => s + (v.weight || 0), 0);
        const scoreClass = netScore > 0 ? 'pos' : netScore < 0 ? 'neg' : 'zero';
        const matLevel = rec.maturity != null ? rec.maturity : 0;
        const chips = fairSummaryChipsHtml(fairSummary);

        const identifier = rec.identifier || extractIdentifier(rec.testedguid || '');
        const shortId = identifier.length > 20
            ? identifier.substring(0, 20) + '…'
            : identifier;

        const tr = document.createElement('tr');
        tr.setAttribute('tabindex', '0');
        tr.setAttribute('role', 'button');
        tr.setAttribute('aria-label',
            `Record ${shortId}, FAIR summary ${fairSummaryTextValue || 'not available'}, net score ${netScore > 0 ? '+' : ''}${Number(netScore).toFixed(1)}, maturity level ${matLevel}. Press Enter to view details.`);
        tr.innerHTML = `
        <td class="guid-cell" title="${identifier}">${shortId}</td>
        <td><div class="result-chips">${chips}</div></td>
        <td><span class="score-col ${scoreClass}">${netScore > 0 ? '+' : ''}${Number(netScore).toFixed(1)}</span></td>
        <td>${maturityBadgeHTML(matLevel)}</td>
        <td style="color:var(--muted);font-size:16px" aria-hidden="true">→</td>`;
        const open = () => openPanel(rec);
        tr.addEventListener('click', open);
        tr.addEventListener('keydown', e => {
            if (e.key === 'Enter' || e.key === ' ') {
                e.preventDefault();
                open();
            }
        });
        tbody.appendChild(tr);
    }
}

function extractIdentifier(url) {
    if (!url) return '';
    const idx = url.lastIndexOf('identifier=');
    if (idx < 0) return url;
    const after = url.substring(idx + 'identifier='.length);
    const amp = after.indexOf('&');
    return amp >= 0 ? after.substring(0, amp) : after;
}

// ── Detail side panel ─────────────────────────────────────────────────────
function openPanel(rec) {
    const results = rec.test_results || {};
    const fairSummary = buildFairSummary(results);
    const fairSummaryRows = fairSummaryRowsHtml(fairSummary);
    const identifier = rec.identifier || extractIdentifier(rec.testedguid || '');
    const guid = rec.testedguid || '';

    const testRows = Object.entries(results).map(([tid, v]) => {
        const cat = fairCategory(tid);
        return `
      <div class="test-row">
        <span class="test-row-id">${tid.trim()}</span>
        ${cat ? `<span class="test-fair-badge badge-${cat}">${cat}</span>` : ''}
        <span class="test-row-result ${v.result === 'indeterminate' ? 'indeterminate' : v.result}">${v.result}</span>
        <span class="test-row-weight">weight: ${v.weight}</span>
      </div>`;
    }).join('');

    const narrativeRows = (rec.narratives || []).map(n => {
        const cls = n.toLowerCase().startsWith('unacceptable') ? 'unacceptable' : 'acceptable';
        return `<div class="narrative-item ${cls}">${n}</div>`;
    }).join('');

    const guidanceRows = (rec.guidances || [])
        .flatMap(group =>
            (Array.isArray(group) ? group : [])
                .filter(item =>
                    Array.isArray(item) &&
                    item.length >= 2 &&
                    item[0] &&
                    item[1]
                )
                .map(([url, label]) =>
                    `<div class="guidance-item">
           <a href="${url}" target="_blank" rel="noopener noreferrer">
             ↗ ${label}
           </a>
         </div>`
                )
        )
        .join('');

    document.getElementById('panel-content').innerHTML = `
      <p class="panel-guid" id="panel-heading">
        <a href="${guid}" target="_blank" rel="noopener noreferrer">${identifier}</a>
      </p>
      ${fairSummaryRows ? `<div class="panel-section">
        <p class="panel-section-title">FAIR Summary</p>
        <div class="fair-summary-list">${fairSummaryRows}</div>
      </div>` : ''}
      <div class="panel-section">
        <p class="panel-section-title">Test Results</p>
        <div class="test-list">${testRows}</div>
      </div>
      ${narrativeRows ? `<div class="panel-section">
        <p class="panel-section-title">Narratives</p>
        <div class="narrative-list">${narrativeRows}</div>
      </div>` : ''}
      ${guidanceRows ? `<div class="panel-section">
        <p class="panel-section-title">Guidance</p>
        <div class="guidance-list">${guidanceRows}</div>
      </div>` : ''}`;

    document.getElementById('overlay').style.display = 'block';
    document.getElementById('detail-panel').style.display = 'block';
    document.body.style.overflow = 'hidden';
    document.getElementById('detail-panel').focus();
}

function closePanel() {
    document.getElementById('overlay').style.display = 'none';
    document.getElementById('detail-panel').style.display = 'none';
    document.body.style.overflow = '';
}

document.addEventListener('keydown', e => {
    if (e.key === 'Escape') closePanel();
});

// ── Error helpers ─────────────────────────────────────────────────────────
function showError(msg) {
    document.getElementById('loading').style.display = 'none';
    const el = document.getElementById('error-msg');
    el.style.display = 'block';
    el.innerHTML = `<strong>Error loading data</strong>
        <p><pre style="margin-top:12px;font-size:11px;text-align:left;display:inline-block;">${msg}</pre></p>`;
}

function showTableError(msg) {
    const tbody = document.getElementById('records-tbody');
    tbody.innerHTML = `<tr><td colspan="5" style="padding:20px;color:var(--fail)">${msg}</td></tr>`;
}

// ── Bootstrap ─────────────────────────────────────────────────────────────
// Fetch tenant config (set-names, fair-map) before rendering anything that
// depends on those maps.  Header text and page title are set here so they
// always reflect the fetched names.

// Populates the footer with the current tenant's own branding text,
// the same way index.html (and fetch-identifiers.html) do -- this
// page previously showed a hardcoded "CESSDA FAIR Benchmark" string
// regardless of which tenant's key was in use.
async function loadBranding() {
    try {
        const res = await apiFetch('/api/tenant/branding');
        const branding = await res.json();
        if (branding.footer) {
            document.getElementById('footer-text').textContent = branding.footer;
        }
    } catch (e) {
        console.warn('Could not load /api/tenant/branding:', e.message);
    }
}

function initSet() {
    const qp = new URLSearchParams(window.location.search);
    const hp = new URLSearchParams(window.location.hash.replace(/^#/, ''));
    set = qp.get('set') || hp.get('set') || '';
    if (set) {
        set = decodeURIComponent(set.trim());
    }
}

// ── About / user guide modal ────────────────────────────────────────────
// The guide is a plain Markdown file served as a static resource
// (About_detail.md), fetched without an API key -- it is generic help
// text, not tenant data, so it is exempted from TenantAuthFilter the
// same way detail.html itself is.
let aboutLoaded = false;

function openAboutModal(aboutPage) {
    document.getElementById('about-modal-backdrop').style.display = 'flex';
    document.getElementById('about-modal-close').focus();
    if (!aboutLoaded) {
        loadAboutContent(aboutPage);
    }
    document.addEventListener('keydown', closeAboutModal);
}

function closeAboutModal(e) {
    if (e.key === 'Escape'
        && document.getElementById('about-modal-backdrop').style.display === 'flex') {
        document.getElementById('about-modal-backdrop').style.display = 'none';
        document.removeEventListener('keydown', closeAboutModal);
    }
}

async function loadAboutContent(aboutPage) {
    const bodyEl = document.getElementById('about-modal-body');
    try {
        const res = await fetch(aboutPage);
        if (!res.ok) throw new Error(`HTTP ${res.status}`);
        bodyEl.innerHTML = await res.text();
        aboutLoaded = true;
    } catch (e) {
        bodyEl.innerHTML = `<p style="color:var(--fail)">Could not load the user guide (${e.message}).</p>`;
    }
}

// ── API key handling ───────────────────────────────────────────────────────
//
// The key is stored in sessionStorage so it survives page navigation within
// the same tab (index → detail → back) without reappearing in the URL or
// persisting across browser sessions.  "Change key" clears it and re-shows
// the gate, letting the same tab switch between tenants during testing.

function getStoredApiKey() {
    const _apiKey = new URLSearchParams(window.location.search).get('key')
    if (_apiKey) {
        setStoredApiKey(_apiKey);
        return _apiKey;
    } else {
        return sessionStorage.getItem('apiKey');
    }
}

function setStoredApiKey(key) {
    sessionStorage.setItem('apiKey', key);
}

function clearStoredApiKey() {
    sessionStorage.removeItem('apiKey');
}

async function apiFetch(url, options = {}) {
    const key = getStoredApiKey();
    const headers = {...options.headers};

    if (key) {
        headers['X-API-Key'] = key;
    }

    return fetch(url, {...options, headers});
}

// ── Key gate ───────────────────────────────────────────────────────────────

function showKeyGate() {
    document.getElementById('loading').style.display = 'none';
    document.getElementById('dashboard').style.display = 'none';
    document.getElementById('error-msg').style.display = 'none';
    document.getElementById('change-key-btn').style.display = 'none';
    document.getElementById('fetch-identifiers-btn').style.display = 'none';
    document.getElementById('generate-btn').style.display = 'none';
    document.getElementById('run-assessment-btn').style.display = 'none';
    document.getElementById('key-gate').style.display = 'flex';
    document.getElementById('key-gate-input').focus();
}

function hideKeyGate() {
    document.getElementById('key-gate').style.display = 'none';
    document.getElementById('key-gate-error').style.display = 'none';
    document.getElementById('change-key-btn').style.display = 'inline-block';
    document.getElementById('fetch-identifiers-btn').style.display = 'inline-block';
    document.getElementById('generate-btn').style.display = 'inline-block';
    document.getElementById('run-assessment-btn').style.display = 'inline-block';
}

function onKeyGateSubmit(e) {
    e.preventDefault();
    const key = document.getElementById('key-gate-input').value.trim();
    if (!key) return;
    setStoredApiKey(key);
    init();
}

function onChangeKeyClick() {
    clearStoredApiKey();
    SET_NAMES = {};
    FAIR_MAP = {};
    document.getElementById('key-gate-input').value = '';
    showKeyGate();
}

// ── Run Assessment modal ───────────────────────────────────────────────────

async function onRunAssessmentClick() {
    const backdrop = document.getElementById('assessment-modal-backdrop');
    const algInput = document.getElementById('assessment-algorithm-input');
    const runInput = document.getElementById('assessment-runner-input');
    const errEl = document.getElementById('assessment-modal-error');
    const confirmBtn = document.getElementById('assessment-modal-confirm');
    const cancelBtn = document.getElementById('assessment-modal-cancel');

    errEl.style.display = 'none';
    algInput.value = '';
    runInput.value = '';
    confirmBtn.disabled = true;
    cancelBtn.disabled = false;
    confirmBtn.textContent = 'Run assessment';
    renderSetsList([]);
    backdrop.style.display = 'flex';

    try {
        const [defaultsRes, filesRes] = await Promise.all([
            apiFetch('/api/run-assessment/defaults'),
            apiFetch('/api/run-assessment/guid-files')
        ]);

        if (defaultsRes.status === 401 || filesRes.status === 401) {
            hideAssessmentModal();
            clearStoredApiKey();
            showKeyGate();
            document.getElementById('key-gate-error').style.display = 'block';
            return;
        }

        if (!defaultsRes.ok) throw new Error(`HTTP ${defaultsRes.status}`);
        const defaults = await defaultsRes.json();
        algInput.value = defaults.algorithm || '';
        runInput.value = defaults.runner || '';

        if (!filesRes.ok) throw new Error(`HTTP ${filesRes.status}`);
        const filesBody = await filesRes.json();
        renderSetsList(filesBody.files || [], true);
    } catch (e) {
        errEl.textContent = `Could not load current defaults.\n${e.message}`;
        errEl.style.display = 'block';
    } finally {
        confirmBtn.disabled = false;
        algInput.focus();
    }
}

function renderSetsList(files, checkAllByDefault) {
    const listEl = document.getElementById('assessment-sets-list');
    listEl.innerHTML = '';

    if (!files || files.length === 0) {
        const empty = document.createElement('p');
        empty.id = 'assessment-sets-empty';
        empty.textContent = 'No guids_*.txt files found for your ' +
            'organisation yet. Run "Fetch identifiers" first.';
        listEl.appendChild(empty);
        return;
    }

    files.forEach((filename, i) => {
        const item = document.createElement('div');
        item.className = 'assessment-set-item';
        const inputId = `assessment-set-${i}`;
        item.innerHTML = `
        <input type="checkbox" id="${inputId}" value="${filename}"
               ${checkAllByDefault ? 'checked' : ''} />
        <label for="${inputId}">${filename}</label>`;
        listEl.appendChild(item);
    });
}

function onSetsSelectAllClick() {
    document.querySelectorAll('#assessment-sets-list input[type="checkbox"]')
        .forEach(cb => {
            cb.checked = true;
        });
}

function onSetsSelectNoneClick() {
    document.querySelectorAll('#assessment-sets-list input[type="checkbox"]')
        .forEach(cb => {
            cb.checked = false;
        });
}

function hideAssessmentModal() {
    document.getElementById('assessment-modal-backdrop').style.display = 'none';
}

async function onAssessmentModalSubmit(e) {
    e.preventDefault();

    const algorithm = document.getElementById('assessment-algorithm-input').value.trim();
    const runner = document.getElementById('assessment-runner-input').value.trim();
    const errEl = document.getElementById('assessment-modal-error');
    const confirmBtn = document.getElementById('assessment-modal-confirm');
    const cancelBtn = document.getElementById('assessment-modal-cancel');
    const headerBtn = document.getElementById('run-assessment-btn');

    errEl.style.display = 'none';
    confirmBtn.disabled = true;
    cancelBtn.disabled = true;
    confirmBtn.textContent = 'Running…';
    const headerBtnLabel = headerBtn.textContent;
    headerBtn.disabled = true;
    headerBtn.textContent = '▶ Running…';

    try {
        const selected = Array.from(
            document.querySelectorAll('#assessment-sets-list input[type="checkbox"]:checked')
        ).map(cb => cb.value);

        const totalAvailable = document.querySelectorAll(
            '#assessment-sets-list input[type="checkbox"]'
        ).length;

        if (totalAvailable > 0 && selected.length === 0) {
            errEl.textContent = 'Select at least one set to assess, or click "None" then "All" to reset.';
            errEl.style.display = 'block';
            return;
        }

        const params = new URLSearchParams();
        if (algorithm) params.set('benchmarkAlgorithmUri', algorithm);
        if (runner) params.set('benchmarkRunnerUri', runner);

        if (selected.length > 0) {
            selected.forEach(filename => params.append('guidFiles', filename));
        } else {
            params.set('processAll', 'true');
        }

        const res = await apiFetch('/api/run-assessment', {
            method: 'POST',
            headers: {'Content-Type': 'application/x-www-form-urlencoded'},
            body: params.toString()
        });

        if (res.status === 401) {
            hideAssessmentModal();
            clearStoredApiKey();
            showKeyGate();
            document.getElementById('key-gate-error').style.display = 'block';
            return;
        }

        const body = await res.json().catch(() => ({}));

        if (!res.ok) {
            errEl.textContent = body.message || `HTTP ${res.status}`;
            errEl.style.display = 'block';
            return;
        }

        hideAssessmentModal();
        window.alert(
            (body.message || 'Assessment complete.') +
            '\n\nClick "Generate manifest" to refresh the dashboard with these results.'
        );
    } catch (err) {
        errEl.textContent = err.message;
        errEl.style.display = 'block';
    } finally {
        confirmBtn.disabled = false;
        cancelBtn.disabled = false;
        confirmBtn.textContent = 'Run assessment';
        headerBtn.disabled = false;
        headerBtn.textContent = headerBtnLabel;
    }
}

// ── Generate Manifest ──────────────────────────────────────────────────────

async function onGenerateManifestClick() {
    const btn = document.getElementById('generate-btn');
    const originalLabel = btn.textContent;
    btn.disabled = true;
    btn.textContent = '⚙ Generating…';

    try {
        const res = await apiFetch('/api/generate-manifest', {method: 'POST'});

        if (res.status === 401) {
            clearStoredApiKey();
            showKeyGate();
            document.getElementById('key-gate-error').style.display = 'block';
            return;
        }

        const body = await res.json().catch(() => ({}));

        if (!res.ok) {
            showError(`Failed to generate manifest.\n\n${body.message || `HTTP ${res.status}`}`);
            return;
        }

        await loadData();
    } catch (e) {
        showError(`Failed to generate manifest.\n\n${e.message}`);
    } finally {
        btn.disabled = false;
        btn.textContent = originalLabel;
    }
}
