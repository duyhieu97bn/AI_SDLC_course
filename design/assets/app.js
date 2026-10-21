/* CamHub – HTML design prototype: khung trang dùng chung và tương tác giả lập.
   Không dùng thư viện ngoài để mở được trực tiếp bằng file://. */

const ICONS = {
  dashboard: '<rect x="3" y="3" width="7" height="9" rx="1"/><rect x="14" y="3" width="7" height="5" rx="1"/><rect x="14" y="12" width="7" height="9" rx="1"/><rect x="3" y="16" width="7" height="5" rx="1"/>',
  video: '<rect x="2" y="6" width="14" height="12" rx="2"/><path d="M16 10l6-3v10l-6-3z"/>',
  history: '<path d="M3 12a9 9 0 1 0 3-6.7L3 8"/><path d="M3 3v5h5"/><path d="M12 7v5l3 3"/>',
  bell: '<path d="M18 8a6 6 0 0 0-12 0c0 7-3 9-3 9h18s-3-2-3-9"/><path d="M13.7 21a2 2 0 0 1-3.4 0"/>',
  camera: '<path d="M23 19a2 2 0 0 1-2 2H3a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2h4l2-3h6l2 3h4a2 2 0 0 1 2 2z"/><circle cx="12" cy="13" r="4"/>',
  template: '<path d="M12 2l9 5-9 5-9-5z"/><path d="M3 12l9 5 9-5"/><path d="M3 17l9 5 9-5"/>',
  users: '<path d="M17 21v-2a4 4 0 0 0-4-4H5a4 4 0 0 0-4 4v2"/><circle cx="9" cy="7" r="4"/><path d="M23 21v-2a4 4 0 0 0-3-3.9"/><path d="M16 3.1a4 4 0 0 1 0 7.8"/>',
  audit: '<path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"/><path d="M14 2v6h6"/><path d="M8 13h8M8 17h8M8 9h2"/>',
  server: '<rect x="2" y="3" width="20" height="8" rx="2"/><rect x="2" y="13" width="20" height="8" rx="2"/><path d="M6 7h.01M6 17h.01"/>',
  search: '<circle cx="11" cy="11" r="7"/><path d="M21 21l-4.3-4.3"/>',
  sun: '<circle cx="12" cy="12" r="4"/><path d="M12 2v2M12 20v2M4.9 4.9l1.4 1.4M17.7 17.7l1.4 1.4M2 12h2M20 12h2M4.9 19.1l1.4-1.4M17.7 6.3l1.4-1.4"/>',
  moon: '<path d="M21 12.8A9 9 0 1 1 11.2 3a7 7 0 0 0 9.8 9.8z"/>',
  menu: '<path d="M3 6h18M3 12h18M3 18h18"/>',
  plus: '<path d="M12 5v14M5 12h14"/>',
  refresh: '<path d="M23 4v6h-6"/><path d="M1 20v-6h6"/><path d="M3.5 9a9 9 0 0 1 14.9-3.4L23 10M1 14l4.6 4.4A9 9 0 0 0 20.5 15"/>',
  download: '<path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"/><path d="M7 10l5 5 5-5"/><path d="M12 15V3"/>',
  upload: '<path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"/><path d="M17 8l-5-5-5 5"/><path d="M12 3v12"/>',
  x: '<path d="M18 6L6 18M6 6l12 12"/>',
  check: '<path d="M20 6L9 17l-5-5"/>',
  up: '<path d="M18 15l-6-6-6 6"/>',
  down: '<path d="M6 9l6 6 6-6"/>',
  left: '<path d="M15 18l-6-6 6-6"/>',
  right: '<path d="M9 18l6-6-6-6"/>',
  home: '<circle cx="12" cy="12" r="3"/>',
  zoomIn: '<circle cx="11" cy="11" r="7"/><path d="M21 21l-4.3-4.3M11 8v6M8 11h6"/>',
  zoomOut: '<circle cx="11" cy="11" r="7"/><path d="M21 21l-4.3-4.3M8 11h6"/>',
  image: '<rect x="3" y="3" width="18" height="18" rx="2"/><circle cx="8.5" cy="8.5" r="1.5"/><path d="M21 15l-5-5L5 21"/>',
  maximize: '<path d="M8 3H5a2 2 0 0 0-2 2v3M21 8V5a2 2 0 0 0-2-2h-3M3 16v3a2 2 0 0 0 2 2h3M16 21h3a2 2 0 0 0 2-2v-3"/>',
  mic: '<path d="M12 1a3 3 0 0 0-3 3v8a3 3 0 0 0 6 0V4a3 3 0 0 0-3-3z"/><path d="M19 10v2a7 7 0 0 1-14 0v-2M12 19v4M8 23h8"/>',
  warning: '<path d="M10.3 3.9L1.8 18a2 2 0 0 0 1.7 3h17a2 2 0 0 0 1.7-3L13.7 3.9a2 2 0 0 0-3.4 0z"/><path d="M12 9v4M12 17h.01"/>',
  info: '<circle cx="12" cy="12" r="10"/><path d="M12 16v-4M12 8h.01"/>',
  wifiOff: '<path d="M1 1l22 22M16.7 11.1A11 11 0 0 1 19 12.6M5 12.6a11 11 0 0 1 5.2-2.5M10.7 5.1A16 16 0 0 1 22.6 9M1.4 9a16 16 0 0 1 4.7-2.9M8.5 16.1a5 5 0 0 1 7 0M12 20h.01"/>',
  disk: '<path d="M22 12H2"/><path d="M5.5 5.1L2 12v6a2 2 0 0 0 2 2h16a2 2 0 0 0 2-2v-6l-3.5-6.9A2 2 0 0 0 16.8 4H7.2a2 2 0 0 0-1.7 1.1z"/><path d="M6 16h.01M10 16h.01"/>',
  lock: '<rect x="3" y="11" width="18" height="11" rx="2"/><path d="M7 11V7a5 5 0 0 1 10 0v4"/>',
  filter: '<path d="M22 3H2l8 9.5V19l4 2v-8.5z"/>',
  grid: '<rect x="3" y="3" width="7" height="7"/><rect x="14" y="3" width="7" height="7"/><rect x="14" y="14" width="7" height="7"/><rect x="3" y="14" width="7" height="7"/>',
  star: '<path d="M12 2l3.1 6.3 6.9 1-5 4.9 1.2 6.8L12 17.8 5.8 21l1.2-6.8-5-4.9 6.9-1z"/>',
  userPlus: '<path d="M16 21v-2a4 4 0 0 0-4-4H5a4 4 0 0 0-4 4v2"/><circle cx="8.5" cy="7" r="4"/><path d="M20 8v6M23 11h-6"/>',
  link: '<path d="M10 13a5 5 0 0 0 7.5.5l3-3a5 5 0 0 0-7-7l-1.7 1.7"/><path d="M14 11a5 5 0 0 0-7.5-.5l-3 3a5 5 0 0 0 7 7l1.7-1.7"/>',
  play: '<path d="M5 3l14 9-14 9z"/>',
  pause: '<rect x="6" y="4" width="4" height="16"/><rect x="14" y="4" width="4" height="16"/>',
  stepBack: '<path d="M19 20L9 12l10-8z"/><path d="M5 19V5"/>',
  stepFwd: '<path d="M5 4l10 8-10 8z"/><path d="M19 5v14"/>',
  scissors: '<circle cx="6" cy="6" r="3"/><circle cx="6" cy="18" r="3"/><path d="M20 4L8.1 15.9M14.5 14.5L20 20M8.1 8.1L12 12"/>',
  copy: '<rect x="9" y="9" width="13" height="13" rx="2"/><path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1"/>',
  trash: '<path d="M3 6h18M19 6l-1 14a2 2 0 0 1-2 2H8a2 2 0 0 1-2-2L5 6M10 11v6M14 11v6M9 6V4h6v2"/>',
  edit: '<path d="M12 20h9"/><path d="M16.5 3.5a2.1 2.1 0 0 1 3 3L7 19l-4 1 1-4z"/>',
  logout: '<path d="M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4M16 17l5-5-5-5M21 12H9"/>',
  key: '<circle cx="7.5" cy="15.5" r="5.5"/><path d="M21 2l-9.6 9.6M15.5 7.5l3 3L22 7l-3-3"/>',
  shield: '<path d="M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z"/>',
  pin: '<path d="M21 10c0 7-9 13-9 13S3 17 3 10a9 9 0 0 1 18 0z"/><circle cx="12" cy="10" r="3"/>',
  move: '<path d="M5 9l-3 3 3 3M9 5l3-3 3 3M15 19l-3 3-3-3M19 9l3 3-3 3M2 12h20M12 2v20"/>',
  send: '<path d="M22 2L11 13M22 2l-7 20-4-9-9-4z"/>',
  power: '<path d="M18.4 6.6a9 9 0 1 1-12.8 0M12 2v10"/>',
};

function icon(name, cls = "icon") {
  const body = ICONS[name] || ICONS.info;
  return `<svg class="${cls}" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">${body}</svg>`;
}

function renderIcons(root = document) {
  root.querySelectorAll("i[data-icon]").forEach((el) => {
    el.outerHTML = icon(el.dataset.icon, el.classList.contains("sm") ? "icon icon-sm" : "icon");
  });
}

/* ---------- Theme ---------- */
function readTheme() {
  try { return localStorage.getItem("camhub-theme"); } catch (e) { return null; }
}
function applyTheme(t) {
  document.documentElement.setAttribute("data-theme", t);
  try { localStorage.setItem("camhub-theme", t); } catch (e) { /* trình duyệt chặn storage */ }
  const btn = document.getElementById("themeBtn");
  if (btn) btn.innerHTML = icon(t === "dark" ? "sun" : "moon");
}
document.documentElement.setAttribute("data-theme", readTheme() || "dark");

/* ---------- Layout shell ---------- */
const NAV = [
  { section: "Giám sát" },
  { id: "dashboard", href: "dashboard.html", icon: "dashboard", label: "Tổng quan" },
  { id: "live", href: "live.html", icon: "video", label: "Xem trực tiếp" },
  { id: "playback", href: "playback.html", icon: "history", label: "Xem lại" },
  { id: "alerts", href: "alerts.html", icon: "bell", label: "Cảnh báo", count: 3 },
  { section: "Thiết bị" },
  { id: "cameras", href: "cameras.html", icon: "camera", label: "Camera" },
  { id: "templates", href: "templates.html", icon: "template", label: "Cấu hình mẫu" },
  { section: "Quản trị" },
  { id: "users", href: "users.html", icon: "users", label: "Người dùng & vai trò" },
  { id: "audit", href: "audit.html", icon: "audit", label: "Nhật ký thao tác" },
  { id: "system", href: "system.html", icon: "server", label: "Hệ thống" },
];

function renderShell() {
  const body = document.body;
  const page = body.dataset.page;
  const sidebar = document.getElementById("sidebar");
  if (sidebar) {
    sidebar.innerHTML = `
      <div class="brand"><span class="brand-mark">${icon("camera")}</span> CamHub</div>
      <nav class="nav">
        ${NAV.map((n) => n.section
          ? `<div class="nav-section">${n.section}</div>`
          : `<a class="nav-item ${n.id === page ? "active" : ""}" href="${n.href}">${icon(n.icon)}<span>${n.label}</span>${n.count ? `<span class="count">${n.count}</span>` : ""}</a>`
        ).join("")}
      </nav>
      <div class="sidebar-foot"><a class="nav-item" href="index.html">${icon("grid")}<span>Danh mục màn hình</span></a></div>`;
  }
  const topbar = document.getElementById("topbar");
  if (topbar) {
    topbar.innerHTML = `
      <button class="icon-btn menu-toggle" id="menuBtn" aria-label="Mở menu">${icon("menu")}</button>
      <div class="title"><h1>${body.dataset.title || ""}</h1>${body.dataset.subtitle ? `<span class="breadcrumb">${body.dataset.subtitle}</span>` : ""}</div>
      <div class="spacer"></div>
      <label class="search">${icon("search")}<input placeholder="Tìm camera, địa điểm…" aria-label="Tìm kiếm"></label>
      <button class="icon-btn" id="themeBtn" title="Đổi giao diện sáng/tối" aria-label="Đổi giao diện"></button>
      <a class="icon-btn" href="alerts.html" title="Cảnh báo chưa xử lý" aria-label="Cảnh báo">${icon("bell")}<span class="dot">3</span></a>
      <div class="user-chip"><span class="avatar">TT</span><span class="who"><b>Trần Tuấn</b><br><span class="small muted">Admin</span></span></div>
      <a class="icon-btn" href="login.html" title="Đăng xuất" aria-label="Đăng xuất">${icon("logout")}</a>`;
    document.getElementById("themeBtn").addEventListener("click", () => {
      applyTheme(document.documentElement.getAttribute("data-theme") === "dark" ? "light" : "dark");
    });
    document.getElementById("menuBtn").addEventListener("click", () => sidebar && sidebar.classList.toggle("open"));
  }
  applyTheme(document.documentElement.getAttribute("data-theme"));
}

/* ---------- Tabs: <div data-tabs> .tab[data-tab=id] + .tab-panel#id ---------- */
function initTabs(root = document) {
  root.querySelectorAll("[data-tabs]").forEach((group) => {
    const tabs = group.querySelectorAll(".tab");
    tabs.forEach((t) => t.addEventListener("click", () => {
      tabs.forEach((x) => {
        x.classList.toggle("active", x === t);
        const p = document.getElementById(x.dataset.tab);
        if (p) p.classList.toggle("active", x === t);
      });
    }));
  });
}

/* ---------- Modals: [data-open=id], [data-close] ---------- */
function openModal(id) { const m = document.getElementById(id); if (m) m.classList.add("open"); }
function closeModal(el) { const m = el.closest(".modal-backdrop"); if (m) m.classList.remove("open"); }
function initModals() {
  document.addEventListener("click", (e) => {
    const opener = e.target.closest("[data-open]");
    if (opener) { e.preventDefault(); openModal(opener.dataset.open); return; }
    const closer = e.target.closest("[data-close]");
    if (closer) { e.preventDefault(); closeModal(closer); return; }
    if (e.target.classList.contains("modal-backdrop")) e.target.classList.remove("open");
  });
  document.addEventListener("keydown", (e) => {
    if (e.key === "Escape") document.querySelectorAll(".modal-backdrop.open").forEach((m) => m.classList.remove("open"));
  });
}

/* ---------- Toast ---------- */
function toast(html, ms = 4000) {
  let host = document.querySelector(".toast-host");
  if (!host) { host = document.createElement("div"); host.className = "toast-host"; document.body.appendChild(host); }
  const t = document.createElement("div");
  t.className = "toast";
  t.innerHTML = html;
  host.appendChild(t);
  setTimeout(() => t.remove(), ms);
}

/* ---------- Mock data dùng chung ---------- */
const CAMERAS = [
  { id: 1, name: "Cổng chính", site: "Kho Bắc Ninh", zone: "Cổng & bãi xe", brand: "Hikvision", model: "DS-2CD2386G2", ip: "10.20.1.11", ptz: false, status: "on", scene: 1, rec: true },
  { id: 2, name: "Bãi xe – PTZ", site: "Kho Bắc Ninh", zone: "Cổng & bãi xe", brand: "Dahua", model: "SD49425XB", ip: "10.20.1.12", ptz: true, status: "on", scene: 2, rec: true },
  { id: 3, name: "Kho A – Lối 1", site: "Kho Bắc Ninh", zone: "Kho A", brand: "Hikvision", model: "DS-2CD2143G2", ip: "10.20.1.21", ptz: false, status: "on", scene: 4, rec: true },
  { id: 4, name: "Kho A – Lối 2", site: "Kho Bắc Ninh", zone: "Kho A", brand: "Hikvision", model: "DS-2CD2143G2", ip: "10.20.1.22", ptz: false, status: "on", scene: 4, rec: true },
  { id: 5, name: "Kho B – Cửa nhập", site: "Kho Bắc Ninh", zone: "Kho B", brand: "KBVision", model: "KX-C2121S5", ip: "10.20.1.31", ptz: false, status: "off", scene: 5, rec: false },
  { id: 6, name: "Cổng sau", site: "Kho Bắc Ninh", zone: "Cổng & bãi xe", brand: "Axis", model: "P3265-LVE", ip: "10.20.1.13", ptz: false, status: "on", scene: "night", rec: true },
  { id: 7, name: "Kho số 7", site: "Kho Bắc Ninh", zone: "Kho B", brand: "Dahua", model: "IPC-HFW2441S", ip: "10.20.1.37", ptz: false, status: "off", scene: 5, rec: false },
  { id: 8, name: "Sảnh lễ tân", site: "Chi nhánh Hà Nội", zone: "Tầng 1", brand: "Axis", model: "M3086-V", ip: "10.30.1.11", ptz: false, status: "on", scene: 3, rec: true },
  { id: 9, name: "Quầy giao dịch 1", site: "Chi nhánh Hà Nội", zone: "Tầng 1", brand: "Hikvision", model: "DS-2CD2346G2", ip: "10.30.1.12", ptz: false, status: "on", scene: 6, rec: true },
  { id: 10, name: "Quầy giao dịch 2", site: "Chi nhánh Hà Nội", zone: "Tầng 1", brand: "Hikvision", model: "DS-2CD2346G2", ip: "10.30.1.13", ptz: false, status: "on", scene: 6, rec: true },
  { id: 11, name: "Hành lang tầng 2", site: "Chi nhánh Hà Nội", zone: "Tầng 2", brand: "Dahua", model: "IPC-HDW2441T", ip: "10.30.1.21", ptz: false, status: "on", scene: 3, rec: false },
  { id: 12, name: "Quầy thu ngân", site: "Cửa hàng Q1 HCM", zone: "Khu bán hàng", brand: "Imou", model: "IPC-F42P", ip: "10.40.1.11", ptz: false, status: "on", scene: 6, rec: true },
  { id: 13, name: "Kệ hàng 1", site: "Cửa hàng Q1 HCM", zone: "Khu bán hàng", brand: "Ezviz", model: "C6N", ip: "10.40.1.12", ptz: true, status: "on", scene: 4, rec: true },
  { id: 14, name: "Kho sau cửa hàng", site: "Cửa hàng Q1 HCM", zone: "Kho", brand: "KBVision", model: "KX-A2112N2", ip: "10.40.1.21", ptz: false, status: "on", scene: 5, rec: true },
  { id: 15, name: "Lối thoát hiểm", site: "Cửa hàng Q1 HCM", zone: "Kho", brand: "Hikvision", model: "DS-2CD1023G0", ip: "10.40.1.22", ptz: false, status: "on", scene: 1, rec: true },
  { id: 16, name: "Bãi xe máy", site: "Cửa hàng Q1 HCM", zone: "Bên ngoài", brand: "Dahua", model: "IPC-HFW1230S", ip: "10.40.1.31", ptz: false, status: "on", scene: 2, rec: true },
];

function nowText() {
  const d = new Date();
  const p = (n) => String(n).padStart(2, "0");
  return `${p(d.getDate())}/${p(d.getMonth() + 1)}/${d.getFullYear()} ${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}`;
}

function timeText() { return nowText().slice(11); }

function tileHTML(cam, opts = {}) {
  if (!cam) return `<div class="tile empty"><div class="state">${icon("plus")}<br>Kéo camera vào đây</div></div>`;
  const off = cam.status === "off";
  return `
    <div class="tile ${off ? "offline" : ""}" data-cam="${cam.id}" tabindex="0">
      <div class="scene scene-${cam.scene}"></div>
      <div class="overlay-top"><span class="name">${cam.name}</span>${!off && cam.rec ? '<span class="rec">REC</span>' : ""}</div>
      ${off ? `<div class="state"><div>${icon("wifiOff")}<br><b>Mất kết nối</b><br><span class="small">từ 14:32 hôm nay</span></div></div>` : ""}
      <div class="overlay-bottom"><span class="small">${cam.site}</span>${off ? "" : `<span class="chip ${opts.low ? "low" : ""}">${opts.low ? "Chất lượng thấp" : opts.stream || "SUB"}</span><span class="chip live-clock">${timeText()}</span>`}</div>
    </div>`;
}

function tickClocks() {
  setInterval(() => document.querySelectorAll(".live-clock").forEach((c) => (c.textContent = timeText())), 1000);
}

function cameraTreeHTML(filter = () => true) {
  const sites = {};
  CAMERAS.filter(filter).forEach((c) => {
    sites[c.site] = sites[c.site] || {};
    (sites[c.site][c.zone] = sites[c.site][c.zone] || []).push(c);
  });
  return Object.entries(sites).map(([site, zones], i) => `
    <details ${i < 2 ? "open" : ""}><summary>${icon("pin", "icon icon-sm")} ${site}</summary>
      <div class="children">
        ${Object.entries(zones).map(([zone, cams]) => `
          <details open><summary>${zone} <span class="small muted">(${cams.length})</span></summary>
            <div class="children">
              ${cams.map((c) => `<div class="cam ${c.status === "off" ? "off" : ""}" draggable="true" data-cam="${c.id}" title="${c.brand} ${c.model}"><span class="status"></span><span class="grow">${c.name}</span>${c.ptz ? '<span class="badge badge-muted no-dot">PTZ</span>' : ""}</div>`).join("")}
            </div>
          </details>`).join("")}
      </div>
    </details>`).join("");
}

document.addEventListener("DOMContentLoaded", () => {
  renderShell();
  renderIcons();
  initTabs();
  initModals();
  tickClocks();
});
