'use strict';

const THEME_KEY = 'animan-theme';
let sseSource = null;
let plyrInstance = null;

document.addEventListener('DOMContentLoaded', () => {
  initTheme();
  highlightActiveNav();
  bindGlobalSettingsToggle();
  loadEpisodeSizes();
  buildVersionFilters();
  checkFavoriteStatus();
  refreshDownloadBadge();
  bindPlayActionButtons();

  if (document.getElementById('download-list')) {
    initSSE();
    refreshDownloadStats();
    initDragAndDrop();
    initScheduledTicker();
  }
});


function initTheme() {
  const saved = localStorage.getItem(THEME_KEY) || 'dark';
  applyTheme(saved);
  document.getElementById('theme-toggle')?.addEventListener('click', () => {
    const current = document.documentElement.getAttribute('data-theme') || 'dark';
    applyTheme(current === 'dark' ? 'light' : 'dark');
  });
}

function applyTheme(theme) {
  document.documentElement.setAttribute('data-theme', theme);
  document.querySelectorAll('.icon-moon').forEach(el => { el.style.display = theme === 'dark' ? '' : 'none'; });
  document.querySelectorAll('.icon-sun').forEach(el => { el.style.display = theme === 'light' ? '' : 'none'; });
  localStorage.setItem(THEME_KEY, theme);
}

function highlightActiveNav() {
  const path = window.location.pathname;
  const entries = [
    ['/downloads', 'nav-downloads'],
    ['/library', 'nav-library'],
    ['/history', 'nav-history'],
    ['/', 'nav-home']
  ];
  const match = entries.find(([prefix]) => prefix === '/' ? path === '/' : path.startsWith(prefix));
  if (match) document.getElementById(match[1])?.classList.add('active');
}

function bindGlobalSettingsToggle() {
  document.querySelectorAll('[data-toggle-download-settings], #settings-shortcut').forEach(btn => {
    btn.addEventListener('click', () => {
      const panel = document.getElementById('settings-panel');
      if (panel) {
        panel.classList.toggle('open');
        panel.scrollIntoView({ behavior: 'smooth', block: 'nearest' });
      } else {
        window.location.href = '/downloads';
      }
    });
  });
}

function initDownloadSettingsPanel() {
  const panel = document.getElementById('settings-panel');
  const toggle = document.getElementById('toggle-settings-btn');
  const save = document.getElementById('save-settings-btn');
  const uaSelect = document.getElementById('user-agent-select');
  const customUaGroup = document.getElementById('custom-ua-group');
  const customUaInput = document.getElementById('custom-ua-input');

  toggle?.addEventListener('click', () => panel?.classList.toggle('open'));
  uaSelect?.addEventListener('change', () => {
    if (customUaGroup) customUaGroup.style.display = uaSelect.value === 'Custom' ? 'grid' : 'none';
  });

  fetch('/api/download/settings')
    .then(r => r.json())
    .then(settings => {
      const maxInput = document.getElementById('max-concurrent-input');
      const speedInput = document.getElementById('global-speed-input-settings');
      const plexInput = document.getElementById('plex-org-cb');
      if (maxInput) maxInput.value = settings.maxConcurrentDownloads ?? 3;
      if (speedInput) speedInput.value = settings.globalSpeedLimit > 0 ? Math.round(settings.globalSpeedLimit / 1024) : '';
      if (plexInput) plexInput.checked = !!settings.plexOrganization;
      if (settings.selectedUserAgent && uaSelect) {
        const standard = ['Random (Rotator)', 'Chrome', 'Firefox', 'Safari', 'Edge'];
        uaSelect.value = standard.includes(settings.selectedUserAgent) ? settings.selectedUserAgent : 'Custom';
        if (customUaInput && uaSelect.value === 'Custom') customUaInput.value = settings.selectedUserAgent;
        if (customUaGroup) customUaGroup.style.display = uaSelect.value === 'Custom' ? 'grid' : 'none';
      }
      
      const ffmpegBadge = document.getElementById('ffmpeg-status-badge');
      if (ffmpegBadge) {
        if (settings.ffmpegAvailable) {
          ffmpegBadge.textContent = 'Opérationnel';
          ffmpegBadge.className = 'status-badge badge-ok';
        } else {
          ffmpegBadge.textContent = 'Non trouvé (Muxing désactivé)';
          ffmpegBadge.className = 'status-badge badge-err';
        }
      }
    })
    .catch(() => {});

  save?.addEventListener('click', async () => {
    const maxConcurrent = parseInt(document.getElementById('max-concurrent-input')?.value || '3', 10);
    const speedKb = parseInt(document.getElementById('global-speed-input-settings')?.value || '0', 10);
    const plexOrganization = !!document.getElementById('plex-org-cb')?.checked;
    let selectedUserAgent = uaSelect?.value || 'Random (Rotator)';

    if (selectedUserAgent === 'Custom') {
      selectedUserAgent = customUaInput?.value.trim() || '';
      if (!selectedUserAgent) {
        showToast('Veuillez saisir un User-Agent personnalise', 'error');
        return;
      }
    }

    const body = {
      maxConcurrentDownloads: maxConcurrent,
      globalSpeedLimit: speedKb * 1024,
      plexOrganization,
      selectedUserAgent
    };

    try {
      const resp = await fetch('/api/download/settings', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(body)
      });
      const data = await resp.json();
      if (data.success) {
        showToast('Parametres mis a jour', 'success');
      } else {
        showToast(data.error || 'Erreur lors de la mise a jour', 'error');
      }
    } catch (error) {
      showToast('Erreur de connexion', 'error');
    }
  });
}

function loadEpisodeSizes() {
  const nodes = document.querySelectorAll('.episode-size[data-url]');
  if (!nodes.length) return;

  const observer = new IntersectionObserver(entries => {
    entries.forEach(entry => {
      if (!entry.isIntersecting) return;
      const span = entry.target;
      observer.unobserve(span);
      const url = span.dataset.url;
      if (!url) return;

      fetch('/api/episode/size?url=' + encodeURIComponent(url))
        .then(r => r.json())
        .then(data => {
          span.textContent = data.success ? data.formattedSize : '-';
          if (data.success && data.subtitleUrl) {
            const row = span.closest('.episode-item');
            const sub = row?.querySelector('.subtitle-cb-label');
            if (sub) sub.classList.add('is-available');
          }
        })
        .catch(() => { span.textContent = '-'; });
    });
  }, { rootMargin: '160px' });

  nodes.forEach(node => observer.observe(node));
}

function buildVersionFilters() {
  const filterContainer = document.getElementById('version-filter');
  if (!filterContainer) return;

  const items = [...document.querySelectorAll('.episode-item')];
  const versions = [...new Set(items.map(item => item.dataset.version).filter(Boolean))];
  if (versions.length <= 1) return;

  filterContainer.appendChild(createFilterBtn('Tous', true, () => filterEpisodes('ALL')));
  versions.forEach(version => {
    filterContainer.appendChild(createFilterBtn(version, false, () => filterEpisodes(version)));
  });
}

function createFilterBtn(label, active, onClick) {
  const btn = document.createElement('button');
  btn.type = 'button';
  btn.className = 'ver-filter-btn' + (active ? ' active' : '');
  btn.textContent = label;
  btn.addEventListener('click', () => {
    document.querySelectorAll('.ver-filter-btn').forEach(item => item.classList.remove('active'));
    btn.classList.add('active');
    onClick();
  });
  return btn;
}

function filterEpisodes(version) {
  document.querySelectorAll('.episode-item').forEach(item => {
    item.style.display = version === 'ALL' || item.dataset.version === version ? '' : 'none';
  });
}

function onEpisodeCheckChange() {
  const checked = document.querySelectorAll('.episode-check:checked');
  const btn = document.getElementById('dl-selected-btn');
  const info = document.getElementById('selected-count-text');
  if (btn) btn.disabled = checked.length === 0;
  if (info) info.textContent = checked.length + ' selectionne(s)';
}

function toggleSelectAll(checkbox) {
  document.querySelectorAll('.episode-check').forEach(cb => {
    const row = cb.closest('.episode-item');
    if (!row || row.style.display === 'none') return;
    cb.checked = checkbox.checked;
  });
  onEpisodeCheckChange();
}

async function downloadEpisode(btn) {
  const url = btn.dataset.url;
  const epNum = parseInt(btn.dataset.num, 10);
  const subtitleUrl = btn.dataset.subtitle || '';
  const row = btn.closest('.episode-item');
  const downloadSubtitles = row?.querySelector('.subtitle-check')?.checked || false;
  const animeName = typeof ANIME_NAME !== 'undefined' ? ANIME_NAME : 'Anime';
  const source = typeof SOURCE !== 'undefined' ? SOURCE : 'french-manga';
  const fileName = sanitizeFilename(animeName + ' - Episode ' + epNum + '.mp4');

  if (source === 'voir-anime') {
    await handleVoirAnimeDownload(url, animeName, epNum, fileName, subtitleUrl, downloadSubtitles, null);
    return;
  }

  btn.disabled = true;
  try {
    await startDownloadRequest(animeName, epNum, url, fileName, subtitleUrl, null, 0, downloadSubtitles);
  } finally {
    setTimeout(() => { btn.disabled = false; }, 1200);
  }
}

async function handleVoirAnimeDownload(episodeUrl, animeName, epNum, fileName, subtitleUrl, downloadSubtitles, scheduledTime) {
  showToast('Resolution des qualites disponibles...', 'info');
  try {
    const resp = await fetch('/api/voiranime/download-info?episodeUrl=' + encodeURIComponent(episodeUrl));
    const info = await resp.json();
    let qualities = {};
    try { qualities = JSON.parse(info.videoQualities || '{}'); } catch (error) {}

    if (Object.keys(qualities).length > 1) {
      showQualityModal(qualities, animeName, epNum, fileName, subtitleUrl, downloadSubtitles, scheduledTime, info.videoChunks);
    } else {
      await startDownloadRequest(animeName, epNum, info.videoUrl || episodeUrl, fileName, subtitleUrl, scheduledTime, 0, downloadSubtitles);
    }
  } catch (error) {
    showToast('Erreur de resolution: ' + error.message, 'error');
  }
}

function showQualityModal(qualities, animeName, epNum, fileName, subtitleUrl, downloadSubtitles, scheduledTime, chunksJson) {
  const modal = document.getElementById('quality-modal');
  const list = document.getElementById('quality-list');
  if (!modal || !list) return;

  list.innerHTML = '';
  Object.entries(qualities).forEach(([label, url]) => {
    const item = document.createElement('button');
    item.type = 'button';
    item.className = 'quality-item';
    item.innerHTML = `<span class="quality-label">${escapeHtml(label)}</span><span class="quality-download">Telecharger</span>`;
    item.addEventListener('click', async () => {
      modal.style.display = 'none';
      await startDownloadRequest(animeName, epNum, url, label + ' - ' + fileName, subtitleUrl, scheduledTime, 0, downloadSubtitles);
    });
    list.appendChild(item);
  });

  if (chunksJson && chunksJson !== '[]') {
    try {
      const chunks = JSON.parse(chunksJson);
      if (chunks.length > 1) {
        const item = document.createElement('button');
        item.type = 'button';
        item.className = 'quality-item';
        item.innerHTML = `<span class="quality-label">Tous les morceaux (${chunks.length})</span><span class="quality-download">Concatener</span>`;
        item.addEventListener('click', async () => {
          modal.style.display = 'none';
          await startDownloadRequest(animeName, epNum, chunks[0], fileName, subtitleUrl, scheduledTime, 0, downloadSubtitles);
        });
        list.appendChild(item);
      }
    } catch (error) {}
  }

  modal.style.display = 'flex';
}

async function startDownloadRequest(animeName, episodeNumber, url, fileName, subtitleUrl, scheduledTime, speedLimit, downloadSubtitles) {
  const body = { animeName, episodeNumber, url, fileName, downloadSubtitles };
  if (subtitleUrl) body.subtitleUrl = subtitleUrl;
  if (scheduledTime) body.scheduledTime = scheduledTime;
  if (speedLimit) body.speedLimit = String(speedLimit);

  const resp = await fetch('/api/download', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body)
  });
  const data = await resp.json();
  if (data.success) {
    showToast(data.message || 'Telechargement lance', 'success');
  } else {
    showToast(data.error || 'Erreur de telechargement', 'error');
  }
}

async function downloadSelected() {
  const checked = [...document.querySelectorAll('.episode-check:checked')];
  if (!checked.length) return;

  const animeName = typeof ANIME_NAME !== 'undefined' ? ANIME_NAME : 'Anime';
  const source = typeof SOURCE !== 'undefined' ? SOURCE : 'french-manga';
  const scheduledTime = document.getElementById('schedule-input')?.value || '';
  const episodes = checked.map(cb => {
    const row = cb.closest('.episode-item');
    const num = parseInt(row.dataset.num, 10);
    return {
      number: String(num),
      url: row.dataset.url,
      fileName: sanitizeFilename(animeName + ' - Episode ' + num + '.mp4'),
      subtitleUrl: row.dataset.subtitle || '',
      downloadSubtitles: String(row.querySelector('.subtitle-check')?.checked || false),
      scheduledTime
    };
  });

  if (source === 'voir-anime' && episodes.length === 1) {
    await handleVoirAnimeDownload(episodes[0].url, animeName, parseInt(episodes[0].number, 10), episodes[0].fileName, episodes[0].subtitleUrl, episodes[0].downloadSubtitles === 'true', scheduledTime || null);
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
    document.querySelectorAll('.episode-check').forEach(cb => { cb.checked = false; });
    const selectAll = document.getElementById('select-all-cb');
    if (selectAll) selectAll.checked = false;
    onEpisodeCheckChange();
  } else {
    showToast(data.error || 'Erreur batch', 'error');
  }
}

async function playEpisode(btn) {
  const url = btn.dataset.url;
  const epNum = btn.dataset.num;
  const source = typeof SOURCE !== 'undefined' ? SOURCE : 'french-manga';
  const animeName = typeof ANIME_NAME !== 'undefined' ? ANIME_NAME : '';
  const animeUrl = typeof ANIME_URL !== 'undefined' ? ANIME_URL : '';
  const version = btn.closest('.episode-item')?.dataset.version || '';
  const playerSection = document.getElementById('player-section');
  const video = document.getElementById('plyr-player');
  if (!playerSection || !video) return;

  showToast('Resolution du flux video...', 'info');
  try {
    let videoUrl = '';
    let tracks = [];

    const statusResp = await fetch('/api/download/status');
    const tasks = await statusResp.json();
    const localTask = tasks.find(task =>
      (task.animeName || '').toLowerCase() === animeName.toLowerCase() &&
      String(task.episodeNumber) === String(epNum) &&
      task.status === 'COMPLETED'
    );

    if (localTask) {
      videoUrl = '/api/media/stream/' + localTask.id;
      const subsResp = await fetch('/api/media/subtitles/' + localTask.id);
      const subs = await subsResp.json();
      tracks = subs.map(sub => ({ kind: 'captions', label: sub.label, srclang: sub.srclang, src: sub.src, default: sub.srclang === 'fr' }));
    } else if (source === 'voir-anime') {
      const resp = await fetch('/api/voiranime/download-info?episodeUrl=' + encodeURIComponent(url));
      const info = await resp.json();
      videoUrl = info.videoUrl || '';
      if (info.subtitleUrl) tracks.push({ kind: 'captions', label: 'Francais', srclang: 'fr', src: info.subtitleUrl, default: true });
    } else {
      const resp = await fetch('/api/episode/size?url=' + encodeURIComponent(url));
      const info = await resp.json();
      videoUrl = info.directUrl || '';
      if (info.subtitleUrl) tracks.push({ kind: 'captions', label: 'Francais', srclang: 'fr', src: info.subtitleUrl, default: true });
    }

    if (!videoUrl) {
      showToast('Flux video introuvable', 'error');
      return;
    }

    video.innerHTML = `<source src="${videoUrl}" type="video/mp4"/>`;
    tracks.forEach(track => {
      const trackEl = document.createElement('track');
      trackEl.kind = 'subtitles';
      trackEl.label = track.label;
      trackEl.srclang = track.srclang;
      trackEl.src = track.src;
      if (track.default) trackEl.setAttribute('default', '');
      video.appendChild(trackEl);
    });
    video.load();
    playerSection.style.display = 'block';
    playerSection.scrollIntoView({ behavior: 'smooth', block: 'start' });

    if (window.Plyr) {
      if (!plyrInstance) {
        plyrInstance = new Plyr('#plyr-player', {
          controls: ['play', 'progress', 'current-time', 'mute', 'volume', 'captions', 'settings', 'fullscreen'],
          captions: { active: true, update: true, language: 'fr' }
        });
      } else {
        plyrInstance.source = { type: 'video', sources: [{ src: videoUrl, type: 'video/mp4' }], tracks };
      }
      plyrInstance.play();
    } else {
      video.play();
    }

    if (animeUrl) {
      await fetch('/api/progress/watched', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ animeUrl, animeName, episodeNumber: String(epNum), version })
      });
      const row = btn.closest('.episode-item');
      row?.classList.add('ep-watched');
    }
  } catch (error) {
    showToast('Erreur lecteur: ' + error.message, 'error');
  }
}

function closePlayer() {
  document.getElementById('player-section')?.style.setProperty('display', 'none');
  if (plyrInstance) plyrInstance.pause();
}

async function toggleFavorite(btn) {
  const url = btn.dataset.url;
  const title = btn.dataset.title;
  const imageUrl = btn.dataset.image || '';
  const source = btn.dataset.source || 'french-manga';
  const active = btn.classList.contains('active');

  try {
    if (active) {
      await fetch('/api/favorites', {
        method: 'DELETE',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ url })
      });
      btn.classList.remove('active');
      btn.querySelector('span').textContent = 'Ajouter a la bibliotheque';
      showToast('Retire de la bibliotheque', 'info');
    } else {
      await fetch('/api/favorites', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ url, title, imageUrl, source })
      });
      btn.classList.add('active');
      btn.querySelector('span').textContent = 'Dans la bibliotheque';
      showToast('Ajoute a la bibliotheque', 'success');
    }
  } catch (error) {
    showToast('Erreur favoris', 'error');
  }
}

async function checkFavoriteStatus() {
  const favBtn = document.getElementById('fav-btn');
  if (!favBtn) return;
  try {
    const resp = await fetch('/api/favorites/check?url=' + encodeURIComponent(favBtn.dataset.url));
    const data = await resp.json();
    if (data.isFavorite) {
      favBtn.classList.add('active');
      favBtn.querySelector('span').textContent = 'Dans la bibliotheque';
    }
  } catch (error) {}
}

async function toggleAutoDownload(input) {
  try {
    await fetch('/api/favorites/auto-download', {
      method: 'PATCH',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ url: input.dataset.url, autoDownload: input.checked })
    });
    showToast(input.checked ? 'Auto-DL active' : 'Auto-DL desactive', 'info');
  } catch (error) {
    showToast('Erreur Auto-DL', 'error');
  }
}

async function toggleAutoDownloadFromLibrary(input) {
  await toggleAutoDownload(input);
}

async function removeFavorite(btn) {
  if (!confirm('Retirer de la bibliotheque ?')) return;
  try {
    await fetch('/api/favorites', {
      method: 'DELETE',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ url: btn.dataset.url })
    });
    btn.closest('.anime-card')?.remove();
    showToast('Retire de la bibliotheque', 'info');
  } catch (error) {
    showToast('Erreur', 'error');
  }
}

function initSSE() {
  if (sseSource) sseSource.close();
  sseSource = new EventSource('/api/download/stream');
  sseSource.addEventListener('download-update', event => {
    try { updateTaskRow(JSON.parse(event.data)); } catch (error) {}
  });
  sseSource.onerror = () => setTimeout(initSSE, 3000);
}

function updateTaskRow(task) {
  let row = document.getElementById('task-' + task.id);
  const targetParentId = getTargetParentId(task.status);
  const targetParent = document.getElementById(targetParentId);

  if (!row) {
    row = createTaskRow(task);
    targetParent?.prepend(row);
  } else {
    const currentParent = row.parentNode;
    if (currentParent && currentParent.id !== targetParentId && targetParent) {
      targetParent.prepend(row);
    }
  }
  applyTaskData(row, task);
}

function getTargetParentId(status) {
  if (['DOWNLOADING', 'RETRYING', 'PAUSED', 'MUXING'].includes(status)) {
    return 'download-list';
  }
  if (['PENDING', 'SCHEDULED'].includes(status)) {
    return 'queue-list';
  }
  return 'completed-list';
}

function createTaskRow(task) {
  const row = document.createElement('div');
  row.className = 'dl-task-row';
  row.id = 'task-' + task.id;
  row.dataset.id = task.id;
  row.dataset.status = task.status;
  row.innerHTML = `
    <div class="dl-main">
      <div class="dl-cover"></div>
      <div>
        <span class="dl-task-name"></span>
        <span class="dl-subtitle"></span>
      </div>
    </div>
    <div class="dl-progress-wrap">
      <div class="dl-progress-bar"><div class="dl-progress-fill"></div></div>
      <span class="dl-size"></span>
    </div>
    <span class="dl-progress-text"></span>
    <div>
      <span class="dl-speed"></span>
      <span class="dl-error"></span>
    </div>
    <div class="dl-task-actions"></div>`;
  return row;
}

function applyTaskData(row, task) {
  row.dataset.status = task.status;
  row.querySelector('.dl-cover').textContent = (task.animeName || 'A').trim().charAt(0).toUpperCase();
  row.querySelector('.dl-task-name').textContent = task.animeName || 'Anime';
  row.querySelector('.dl-subtitle').textContent = 'Episode ' + task.episodeNumber + ' - ' + (task.fileName || '');
  
  if (['PENDING', 'SCHEDULED'].includes(task.status)) {
    row.classList.add('queue-row');
    row.setAttribute('draggable', 'true');
    let idxEl = row.querySelector('.queue-index');
    if (!idxEl) {
      idxEl = document.createElement('span');
      idxEl.className = 'queue-index';
      row.prepend(idxEl);
    }
    let dotsEl = row.querySelector('.drag-dots');
    if (!dotsEl) {
      dotsEl = document.createElement('span');
      dotsEl.className = 'drag-dots';
      dotsEl.textContent = '::';
      idxEl.after(dotsEl);
    }
    reindexQueueRows();
  } else {
    row.classList.remove('queue-row');
    row.removeAttribute('draggable');
    row.querySelector('.queue-index')?.remove();
    row.querySelector('.drag-dots')?.remove();
  }

  let statusBadge = row.querySelector('.dl-task-status');
  const progressWrap = row.querySelector('.dl-progress-wrap');
  if (['COMPLETED', 'FAILED', 'CANCELLED'].includes(task.status)) {
    if (!statusBadge) {
      statusBadge = document.createElement('span');
      statusBadge.className = 'status-badge dl-task-status';
      row.insertBefore(statusBadge, progressWrap || row.querySelector('.dl-progress-text'));
    }
    statusBadge.textContent = translateStatus(task.status);
    statusBadge.className = 'status-badge dl-task-status ' + statusClass(task.status);
    if (progressWrap) progressWrap.style.display = 'none';
  } else {
    statusBadge?.remove();
    if (progressWrap) progressWrap.style.display = '';
  }

  const fill = row.querySelector('.dl-progress-fill');
  if (fill) fill.style.width = (task.progress || 0) + '%';
  
  const text = row.querySelector('.dl-progress-text');
  if (text) text.textContent = Number(task.progress || 0).toFixed(1) + '%';
  
  const size = row.querySelector('.dl-size');
  if (size) {
    if (task.status === 'SCHEDULED' && task.scheduledStartTime) {
      size.innerHTML = `<span class="dl-scheduled-info" data-time="${task.scheduledStartTime}">Planifié : ${formatScheduledTime(task.scheduledStartTime)}</span>`;
    } else {
      size.textContent = (task.downloadedSize || '0 B') + ' / ' + (task.totalSize || '0 B');
    }
  }
  
  const speed = row.querySelector('.dl-speed');
  if (speed) speed.textContent = task.speed || '';
  
  const error = row.querySelector('.dl-error');
  if (error) error.textContent = task.error || '';
  
  let limitContainer = row.querySelector('.dl-speed-limit-container');
  if (['DOWNLOADING', 'RETRYING', 'PAUSED', 'MUXING'].includes(task.status)) {
    if (!limitContainer) {
      limitContainer = document.createElement('div');
      limitContainer.className = 'dl-speed-limit-container';
      limitContainer.style.marginTop = '0.25rem';
      speed?.parentNode?.appendChild(limitContainer);
    }
    const limitKb = task.maxSpeedLimit > 0 ? Math.round(task.maxSpeedLimit / 1024) : 0;
    if (limitKb > 0) {
      limitContainer.innerHTML = `<span class="dl-speed-limit-badge">
        Max: ${limitKb} KB/s
        <button class="speed-limit-btn" onclick="changeTaskSpeedLimit('${task.id}')">Modifier</button>
      </span>`;
    } else {
      limitContainer.innerHTML = `<button class="speed-limit-btn" onclick="changeTaskSpeedLimit('${task.id}')">Limiter</button>`;
    }
  } else {
    limitContainer?.remove();
  }
  
  row.querySelector('.dl-task-actions').innerHTML = renderTaskActions(task);
  refreshDownloadStats();
  refreshDownloadBadge();
}

function renderTaskActions(task) {
  const id = task.id;
  const animeName = task.animeName || '';
  const safeAnime = animeName.replace(/`/g, '\\`').replace(/\$/g, '\\$');
  const upIcon = '<svg viewBox="0 0 24 24"><path d="M12 19V5"/><path d="m5 12 7-7 7 7"/></svg>';
  const downIcon = '<svg viewBox="0 0 24 24"><path d="M12 5v14"/><path d="m19 12-7 7-7-7"/></svg>';
  const pauseIcon = '<svg viewBox="0 0 24 24"><path d="M8 5v14M16 5v14"/></svg>';
  const playIcon = '<svg viewBox="0 0 24 24"><path d="M7 4v16l13-8Z"/></svg>';
  const xIcon = '<svg viewBox="0 0 24 24"><path d="M18 6 6 18M6 6l12 12"/></svg>';
  const trashIcon = '<svg viewBox="0 0 24 24"><path d="M3 6h18"/><path d="M8 6V4h8v2"/><path d="m19 6-1 14H6L5 6"/></svg>';
  const retryIcon = '<svg viewBox="0 0 24 24"><path d="M21 12a9 9 0 1 1-3-6.7"/><path d="M21 3v6h-6"/></svg>';

  if (task.status === 'DOWNLOADING' || task.status === 'RETRYING' || task.status === 'MUXING') {
    return `<button class="btn btn-icon" title="Pause" onclick="apiPost('/api/download/${id}/pause')">${pauseIcon}</button>
            <button class="btn btn-icon btn-danger" title="Annuler" onclick="cancelTask('${id}')">${xIcon}</button>`;
  }
  if (task.status === 'PAUSED') {
    return `<button class="btn btn-icon btn-primary" title="Reprendre" onclick="apiPost('/api/download/${id}/resume')">${playIcon}</button>
            <button class="btn btn-icon btn-danger" title="Annuler" onclick="cancelTask('${id}')">${xIcon}</button>`;
  }
  if (task.status === 'PENDING' || task.status === 'SCHEDULED') {
    return `<button class="btn btn-icon" title="Monter" onclick="moveTaskToTop('${id}')">${upIcon}</button>
            <button class="btn btn-icon" title="Descendre" disabled>${downIcon}</button>
            ${animeName ? `<button class="btn btn-icon" title="Prioriser la serie" onclick="moveSeriesToTop(\`${safeAnime}\`)"><svg viewBox="0 0 24 24"><path d="M8 6h13"/><path d="M8 12h13"/><path d="M8 18h13"/><path d="M3 6h.01"/><path d="M3 12h.01"/><path d="M3 18h.01"/></svg></button>` : ''}
            <button class="btn btn-icon btn-danger" title="Annuler" onclick="cancelTask('${id}')">${xIcon}</button>`;
  }
  if (task.status === 'FAILED' || task.status === 'CANCELLED') {
    return `<button class="btn btn-icon btn-primary" title="Reessayer" onclick="apiPost('/api/download/${id}/resume')">${retryIcon}</button>
            <button class="btn btn-icon" title="Retirer" onclick="removeTask('${id}')">${trashIcon}</button>`;
  }
  if (task.status === 'COMPLETED') {
    return `<button class="btn btn-icon btn-primary" title="Lire" onclick="playDownloadedTask('${id}', \`${safeAnime}\`, ${task.episodeNumber})">${playIcon}</button>
            <button class="btn btn-icon" title="Retirer" onclick="removeTask('${id}')">${trashIcon}</button>`;
  }
  return '';
}

function translateStatus(status) {
  const labels = {
    PENDING: 'En attente',
    SCHEDULED: 'Planifie',
    DOWNLOADING: 'Telechargement',
    RETRYING: 'Nouvelle tentative',
    PAUSED: 'Pause',
    COMPLETED: 'Termine',
    FAILED: 'Echec',
    CANCELLED: 'Annule',
    MUXING: 'Muxing sous-titres'
  };
  return labels[status] || status;
}

function statusClass(status) {
  if (status === 'COMPLETED') return 'badge-ok';
  if (status === 'FAILED' || status === 'CANCELLED') return 'badge-err';
  if (status === 'MUXING') return 'badge-warn';
  return 'badge-other';
}

async function apiPost(url, body) {
  await fetch(url, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: body ? JSON.stringify(body) : undefined
  });
  setTimeout(refreshDownloadStats, 250);
}

async function cancelTask(id) {
  await fetch('/api/download/' + id, { method: 'DELETE' });
  setTimeout(refreshDownloadStats, 250);
}

async function removeTask(id) {
  await fetch('/api/download/' + id + '/remove', { method: 'DELETE' });
  document.getElementById('task-' + id)?.remove();
  refreshDownloadStats();
  refreshDownloadBadge();
}

async function moveTaskToTop(id) {
  try {
    const resp = await fetch('/api/download/' + id + '/move-to-top', { method: 'POST' });
    const data = await resp.json();
    showToast(data.message || 'Priorite mise a jour', data.success ? 'success' : 'error');
    if (data.success) setTimeout(() => window.location.reload(), 500);
  } catch (error) {
    showToast('Erreur reseau', 'error');
  }
}

async function moveSeriesToTop(animeName) {
  try {
    const resp = await fetch('/api/download/move-series-to-top', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ animeName })
    });
    const data = await resp.json();
    showToast(data.message || 'Serie priorisee', data.success ? 'success' : 'error');
    if (data.success) setTimeout(() => window.location.reload(), 500);
  } catch (error) {
    showToast('Erreur reseau', 'error');
  }
}

function filterDownloadView(btn) {
  document.querySelectorAll('[data-download-filter]').forEach(item => item.classList.remove('active'));
  btn.classList.add('active');
  const filter = btn.dataset.downloadFilter;
  document.querySelectorAll('[data-download-group]').forEach(group => {
    group.style.display = filter === 'all' || group.dataset.downloadGroup === filter ? '' : 'none';
  });
}

function refreshDownloadStats() {
  const rows = [...document.querySelectorAll('.dl-task-row[data-status]')];
  const active = rows.filter(row => ['DOWNLOADING', 'RETRYING'].includes(row.dataset.status)).length;
  const queued = rows.filter(row => ['PENDING', 'SCHEDULED'].includes(row.dataset.status)).length;
  const speeds = rows
    .filter(row => ['DOWNLOADING', 'RETRYING'].includes(row.dataset.status))
    .map(row => row.querySelector('.dl-speed')?.textContent.trim())
    .filter(Boolean);

  setText('stat-active', active);
  setText('stat-queued', queued);
  setText('stat-speed', speeds[0] || '0 B/s');
  setText('download-count-badge', active + queued);
}

function refreshDownloadBadge() {
  if (!document.getElementById('download-count-badge')) return;
  fetch('/api/download/status')
    .then(r => r.json())
    .then(tasks => {
      const count = tasks.filter(task => ['DOWNLOADING', 'RETRYING', 'PENDING', 'SCHEDULED', 'PAUSED'].includes(task.status)).length;
      setText('download-count-badge', count);
    })
    .catch(() => {});
}

function setText(id, value) {
  const el = document.getElementById(id);
  if (el) el.textContent = value;
}

function showToast(message, type = 'info') {
  const container = document.getElementById('toast-container');
  if (!container) return;
  const toast = document.createElement('div');
  toast.className = 'toast ' + type;
  toast.textContent = message;
  container.appendChild(toast);
  setTimeout(() => toast.remove(), 4000);
}

function sanitizeFilename(name) {
  return name.replace(/[<>:"/\\|?*]/g, '_').replace(/\s+/g, ' ').trim();
}

function escapeHtml(value) {
  return String(value)
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#039;');
}

// Drag and Drop support
function initDragAndDrop() {
  const queueList = document.getElementById('queue-list');
  if (!queueList) return;

  let dragEl = null;

  queueList.addEventListener('dragstart', (e) => {
    const row = e.target.closest('.queue-row');
    if (!row) return;
    dragEl = row;
    e.dataTransfer.effectAllowed = 'move';
    row.classList.add('dragging');
  });

  queueList.addEventListener('dragover', (e) => {
    e.preventDefault();
    const row = e.target.closest('.queue-row');
    if (!row || row === dragEl) return;
    
    const bounding = row.getBoundingClientRect();
    const offset = e.clientY - bounding.top;
    if (offset > bounding.height / 2) {
      row.after(dragEl);
    } else {
      row.before(dragEl);
    }
  });

  queueList.addEventListener('dragend', async (e) => {
    const row = e.target.closest('.queue-row');
    if (row) row.classList.remove('dragging');
    dragEl = null;

    reindexQueueRows();

    const orderedIds = [...queueList.querySelectorAll('.queue-row')].map(r => r.dataset.id);
    
    try {
      const resp = await fetch('/api/download/reorder', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(orderedIds)
      });
      const data = await resp.json();
      if (data.success) {
        showToast('File d\'attente réorganisée', 'success');
      } else {
        showToast(data.error || 'Erreur lors du réordonnancement', 'error');
      }
    } catch (err) {
      showToast('Erreur de connexion', 'error');
    }
  });
}

function reindexQueueRows() {
  const queueList = document.getElementById('queue-list');
  if (!queueList) return;
  queueList.querySelectorAll('.queue-row').forEach((row, idx) => {
    const idxEl = row.querySelector('.queue-index');
    if (idxEl) idxEl.textContent = idx + 1;
  });
}

// Speed limit change
async function changeTaskSpeedLimit(id) {
  const currentLimitStr = prompt("Vitesse maximale pour cette tâche en KB/s (0 pour illimitée) :");
  if (currentLimitStr === null) return;

  const limitKb = parseInt(currentLimitStr, 10);
  if (isNaN(limitKb) || limitKb < 0) {
    showToast("Vitesse invalide", "error");
    return;
  }

  const limitBytes = limitKb * 1024;
  try {
    const resp = await fetch(`/api/download/${id}/speed-limit`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ limit: limitBytes })
    });
    const data = await resp.json();
    if (data.success) {
      showToast("Limite de vitesse mise à jour", "success");
    } else {
      showToast(data.error || "Erreur", "error");
    }
  } catch (err) {
    showToast("Erreur de connexion", "error");
  }
}

// Scheduled count down
function formatScheduledTime(isoString) {
  try {
    const target = new Date(isoString);
    const now = new Date();
    const diffMs = target - now;
    if (diffMs <= 0) return "Démarrage imminent...";
    
    const diffSecs = Math.floor(diffMs / 1000);
    const hours = Math.floor(diffSecs / 3600);
    const mins = Math.floor((diffSecs % 3600) / 60);
    const secs = diffSecs % 60;
    
    let parts = [];
    if (hours > 0) parts.push(hours + "h");
    if (mins > 0 || hours > 0) parts.push(mins + "m");
    parts.push(secs + "s");
    
    const timeFormatted = target.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
    return `Démarre à ${timeFormatted} (dans ${parts.join(' ')})`;
  } catch (e) {
    return isoString;
  }
}

function initScheduledTicker() {
  setInterval(() => {
    document.querySelectorAll('.dl-scheduled-info[data-time]').forEach(el => {
      el.textContent = formatScheduledTime(el.dataset.time);
    });
  }, 1000);
}

// Delegated listener for Thymeleaf-rendered play buttons (data-action="play")
function bindPlayActionButtons() {
  document.addEventListener('click', (e) => {
    // Play button
    const playBtn = e.target.closest('[data-action="play"]');
    if (playBtn) {
      const taskId = playBtn.dataset.taskId;
      if (taskId) playDownloadedTask(taskId);
      return;
    }
    // Delete history entry button
    const delBtn = e.target.closest('[data-action="delete-history"]');
    if (delBtn) {
      const taskId = delBtn.dataset.taskId;
      if (taskId) deleteHistoryEntry(taskId, delBtn);
    }
  });
}

// =========================================================================
// Nettoyage historique & bibliothèque
// =========================================================================

async function deleteHistoryEntry(taskId, triggerEl) {
  try {
    const resp = await fetch('/api/history/' + taskId, { method: 'DELETE' });
    const data = await resp.json();
    if (data.success) {
      // Retire la ligne du DOM (table row ou history-item)
      const row = triggerEl?.closest('tr') || triggerEl?.closest('.history-item');
      row?.remove();
      showToast('Entree supprimee', 'success');
    } else {
      showToast(data.error || 'Erreur', 'error');
    }
  } catch (err) {
    showToast('Erreur de connexion', 'error');
  }
}

async function clearHistory(statusFilter) {
  const msg = statusFilter
    ? 'Supprimer toutes les entrees avec ce statut ?'
    : 'Vider completement l\'historique ? Cette action est irreversible.';
  if (!confirm(msg)) return;

  let url = '/api/history';
  if (statusFilter) url += '?status=' + encodeURIComponent(statusFilter);

  try {
    const resp = await fetch(url, { method: 'DELETE' });
    const data = await resp.json();
    if (data.success) {
      showToast(data.message || 'Historique vide', 'success');
      setTimeout(() => window.location.reload(), 600);
    } else {
      showToast(data.error || 'Erreur', 'error');
    }
  } catch (err) {
    showToast('Erreur de connexion', 'error');
  }
}

async function clearHistoryByStatus(statuses) {
  await clearHistory(statuses);
}

async function clearAllFavorites() {
  if (!confirm('Retirer tous les favoris de la bibliotheque ? Cette action est irreversible.')) return;
  try {
    const resp = await fetch('/api/favorites/all', { method: 'DELETE' });
    const data = await resp.json();
    if (data.success) {
      showToast(data.message || 'Bibliotheque videe', 'success');
      const grid = document.getElementById('favorites-grid');
      if (grid) grid.innerHTML = '';
      setTimeout(() => window.location.reload(), 600);
    } else {
      showToast(data.error || 'Erreur', 'error');
    }
  } catch (err) {
    showToast('Erreur de connexion', 'error');
  }
}

// Direct play from downloads page
async function playDownloadedTask(taskId, animeName, episodeNumber) {
  const playerSection = document.getElementById('player-section');
  const video = document.getElementById('plyr-player');
  if (!playerSection || !video) return;

  showToast('Résolution du flux vidéo local...', 'info');
  try {
    const videoUrl = '/api/media/stream/' + taskId;
    const subsResp = await fetch('/api/media/subtitles/' + taskId);
    let tracks = [];
    try {
      const subs = await subsResp.json();
      tracks = subs.map(sub => ({ kind: 'captions', label: sub.label, srclang: sub.srclang, src: sub.src, default: sub.srclang === 'fr' }));
    } catch (e) {
      console.log("No subtitles found or error fetching subs", e);
    }

    video.innerHTML = `<source src="${videoUrl}" type="video/mp4"/>`;
    tracks.forEach(track => {
      const trackEl = document.createElement('track');
      trackEl.kind = 'subtitles';
      trackEl.label = track.label;
      trackEl.srclang = track.srclang;
      trackEl.src = track.src;
      if (track.default) trackEl.setAttribute('default', '');
      video.appendChild(trackEl);
    });
    video.load();
    playerSection.style.display = 'block';
    playerSection.scrollIntoView({ behavior: 'smooth', block: 'start' });

    if (window.Plyr) {
      if (!plyrInstance) {
        plyrInstance = new Plyr('#plyr-player', {
          controls: ['play', 'progress', 'current-time', 'mute', 'volume', 'captions', 'settings', 'fullscreen'],
          captions: { active: true, update: true, language: 'fr' }
        });
      } else {
        plyrInstance.source = { type: 'video', sources: [{ src: videoUrl, type: 'video/mp4' }], tracks };
      }
      plyrInstance.play();
    } else {
      video.play();
    }
  } catch (error) {
    showToast('Erreur lecteur: ' + error.message, 'error');
  }
}
