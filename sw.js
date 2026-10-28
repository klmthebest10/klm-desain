/* KLM Desain service worker: aplikasi tetap terbuka saat offline */
const VERSION='klm-desain-v4';
const CORE=['./','./index.html','./manifest.webmanifest','./icons/icon-192.png','./icons/icon-512.png','./icons/maskable-512.png','./icons/apple-touch-icon.png','./icons/favicon-32.png'];
const LIBS=['https://cdnjs.cloudflare.com/ajax/libs/fabric.js/5.3.0/fabric.min.js','https://cdnjs.cloudflare.com/ajax/libs/jspdf/2.5.1/jspdf.umd.min.js','https://cdnjs.cloudflare.com/ajax/libs/jszip/3.10.1/jszip.min.js'];
const DATA=['./icons.json','./emoji.json','./u2netp.json','./u2netp-weights.json'];
self.addEventListener('install',e=>{
  e.waitUntil((async()=>{
    const c=await caches.open(VERSION);
    await c.addAll(CORE);
    await Promise.all(LIBS.map(u=>c.add(new Request(u,{mode:'cors'})).catch(()=>{})));
    // data besar diunduh di latar; gagal pun tidak masalah, akan dicache saat dipakai
    Promise.all(DATA.map(u=>c.add(u).catch(()=>{})));
  })());
});
self.addEventListener('activate',e=>{
  e.waitUntil((async()=>{
    for(const k of await caches.keys()) if(k!==VERSION) await caches.delete(k);
    await self.clients.claim();
  })());
});
self.addEventListener('message',e=>{ if(e.data==='skip') self.skipWaiting(); });
self.addEventListener('fetch',e=>{
  const req=e.request; if(req.method!=='GET') return;
  const url=new URL(req.url);
  if(/pollinations\.ai$/.test(url.hostname)) return; // AI selalu lewat jaringan
  if(req.mode==='navigate'){
    e.respondWith((async()=>{
      try{ const r=await fetch(req); const c=await caches.open(VERSION); c.put('./index.html',r.clone()); return r; }
      catch(_){ return (await caches.match('./index.html'))||(await caches.match('./'))||Response.error(); }
    })());
    return;
  }
  const same=url.origin===self.location.origin, cdn=url.hostname==='cdnjs.cloudflare.com', fonts=/fonts\.(googleapis|gstatic)\.com$/.test(url.hostname);
  if(!same&&!cdn&&!fonts) return;
  e.respondWith((async()=>{
    const c=await caches.open(VERSION);
    const hit=await c.match(req);
    const net=fetch(req).then(r=>{ if(r&&(r.ok||r.type==='opaque')) c.put(req,r.clone()); return r; }).catch(()=>null);
    if(hit){ e.waitUntil(net); return hit; }
    return (await net)||Response.error();
  })());
});
