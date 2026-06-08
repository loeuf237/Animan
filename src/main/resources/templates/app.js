/* ================================================================
   Animan v2.0 — Frontend JavaScript
   ================================================================ */

'use strict';

// ----------------------------------------------------------------
// Thème clair/sombre
// ----------------------------------------------------------------
const THEME_KEY = 'animan-theme';

function initTheme() {
  const saved = localStorage.getItem(THEME_KEY) || 'dark';
  applyTheme(saved);
}

function applyTheme(theme) {
  document.documentElement.setAttribute('data-theme', theme);
  const moonIcon = document.querySelector('.icon-moon');
  const sunIcon  = document.querySelector('.icon-sun');
  if (moonIcon) moonIcon.style.display = theme === 'dark' ? '' : 'none';
  if (sunIcon)  sunIcon.style.display  = theme === 'light' ? '' : 'none';
  localStorage.setItem(THEME_KEY, theme);
}

document.addEventListener('DOMContentLoaded', () => {
  initTheme();
  const toggleBtn = document.getElementById('theme-toggle');
  if (toggleBtn) {
    toggleBtn.addEventListener('click', () => {
      const current = document.documentElement.getAttribute('data-theme') || 'dark';
      applyTheme(current === 'dark' ? 'light' : 'dark');
    });
  }

  // Charger les tailles des épisodes de façon asynchrone
  loadEpisodeSizes();

  // Filtres de version
  buildVersionFilters();

  // SSE si on est sur la page downloads
  if (document.getElementById('download-list')) {
    initSSE();
  }

  // Highlight nav actif
  highlightActiveNav();

  // Vérifier si l'anime courant est favori
  checkFavoriteStatus();
});

// ----------------------------------------------------------------
// Navigation active
// ----------------------------------------------------------------
function highlightActiveNav() {
  const path = window.location.pathname;
  const map = { '/': 'nav-home', '/library': 'nav-library', '/downloads': 'nav-downloads', '/history': 'nav-history' };
  const key = Object.keys(map).find(k => k !== '/' && path.startsWith(k)) || (path === '/' ? '/' : null);
  if (key && map[key]) document.getElementById(map[key])?.classList.add('active');
}

// ----------------------------------------------------------------
// Tailles épisodes (chargement asynchrone)
// ----------------------------------------------------------------
function loadEpisodeSizes() {
  const sizeSpans = document.querySelectorAll('.episode-size[data-url]');
  if (!sizeSpans.length) return;

  const observer = new IntersectionObserver((entries) => {
    entries.forEach(entry => {
      if (entry.isIntersecting) {
        const span = entry.target;
        observer.unobserve(span);
        const url = span.dataset.url;
        if (!url) return;
        fetch('/api/episode/size?url=' + encodeURIComponent(url))
          .then(r => r.json())
          .then(data => {
            span.textContent = data.success ? data.formattedSize : '—';
            // Cacher les sous-titres si aucun dispo
            if (data.success && data.subtitles) {
              try {
                const subs = JSON.parse(data.subtitles || '[]');
                const row = span.closest('.episode-item');
                if (row && subs.length === 0) {
                  const subLabel = row.querySelector('.subtitle-cb-label');
                  if (subLabel) subLabel.style.opacity = '0.4';
                }
              } catch(e) {}
            }
          })
          .catch(() => { span.textContent = '—'; });
      }
    });
  }, { rootMargin: '100px' });

  sizeSpans.forEach(s => observer.observe(s));
}

// ----------------------------------------------------------------
// Filtres de version sur la page anime-detail
// ----------------------------------------------------------------
function buildVersionFilters() {
  const filterContainer = document.getElementById('version-filter');
  if (!filterContainer) return;
  const items = document.querySelectorAll('.episode-item');
  if (!items.length) return;

  const versions = new Set();
  items.forEach(item => {
    const v = item.dataset.version;
    if (v) versions.add(v);
  });

  if (versions.size <= 1) return;

  const allBtn = createFilterBtn('Tous', true, () => filterEpisodes('ALL'));
  filterContainer.appendChild(allBtn);

  versions.forEach(v => {
    const btn = createFilterBtn(v, false, () => filterEpisodes(v));
    btn.classList.add(v === 'VOSTFR' ? 'ver-vostfr' : 'ver-vf');
    filterContainer.appendChild(btn);
  });
}

function createFilterBtn(label, active, onClick) {
  const btn = document.createElement('button');
  btn.className = 'ver-filter-btn' + (active ? ' active' : '');
  btn.textContent = label;
  btn.addEventListener('click', () => {
    document.querySelectorAll('.ver-filter-btn').forEach(b => b.classList.remove('active'));
    btn.classList.add('active');
    onClick();
  });
  return btn;
}

function filterEpisodes(version) {
  document.querySelectorAll('.episode-item').forEach(item => {
    item.style.display = (version === 'ALL' || item.dataset.version === version) ? '' : 'none';
  });
}

// ----------------------------------------------------------------
// Sélection d'épisodes
// ----------------------------------------------------------------
function onEpisodeCheckChange() {
  const checked = document.querySelectorAll('.episode-check:checked');
  const btn = document.getElementById('dl-selected-btn');
  const info = document.getElementById('selected-count-text');
  if (btn) btn.disabled = checked.length === 0;
  if (info) info.textContent = checked.length + ' sélectionné(s)';
}

function toggleSelectAll(checkbox) {
  document.querySelectorAll('.episode-check').forEach(cb => {
    const row = cb.closest('.episode-item');
    if (!row || row.style.display === 'none') return;
    cb.checked = checkbox.checked;
  });
  onEpisodeCheckChange();
}

// ----------------------------------------------------------------
// Téléchargement d'un épisode
// ----------------------------------------------------------------
async function downloadEpisode(btn) {
  const url       = btn.dataset.url;
  const epNum     = parseInt(btn.dataset.num, 10);
  const epTitle   = btn.dataset.title || ('Episode ' + epNum);
  const subtitleUrl = btn.dataset.subtitle || '';
  const row       = btn.closest('.episode-item');
  const downloadSubs = row?.querySelector('.subtitle-check')?.checked || false;

  const animeName = typeof ANIME_NAME !== 'undefined' ? ANIME_NAME : 'Anime';
  const source    = typeof SOURCE !== 'undefined' ? SOURCE : 'french-manga';

  const fileName = sanitizeFilename(animeName + ' - Episode ' + epNum + '.mp4');

  // Si voir-anime, proposer les qualités
  if (source === 'voir-anime') {
    await handleVoirAnimeDownload(url, animeName, epNum, fileName, subtitleUrl, downloadSubs, null);
    return;
  }

  btn.disabled = true;
  const oldHtml = btn.innerHTML;
  btn.innerHTML = '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M21 12a9 9 0 1 1-18 0 9 9 0 0 1 18 0z"/></svg>';

  try {
    const resp = await fetch('/api/download', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ animeName, episodeNumber: epNum, url, fileName, subtitleUrl, downloadSubtitles: downloadSubs })
    });
    const data = await resp.json();
    if (data.success) {
      showToast('Téléchargement démarré: ' + fileName, 'success');
      btn.style.background = 'rgba(80,208,128,0.15)';
      btn.style.color = 'var(--success)';
    } else {
      showToast('Erreur: ' + (data.error || 'inconnue'), 'error');
    }
  } catch(e) {
    showToast('Erreur réseau', 'error');
  } finally {
    setTimeout(() => { btn.disabled = false; btn.innerHTML = oldHtml; }, 2000);
  }
}

async function handleVoirAnimeDownload(episodeUrl, animeName, epNum, fileName, subtitleUrl, downloadSubs, scheduledTime) {
  showToast('Résolution des qualités disponibles…', 'info');
  try {
    const resp = await fetch('/api/voiranime/download-info?episodeUrl=' + encodeURIComponent(episodeUrl));
    const info = await resp.json();

    let qualities = {};
    try { qualities = JSON.parse(info.videoQualities || '{}'); } catch(e) {}

    const qualityCount = Object.keys(qualities).length;

    if (qualityCount > 1) {
      showQualityModal(qualities, animeName, epNum, fileName, subtitleUrl, downloadSubs, scheduledTime, info.videoChunks);
    } else {
      // Une seule qualité ou fichier unique
      const videoUrl = info.videoUrl || episodeUrl;
      await startDownloadRequest(animeName, epNum, videoUrl, fileName, subtitleUrl, scheduledTime, 0, downloadSubs);
    }
  } catch(e) {
    showToast('Erreur résolution: ' + e.message, 'error');
  }
}

function showQualityModal(qualities, animeName, epNum, fileName, subtitleUrl, downloadSubs, scheduledTime, chunksJson) {
  const modal = document.getElementById('quality-modal');
  const list  = document.getElementById('quality-list');
  if (!modal || !list) return;

  list.innerHTML = '';
  Object.entries(qualities).forEach(([label, url]) => {
    const item = document.createElement('div');
    item.className = 'quality-item';
    item.innerHTML = `<span class="quality-label">${label}</span><span class="quality-download">⬇ Télécharger</span>`;
    item.addEventListener('click', async () => {
      modal.style.display = 'none';
      await startDownloadRequest(animeName, epNum, url, label + ' - ' + fileName, subtitleUrl, scheduledTime, 0, downloadSubs);
    });
    list.appendChild(item);
  });

  // Option: télécharger tous les morceaux et concaténer
  if (chunksJson && chunksJson !== '[]') {
    try {
      const chunks = JSON.parse(chunksJson);
      if (chunks.length > 1) {
        const allItem = document.createElement('div');
        allItem.className = 'quality-item';
        allItem.innerHTML = `<span class="quality-label">Tous les morceaux (${chunks.length}x)</span><span class="quality-download">⬇ Concaténer</span>`;
        allItem.addEventListener('click', async () => {
          modal.style.display = 'none';
          await startDownloadRequest(animeName, epNum, chunks[0], fileName, subtitleUrl, scheduledTime, 0, downloadSubs);
          showToast(`${chunks.length} morceaux en queue de téléchargement`, 'info');
        });
        list.appendChild(allItem);
      }
    } catch(e) {}
  }

  modal.style.display = 'flex';
}

async function startDownloadRequest(animeName, episodeNumber, url, fileName, subtitleUrl, scheduledTime, speedLimit, downloadSubtitles) {
  const body = { animeName, episodeNumber, url, fileName };
  if (subtitleUrl) body.subtitleUrl = subtitleUrl;
  if (scheduledTime) body.scheduledTime = scheduledTime;
  if (speedLimit) body.speedLimit = String(speedLimit);
  body.downloadSubtitles = downloadSubtitles;

  const resp = await fetch('/api/download', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body)
  });
  const data = await resp.json();
  if (data.success) {
    showToast(data.message || 'Téléchargement lancé', 'success');
  } else {
    showToast('Erreur: ' + (data.error || 'inconnue'), 'error');
  }
}

// ----------------------------------------------------------------
// Téléchargement en lot
// ----------------------------------------------------------------
async function downloadSelected() {
  const animeName   = typeof ANIME_NAME !== 'undefined' ? ANIME_NAME : 'Anime';
  const source      = typeof SOURCE !== 'undefined' ? SOURCE : 'french-manga';
  const scheduleVal = document.getElementById('schedule-input')?.value || '';
  const checked     = document.querySelectorAll('.episode-check:checked');
  if (!checked.length) return;

  const episodes = [];
  checked.forEach(cb => {
    const row = cb.closest('.episode-item');
    if (!row) return;
    const num     = parseInt(row.dataset.num, 10);
    const url     = row.dataset.url;
    const version = row.dataset.version || '';
    const subUrl  = row.dataset.subtitle || '';
    const dlSubs  = row.querySelector('.subtitle-check')?.checked || false;
    const fileName = sanitizeFilename(animeName + ' - Episode ' + num + '.mp4');

    const epData = { number: String(num), url, fileName, subtitleUrl: subUrl, downloadSubtitles: String(dlSubs) };
    if (scheduleVal) epData.scheduledTime = scheduleVal;
    episodes.push(epData);
  });

  if (source === 'voir-anime' && episodes.length === 1) {
    await handleVoirAnimeDownload(episodes[0].url, animeName, parseInt(episodes[0].number), episodes[0].fileName, episodes[0].subtitleUrl, episodes[0].downloadSubtitles === 'true', scheduleVal || null);
    return;
  }

  const resp = await fetch('/api/download/batch', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ animeName, episodes })
  });
  const data = await resp.json();
  if (data.success) {
    showToast(data.message, 'success');
    document.getElementById('select-all-cb').checked = false;
    document.querySelectorAll('.episode-check').forEach(c => c.checked = false);
    onEpisodeCheckChange();
  } else {
    showToast('Erreur batch: ' + (data.error || ''), 'error');
  }
}

// ----------------------------------------------------------------
// Lecteur Plyr
// ----------------------------------------------------------------
let plyrInstance = null;

async function playEpisode(btn) {
  const url    = btn.dataset.url;
  const epNum  = btn.dataset.num;
  const source = typeof SOURCE !== 'undefined' ? SOURCE : 'french-manga';

  const playerSection = document.getElementById('player-section');
  if (!playerSection) return;

  showToast('Résolution du flux vidéo…', 'info');

  try {
    let videoUrl = '', subtitleUrl = '';

    if (source === 'voir-anime') {
      const resp = await fetch('/api/voiranime/download-info?episodeUrl=' + encodeURIComponent(url));
      const info = await resp.json();
      videoUrl    = info.videoUrl || '';
      subtitleUrl = info.subtitleUrl || '';
    } else {
      const resp = await fetch('/api/episode/size?url=' + encodeURIComponent(url));
      const info = await resp.json();
      videoUrl    = info.directUrl || '';
      subtitleUrl = info.subtitleUrl || '';
    }

    if (!videoUrl) { showToast('Flux vidéo introuvable', 'error'); return; }

    const srcEl  = document.getElementById('player-source');
    const subEl  = document.getElementById('player-subtitle');
    const vidEl  = document.getElementById('plyr-player');
    if (!srcEl || !vidEl) return;

    srcEl.src = videoUrl;
    if (subEl && subtitleUrl) { subEl.src = subtitleUrl; subEl.style.display = ''; }
    else if (subEl) subEl.style.display = 'none';

    vidEl.load();
    playerSection.style.display = 'block';
    playerSection.scrollIntoView({ behavior: 'smooth', block: 'start' });

    if (!plyrInstance) {
      plyrInstance = new Plyr('#plyr-player', {
        controls: ['play','progress','current-time','mute','volume','captions','fullscreen'],
        captions: { active: !!subtitleUrl, language: 'fr' }
      });
    } else {
      plyrInstance.source = { type: 'video', sources: [{ src: videoUrl, type: 'video/mp4' }] };
    }
    plyrInstance.play();
    showToast('Lecture de l\'épisode ' + epNum, 'success');

  } catch(e) {
    showToast('Erreur lecteur: ' + e.message, 'error');
  }
}

function closePlayer() {
  const ps = document.getElementById('player-section');
  if (ps) ps.style.display = 'none';
  if (plyrInstance) plyrInstance.pause();
}

// ----------------------------------------------------------------
// Favoris
// ----------------------------------------------------------------
async function toggleFavorite(btn) {
  const url      = btn.dataset.url;
  const title    = btn.dataset.title;
  const imageUrl = btn.dataset.image || '';
  const source   = btn.dataset.source || 'french-manga';
  const isActive = btn.classList.contains('active');

  try {
    if (isActive) {
      await fetch('/api/favorites', {
        method: 'DELETE',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ url })
      });
      btn.classList.remove('active');
      btn.querySelector('span').textContent = 'Ajouter à Ma Liste';
      showToast('Retiré de Ma Liste', 'info');
    } else {
      await fetch('/api/favorites', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ url, title, imageUrl, source })
      });
      btn.classList.add('active');
      btn.querySelector('span').textContent = 'Dans Ma Liste';
      showToast('Ajouté à Ma Liste ❤️', 'success');
    }
  } catch(e) {
    showToast('Erreur favoris', 'error');
  }
}

async function checkFavoriteStatus() {
  const favBtn = document.getElementById('fav-btn');
  if (!favBtn) return;
  const url = favBtn.dataset.url;
  if (!url) return;
  try {
    const resp = await fetch('/api/favorites/check?url=' + encodeURIComponent(url));
    const data = await resp.json();
    if (data.isFavorite) {
      favBtn.classList.add('active');
      favBtn.querySelector('span').textContent = 'Dans Ma Liste';
    }
  } catch(e) {}
}

async function toggleAutoDownload(input) {
  const url  = input.dataset.url;
  const auto = input.checked;
  try {
    await fetch('/api/favorites/auto-download', {
      method: 'PATCH',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ url, autoDownload: auto })
    });
    showToast(auto ? 'Auto-DL activé' : 'Auto-DL désactivé', 'info');
  } catch(e) {
    showToast('Erreur auto-DL', 'error');
  }
}

async function toggleAutoDownloadFromLibrary(input) {
  await toggleAutoDownload(input);
}

async function removeFavorite(btn) {
  const url = btn.dataset.url;
  if (!confirm('Retirer de Ma Liste ?')) return;
  try {
    await fetch('/api/favorites', {
      method: 'DELETE',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ url })
    });
    showToast('Retiré de Ma Liste', 'info');
    btn.closest('.anime-card')?.remove();
  } catch(e) {
    showToast('Erreur', 'error');
  }
}

// ----------------------------------------------------------------
// SSE — page downloads
// ----------------------------------------------------------------
let sseSource = null;

function initSSE() {
  if (sseSource) sseSource.close();
  sseSource = new EventSource('/api/download/stream');
  sseSource.addEventListener('download-update', e => {
    try { updateTaskRow(JSON.parse(e.data)); } catch(ex) {}
  });
  sseSource.onerror = () => {
    setTimeout(initSSE, 3000);
  };
}

function updateTaskRow(task) {
  let row = document.getElementById('task-' + task.id);
  if (!row) {
    row = createTaskRow(task);
    const list = document.getElementById('download-list');
    if (list) list.prepend(row);
  }
  applyTaskData(row, task);
}

function createTaskRow(task) {
  const div = document.createElement('div');
  div.className = 'dl-task-row';
  div.id = 'task-' + task.id;
  div.innerHTML = `
    <div class="dl-task-header">
      <span class="dl-task-name"></span>
      <span class="dl-task-status badge-other"></span>
    </div>
    <div class="dl-progress-wrap" style="display:none">
      <div class="dl-progress-bar"><div class="dl-progress-fill"></div></div>
      <span class="dl-progress-text">0%</span>
    </div>
    <div class="dl-task-meta">
      <span class="dl-size"></span>
      <span class="dl-speed"></span>
      <span class="dl-error" style="color:var(--danger);font-size:0.78rem;"></span>
    </div>
    <div class="dl-task-actions"></div>`;
  return div;
}

function applyTaskData(row, task) {
  row.querySelector('.dl-task-name').textContent = task.animeName + ' — Ép.' + task.episodeNumber + ' ' + (task.fileName || '');
  const statusEl = row.querySelector('.dl-task-status');
  statusEl.textContent = translateStatus(task.status);
  statusEl.className = 'dl-task-status status-badge ' + statusClass(task.status);

  const progressWrap = row.querySelector('.dl-progress-wrap');
  const fill  = row.querySelector('.dl-progress-fill');
  const pText = row.querySelector('.dl-progress-text');
  if (task.status === 'DOWNLOADING' || task.progress > 0) {
    progressWrap.style.display = 'flex';
    fill.style.width = task.progress + '%';
    pText.textContent = task.progress.toFixed(1) + '%';
  }
  row.querySelector('.dl-size').textContent  = task.downloadedSize + ' / ' + task.totalSize;
  row.querySelector('.dl-speed').textContent = task.speed || '';
  const errEl = row.querySelector('.dl-error');
  errEl.textContent = task.error || '';

  // Boutons d'action
  const actEl = row.querySelector('.dl-task-actions');
  actEl.innerHTML = renderTaskActions(task);
}

function renderTaskActions(task) {
  const id = task.id;
  if (task.status === 'DOWNLOADING') {
    return `<button class="btn btn-sm" onclick="apiPost('/api/download/${id}/pause')">⏸ Pause</button>
            <button class="btn btn-sm btn-danger" onclick="cancelTask('${id}')">✕ Annuler</button>`;
  }
  if (task.status === 'PAUSED') {
    return `<button class="btn btn-sm btn-primary" onclick="apiPost('/api/download/${id}/resume')">▶ Reprendre</button>
            <button class="btn btn-sm btn-danger" onclick="cancelTask('${id}')">✕ Annuler</button>`;
  }
  if (task.status === 'COMPLETED') {
    return `<button class="btn btn-sm" onclick="removeTask('${id}')">🗑 Supprimer</button>`;
  }
  if (task.status === 'FAILED' || task.status === 'CANCELLED') {
    return `<button class="btn btn-sm btn-primary" onclick="apiPost('/api/download/${id}/resume')">↺ Réessayer</button>
            <button class="btn btn-sm" onclick="removeTask('${id}')">🗑 Supprimer</button>`;
  }
  if (task.status === 'PENDING' || task.status === 'SCHEDULED') {
    return `<button class="btn btn-sm btn-danger" onclick="cancelTask('${id}')">✕ Annuler</button>`;
  }
  return '';
}

function translateStatus(s) {
  const m = { PENDING:'En attente', SCHEDULED:'Planifié', DOWNLOADING:'Téléchargement',
    RETRYING:'Nouvelle tentative', PAUSED:'Pause', COMPLETED:'Terminé', FAILED:'Échec', CANCELLED:'Annulé' };
  return m[s] || s;
}
function statusClass(s) {
  if (s === 'COMPLETED') return 'badge-ok';
  if (s === 'FAILED' || s === 'CANCELLED') return 'badge-err';
  return 'badge-other';
}

async function apiPost(url, body) {
  await fetch(url, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: body ? JSON.stringify(body) : undefined });
}

async function cancelTask(id) {
  const ok = await fetch('/api/download/' + id, { method: 'DELETE' });
}

async function removeTask(id) {
  await fetch('/api/download/' + id + '/remove', { method: 'DELETE' });
  document.getElementById('task-' + id)?.remove();
}

// ----------------------------------------------------------------
// Toast
// ----------------------------------------------------------------
function showToast(message, type = 'info') {
  const container = document.getElementById('toast-container');
  if (!container) return;
  const toast = document.createElement('div');
  toast.className = 'toast ' + type;
  toast.textContent = message;
  container.appendChild(toast);
  setTimeout(() => toast.remove(), 4000);
}

// ----------------------------------------------------------------
// Utilitaires
// ----------------------------------------------------------------
function sanitizeFilename(name) {
  return name.replace(/[<>:"/\\|?*]/g, '_').replace(/\s+/g, ' ').trim();
}
