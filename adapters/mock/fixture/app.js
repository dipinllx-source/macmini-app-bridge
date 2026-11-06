// Fixture "original app" script. Uses only the bridge API injected by the platform shim.
const status = document.getElementById('status');
const result = document.getElementById('result');

try {
  await window.mmab.ready;
  const echoed = await window.mmab.invoke('mock.echo', {
    text: '你好，引桥',
    when: new Date(0),
    big: 2n ** 70n,
    bytes: new Uint8Array([1, 2, 3]),
    tags: new Set(['a', 'b']),
  });
  const sum = await window.mmab.invoke('mock.add', { a: 2, b: 40 });
  const ok = echoed.text === '你好，引桥' && echoed.big === 2n ** 70n && echoed.bytes instanceof Uint8Array
    && echoed.tags instanceof Set && sum === 42;
  result.textContent = JSON.stringify({ text: echoed.text, big: String(echoed.big), sum });
  status.textContent = ok ? '往返成功' : '往返结果不一致';
  status.dataset.state = ok ? 'ok' : 'mismatch';
} catch (e) {
  status.textContent = `失败：${e?.code ?? e?.message ?? e}`;
  status.dataset.state = 'error';
}
