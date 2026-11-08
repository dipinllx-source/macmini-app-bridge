// MMAB MessagePort relay (classic script, M3; design §9.4 / §14.2 "MessagePort 中继").
// The same file runs in the browser (prefix "w") and inside the native renderer via CDP (prefix "n").
// Requires codec.js (globalThis.MmabCodec). Exposes globalThis.MmabPorts.create(options).
//
// toWire(value): every MessagePort found in a plain value tree is registered under a fresh id in this side's id space
// and replaced by a PortRef; messages arriving on it are forwarded with sendData(id, mmab-sv1 text).
// fromWire(value, transfers): every PortRef from the other side becomes a fresh MessageChannel; port1 is kept and
// relayed under the remote id, port2 replaces the reference and is pushed to `transfers`.
// Ids are never reused within one relay; frames for unknown / closed ids are dropped.
(function (root) {
  'use strict';
  if (root.MmabPorts) return;
  const { encode, decode, PortRef } = root.MmabCodec;
  const MAX_PORTS = 256;

  const isPlain = (v) => v !== null && typeof v === 'object'
    && (Object.getPrototypeOf(v) === Object.prototype || Object.getPrototypeOf(v) === null);

  /**
   * @param {object} o
   * @param {'w'|'n'} o.prefix id space of ports created on this side
   * @param {(id: string, payloadText: string) => void} o.sendData
   * @param {(id: string) => void} [o.sendClose]
   */
  function create(o) {
    if (o.prefix !== 'w' && o.prefix !== 'n') throw new Error('port prefix must be w or n');
    const ports = new Map();
    const closedIds = new Set();
    let next = 0;
    let frames = 0;

    function attach(id, port) {
      if (ports.size >= MAX_PORTS) throw Object.assign(new Error('port limit'), { code: 'QUEUE_LIMIT' });
      ports.set(id, port);
      port.onmessage = (e) => {
        frames++;
        let text;
        try {
          text = encode(toWire(e.data));
        } catch (err) {
          console.error('[mmab] port payload not encodable', err && err.code);
          return;
        }
        o.sendData(id, text);
      };
      port.start();
    }

    function toWire(v, depth = 0) {
      if (depth > 32) throw Object.assign(new Error('depth'), { code: 'CODEC_UNSUPPORTED' });
      if (typeof MessagePort !== 'undefined' && v instanceof MessagePort) {
        const id = `${o.prefix}${++next}`;
        attach(id, v);
        return new PortRef(id);
      }
      if (Array.isArray(v)) return v.map((x) => toWire(x, depth + 1));
      if (v instanceof Map) return new Map([...v].map(([k, x]) => [toWire(k, depth + 1), toWire(x, depth + 1)]));
      if (v instanceof Set) return new Set([...v].map((x) => toWire(x, depth + 1)));
      if (isPlain(v)) {
        const out = {};
        for (const k of Object.keys(v)) out[k] = toWire(v[k], depth + 1);
        return out;
      }
      return v;
    }

    function fromWire(v, transfers, depth = 0) {
      if (depth > 32) throw Object.assign(new Error('depth'), { code: 'CODEC_UNSUPPORTED' });
      if (v instanceof PortRef) {
        if (v.id[0] === o.prefix || ports.has(v.id) || closedIds.has(v.id)) {
          throw Object.assign(new Error('foreign or reused port id'), { code: 'PORT_STATE' });
        }
        const { port1, port2 } = new MessageChannel();
        attach(v.id, port1);
        transfers.push(port2);
        return port2;
      }
      if (Array.isArray(v)) return v.map((x) => fromWire(x, transfers, depth + 1));
      if (v instanceof Map) {
        return new Map([...v].map(([k, x]) => [fromWire(k, transfers, depth + 1), fromWire(x, transfers, depth + 1)]));
      }
      if (v instanceof Set) return new Set([...v].map((x) => fromWire(x, transfers, depth + 1)));
      if (isPlain(v)) {
        const out = {};
        for (const k of Object.keys(v)) out[k] = fromWire(v[k], transfers, depth + 1);
        return out;
      }
      return v;
    }

    return {
      toWire,
      fromWire,
      /** encode a value (with ports) for the wire */
      encode: (value) => encode(toWire(value)),
      /** decode wire text; new remote ports are created and appended to transfers */
      decode: (text, transfers) => fromWire(decode(text), transfers),
      receiveData(id, payloadText) {
        const port = ports.get(id);
        if (!port) return false;
        const transfers = [];
        const value = fromWire(decode(payloadText), transfers);
        port.postMessage(value, transfers);
        return true;
      },
      receiveClose(id) {
        const port = ports.get(id);
        if (!port) return false;
        ports.delete(id);
        closedIds.add(id);
        port.close();
        return true;
      },
      /** close one port locally and tell the other side */
      close(id) {
        if (this.receiveClose(id) && o.sendClose) o.sendClose(id);
      },
      closeAll() {
        for (const [id, port] of ports) {
          closedIds.add(id);
          port.close();
        }
        ports.clear();
      },
      size: () => ports.size,
      frames: () => frames,
    };
  }

  root.MmabPorts = Object.freeze({ create });
})(globalThis);
