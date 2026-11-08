// M3 runtime contract: connection tracking, adapter-initiated events / port frames, CONTROL mmab.close both ways,
// lease change forgets connections, raw handlers. Platform side played by testing/platform-client.mjs.
import assert from 'node:assert/strict';
import { test } from 'node:test';
import { decode, encode, PortRef } from '../web/codec.mjs';
import { inProcess, platform } from './testing/platform-client.mjs';

async function setup(extra = {}) {
  const closed = [];
  const portEvents = [];
  const adapter = await inProcess({
    handlers: {
      'test.attach': () => 'ok',
      'test.raw': { raw: true, handle: (text) => text },
      'test.port': (v, ctx) => ({ port: new PortRef('n1'), connection: ctx.connectionId }),
    },
    ports: {
      onData: (connectionId, portId, payloadText, api) => {
        portEvents.push(['data', connectionId, portId, decode(payloadText)]);
        return api.portData(connectionId, portId, encode({ echo: decode(payloadText) }));
      },
      onClose: (connectionId, portId) => portEvents.push(['close', connectionId, portId]),
    },
    onConnectionClosed: (id) => closed.push(id),
    ...extra,
  });
  const p = await platform(adapter);
  p.fence('lease-1', 7);
  return { adapter, p, closed, portEvents };
}

test('connections are tracked from fenced requests; events reach only tracked, current connections', async () => {
  const { adapter, p, closed } = await setup();
  try {
    assert.equal(await adapter.api.emit('c-1', 'test.evt', 1), false, 'unknown connection: not deliverable');
    const r = await p.response(p.request('c-1', 'test.attach', encode(undefined)));
    assert.equal(decode(r.payload), 'ok');
    assert.deepEqual(adapter.api.connections(), ['c-1']);
    assert.equal(await adapter.api.emit('c-1', 'test.evt', { n: 1 }), true);
    const ev = await p.nextWhere((e) => e.messageKind === 'EVENT');
    assert.equal(ev.connectionId, 'c-1');
    assert.equal(ev.leaseId, 'lease-1');
    assert.equal(ev.fencingToken, 7);
    assert.deepEqual(decode(ev.payload), { n: 1 });

    // sync-* one-shot calls are not tracked as browser connections
    await p.response(p.request('sync-abc', 'test.attach', encode(undefined)));
    assert.deepEqual(adapter.api.connections(), ['c-1']);

    // platform reports the browser connection closed
    p.closeConnection('c-1');
    await new Promise((r) => setTimeout(r, 50));
    assert.deepEqual(closed, ['c-1']);
    assert.equal(await adapter.api.emit('c-1', 'test.evt', 2), false);
  } finally {
    p.close();
    await adapter.close();
  }
});

test('a lease change forgets every connection of the old lease', async () => {
  const { adapter, p, closed } = await setup();
  try {
    await p.response(p.request('c-1', 'test.attach', encode(undefined)));
    await p.response(p.request('c-2', 'test.attach', encode(undefined)));
    p.fence('lease-2', 8);
    await new Promise((r) => setTimeout(r, 50));
    assert.deepEqual(closed.sort(), ['c-1', 'c-2']);
    assert.deepEqual(adapter.api.connections(), []);
  } finally {
    p.close();
    await adapter.close();
  }
});

test('port frames are dispatched only for tracked connections with the current fence', async () => {
  const { adapter, p, portEvents } = await setup();
  try {
    p.portData('c-9', 'w1', encode('ignored')); // never made a request: dropped
    const r = await p.response(p.request('c-1', 'test.port', encode(undefined)));
    assert.match(r.payload, /"\$t":"port","id":"n1"/);
    p.portData('c-1', 'n1', encode('hi'));
    const echo = await p.nextWhere((e) => e.messageKind === 'PORT_DATA');
    assert.equal(echo.streamId, 'n1');
    assert.deepEqual(decode(echo.payload), { echo: 'hi' });
    p.portClose('c-1', 'n1');
    await new Promise((r) => setTimeout(r, 50));
    assert.deepEqual(portEvents.map((e) => e[0]), ['data', 'close']);
    assert.equal(portEvents[0][1], 'c-1');
  } finally {
    p.close();
    await adapter.close();
  }
});

test('the adapter can ask the platform to close a connection; raw handlers pass mmab-sv1 text through', async () => {
  const { adapter, p, closed } = await setup();
  try {
    const text = encode({ x: [1, 2n] });
    const r = await p.response(p.request('c-1', 'test.raw', text));
    assert.equal(r.payload, text);
    assert.equal(await adapter.api.closeConnection('c-1', 'QUEUE_LIMIT'), true);
    const ctl = await p.nextWhere((e) => e.messageKind === 'CONTROL');
    assert.equal(ctl.operation, 'mmab.close');
    assert.equal(ctl.connectionId, 'c-1');
    assert.equal(ctl.error.code, 'QUEUE_LIMIT');
    assert.deepEqual(closed, ['c-1']);
  } finally {
    p.close();
    await adapter.close();
  }
});

test('when the platform link drops every connection is forgotten', async () => {
  const { adapter, p, closed } = await setup();
  await p.response(p.request('c-1', 'test.attach', encode(undefined)));
  p.close();
  await new Promise((r) => setTimeout(r, 100));
  assert.deepEqual(closed, ['c-1']);
  await adapter.close();
});
