import { initializeApp }  from 'https://www.gstatic.com/firebasejs/10.12.0/firebase-app.js';
import {
    getFirestore,
    doc, onSnapshot,
    collection, query, orderBy, limit, getDocs
} from 'https://www.gstatic.com/firebasejs/10.12.0/firebase-firestore.js';
import { firebaseConfig }  from './firebase.config.js';
import { initAuth, waitForAuth, onAuthChange, signOutUser } from './auth.js';

// ── Init ──────────────────────────────────────────────────────────
const app = initializeApp(firebaseConfig);
const db  = getFirestore(app);
initAuth(app);

const deviceId = new URLSearchParams(window.location.search).get('id');
if (!deviceId) window.location.href = 'index.html';

// ── Auth gate ─────────────────────────────────────────────────────
waitForAuth().then(user => {
    if (!user) { window.location.href = 'login.html'; return; }
    startRiderPage();
});
onAuthChange(user => { if (!user) window.location.href = 'login.html'; });
document.getElementById('btn-signout').addEventListener('click', () => signOutUser());

// ── Page ──────────────────────────────────────────────────────────
let alertDocs = [];   // cached for CSV export

function startRiderPage() {
    // Real-time status listener
    onSnapshot(doc(db, 'riders', deviceId), snap => {
        if (!snap.exists()) {
            document.getElementById('rider-title').textContent = 'Rider not found';
            return;
        }
        renderStatus(snap.data());
    });

    // Alert history (latest 200, ordered by timestamp desc)
    const alertsRef = query(
        collection(db, 'riders', deviceId, 'alerts'),
        orderBy('timestamp', 'desc'),
        limit(200)
    );
    getDocs(alertsRef).then(snap => {
        alertDocs = snap.docs.map(d => ({ id: d.id, ...d.data() }));
        renderAlertTable(alertDocs);
        renderChart(alertDocs);
    });

    // Export
    document.getElementById('btn-export').addEventListener('click', () => exportCsv(alertDocs, deviceId));
}

// ── Status cards ──────────────────────────────────────────────────
function renderStatus(d) {
    document.getElementById('rider-title').textContent = d.riderName || deviceId;

    const badge = document.getElementById('rider-status-badge');
    if (d.alertActive) {
        badge.innerHTML = '<span class="badge alert">⚠ ALERT</span>';
    } else if (!d.connected) {
        badge.innerHTML = '<span class="badge unknown">OFFLINE</span>';
    } else {
        badge.innerHTML = '<span class="badge open">ACTIVE</span>';
    }

    const eye = (d.eyeState || 'UNKNOWN');
    document.getElementById('stat-eye').textContent      = eye;
    document.getElementById('stat-eye').style.color      = eyeColor(eye, d.alertActive);
    document.getElementById('stat-perclos').textContent  = `${(d.perclos || 0).toFixed(1)}%`;
    document.getElementById('stat-closure').textContent  = `${(d.continuousClosureSec || 0).toFixed(1)}s`;
    document.getElementById('stat-status').textContent   = d.alertActive ? '🚨 DROWSY' : d.connected ? '✓ Normal' : 'Offline';
    document.getElementById('stat-location').textContent = d.location
        ? `${d.location.latitude.toFixed(5)}, ${d.location.longitude.toFixed(5)}`
        : '—';
}

function eyeColor(state, alert) {
    if (alert) return '#ff4444';
    if (state === 'OPEN')   return '#22cc44';
    if (state === 'CLOSED') return '#ff4444';
    return '#888888';
}

// ── Alert table ───────────────────────────────────────────────────
function renderAlertTable(docs) {
    const tbody = document.getElementById('alert-table-body');
    if (docs.length === 0) {
        tbody.innerHTML = '<tr><td colspan="5" style="text-align:center;color:#666;padding:24px">No alerts recorded</td></tr>';
        document.getElementById('stat-alerts').textContent = '0';
        return;
    }

    document.getElementById('stat-alerts').textContent = docs.length;

    tbody.innerHTML = docs.map(d => {
        const ts   = d.timestamp?.seconds
            ? new Date(d.timestamp.seconds * 1000).toLocaleString()
            : '—';
        const type = (d.type || 'ALERT').toUpperCase();
        const cls  = 'alert-type-' + type.toLowerCase().replace('_', '-');
        const pc   = (parseFloat(d.perclos) || 0).toFixed(1);
        const dur  = (parseFloat(d.continuousSec) || 0).toFixed(1);
        const loc  = d.location
            ? `${d.location.latitude.toFixed(4)}, ${d.location.longitude.toFixed(4)}`
            : '—';
        return `<tr>
            <td style="font-size:12px;color:#aaa">${ts}</td>
            <td class="${cls}">${type}</td>
            <td>${pc}%</td>
            <td>${dur}s</td>
            <td style="font-size:12px;color:#888">${loc}</td>
        </tr>`;
    }).join('');
}

// ── Hourly bar chart (pure SVG, no library) ───────────────────────
function renderChart(docs) {
    if (docs.length === 0) return;

    // Count alerts per hour for the last 24 hours
    const now    = Date.now() / 1000;
    const counts = new Array(24).fill(0);

    docs.forEach(d => {
        const ts = d.timestamp?.seconds ?? 0;
        const hoursAgo = Math.floor((now - ts) / 3600);
        if (hoursAgo >= 0 && hoursAgo < 24) {
            counts[23 - hoursAgo]++;
        }
    });

    const maxCount = Math.max(...counts, 1);
    const W = 600, H = 120, PAD = 20, BAR_GAP = 2;
    const barW = (W - PAD * 2) / 24 - BAR_GAP;

    const bars = counts.map((c, i) => {
        const x    = PAD + i * ((W - PAD * 2) / 24);
        const barH = Math.max(2, ((c / maxCount) * (H - PAD - 20)));
        const y    = H - PAD - barH;
        const fill = c > 0 ? '#ff4444' : '#333';
        const title = c > 0 ? `${c} alert${c > 1 ? 's' : ''}` : '';
        return `<rect x="${x.toFixed(1)}" y="${y.toFixed(1)}" width="${barW.toFixed(1)}" height="${barH.toFixed(1)}" fill="${fill}" rx="2">
            ${title ? `<title>${title}</title>` : ''}
        </rect>`;
    }).join('');

    // Hour labels at -24h, -12h, now
    const labels = `
        <text x="${PAD}"   y="${H - 4}" fill="#555" font-size="10" text-anchor="middle">24h ago</text>
        <text x="${W / 2}" y="${H - 4}" fill="#555" font-size="10" text-anchor="middle">12h ago</text>
        <text x="${W - PAD}" y="${H - 4}" fill="#555" font-size="10" text-anchor="middle">now</text>`;

    document.getElementById('chart-svg').innerHTML = bars + labels;
}

// ── CSV export ────────────────────────────────────────────────────
function exportCsv(docs, id) {
    const header = 'timestamp,type,perclos,continuous_sec,lat,lng\n';
    const rows   = docs.map(d => {
        const ts  = d.timestamp?.seconds
            ? new Date(d.timestamp.seconds * 1000).toISOString()
            : '';
        const lat = d.location?.latitude  ?? '';
        const lng = d.location?.longitude ?? '';
        return [ts, d.type || '', d.perclos || 0, d.continuousSec || 0, lat, lng].join(',');
    });
    const csv  = header + rows.join('\n');
    const blob = new Blob([csv], { type: 'text/csv' });
    const url  = URL.createObjectURL(blob);
    const a    = document.createElement('a');
    a.href     = url;
    a.download = `smarthelm_${id}_alerts.csv`;
    a.click();
    URL.revokeObjectURL(url);
}
