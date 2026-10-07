import htmx from "htmx.org";

window.htmx = htmx;

// Show a short toast when an htmx request fails (forms without htmx keep normal page errors).
document.addEventListener("htmx:responseError", (event) => {
  const toast = document.createElement("div");
  toast.setAttribute("role", "alert");
  // inline styles: Tailwind does not scan JS files, so no utility classes here
  toast.style.cssText = "position:fixed;right:16px;bottom:16px;z-index:50;padding:12px 16px;border-radius:12px;"
    + "background:#FDE8E7;color:#8F1D14;font:500 14px 'IBM Plex Sans',sans-serif;box-shadow:0 6px 24px rgba(22,32,43,.18)";
  toast.textContent = "Request failed (" + event.detail.xhr.status + "). Please retry.";
  document.body.appendChild(toast);
  setTimeout(() => toast.remove(), 6000);
});
