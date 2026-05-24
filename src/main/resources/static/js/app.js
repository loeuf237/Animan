'use strict';

// ---- Toast ----
function showToast(message, type = 'info', duration = 4000) {
  const c = document.getElementById('toast-container');
  if (!c) return;
  const icons = { success: '✅', error: '❌', info: '💬' };
  const t = document.createElement('div');
  t.className = `toast ${type}`;
  t.innerHTML = `<span class="toast-icon">${icons[type]||'💬'}</span><span class="toast-msg">${message}</span><button class="toast-close" onclick="this.parentElement.remove()">✕</button>`;
  c.appendChild(t);
  setTimeout(() => { t.style.animation = 'fadeOut .3s ease forwards'; setTimeout(() => t.remove(), 300); }, duration);
}

// ---- Nav Badge ----
function updateNavBadge(count) {
  const b = document.getElementById('nav-dl-count');
  if (!b) return;
  if (count > 0) { b.textContent = count; b.style.display = 'inline-block'; }
  else { b.style.display = 'none'; }
}

function refreshNavBadge() {
  fetch('/api/download/status').then(r => r.json()).then(tasks => {
    updateNavBadge(tasks.filter(t => t.status === 'DOWNLOADING' || t.status === 'PENDING' || t.status === 'RETRYING').length);
  }).catch(() => {});
}

// ---- Notification System ----
let lastKnownStatus = {};

function requestNotificationPermission() {
  if (typeof Notification !== 'undefined' && Notification.permission === 'default') {
    Notification.requestPermission().then(permission => {
      if (permission === 'granted') {
        showToast('Notifications de bureau activées ! 🔔', 'success');
      }
    });
  }
}

function sendDesktopNotification(title, body) {
  if (typeof Notification !== 'undefined' && Notification.permission === 'granted') {
    try {
      new Notification(title, {
        body: body,
        icon: '/favicon.ico'
      });
    } catch (e) {
      console.warn('Desktop notification failed: ', e);
    }
  }
}

// ---- Global Speed Limit ----
function setGlobalSpeedLimit(limitBytes) {
  fetch('/api/download/limit-speed', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ limit: limitBytes })
  })
  .then(r => r.json())
  .then(data => {
    if (data.success) {
      showToast(`Limite globale de vitesse mise à jour ! 🚀`, 'success');
      // Sync speed limit selector in settings modal as well
      const modalSelect = document.getElementById('global-speed-limit');
      if (modalSelect) modalSelect.value = limitBytes;
    } else {
      showToast(data.error || 'Erreur', 'error');
    }
  })
  .catch(err => showToast('Erreur lors du réglage de la vitesse : ' + err.message, 'error'));
}

// ---- Episode Download ----
function downloadEpisode(btn) {
  const url = btn.dataset.url;
  const num = parseInt(btn.dataset.num);
  const title = btn.dataset.title || ('Épisode ' + num);
  const subtitleUrl = btn.dataset.subtitle || '';
  const animeName = typeof ANIME_NAME !== 'undefined' ? ANIME_NAME : 'Anime';

  if (!url) { showToast('URL non disponible pour cet épisode.', 'error'); return; }

  // Check subtitles choice
  const row = btn.closest('.episode-item');
  const subtitleCheck = row ? row.querySelector('.subtitle-check') : null;
  const downloadSubtitles = subtitleCheck ? subtitleCheck.checked : false;

  // Lecture des options de planification si présentes
  let scheduledTime = null;
  const scheduleInput = document.getElementById('schedule-input');
  if (scheduleInput && scheduleInput.value) {
    scheduledTime = scheduleInput.value; // Format: YYYY-MM-DDTHH:MM (compatible LocalDateTime)
  }

  btn.disabled = true;
  btn.innerHTML = '<div class="btn-spinner" style="width:14px;height:14px;border:2px solid rgba(255,255,255,.3);border-top-color:#fff;border-radius:50%;animation:spin .6s linear infinite"></div>';

  fetch('/api/download', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      animeName, episodeNumber: String(num), url,
      fileName: sanitizeName(animeName) + ' - Episode ' + num + '.mp4',
      subtitleUrl,
      scheduledTime,
      downloadSubtitles: String(downloadSubtitles)
    })
  })
  .then(r => r.json())
  .then(data => {
    if (data.success) {
      const msg = scheduledTime ? `Planifié pour : ${scheduledTime.replace('T', ' ')}` : `Téléchargement démarré : Épisode ${num}`;
      showToast(msg, 'success');
      btn.innerHTML = '✅'; refreshNavBadge();
      if (scheduleInput) scheduleInput.value = ''; // Reset input
    } else {
      showToast(data.error || 'Erreur.', 'error'); btn.disabled = false;
      btn.innerHTML = '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"/><polyline points="7 10 12 15 17 10"/><line x1="12" y1="15" x2="12" y2="3"/></svg>';
    }
  })
  .catch(err => {
    showToast('Erreur réseau : ' + err.message, 'error'); btn.disabled = false;
    btn.innerHTML = '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"/><polyline points="7 10 12 15 17 10"/><line x1="12" y1="15" x2="12" y2="3"/></svg>';
  });
}

// ---- Checkbox Selection ----
function toggleSelectAll(masterCb) {
  document.querySelectorAll('.episode-check:not(:disabled)').forEach(cb => {
    cb.checked = masterCb.checked;
    const row = cb.closest('.episode-item');
    if (row) row.classList.toggle('selected', cb.checked);
  });
  updateSelectedCount();
}

function onEpisodeCheckChange() {
  const all = document.querySelectorAll('.episode-check:not(:disabled)');
  const checked = document.querySelectorAll('.episode-check:checked:not(:disabled)');
  const master = document.getElementById('select-all-cb');
  all.forEach(cb => { const r = cb.closest('.episode-item'); if (r) r.classList.toggle('selected', cb.checked); });
  if (master) { master.indeterminate = checked.length > 0 && checked.length < all.length; master.checked = checked.length === all.length && all.length > 0; }
  updateSelectedCount();
}

function updateSelectedCount() {
  const checked = document.querySelectorAll('.episode-check:checked:not(:disabled)');
  const ct = document.getElementById('selected-count-text');
  const btn = document.getElementById('dl-selected-btn');
  if (ct) ct.textContent = `${checked.length} sélectionné(s)`;
  if (btn) btn.disabled = checked.length === 0;
}

// ---- Batch Download ----
function downloadSelected() {
  const checked = document.querySelectorAll('.episode-check:checked');
  if (checked.length === 0) { showToast('Aucun épisode sélectionné.', 'error'); return; }

  const animeName = typeof ANIME_NAME !== 'undefined' ? ANIME_NAME : 'Anime';
  const episodes = [];

  // Lecture des options de planification si présentes
  let scheduledTime = null;
  const scheduleInput = document.getElementById('schedule-input');
  if (scheduleInput && scheduleInput.value) {
    scheduledTime = scheduleInput.value;
  }

  checked.forEach(cb => {
    const row = cb.closest('.episode-item');
    if (!row) return;
    const url = row.dataset.url;
    const num = row.dataset.num;
    const subtitleUrl = row.dataset.subtitle || '';
    
    // Check if subtitles selection is checked
    const subtitleCheck = row.querySelector('.subtitle-check');
    const downloadSubtitles = subtitleCheck ? subtitleCheck.checked : false;

    if (url) {
      episodes.push({ 
        number: num, 
        url, 
        fileName: sanitizeName(animeName) + ' - Episode ' + num + '.mp4', 
        subtitleUrl,
        scheduledTime,
        downloadSubtitles: String(downloadSubtitles)
      });
    }
  });

  if (episodes.length === 0) { showToast('Aucune URL disponible.', 'error'); return; }

  const dlBtn = document.getElementById('dl-selected-btn');
  if (dlBtn) { dlBtn.disabled = true; dlBtn.innerHTML = '<div class="btn-spinner" style="width:14px;height:14px;border:2px solid rgba(255,255,255,.3);border-top-color:#fff;border-radius:50%;animation:spin .6s linear infinite"></div> Démarrage…'; }

  fetch('/api/download/batch', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ animeName, episodes })
  })
  .then(r => r.json())
  .then(data => {
    if (data.success) { 
      const msg = scheduledTime ? `${data.count} tâche(s) planifiée(s) avec succès ! 📅` : `${data.count} téléchargement(s) démarré(s) !`;
      showToast(msg, 'success'); 
      refreshNavBadge(); 
      if (scheduleInput) scheduleInput.value = '';
    }
    else { showToast(data.error || 'Erreur.', 'error'); }
  })
  .catch(err => showToast('Erreur : ' + err.message, 'error'))
  .finally(() => {
    if (dlBtn) {
      dlBtn.disabled = false;
      dlBtn.innerHTML = '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"/><polyline points="7 10 12 15 17 10"/><line x1="12" y1="15" x2="12" y2="3"/></svg> Télécharger la sélection';
    }
  });
}

// ---- Downloads Page ----
let sseSource = null;

function initDownloadsPage() {
  if (!document.getElementById('downloads-list')) return;
  connectSSE();
  fetchAndRenderAll();
  
  // Sync global speed selector from API
  fetch('/api/download/settings')
    .then(r => r.json())
    .then(data => {
      const globSelect = document.getElementById('global-speed-select');
      if (globSelect) globSelect.value = data.globalSpeedLimit;
    })
    .catch(() => {});
  
  // Demander la permission de notification au chargement de la page de téléchargement
  requestNotificationPermission();
}

function connectSSE() {
  if (sseSource) sseSource.close();
  sseSource = new EventSource('/api/download/stream');
  sseSource.addEventListener('download-update', (e) => {
    try { 
      const t = JSON.parse(e.data); 
      handleStatusChangeNotification(t);
      updateOrCreateTaskCard(t); 
      updateStats(); 
    } catch (_) {}
  });
  sseSource.onerror = () => { setTimeout(connectSSE, 3000); };
}

function handleStatusChangeNotification(task) {
  const lastStatus = lastKnownStatus[task.id];
  const newStatus = task.status;
  
  if (lastStatus && lastStatus !== newStatus) {
    if (newStatus === 'COMPLETED') {
      sendDesktopNotification(
        `Téléchargement Complété ! 🎉`,
        `${task.animeName} – Épisode ${task.episodeNumber} est maintenant disponible sur votre disque dur.`
      );
      showToast(`Téléchargement terminé : ${task.animeName} Ep ${task.episodeNumber} !`, 'success');
    } else if (newStatus === 'FAILED') {
      sendDesktopNotification(
        `Échec du téléchargement ❌`,
        `Une erreur s'est produite lors du téléchargement de l'épisode ${task.episodeNumber} de ${task.animeName}.`
      );
      showToast(`Échec du téléchargement pour ${task.animeName} Ep ${task.episodeNumber}`, 'error');
    }
  }
  // Enregistrer le statut courant
  lastKnownStatus[task.id] = newStatus;
}

function fetchAndRenderAll() {
  fetch('/api/download/status').then(r => r.json()).then(tasks => {
    tasks.forEach(t => {
      lastKnownStatus[t.id] = t.status;
      updateOrCreateTaskCard(t);
    });
    updateStats();
  }).catch(() => {});
}

function updateOrCreateTaskCard(task) {
  const list = document.getElementById('downloads-list');
  if (!list) return;
  
  let card = document.getElementById('task-' + task.id);
  const isCompleted = task.status === 'COMPLETED';
  const isMkv = task.fileName ? task.fileName.endsWith('.mkv') : false;
  const sc = 'status-' + task.status.toLowerCase();

  // Normalisation des métriques (support REST et SSE)
  const dlSize = task.downloadedSize || task.formattedDownloadedSize || '0 B';
  const totSize = task.totalSize || task.formattedTotalSize || 'Taille inconnue';
  const speedVal = (typeof task.speed === 'string' ? task.speed : null) || task.formattedSpeed || '0 B/s';

  if (!card) {
    card = document.createElement('div');
    card.id = 'task-' + task.id;
    card.className = `task-card ${sc}`;
    card.innerHTML = `
      <div class="task-header">
        <div class="task-info">
          <div class="task-name">${escHtml(task.animeName)} – Épisode ${task.episodeNumber}</div>
          <div class="task-meta">
            <span class="task-status-badge">${task.status}</span>
            <span class="task-filename">${escHtml(task.fileName)}</span>
            <span class="task-error-text" style="color:#f87171; display:${task.error ? 'inline-block' : 'none'};">${escHtml(task.error || '')}</span>
          </div>
        </div>
        <div class="task-actions"></div>
      </div>
      <div class="progress-section">
        <div class="progress-info">
          <span class="progress-sizes">${dlSize} / ${totSize}</span>
          <span class="progress-speed-wrap">
            <span id="speed-${task.id}">${speedVal}</span>
            <span style="color:rgba(255,255,255,0.3);font-size:0.7rem;">|</span>
            <select class="task-speed-select" data-id="${task.id}" onchange="setTaskSpeedLimit('${task.id}', this.value)" style="background:transparent;border:none;color:var(--text-secondary);font-size:0.75rem;font-weight:600;cursor:pointer;outline:none;padding:0;">
              <option value="0" ${task.maxSpeedLimit === 0 ? 'selected' : ''} style="background:var(--bg-card);color:#fff;">Illimité</option>
              <option value="524288" ${task.maxSpeedLimit === 524288 ? 'selected' : ''} style="background:var(--bg-card);color:#fff;">512 KB/s</option>
              <option value="1048576" ${task.maxSpeedLimit === 1048576 ? 'selected' : ''} style="background:var(--bg-card);color:#fff;">1 MB/s</option>
              <option value="2097152" ${task.maxSpeedLimit === 2097152 ? 'selected' : ''} style="background:var(--bg-card);color:#fff;">2 MB/s</option>
              <option value="5242880" ${task.maxSpeedLimit === 5242880 ? 'selected' : ''} style="background:var(--bg-card);color:#fff;">5 MB/s</option>
            </select>
          </span>
          <span class="progress-percent">${task.progress.toFixed(1)}%</span>
        </div>
        <div class="progress-bar-track">
          <div class="progress-bar-fill ${task.status === 'DOWNLOADING' || task.status === 'RETRYING' ? 'pulse' : ''}" style="width:${task.progress}%"></div>
        </div>
      </div>
      <div class="task-links-accordion" style="margin-top:12px;border-top:1px solid rgba(255,255,255,0.06);padding-top:10px;">
        <button class="btn-accordion-toggle" onclick="toggleAccordion(this)" style="background:none;border:none;color:var(--text-secondary);font-size:0.78rem;font-weight:600;display:flex;align-items:center;gap:6px;cursor:pointer;padding:0;outline:none;width:100%;text-align:left;">
          <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" class="arrow-icon" style="transition:transform 0.2s;"><polyline points="6 9 12 15 18 9"/></svg>
          Afficher les liens originaux & directs
        </button>
        <div class="accordion-content" style="display:none;flex-direction:column;gap:10px;margin-top:10px;padding:4px 0;">
          <div class="link-field" style="display:flex;flex-direction:column;gap:5px;">
            <span style="font-size:0.72rem;color:var(--text-muted);font-weight:600;">Page de téléchargement source</span>
            <div style="display:flex;gap:8px;align-items:center;">
              <input type="text" value="${escHtml(task.downloadPageUrl || '')}" readonly style="flex:1;background:rgba(0,0,0,0.25);border:1px solid rgba(255,255,255,0.08);border-radius:8px;padding:6px 10px;font-size:0.75rem;color:var(--text-secondary);outline:none;font-family:monospace;" />
              <button class="btn btn-secondary btn-icon" onclick="copyLink(this)" style="padding:6px 8px;border-radius:8px;background:rgba(255,255,255,0.04);" title="Copier le lien source">
                <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><rect x="9" y="9" width="13" height="13" rx="2" ry="2"/><path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1"/></svg>
              </button>
            </div>
          </div>
          <div class="link-field" style="display:flex;flex-direction:column;gap:5px;">
            <span style="font-size:0.72rem;color:var(--text-muted);font-weight:600;">Flux vidéo direct (.mp4)</span>
            <div style="display:flex;gap:8px;align-items:center;">
              <input type="text" id="direct-url-${task.id}" value="${escHtml(task.directDownloadUrl || 'Lien direct en cours de résolution...')}" readonly style="flex:1;background:rgba(0,0,0,0.25);border:1px solid rgba(255,255,255,0.08);border-radius:8px;padding:6px 10px;font-size:0.75rem;color:var(--text-secondary);outline:none;font-family:monospace;" />
              <button class="btn btn-secondary btn-icon" onclick="copyLink(this)" style="padding:6px 8px;border-radius:8px;background:rgba(255,255,255,0.04);" title="Copier le lien direct">
                <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><rect x="9" y="9" width="13" height="13" rx="2" ry="2"/><path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1"/></svg>
              </button>
            </div>
          </div>
        </div>
      </div>
    `;
    list.appendChild(card);
  }

  // Update classes
  card.className = `task-card ${sc}`;

  // Update text elements safely to preserve interactive focus/ accordion states
  const badge = card.querySelector('.task-status-badge');
  if (badge && badge.textContent !== task.status) badge.textContent = task.status;

  const filename = card.querySelector('.task-filename');
  if (filename && filename.textContent !== task.fileName) filename.textContent = task.fileName;

  const errorEl = card.querySelector('.task-error-text');
  if (errorEl) {
    if (task.error) {
      errorEl.textContent = task.error;
      errorEl.style.display = 'inline-block';
    } else {
      errorEl.style.display = 'none';
    }
  }

  const sizes = card.querySelector('.progress-sizes');
  if (sizes) sizes.textContent = `${dlSize} / ${totSize}`;

  const speed = card.querySelector(`#speed-${task.id}`);
  if (speed && speed.textContent !== speedVal) speed.textContent = speedVal;

  const percent = card.querySelector('.progress-percent');
  if (percent) percent.textContent = `${task.progress.toFixed(1)}%`;

  const bar = card.querySelector('.progress-bar-fill');
  if (bar) {
    bar.style.width = `${task.progress}%`;
    if (task.status === 'DOWNLOADING' || task.status === 'RETRYING') {
      bar.classList.add('pulse');
    } else {
      bar.classList.remove('pulse');
    }
  }

  // Update speed selector or schedule info
  const speedWrap = card.querySelector('.progress-speed-wrap');
  if (speedWrap) {
    if (task.status === 'SCHEDULED' && task.scheduledStartTime) {
      speedWrap.style.display = 'none';
      let schedEl = card.querySelector('.progress-scheduled-text');
      if (!schedEl) {
        schedEl = document.createElement('span');
        schedEl.className = 'progress-scheduled-text';
        schedEl.style.cssText = 'color:var(--warning);font-weight:500;font-size:0.75rem;';
        speedWrap.parentElement.insertBefore(schedEl, speedWrap.nextSibling);
      }
      schedEl.textContent = `📅 Planifié pour le ${task.scheduledStartTime.replace('T', ' ').substring(0, 16)}`;
    } else {
      speedWrap.style.display = 'inline-flex';
      const schedEl = card.querySelector('.progress-scheduled-text');
      if (schedEl) schedEl.remove();

      // Sync select value
      const select = speedWrap.querySelector('.task-speed-select');
      if (select && parseInt(select.value) !== task.maxSpeedLimit) {
        select.value = task.maxSpeedLimit;
      }
    }
  }

  // Update direct link input if resolved
  const directInput = card.querySelector(`#direct-url-${task.id}`);
  if (directInput && task.directDownloadUrl && directInput.value !== task.directDownloadUrl) {
    directInput.value = task.directDownloadUrl;
  }

  // Update action buttons dynamically without full rerender to prevent flicker
  const actionsWrap = card.querySelector('.task-actions');
  if (actionsWrap && actionsWrap.dataset.status !== task.status) {
    actionsWrap.dataset.status = task.status;
    actionsWrap.innerHTML = `
      ${isCompleted ? `<button class="btn btn-primary btn-icon btn-play-task" onclick="playVideo('${task.id}', '${escHtml(task.animeName)} – Épisode ${task.episodeNumber}', ${isMkv})" title="Lire la vidéo" style="background:linear-gradient(135deg, var(--accent), var(--neon-blue)); border:none; box-shadow:0 0 15px rgba(59,130,246,0.3);"><svg width="14" height="14" viewBox="0 0 24 24" fill="currentColor"><polygon points="5 3 19 12 5 21 5 3"/></svg></button>` : ''}
      
      ${(task.status === 'DOWNLOADING' || task.status === 'PENDING' || task.status === 'RETRYING') ? `<button class="btn btn-warning btn-icon" onclick="pauseTask('${task.id}')" title="Mettre en pause" style="background:linear-gradient(135deg, #f59e0b, #d97706); border:none; box-shadow:0 0 15px rgba(245,158,11,0.3);"><svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect x="14" y="4" width="4" height="16" rx="1"></rect><rect x="6" y="4" width="4" height="16" rx="1"></rect></svg></button>` : ''}
      
      ${(task.status === 'PAUSED' || task.status === 'FAILED' || task.status === 'CANCELLED') ? `<button class="btn btn-success btn-icon" onclick="resumeTask('${task.id}')" title="Reprendre" style="background:linear-gradient(135deg, #10b981, #059669); border:none; box-shadow:0 0 15px rgba(16,185,129,0.3);"><svg width="14" height="14" viewBox="0 0 24 24" fill="currentColor"><polygon points="5 3 19 12 5 21 5 3"/></svg></button>` : ''}
      
      ${(task.status === 'DOWNLOADING' || task.status === 'PENDING' || task.status === 'RETRYING' || task.status === 'SCHEDULED' || task.status === 'PAUSED') ? `<button class="btn btn-danger btn-icon" onclick="cancelTask('${task.id}')" title="Annuler"><svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M18 6 6 18M6 6l12 12"/></svg></button>` : ''}
      
      ${(task.status === 'COMPLETED' || task.status === 'FAILED' || task.status === 'CANCELLED' || task.status === 'PAUSED') ? `<button class="btn btn-secondary btn-icon" onclick="removeTask('${task.id}')" title="Supprimer"><svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><polyline points="3 6 5 6 21 6"/><path d="M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6m3 0V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2"/></svg></button>` : ''}
    `;
  }

  const empty = document.getElementById('empty-downloads');
  if (empty) { empty.style.display = list.querySelectorAll('.task-card').length > 0 ? 'none' : 'block'; }
}

function updateStats() {
  fetch('/api/download/status').then(r => r.json()).then(tasks => {
    const c = s => tasks.filter(x => x.status === s).length;
    setText('stat-total', tasks.length);
    setText('stat-downloading', c('DOWNLOADING') + c('PENDING') + c('RETRYING'));
    setText('stat-completed', c('COMPLETED'));
    setText('stat-failed', c('FAILED'));
    updateNavBadge(c('DOWNLOADING') + c('PENDING') + c('RETRYING'));
  }).catch(() => {});
}

function cancelTask(id) {
  fetch(`/api/download/${id}`, { method: 'DELETE' }).then(r => r.json()).then(d => {
    if (d.success) showToast('Téléchargement annulé.', 'info'); else showToast(d.error, 'error');
  }).catch(e => showToast('Erreur : ' + e.message, 'error'));
}

function pauseTask(id) {
  fetch(`/api/download/${id}/pause`, { method: 'POST' }).then(r => r.json()).then(d => {
    if (d.success) showToast('Téléchargement mis en pause.', 'info'); else showToast(d.error, 'error');
  }).catch(e => showToast('Erreur : ' + e.message, 'error'));
}

function resumeTask(id) {
  fetch(`/api/download/${id}/resume`, { method: 'POST' }).then(r => r.json()).then(d => {
    if (d.success) showToast('Téléchargement repris.', 'success'); else showToast(d.error, 'error');
  }).catch(e => showToast('Erreur : ' + e.message, 'error'));
}

function removeTask(id) {
  fetch(`/api/download/${id}/remove`, { method: 'DELETE' }).then(r => r.json()).then(d => {
    if (d.success) { 
      const card = document.getElementById('task-' + id); 
      if (card) {
        card.style.transition = 'all 0.4s ease';
        card.style.opacity = '0';
        card.style.transform = 'translateY(20px)';
        setTimeout(() => { card.remove(); updateStats(); }, 400);
      } else {
        updateStats();
      }
    } else showToast(d.error, 'error');
  }).catch(e => showToast('Erreur : ' + e.message, 'error'));
}

// ---- Clean History ----
function clearFinished() {
  const cards = document.querySelectorAll('.task-card.status-completed, .task-card.status-failed, .task-card.status-cancelled');
  if (cards.length === 0) {
    showToast('Aucune tâche inactive à nettoyer.', 'info');
    return;
  }

  cards.forEach(card => {
    card.style.transition = 'all 0.5s ease';
    card.style.opacity = '0';
    card.style.transform = 'translateX(50px)';
  });

  setTimeout(() => {
    fetch('/api/download/clean', { method: 'POST' })
      .then(r => r.json())
      .then(d => {
        if (d.success) {
          cards.forEach(c => c.remove());
          updateStats();
          showToast('Tâches inactives nettoyées ! 🧹', 'success');
        } else {
          showToast(d.error || 'Erreur', 'error');
          cards.forEach(c => { c.style.opacity = '1'; c.style.transform = 'none'; });
        }
      })
      .catch(e => {
        showToast('Erreur : ' + e.message, 'error');
        cards.forEach(c => { c.style.opacity = '1'; c.style.transform = 'none'; });
      });
  }, 500);
}

// ---- Bulk Pause ----
function pauseAllDownloads() {
  fetch('/api/download/pause-all', { method: 'POST' })
    .then(r => r.json())
    .then(data => {
      if (data.success) {
        showToast('Tous les téléchargements en cours ont été mis en pause. ⏸️', 'info');
        updateStats();
      } else {
        showToast(data.error || 'Erreur', 'error');
      }
    })
    .catch(err => showToast('Erreur : ' + err.message, 'error'));
}

// ---- Bulk Resume ----
function resumeAllDownloads() {
  fetch('/api/download/resume-all', { method: 'POST' })
    .then(r => r.json())
    .then(data => {
      if (data.success) {
        showToast('Tous les téléchargements en pause ont été repris. 🚀', 'success');
        updateStats();
      } else {
        showToast(data.error || 'Erreur', 'error');
      }
    })
    .catch(err => showToast('Erreur : ' + err.message, 'error'));
}

// ---- Settings Modal ----
function openSettingsModal() {
  const modal = document.getElementById('settings-modal');
  if (!modal) return;
  fetch('/api/download/settings')
    .then(r => r.json())
    .then(data => {
      document.getElementById('max-concurrent').value = data.maxConcurrentDownloads;
      document.getElementById('global-speed-limit').value = data.globalSpeedLimit;
      modal.style.display = 'flex';
    })
    .catch(err => showToast('Erreur de chargement des paramètres : ' + err.message, 'error'));
}

function closeSettingsModal() {
  const modal = document.getElementById('settings-modal');
  if (modal) modal.style.display = 'none';
}

function saveSettings(event) {
  event.preventDefault();
  const maxConcurrent = parseInt(document.getElementById('max-concurrent').value);
  const globalSpeed = parseInt(document.getElementById('global-speed-limit').value);

  fetch('/api/download/settings', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ maxConcurrentDownloads: maxConcurrent, globalSpeedLimit: globalSpeed })
  })
  .then(r => r.json())
  .then(data => {
    if (data.success) {
      showToast('Paramètres mis à jour ! ⚙️', 'success');
      closeSettingsModal();
      const globSelect = document.getElementById('global-speed-select');
      if (globSelect) globSelect.value = globalSpeed;
      refreshNavBadge();
    } else {
      showToast(data.error || 'Erreur', 'error');
    }
  })
  .catch(err => showToast('Erreur : ' + err.message, 'error'));
}

// ---- Individual Speed Throttling ----
function setTaskSpeedLimit(taskId, limit) {
  fetch(`/api/download/${taskId}/speed-limit`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ limit: parseInt(limit) })
  })
  .then(r => r.json())
  .then(data => {
    if (data.success) {
      showToast('Vitesse de la tâche mise à jour ! ⚡', 'success');
    } else {
      showToast(data.error || 'Erreur', 'error');
    }
  })
  .catch(err => showToast('Erreur de vitesse : ' + err.message, 'error'));
}

// ---- Accordion & Links ----
function toggleAccordion(btn) {
  const content = btn.nextElementSibling;
  const arrow = btn.querySelector('.arrow-icon');
  if (!content) return;
  if (content.style.display === 'none' || content.style.display === '') {
    content.style.display = 'flex';
    if (arrow) arrow.style.transform = 'rotate(180deg)';
  } else {
    content.style.display = 'none';
    if (arrow) arrow.style.transform = 'rotate(0deg)';
  }
}

function copyLink(btn) {
  const input = btn.previousElementSibling;
  if (!input) return;
  input.select();
  input.setSelectionRange(0, 99999);
  try {
    navigator.clipboard.writeText(input.value);
    showToast('Lien copié dans le presse-papiers ! 📋', 'success');
  } catch (err) {
    document.execCommand('copy');
    showToast('Lien copié ! 📋', 'success');
  }
}

// ---- Plyr Video Player Modal ----
let plyrPlayer = null;

function playVideo(id, title, isMkv) {
  const modal = document.getElementById('player-modal');
  const titleEl = document.getElementById('modal-video-title');
  const video = document.getElementById('player');
  
  if (!modal || !video) return;
  
  titleEl.textContent = title;
  modal.style.display = 'flex';
  
  if (!plyrPlayer) {
    plyrPlayer = new Plyr(video, {
      captions: { active: true, update: true, language: 'auto' }
    });
  }
  
  const videoSource = `/api/media/stream/${id}`;
  const subtitleSource = `/api/media/subtitle/${id}`;
  const videoType = isMkv ? 'video/x-matroska' : 'video/mp4';

  plyrPlayer.source = {
    type: 'video',
    title: title,
    sources: [
      {
        src: videoSource,
        type: videoType
      }
    ],
    tracks: isMkv ? [] : [
      {
        kind: 'captions',
        label: 'Français (VTT)',
        srclang: 'fr',
        src: subtitleSource,
        default: true
      }
    ]
  };
  
  plyrPlayer.play().catch(err => {
    console.warn("Autoplay/play failed:", err);
  });
}

function closePlayerModal() {
  const modal = document.getElementById('player-modal');
  if (modal) modal.style.display = 'none';
  if (plyrPlayer) {
    plyrPlayer.pause();
  }
}

// ---- File Size Queue Loader ----
let sizeQueue = [];
let sizeProcessing = false;

function enqueueSizeResolution() {
  const sizeElements = document.querySelectorAll('.episode-size');
  if (sizeElements.length === 0) return;

  sizeElements.forEach(el => {
    const url = el.dataset.url;
    if (url) {
      sizeQueue.push({ element: el, url: url });
    }
  });

  processSizeQueue();
}

function processSizeQueue() {
  if (sizeProcessing || sizeQueue.length === 0) return;
  sizeProcessing = true;

  const current = sizeQueue.shift();
  const el = current.element;
  const url = current.url;

  fetch(`/api/episode/size?url=${encodeURIComponent(url)}`)
    .then(r => r.json())
    .then(data => {
      if (data.success && data.formattedSize) {
        el.textContent = data.formattedSize;
        el.classList.add('loaded');
        const row = el.closest('.episode-item');
        if (row) {
          if (data.directUrl) row.dataset.directUrl = data.directUrl;
          if (data.subtitleUrl) row.dataset.subtitle = data.subtitleUrl;
        }
      } else {
        el.textContent = 'Taille inconnue';
        el.style.opacity = '0.6';
      }
    })
    .catch(() => {
      el.textContent = 'Inconnue';
      el.style.opacity = '0.6';
    })
    .finally(() => {
      sizeProcessing = false;
      setTimeout(processSizeQueue, 100); // 100ms request throttle
    });
}

// ---- Detail Page Sync and SSE ----
let detailSseSource = null;

function initDetailPage() {
  const epList = document.getElementById('episode-list');
  if (!epList) return;

  enqueueSizeResolution();

  fetch('/api/download/status')
    .then(r => r.json())
    .then(tasks => {
      tasks.forEach(task => {
        updateEpisodeRowUI(task);
      });
    })
    .catch(err => console.error('Error fetching download statuses', err));

  connectDetailSSE();
}

function connectDetailSSE() {
  if (detailSseSource) detailSseSource.close();
  detailSseSource = new EventSource('/api/download/stream');
  
  detailSseSource.addEventListener('download-update', (e) => {
    try {
      const task = JSON.parse(e.data);
      updateEpisodeRowUI(task);
    } catch (err) {
      console.error('Error parsing SSE event in detail page', err);
    }
  });

  detailSseSource.onerror = () => {
    setTimeout(connectDetailSSE, 3000);
  };
}

function updateEpisodeRowUI(task) {
  const animeName = typeof ANIME_NAME !== 'undefined' ? ANIME_NAME : '';
  if (task.animeName !== animeName) return;

  const rows = document.querySelectorAll(`.episode-item[data-num="${task.episodeNumber}"]`);
  rows.forEach(row => {
    if (row.dataset.url !== task.downloadPageUrl && row.dataset.url !== task.fileUrl) {
      return;
    }

    const check = row.querySelector('.episode-check');
    const btn = row.querySelector('.episode-dl-btn');
    const sizeSpan = row.querySelector('.episode-size');
    const subCheckLabel = row.querySelector('.subtitle-cb-label');

    let progressWrap = row.querySelector('.ep-progress-wrap');
    if (!progressWrap) {
      progressWrap = document.createElement('div');
      progressWrap.className = 'ep-progress-wrap';
      progressWrap.style.cssText = 'flex: 1; margin-left: 15px; margin-right: 15px; display: none; flex-direction: column; gap: 4px;';
      progressWrap.innerHTML = `
        <div style="display:flex; justify-content:space-between; font-size:0.72rem; color:var(--text-secondary);">
          <span class="ep-progress-status">Téléchargement...</span>
          <span class="ep-progress-percent">0%</span>
        </div>
        <div style="height:4px; background:rgba(255,255,255,0.05); border-radius:4px; overflow:hidden;">
          <div class="ep-progress-bar" style="width:0%; height:100%; background:linear-gradient(90deg,var(--accent),var(--neon-blue)); transition: width 0.3s ease;"></div>
        </div>
      `;
      row.insertBefore(progressWrap, btn);
    }

    const titleEl = row.querySelector('.episode-title');
    const badgeEl = row.querySelector('.episode-version-badge');

    if (task.status === 'DOWNLOADING' || task.status === 'PENDING' || task.status === 'RETRYING') {
      if (titleEl) titleEl.style.display = 'none';
      if (badgeEl) badgeEl.style.display = 'none';
      if (sizeSpan) sizeSpan.style.display = 'none';
      if (subCheckLabel) subCheckLabel.style.display = 'none';
      
      progressWrap.style.display = 'flex';
      
      const percentEl = progressWrap.querySelector('.ep-progress-percent');
      const barEl = progressWrap.querySelector('.ep-progress-bar');
      const statusEl = progressWrap.querySelector('.ep-progress-status');
      
      percentEl.textContent = `${task.progress.toFixed(1)}%`;
      barEl.style.width = `${task.progress}%`;
      
      if (task.status === 'PENDING') statusEl.textContent = 'En file d\'attente...';
      else if (task.status === 'RETRYING') statusEl.textContent = 'Retentative...';
      else statusEl.textContent = `En cours (${task.speed})`;

      if (check) { check.disabled = true; check.checked = false; }
      row.classList.add('downloading');
      row.classList.remove('completed');
      
      btn.disabled = false;
      btn.onclick = function() { pauseTask(task.id); };
      btn.title = 'Mettre en pause';
      btn.style.background = 'linear-gradient(135deg, #f59e0b, #d97706)';
      btn.style.border = 'none';
      btn.innerHTML = '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect x="14" y="4" width="4" height="16" rx="1"></rect><rect x="6" y="4" width="4" height="16" rx="1"></rect></svg>';
    } 
    else if (task.status === 'PAUSED') {
      if (titleEl) titleEl.style.display = 'none';
      if (badgeEl) badgeEl.style.display = 'none';
      if (sizeSpan) sizeSpan.style.display = 'none';
      if (subCheckLabel) subCheckLabel.style.display = 'none';
      
      progressWrap.style.display = 'flex';
      
      const percentEl = progressWrap.querySelector('.ep-progress-percent');
      const barEl = progressWrap.querySelector('.ep-progress-bar');
      const statusEl = progressWrap.querySelector('.ep-progress-status');
      
      percentEl.textContent = `${task.progress.toFixed(1)}%`;
      barEl.style.width = `${task.progress}%`;
      statusEl.textContent = 'En pause';

      if (check) { check.disabled = true; check.checked = false; }
      row.classList.add('downloading');
      row.classList.remove('completed');
      
      btn.disabled = false;
      btn.onclick = function() { resumeTask(task.id); };
      btn.title = 'Reprendre';
      btn.style.background = 'linear-gradient(135deg, #10b981, #059669)';
      btn.style.border = 'none';
      btn.innerHTML = '<svg width="14" height="14" viewBox="0 0 24 24" fill="currentColor"><polygon points="5 3 19 12 5 21 5 3"/></svg>';
    }
    else if (task.status === 'COMPLETED') {
      if (titleEl) titleEl.style.display = 'block';
      if (badgeEl) badgeEl.style.display = 'inline-block';
      if (sizeSpan) {
        sizeSpan.style.display = 'inline';
        sizeSpan.textContent = task.totalSize || sizeSpan.textContent;
      }
      if (subCheckLabel) subCheckLabel.style.display = 'none';
      progressWrap.style.display = 'none';

      if (check) { check.disabled = true; check.checked = false; }
      row.classList.remove('downloading');
      row.classList.add('completed');
      
      btn.disabled = true;
      btn.style.background = '';
      btn.style.border = '';
      btn.onclick = null;
      btn.innerHTML = '✅';
      btn.title = 'Épisode déjà téléchargé';
    }
    else {
      if (titleEl) titleEl.style.display = 'block';
      if (badgeEl) badgeEl.style.display = 'inline-block';
      if (sizeSpan) sizeSpan.style.display = 'inline';
      if (subCheckLabel) subCheckLabel.style.display = 'inline-flex';
      progressWrap.style.display = 'none';

      if (check) { check.disabled = false; }
      row.classList.remove('downloading', 'completed');
      
      btn.disabled = false;
      btn.style.background = '';
      btn.style.border = '';
      btn.onclick = function() { downloadEpisode(this); };
      btn.innerHTML = '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"/><polyline points="7 10 12 15 17 10"/><line x1="12" y1="15" x2="12" y2="3"/></svg>';
      btn.title = 'Télécharger';
    }
  });

  onEpisodeCheckChange();
}

// ---- Utils ----
function sanitizeName(n) { return (n || 'Anime').replace(/[<>:"/\\|?*]/g, '_').trim(); }
function escHtml(s) { if (!s) return ''; return String(s).replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;').replace(/"/g,'&quot;'); }
function setText(id, v) { const e = document.getElementById(id); if (e) e.textContent = v; }

// ---- Init ----
document.addEventListener('DOMContentLoaded', () => {
  const path = window.location.pathname;
  document.querySelectorAll('.nav-links a').forEach(a => {
    if (a.getAttribute('href') === path || (path.startsWith('/anime') && a.id === 'nav-home')) a.classList.add('active');
    if (path === '/downloads' && a.id === 'nav-downloads') a.classList.add('active');
  });

  const sf = document.getElementById('search-form');
  if (sf) {
    sf.addEventListener('submit', () => {
      const txt = document.getElementById('search-btn-text');
      const sp = document.getElementById('search-spinner');
      if (txt) txt.style.display = 'none';
      if (sp) sp.style.display = 'block';
    });
  }

  initDownloadsPage();
  initDetailPage();
  refreshNavBadge();
});
