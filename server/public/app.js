// Application State
const state = {
  currentTab: 'dashboard',
  totalFiles: 0,
  totalDevices: 0,
  storage: { total: 0, free: 0, used: 0, percentUsed: 0 },
  devices: [],
  photos: [],
  pairingCode: null,
  pairingTimer: null,
  pairingPollInterval: null
};

// Initialize
document.addEventListener('DOMContentLoaded', () => {
  initTabs();
  initModals();
  initLightbox();
  
  // Initial load
  refreshAllData();
  
  // Refresh stats and gallery periodically
  setInterval(refreshAllData, 10000);
});

// Helper: Format Bytes to human readable
function formatBytes(bytes, decimals = 2) {
  if (bytes === 0) return '0 Bytes';
  const k = 1024;
  const dm = decimals < 0 ? 0 : decimals;
  const sizes = ['Bytes', 'KB', 'MB', 'GB', 'TB', 'PB'];
  const i = Math.floor(Math.log(bytes) / Math.log(k));
  return parseFloat((bytes / Math.pow(k, i)).toFixed(dm)) + ' ' + sizes[i];
}

// Helper: Format Date
function formatDate(isoString) {
  if (!isoString) return 'Nigdy';
  const date = new Date(isoString);
  return date.toLocaleString('pl-PL', {
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit'
  });
}

// Helper: Group photos by day
function groupPhotosByDate(photos) {
  const groups = {};
  photos.forEach(photo => {
    // Extract date YYYY-MM-DD
    const dateKey = photo.timestamp.substring(0, 10);
    if (!groups[dateKey]) {
      groups[dateKey] = [];
    }
    groups[dateKey].push(photo);
  });
  return groups;
}

// Tab Navigation
function initTabs() {
  const buttons = document.querySelectorAll('.nav-btn');
  buttons.forEach(btn => {
    btn.addEventListener('click', () => {
      const tabId = btn.getAttribute('data-tab');
      switchTab(tabId);
    });
  });
}

function switchTab(tabId) {
  state.currentTab = tabId;
  
  // Update nav buttons
  document.querySelectorAll('.nav-btn').forEach(btn => {
    if (btn.getAttribute('data-tab') === tabId) {
      btn.classList.add('active');
    } else {
      btn.classList.remove('active');
    }
  });

  // Update content sections
  document.querySelectorAll('.tab-content').forEach(content => {
    if (content.id === `tab-${tabId}`) {
      content.classList.add('active');
    } else {
      content.classList.remove('active');
    }
  });

  // Update Page Title in header
  const titleMap = {
    'dashboard': 'Dashboard',
    'gallery': 'Galeria Zdjęć',
    'devices': 'Połączone Telefony'
  };
  document.getElementById('page-title').textContent = titleMap[tabId] || 'NAS QNAP PhotoSync';
  
  // Specific tab loading
  if (tabId === 'gallery') {
    loadPhotos();
  } else if (tabId === 'devices') {
    loadDevices();
  } else {
    loadStatus();
  }
}

// Modals Handling
function initModals() {
  const modal = document.getElementById('modal-pair');
  const btnPairHeader = document.getElementById('btn-pair-header');
  const btnCloseModal = document.getElementById('btn-close-modal');

  const openPairModal = () => {
    modal.classList.add('active');
    generatePairingCode();
    // Start polling to detect when the phone registers successfully
    startPairingPoll();
  };

  const closePairModal = () => {
    modal.classList.remove('active');
    stopPairingPoll();
  };

  btnPairHeader.addEventListener('click', openPairModal);
  btnCloseModal.addEventListener('click', closePairModal);

  // Close on backdrop click
  modal.addEventListener('click', (e) => {
    if (e.target === modal) closePairModal();
  });
}

// Lightbox Handling
function initLightbox() {
  const lightbox = document.getElementById('lightbox');
  const btnCloseLightbox = document.getElementById('btn-close-lightbox');
  
  btnCloseLightbox.addEventListener('click', () => {
    lightbox.classList.remove('active');
  });

  lightbox.addEventListener('click', (e) => {
    if (e.target === lightbox) {
      lightbox.classList.remove('active');
    }
  });
}

function openLightbox(photo) {
  const lightbox = document.getElementById('lightbox');
  const img = document.getElementById('lightbox-img');
  const filename = document.getElementById('lightbox-filename');
  const info = document.getElementById('lightbox-info');

  img.src = `/photos/${photo.filePath}`;
  filename.textContent = photo.fileName;
  
  const uploadDate = formatDate(photo.timestamp);
  const size = formatBytes(photo.fileSize);
  info.textContent = `Przesłano z: ${photo.deviceName || 'Nieznane urządzenie'} | Data: ${uploadDate} | Rozmiar: ${size}`;

  lightbox.classList.add('active');
}

// Load System Status
async function loadStatus() {
  try {
    const res = await fetch('/api/status');
    const data = await res.json();
    
    state.totalFiles = data.totalFiles;
    state.totalDevices = data.totalDevices;
    state.storage = data.storage;

    // Update UI elements
    document.getElementById('stat-files').textContent = state.totalFiles;
    document.getElementById('stat-devices').textContent = state.totalDevices;
    
    const storageUsed = formatBytes(state.storage.used);
    const storageTotal = formatBytes(state.storage.total);
    document.getElementById('stat-storage-used').textContent = `${storageUsed} / ${storageTotal}`;
    document.getElementById('storage-progress').style.width = `${state.storage.percentUsed}%`;
    document.getElementById('stat-storage-percent').textContent = `${state.storage.percentUsed}% zajęte`;
  } catch (error) {
    console.error('Failed to load status:', error);
  }
}

// Load Photos
async function loadPhotos() {
  try {
    const res = await fetch('/api/photos?limit=60');
    const data = await res.json();
    state.photos = data;

    const grid = document.getElementById('gallery-grid');
    grid.innerHTML = '';

    if (state.photos.length === 0) {
      grid.innerHTML = `
        <div class="empty-state">
          <i data-lucide="images"></i>
          <p>Brak zdjęć do wyświetlenia. Sparuj telefon i zacznij synchronizację.</p>
        </div>
      `;
      lucide.createIcons();
      return;
    }

    // Group photos by date
    const groups = groupPhotosByDate(state.photos);
    
    // Sort dates descending
    const sortedDates = Object.keys(groups).sort((a, b) => b.localeCompare(a));

    sortedDates.forEach(dateStr => {
      const dateSection = document.createElement('div');
      dateSection.className = 'date-group';
      
      const formattedHeaderDate = new Date(dateStr).toLocaleDateString('pl-PL', {
        weekday: 'long', year: 'numeric', month: 'long', day: 'numeric'
      });

      dateSection.innerHTML = `
        <h3 class="date-header">
          <i data-lucide="calendar"></i>
          <span>${formattedHeaderDate}</span>
        </h3>
        <div class="masonry-grid" id="grid-${dateStr}"></div>
      `;

      grid.appendChild(dateSection);
      const subGrid = document.getElementById(`grid-${dateStr}`);

      groups[dateStr].forEach(photo => {
        const card = document.createElement('div');
        card.className = 'photo-card';
        card.innerHTML = `
          <img src="/photos/${photo.filePath}" alt="${photo.fileName}" loading="lazy">
          <div class="photo-overlay">
            <span class="filename">${photo.fileName}</span>
            <span class="device">${photo.deviceName || 'Urządzenie'}</span>
          </div>
        `;
        card.addEventListener('click', () => openLightbox(photo));
        subGrid.appendChild(card);
      });
    });

    lucide.createIcons();
  } catch (error) {
    console.error('Failed to load photos:', error);
  }
}

// Load Devices
async function loadDevices() {
  try {
    const res = await fetch('/api/devices');
    const data = await res.json();
    state.devices = data;

    const tbody = document.getElementById('devices-list');
    tbody.innerHTML = '';

    if (state.devices.length === 0) {
      tbody.innerHTML = `
        <tr>
          <td colspan="4" class="text-center">Brak połączonych urządzeń</td>
        </tr>
      `;
      return;
    }

    state.devices.forEach(device => {
      const tr = document.createElement('tr');
      tr.innerHTML = `
        <td><strong>${escapeHTML(device.name)}</strong></td>
        <td>${formatDate(device.createdAt)}</td>
        <td>${formatDate(device.lastSync)}</td>
        <td>
          <button class="btn btn-danger btn-sm" onclick="deleteDevice('${device.id}')">
            <i data-lucide="trash-2"></i>
            <span>Usuń</span>
          </button>
        </td>
      `;
      tbody.appendChild(tr);
    });

    lucide.createIcons();
  } catch (error) {
    console.error('Failed to load devices:', error);
  }
}

// Delete Device
async function deleteDevice(deviceId) {
  if (!confirm('Czy na pewno chcesz usunąć to urządzenie? Telefon straci możliwość synchronizacji.')) {
    return;
  }
  
  try {
    const res = await fetch(`/api/devices/${deviceId}`, {
      method: 'DELETE'
    });
    const result = await res.json();
    if (result.success) {
      loadDevices();
      loadStatus();
    }
  } catch (error) {
    console.error('Failed to delete device:', error);
    alert('Błąd podczas usuwania urządzenia.');
  }
}

// Load Recent Photos for Dashboard
async function loadRecentPhotos() {
  try {
    const res = await fetch('/api/photos?limit=6');
    const data = await res.json();
    
    const previewGrid = document.getElementById('recent-photos-preview');
    previewGrid.innerHTML = '';

    if (data.length === 0) {
      previewGrid.innerHTML = `
        <div class="empty-state">
          <i data-lucide="image-off"></i>
          <p>Brak zsynchronizowanych zdjęć</p>
        </div>
      `;
      lucide.createIcons();
      return;
    }

    data.forEach(photo => {
      const card = document.createElement('div');
      card.className = 'photo-card';
      card.innerHTML = `
        <img src="/photos/${photo.filePath}" alt="${photo.fileName}">
        <div class="photo-overlay">
          <span class="filename">${photo.fileName}</span>
          <span class="device">${photo.deviceName || 'Urządzenie'}</span>
        </div>
      `;
      card.addEventListener('click', () => openLightbox(photo));
      previewGrid.appendChild(card);
    });

    lucide.createIcons();
  } catch (error) {
    console.error('Failed to load recent photos:', error);
  }
}

// Generate Pairing QR Code and Code
async function generatePairingCode() {
  try {
    const res = await fetch('/api/auth/pairing-code');
    const data = await res.json();
    
    state.pairingCode = data.code;
    
    document.getElementById('pairing-code').textContent = data.code;
    
    // Resolve Server URL. We use browser's current origin (e.g. http://192.168.1.15:3000)
    const serverUrl = window.location.origin;
    document.getElementById('server-url').textContent = serverUrl;

    // Create QR Code containing connection payload
    const qrPayload = JSON.stringify({
      url: serverUrl,
      code: data.code
    });

    const qrContainer = document.getElementById('pairing-qr-canvas');
    qrContainer.innerHTML = ''; // Clear previous QR

    QrCreator.render({
      text: qrPayload,
      radius: 0.0,
      ecLevel: 'M',
      fill: '#0d0f19',
      background: '#ffffff',
      size: 200
    }, qrContainer);

    // Handle Countdown Timer
    clearInterval(state.pairingTimer);
    const updateTimer = () => {
      const remainingMs = data.expires - Date.now();
      if (remainingMs <= 0) {
        clearInterval(state.pairingTimer);
        document.getElementById('pairing-timer').textContent = "Wygasł";
        document.getElementById('pairing-code').textContent = "EXPIRED";
        return;
      }
      
      const seconds = Math.floor((remainingMs / 1000) % 60);
      const minutes = Math.floor((remainingMs / (1000 * 60)) % 60);
      document.getElementById('pairing-timer').textContent = 
        `${minutes}:${seconds.toString().padStart(2, '0')}`;
    };
    
    updateTimer();
    state.pairingTimer = setInterval(updateTimer, 1000);

  } catch (error) {
    console.error('Failed to generate pairing code:', error);
    document.getElementById('pairing-code').textContent = "ERROR";
  }
}

// Start polling for pairing status
function startPairingPoll() {
  clearInterval(state.pairingPollInterval);
  
  // Keep track of how many devices were registered initially
  const initialDevicesCount = state.totalDevices;

  state.pairingPollInterval = setInterval(async () => {
    try {
      const res = await fetch('/api/status');
      const data = await res.json();
      
      // If a new device is registered, close the modal and refresh
      if (data.totalDevices > initialDevicesCount) {
        clearInterval(state.pairingPollInterval);
        document.getElementById('modal-pair').classList.remove('active');
        alert('Telefon sparowany pomyślnie!');
        refreshAllData();
      }
    } catch (e) {
      console.error('Polling error', e);
    }
  }, 2000);
}

function stopPairingPoll() {
  clearInterval(state.pairingPollInterval);
  clearInterval(state.pairingTimer);
}

// Refresh all views
function refreshAllData() {
  loadStatus();
  if (state.currentTab === 'dashboard') {
    loadRecentPhotos();
  } else if (state.currentTab === 'gallery') {
    loadPhotos();
  } else if (state.currentTab === 'devices') {
    loadDevices();
  }
}

// HTML Escaper
function escapeHTML(str) {
  return str.replace(/[&<>'"]/g, 
    tag => ({
      '&': '&amp;',
      '<': '&lt;',
      '>': '&gt;',
      "'": '&#39;',
      '"': '&quot;'
    }[tag] || tag)
  );
}
