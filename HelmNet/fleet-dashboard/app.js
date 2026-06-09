import { initializeApp }       from 'https://www.gstatic.com/firebasejs/10.12.0/firebase-app.js';
import { getFirestore, collection, onSnapshot }
                                from 'https://www.gstatic.com/firebasejs/10.12.0/firebase-firestore.js';
import { getAuth, signInAnonymously }
                                from 'https://www.gstatic.com/firebasejs/10.12.0/firebase-auth.js';
import { firebaseConfig }       from './firebase.config.js';

// ── Init ──────────────────────────────────────────────────────────
const app  = initializeApp(firebaseConfig);
const db   = getFirestore(app);
const auth = getAuth(app);

// ── No login required — sign in anonymously and go straight to dashboard ──
document.getElementById('btn-signout').style.display = 'none';

signInAnonymously(auth)
    .then(() => startDashboard())
    .catch(err => {
        // If anonymous auth is disabled, try without auth (open Firestore rules)
        console.warn('Anonymous auth failed, attempting unauthenticated read:', err.message);
        startDashboard();
    });

// ── State ─────────────────────────────────────────────────────────
const riderData       = {};   // deviceId → Firestore document data
const prevAlertState  = {};   // deviceId → boolean (for edge detection)

// ── Dashboard ─────────────────────────────────────────────────────
function startDashboard() {
    document.getElementById('loading').style.display    = 'none';
    document.getElementById('fleet-view').style.display = 'block';

    // Request browser notification permission once
    if (Notification.permission === 'default') Notification.requestPermission();

    // Real-time listener — fires on every Firestore document change
    onSnapshot(collection(db, 'riders'), snapshot => {
        snapshot.docChanges().forEach(change => {
            if (change.type === 'removed') {
                delete riderData[change.doc.id];
                delete prevAlertState[change.doc.id];
            } else {
                riderData[change.doc.id] = change.doc.data();
            }
        });
        renderTable();
        updateSummaryPills();
    });
}

// ── Render ────────────────────────────────────────────────────────
function renderTable() {
    const tbody    = document.getElementById('rider-table-body');
    const noRiders = document.getElementById('no-riders');
    const ids      = Object.keys(riderData);

    if (ids.length === 0) {
        tbody.innerHTML = '';
        noRiders.style.display = 'block';
        updateAlertBanner([]);
        return;
    }
    noRiders.style.display = 'none';

    // Sort: alerts first, then by name
    ids.sort((a, b) => {
        const da = riderData[a], db_ = riderData[b];
        if (da.alertActive && !db_.alertActive) return -1;
        if (!da.alertActive && db_.alertActive) return  1;
        return (da.riderName || '').localeCompare(db_.riderName || '');
    });

    tbody.innerHTML = ids.map(id => buildRow(id, riderData[id])).join('');

    // Attach click handlers
    ids.forEach(id => {
        const row = document.getElementById(`row-${id}`);
        if (row) row.addEventListener('click', () => {
            window.location.href = `rider.html?id=${encodeURIComponent(id)}`;
        });
    });

    // Fire browser notification on alert edge (false → true only)
    ids.forEach(id => {
        const d        = riderData[id];
        const wasAlert = prevAlertState[id] ?? false;
        if (!wasAlert && d.alertActive) {
            fireNotification(d.riderName || id);
        }
        prevAlertState[id] = d.alertActive;
    });

    // Update global alert banner
    const alertingRiders = ids
        .filter(id => riderData[id].alertActive)
        .map(id => riderData[id].riderName || id);
    updateAlertBanner(alertingRiders);
}

// ── Global alert banner ───────────────────────────────────────────
function updateAlertBanner(alertingNames) {
    const banner = document.getElementById('alert-banner');
    const text   = document.getElementById('alert-banner-text');
    if (alertingNames.length === 0) {
        banner.style.display = 'none';
        return;
    }
    banner.style.display = 'flex';
    const names = alertingNames.join(', ');
    text.textContent = alertingNames.length === 1
        ? `DROWSINESS ALERT — ${names}`
        : `DROWSINESS ALERT — ${alertingNames.length} riders: ${names}`;
}

function buildRow(id, d) {
    const now       = Date.now() / 1000;
    const updatedAt = d.updatedAt?.seconds ?? 0;
    const stale     = !d.connected || (now - updatedAt) > 30;

    const rowClass = d.alertActive ? 'row-alert' : (stale ? 'row-offline' : '');

    // Live camera snapshot thumbnail
    const snapshotHtml = d.snapshot
        ? `<img class="snapshot-thumb ${d.alertActive ? 'snapshot-alert' : ''}"
               src="data:image/jpeg;base64,${d.snapshot}"
               alt="cam" />`
        : `<span class="snapshot-none">No feed</span>`;

    // Eye state badge
    const stateClass = (d.eyeState || 'unknown').toLowerCase();
    const stateBadge = d.alertActive
        ? `<span class="badge alert">⚠ ALERT</span>`
        : `<span class="badge ${stateClass}">${d.eyeState || '—'}</span>`;

    // PERCLOS bar
    const pc    = parseFloat(d.perclos) || 0;
    const pcCls = pc >= 30 ? 'danger' : pc >= 15 ? 'warn' : '';
    const perclosHtml = `
        <span>${pc.toFixed(1)}%</span>
        <span class="perclos-bar-bg">
            <span class="perclos-bar-fill ${pcCls}" style="width:${Math.min(pc,100)}%"></span>
        </span>`;

    // Status dot
    const dotCls = d.alertActive ? 'red' : stale ? 'grey' : pc >= 15 ? 'yellow' : 'green';
    const statusHtml = stale
        ? `<span class="status-dot grey"></span><span class="stale-label">OFFLINE</span>`
        : `<span class="status-dot ${dotCls}"></span>${d.alertActive ? 'ALERT' : pc >= 15 ? 'Caution' : 'OK'}`;

    // Location
    const loc = d.location
        ? `${d.location.latitude.toFixed(4)}, ${d.location.longitude.toFixed(4)}`
        : '—';

    // Last seen
    const lastSeen = updatedAt ? relTime(updatedAt) : '—';

    return `
        <tr id="row-${id}" class="${rowClass}">
            <td><strong>${escHtml(d.riderName || id)}</strong></td>
            <td>${snapshotHtml}</td>
            <td>${stateBadge}</td>
            <td>${perclosHtml}</td>
            <td>${statusHtml}</td>
            <td style="color:#888;font-size:12px">${loc}</td>
            <td style="color:#888;font-size:12px">${lastSeen}</td>
        </tr>`;
}

function updateSummaryPills() {
    const riders = Object.values(riderData);
    const active = riders.filter(d => d.connected).length;
    const alerts = riders.filter(d => d.alertActive).length;

    document.getElementById('pill-active').textContent = `${active} Active`;
    const pillAlert = document.getElementById('pill-alert');
    if (alerts > 0) {
        pillAlert.style.display  = 'inline-block';
        pillAlert.textContent    = `${alerts} Alert${alerts > 1 ? 's' : ''}`;
    } else {
        pillAlert.style.display  = 'none';
    }
}

// ── Browser notification (edge-triggered only) ────────────────────
function fireNotification(riderName) {
    if (Notification.permission !== 'granted') return;
    new Notification('SmartHelm — Drowsiness Alert', {
        body: `${riderName} is drowsy — check immediately`,
        icon: 'icon.png',
        tag:  'smarthelm-alert'   // same tag prevents duplicate stacking
    });
}

// ── Utilities ─────────────────────────────────────────────────────
function relTime(seconds) {
    const diff = Math.floor(Date.now() / 1000 - seconds);
    if (diff <  5)  return 'just now';
    if (diff < 60)  return `${diff}s ago`;
    if (diff < 3600) return `${Math.floor(diff / 60)}m ago`;
    return `${Math.floor(diff / 3600)}h ago`;
}

function escHtml(str) {
    return String(str)
        .replace(/&/g,'&amp;').replace(/</g,'&lt;')
        .replace(/>/g,'&gt;').replace(/"/g,'&quot;');
}

// Refresh "last seen" timestamps every 10 seconds without re-querying Firestore
setInterval(renderTable, 10_000);
