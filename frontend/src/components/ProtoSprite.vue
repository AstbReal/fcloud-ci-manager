<template>
  <svg width="0" height="0" style="position: absolute" aria-hidden="true">
    <symbol id="i-home" viewBox="0 0 24 24"><path d="M3 10.5 12 3l9 7.5" /><path d="M5.5 9.5V20h13V9.5" /></symbol>
    <symbol id="i-grid" viewBox="0 0 24 24"><rect x="3" y="3" width="7.5" height="7.5" rx="1.6" /><rect x="13.5" y="3" width="7.5" height="7.5" rx="1.6" /><rect x="3" y="13.5" width="7.5" height="7.5" rx="1.6" /><rect x="13.5" y="13.5" width="7.5" height="7.5" rx="1.6" /></symbol>
    <symbol id="i-layers" viewBox="0 0 24 24"><path d="m12 3 9 5-9 5-9-5 9-5Z" /><path d="m3 13 9 5 9-5" /></symbol>
    <symbol id="i-sliders" viewBox="0 0 24 24"><path d="M4 7h9M17 7h3M4 17h3M11 17h9" /><circle cx="15" cy="7" r="2.2" /><circle cx="9" cy="17" r="2.2" /></symbol>
    <symbol id="i-users" viewBox="0 0 24 24"><circle cx="9" cy="8" r="3.4" /><path d="M2.8 20c0-3.4 2.8-5.6 6.2-5.6s6.2 2.2 6.2 5.6" /><path d="M16.5 5.2a3.4 3.4 0 0 1 0 6.4M17.5 14.7c2.2.5 3.7 2.2 3.7 4.8" /></symbol>
    <symbol id="i-key" viewBox="0 0 24 24"><circle cx="7.5" cy="15.5" r="4" /><path d="m10.5 12.5 8-8M16 5l3 3M13.5 7.5 16 10" /></symbol>
    <symbol id="i-file" viewBox="0 0 24 24"><path d="M14 3H7a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V8l-5-5Z" /><path d="M14 3v5h5M9 13h6M9 17h4" /></symbol>
    <symbol id="i-archive" viewBox="0 0 24 24"><rect x="3" y="4" width="18" height="4.5" rx="1.4" /><path d="M5 8.5V19a1.5 1.5 0 0 0 1.5 1.5h11A1.5 1.5 0 0 0 19 19V8.5" /><path d="M10 12.5h4" /></symbol>
    <symbol id="i-book" viewBox="0 0 24 24"><path d="M4 5.5A2.5 2.5 0 0 1 6.5 3H19v18H6.5A2.5 2.5 0 0 1 4 18.5v-13Z" /><path d="M4 17.5h15M8 7.5h7" /></symbol>
    <symbol id="i-plus" viewBox="0 0 24 24"><path d="M12 5v14M5 12h14" /></symbol>
    <symbol id="i-more" viewBox="0 0 24 24"><circle cx="5" cy="12" r="1.4" /><circle cx="12" cy="12" r="1.4" /><circle cx="19" cy="12" r="1.4" /></symbol>
    <symbol id="i-eye" viewBox="0 0 24 24"><path d="M2.5 12S6 5.8 12 5.8 21.5 12 21.5 12 18 18.2 12 18.2 2.5 12 2.5 12Z" /><circle cx="12" cy="12" r="2.6" /></symbol>
    <symbol id="i-copy" viewBox="0 0 24 24"><rect x="8.5" y="8.5" width="12" height="12" rx="2" /><path d="M15.5 5.5v-1a2 2 0 0 0-2-2h-8a2 2 0 0 0-2 2v8a2 2 0 0 0 2 2h1" /></symbol>
    <symbol id="i-upload" viewBox="0 0 24 24"><path d="M12 16V4M8 8l4-4 4 4" /><path d="M4 16v3a1.5 1.5 0 0 0 1.5 1.5h13A1.5 1.5 0 0 0 20 19v-3" /></symbol>
    <symbol id="i-alert" viewBox="0 0 24 24"><path d="M12 3.8 21 19H3l9-15.2Z" /><path d="M12 9.5v4.5M12 16.8v.2" /></symbol>
    <symbol id="i-check" viewBox="0 0 24 24"><path d="m5 12.5 4.5 4.5L19 7" /></symbol>
    <symbol id="i-grip" viewBox="0 0 24 24"><circle cx="9" cy="6" r="1.3" /><circle cx="15" cy="6" r="1.3" /><circle cx="9" cy="12" r="1.3" /><circle cx="15" cy="12" r="1.3" /><circle cx="9" cy="18" r="1.3" /><circle cx="15" cy="18" r="1.3" /></symbol>
    <symbol id="i-chevron" viewBox="0 0 24 24"><path d="m6 9 6 6 6-6" /></symbol>
    <symbol id="i-x" viewBox="0 0 24 24"><path d="M6 6l12 12M18 6 6 18" /></symbol>
    <symbol id="i-arrow-r" viewBox="0 0 24 24"><path d="M4 12h15M14 7l5 5-5 5" /></symbol>
    <symbol id="i-sync" viewBox="0 0 24 24"><path d="M20 11a8 8 0 0 0-14-4.5L4 9" /><path d="M4 5v4h4M4 13a8 8 0 0 0 14 4.5L20 15" /><path d="M20 19v-4h-4" /></symbol>
    <symbol id="i-search" viewBox="0 0 24 24"><circle cx="11" cy="11" r="6.5" /><path d="m16 16 4 4" /></symbol>
    <symbol id="i-lock" viewBox="0 0 24 24"><rect x="4.5" y="10.5" width="15" height="10" rx="2" /><path d="M8 10.5V7a4 4 0 0 1 8 0v3.5" /></symbol>
    <symbol id="i-cloud" viewBox="0 0 24 24"><path d="M7 18h10a4 4 0 0 0 .4-8A5.5 5.5 0 0 0 6.6 9.6 4.2 4.2 0 0 0 7 18Z" /></symbol>
    <symbol id="i-download" viewBox="0 0 24 24"><path d="M12 4v12M8 12l4 4 4-4" /><path d="M4 19h16" /></symbol>
  </svg>
</template>
