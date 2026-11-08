// MMAB Electron-in-the-browser compatibility helpers (classic script, M3; design §14.2).
// Distilled from the two prototype bridges: secure-context fill-ins and a same-world contextBridge.
// Exposes globalThis.MmabElectronCompat. No network access, no state outside the page.
(function (root) {
  'use strict';
  if (root.MmabElectronCompat) return;

  // ---------------------------------------------------------------- secure-context fill-ins
  // A LAN IP over plain http is not a secure context: crypto.randomUUID / crypto.subtle / navigator.clipboard /
  // navigator.mediaDevices are missing and some apps white-screen on the first call. The platform serves HTTPS, so
  // these normally stay untouched; they are a fallback (e.g. an operator testing over http on loopback-only setups).
  const K = new Uint32Array([0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1, 0x923f82a4,
    0xab1c5ed5, 0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174,
    0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da, 0x983e5152,
    0xa831c66d, 0xb00327c8, 0xbf597fc7, 0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967, 0x27b70a85, 0x2e1b2138,
    0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85, 0xa2bfe8a1, 0xa81a664b, 0xc24b8b70,
    0xc76c51a3, 0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070, 0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5,
    0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3, 0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208, 0x90befffa,
    0xa4506ceb, 0xbef9a3f7, 0xc67178f2]);

  /** Pure-JS SHA-256 (checked against node:crypto in sdk/web/electron-compat.test.mjs). */
  function sha256(data) {
    const len = data.length;
    const bitLen = len * 8;
    const n = ((len + 9 + 63) >> 6) << 6;
    const m = new Uint8Array(n);
    m.set(data);
    m[len] = 0x80;
    const dv = new DataView(m.buffer);
    dv.setUint32(n - 8, Math.floor(bitLen / 0x100000000));
    dv.setUint32(n - 4, bitLen >>> 0);
    const H = new Uint32Array([0x6a09e667, 0xbb67ae85, 0x3c6ef372, 0xa54ff53a, 0x510e527f, 0x9b05688c, 0x1f83d9ab,
      0x5be0cd19]);
    const W = new Uint32Array(64);
    const r = (x, k) => (x >>> k) | (x << (32 - k));
    for (let o = 0; o < n; o += 64) {
      for (let i = 0; i < 16; i++) W[i] = dv.getUint32(o + i * 4);
      for (let i = 16; i < 64; i++) {
        const s0 = r(W[i - 15], 7) ^ r(W[i - 15], 18) ^ (W[i - 15] >>> 3);
        const s1 = r(W[i - 2], 17) ^ r(W[i - 2], 19) ^ (W[i - 2] >>> 10);
        W[i] = (W[i - 16] + s0 + W[i - 7] + s1) | 0;
      }
      let [a, b, c, d, e, f, g, h] = H;
      for (let i = 0; i < 64; i++) {
        const t1 = (h + (r(e, 6) ^ r(e, 11) ^ r(e, 25)) + ((e & f) ^ (~e & g)) + K[i] + W[i]) | 0;
        const t2 = ((r(a, 2) ^ r(a, 13) ^ r(a, 22)) + ((a & b) ^ (a & c) ^ (b & c))) | 0;
        h = g; g = f; f = e; e = (d + t1) | 0; d = c; c = b; b = a; a = (t1 + t2) | 0;
      }
      H[0] += a; H[1] += b; H[2] += c; H[3] += d; H[4] += e; H[5] += f; H[6] += g; H[7] += h;
    }
    const out = new Uint8Array(32);
    const ov = new DataView(out.buffer);
    for (let i = 0; i < 8; i++) ov.setUint32(i * 4, H[i]);
    return out.buffer;
  }

  /**
   * Fill in APIs missing outside a secure context. mediaDevices gets an EventTarget placeholder WITHOUT getUserMedia,
   * so the app's own feature detection reports voice as unavailable instead of crashing on unmount.
   * @returns {string[]} names of the APIs that were filled in
   */
  function polyfillInsecureContext(win) {
    const filled = [];
    const c = win.crypto;
    if (c && typeof c.randomUUID !== 'function') {
      Object.defineProperty(c, 'randomUUID', {
        configurable: true,
        value: function randomUUID() {
          const b = c.getRandomValues(new Uint8Array(16));
          b[6] = (b[6] & 0x0f) | 0x40;
          b[8] = (b[8] & 0x3f) | 0x80;
          const h = [...b].map((x) => x.toString(16).padStart(2, '0')).join('');
          return `${h.slice(0, 8)}-${h.slice(8, 12)}-${h.slice(12, 16)}-${h.slice(16, 20)}-${h.slice(20)}`;
        },
      });
      filled.push('crypto.randomUUID');
    }
    if (c && !c.subtle) {
      const toU8 = (d) => (d instanceof ArrayBuffer ? new Uint8Array(d) : new Uint8Array(d.buffer, d.byteOffset, d.byteLength));
      Object.defineProperty(c, 'subtle', {
        configurable: true,
        value: {
          digest(alg, data) {
            const name = (typeof alg === 'string' ? alg : (alg && alg.name) || '').toUpperCase();
            if (name !== 'SHA-256') return Promise.reject(new Error('[mmab] only SHA-256 digest outside a secure context'));
            return Promise.resolve(sha256(toU8(data)));
          },
        },
      });
      filled.push('crypto.subtle.digest');
    }
    const nav = win.navigator;
    if (nav && !nav.clipboard) {
      const doc = win.document;
      const copyText = (text) => new Promise((resolve, reject) => {
        const t = doc.createElement('textarea');
        t.value = String(text);
        t.style.cssText = 'position:fixed;opacity:0;left:-9999px';
        doc.body.appendChild(t);
        t.select();
        const ok = doc.execCommand('copy');
        t.remove();
        if (ok) resolve();
        else reject(new Error('copy failed'));
      });
      Object.defineProperty(nav, 'clipboard', {
        configurable: true,
        value: {
          writeText: copyText,
          async write(items) {
            for (const it of items || []) {
              if (it.types && it.types.includes('text/plain')) return copyText(await (await it.getType('text/plain')).text());
            }
            throw new Error('[mmab] only text can be copied outside a secure context');
          },
          readText: () => Promise.reject(new Error('[mmab] clipboard read needs a secure context; paste with Ctrl/⌘+V')),
          read: () => Promise.reject(new Error('[mmab] clipboard read needs a secure context; paste with Ctrl/⌘+V')),
        },
      });
      filled.push('navigator.clipboard');
    }
    if (nav && !nav.mediaDevices) {
      const md = new EventTarget();
      md.enumerateDevices = () => Promise.resolve([]);
      md.getSupportedConstraints = () => ({});
      md.ondevicechange = null;
      Object.defineProperty(nav, 'mediaDevices', { configurable: true, value: md });
      filled.push('navigator.mediaDevices');
    }
    return filled;
  }

  // ---------------------------------------------------------------- same-world contextBridge
  /**
   * Electron's contextBridge exposes into an isolated world; in the browser the wrapped preload shares the page's
   * Window. Some preloads first pin an immutable startup snapshot with defineProperty(writable:false) and then expose
   * the very same value: that exact case is accepted; every other conflict still throws like a normal assignment.
   */
  function contextBridge(win) {
    return {
      exposeInMainWorld(key, value) {
        const own = Object.getOwnPropertyDescriptor(win, key);
        if (own && 'value' in own && !own.writable && Object.is(own.value, value)) return;
        win[key] = value;
      },
      exposeInIsolatedWorld() {},
    };
  }

  root.MmabElectronCompat = Object.freeze({ polyfillInsecureContext, contextBridge, sha256 });
})(globalThis);
