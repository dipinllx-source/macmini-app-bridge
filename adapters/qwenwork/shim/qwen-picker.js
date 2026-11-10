// MMAB qwenwork 网页版文件 / 目录选择器（能力 file.pick，classic script，先于 qwen-electron.js 注入）。
//
// 原版在 Mac 主进程弹 electron.dialog.showOpenDialog；远程浏览器看不到那个面板，请求会一直挂起。
// qwen-electron.js 截住 files.selectFilesAndFolders，改由这里在页内弹出选择器，目录内容来自驱动的
// qwenwork.fs.list（只列名称，限用户主目录）。界面放在 Shadow DOM 里，不受应用样式影响、也不影响应用。
(function (root) {
  'use strict';
  if (root.__mmabQwPicker) return;
  var doc = root.document;
  var LAST_KEY = 'mmab.qwenwork.pick.last';

  var CSS = [
    ':host{all:initial}',
    '.mask{position:fixed;inset:0;z-index:2147483646;background:rgba(15,18,30,.42);display:flex;align-items:center;justify-content:center;',
    'font:14px/1.45 -apple-system,BlinkMacSystemFont,"PingFang SC","Microsoft YaHei",sans-serif;color:#1f2330}',
    '.card{width:min(680px,94vw);height:min(560px,88vh);background:#fff;border-radius:14px;box-shadow:0 18px 60px rgba(0,0,0,.28);',
    'display:flex;flex-direction:column;overflow:hidden}',
    '.hd{padding:16px 18px 10px}.hd h3{margin:0;font-size:16px;font-weight:650}',
    '.hd p{margin:4px 0 0;font-size:12px;color:#6b7086}',
    '.bar{display:flex;gap:6px;padding:0 18px 10px;align-items:center}',
    '.bar input{flex:1;min-width:0;height:30px;box-sizing:border-box;border:1px solid #d5d8e3;border-radius:8px;padding:0 10px;font:13px ui-monospace,Menlo,monospace;color:#1f2330}',
    '.bar input:focus{outline:2px solid #8b7cf6;border-color:transparent}',
    'button{height:30px;box-sizing:border-box;padding:0 12px;border-radius:8px;border:1px solid #d5d8e3;background:#fff;color:#1f2330;font:inherit;font-size:13px;cursor:pointer;white-space:nowrap}',
    'button:hover:not(:disabled){background:#f3f2fb}button:disabled{opacity:.45;cursor:default}',
    'button.pri{background:#6d5ce8;border-color:#6d5ce8;color:#fff}button.pri:hover:not(:disabled){background:#5b4bd6}',
    '.list{flex:1;overflow:auto;margin:0 18px;border:1px solid #e6e8ef;border-radius:10px}',
    '.row{display:flex;align-items:center;gap:8px;padding:6px 10px;cursor:pointer;user-select:none;border-bottom:1px solid #e9eaf1}',
    '.row:hover{background:#f7f6fd}.row.sel{background:#ebe7fe}.row .ic{width:18px;text-align:center;flex:none}',
    '.row .nm{flex:1;min-width:0;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}',
    '.row .go{flex:none;color:#6b7086;font-size:14px}',
    '.empty,.err{padding:18px;color:#6b7086;font-size:13px}.err{color:#c4362c}',
    '.ft{display:flex;align-items:center;gap:8px;padding:12px 18px}',
    '.ft label{display:flex;align-items:center;gap:6px;font-size:12px;color:#6b7086;cursor:pointer}',
    '.ft .sp{flex:1}.ft .pick{font-size:12px;color:#6b7086;max-width:300px;margin-right:8px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}',
  ].join('');

  var REASON = {
    NOT_FOUND: '路径不存在',
    NOT_DIRECTORY: '不是目录',
    ACCESS_DENIED: '没有访问权限（macOS 可能需要给 node 授予“文件与文件夹”权限）',
    OUTSIDE_ROOT: '只能浏览 Mac 用户主目录内的路径',
    NOT_ABSOLUTE: '请输入绝对路径，或以 ~/ 开头',
    READ_FAILED: '读取目录失败',
  };

  function el(tag, cls, text) {
    var e = doc.createElement(tag);
    if (cls) e.className = cls;
    if (text != null) e.textContent = text;
    return e;
  }

  /**
   * @param {object} o
   * @param {'folders'|'files'|'both'} o.type
   * @param {string} [o.title]
   * @param {boolean} [o.multiSelect]
   * @param {string} [o.defaultPath]
   * @param {(req: object) => Promise<object>} o.list   qwenwork.fs.list
   * @param {(req: object) => Promise<object>} o.describe qwenwork.fs.describe
   * @returns {Promise<{canceled: boolean, paths: object[]}>}
   */
  function open(o) {
    return new Promise(function (resolve) {
      var type = o.type === 'files' || o.type === 'both' ? o.type : 'folders';
      var multi = !!o.multiSelect;
      var host = el('div');
      host.setAttribute('data-mmab-picker', '');
      var sh = host.attachShadow({ mode: 'open' });
      var style = el('style');
      style.textContent = CSS;
      sh.appendChild(style);

      var mask = el('div', 'mask');
      var card = el('div', 'card');
      card.setAttribute('role', 'dialog');
      card.setAttribute('aria-modal', 'true');
      var hd = el('div', 'hd');
      var defTitle = type === 'folders' ? '选择目录' : type === 'files' ? '选择文件' : '选择文件或目录';
      hd.appendChild(el('h3', null, o.title && /[\u4e00-\u9fa5]/.test(o.title) ? o.title : defTitle));
      hd.appendChild(el('p', null, '浏览的是运行千问办公的 Mac 上的文件（仅限用户主目录）。双击进入目录。'));
      var bar = el('div', 'bar');
      var up = el('button', null, '↑ 上一级');
      var homeBtn = el('button', null, '主目录');
      var input = el('input');
      input.spellcheck = false;
      input.setAttribute('aria-label', '路径');
      var goBtn = el('button', null, '前往');
      bar.appendChild(up);
      bar.appendChild(homeBtn);
      bar.appendChild(input);
      bar.appendChild(goBtn);
      var list = el('div', 'list');
      list.setAttribute('role', 'listbox');
      var ft = el('div', 'ft');
      var hiddenLbl = el('label');
      var hiddenBox = el('input');
      hiddenBox.type = 'checkbox';
      hiddenLbl.appendChild(hiddenBox);
      hiddenLbl.appendChild(doc.createTextNode('显示隐藏项'));
      var pickInfo = el('span', 'pick');
      var cancelBtn = el('button', null, '取消');
      var okBtn = el('button', 'pri', '选择');
      ft.appendChild(hiddenLbl);
      ft.appendChild(el('span', 'sp'));
      ft.appendChild(pickInfo);
      ft.appendChild(cancelBtn);
      ft.appendChild(okBtn);
      card.appendChild(hd);
      card.appendChild(bar);
      card.appendChild(list);
      card.appendChild(ft);
      mask.appendChild(card);
      sh.appendChild(mask);

      var cur = null; // last successful listing
      var selected = []; // absolute paths
      var busy = false;
      var finished = false;

      function join(dir, name) {
        return dir === '/' ? '/' + name : dir + '/' + name;
      }
      function selectable(entry) {
        if (type === 'folders') return entry.type === 'folder';
        if (type === 'files') return entry.type === 'file';
        return true;
      }
      function refreshFooter() {
        if (selected.length) {
          var names = selected.map(function (p) { return p.slice(p.lastIndexOf('/') + 1); });
          pickInfo.textContent = '已选：' + names.join('、');
          okBtn.disabled = false;
        } else if (type === 'folders' && cur) {
          pickInfo.textContent = '将选择当前目录';
          okBtn.disabled = false;
        } else {
          pickInfo.textContent = '';
          okBtn.disabled = true;
        }
        okBtn.textContent = '选择';
        up.disabled = !cur || !cur.parent;
      }
      function render() {
        list.textContent = '';
        if (!cur.entries.length) list.appendChild(el('div', 'empty', type === 'folders' ? '这里没有子目录' : '这里是空的'));
        cur.entries.forEach(function (e) {
          var p = join(cur.path, e.name);
          var row = el('div', 'row' + (selected.indexOf(p) >= 0 ? ' sel' : ''));
          row.setAttribute('role', 'option');
          row.appendChild(el('span', 'ic', e.type === 'folder' ? '📁' : '📄'));
          row.appendChild(el('span', 'nm', e.name));
          if (e.type === 'folder') row.appendChild(el('span', 'go', '›'));
          row.addEventListener('click', function () {
            if (!selectable(e)) return;
            var i = selected.indexOf(p);
            if (multi) {
              if (i >= 0) selected.splice(i, 1);
              else selected.push(p);
            } else {
              selected = i >= 0 ? [] : [p];
            }
            render();
          });
          row.addEventListener('dblclick', function () {
            if (e.type === 'folder') load(p);
            else if (selectable(e)) {
              selected = [p];
              confirm();
            }
          });
          list.appendChild(row);
        });
        if (cur.truncated) list.appendChild(el('div', 'empty', '条目过多，只显示前 ' + cur.entries.length + ' 项'));
        refreshFooter();
      }
      function showError(msg) {
        list.textContent = '';
        list.appendChild(el('div', 'err', msg));
        if (cur) {
          var back = el('button', null, '回到 ' + cur.path);
          back.style.margin = '0 18px 12px';
          back.addEventListener('click', function () { load(cur.path); });
          list.appendChild(back);
        }
        refreshFooter();
      }
      function load(p, homeOnFailure) {
        if (busy) return;
        busy = true;
        list.textContent = '';
        list.appendChild(el('div', 'empty', '正在读取…'));
        o.list({ path: p || '', files: type !== 'folders', hidden: hiddenBox.checked }).then(function (r) {
          busy = false;
          if (finished) return;
          if (!r || !r.ok) {
            if (homeOnFailure && p) return load('');
            input.value = (r && r.path) || p || '';
            showError(REASON[r && r.reason] || '无法打开该路径');
            return;
          }
          cur = r;
          selected = [];
          input.value = r.path;
          try { root.localStorage.setItem(LAST_KEY, r.path); } catch (e) { /* storage disabled */ }
          render();
        }, function (err) {
          busy = false;
          if (finished) return;
          showError('读取失败：' + ((err && (err.code || err.message)) || '未知错误'));
        });
      }
      function finish(result) {
        if (finished) return;
        finished = true;
        doc.removeEventListener('keydown', onKey, true);
        host.remove();
        resolve(result);
      }
      function confirm() {
        var paths = selected.length ? selected.slice() : type === 'folders' && cur ? [cur.path] : [];
        if (!paths.length) return;
        okBtn.disabled = true;
        o.describe({ paths: paths }).then(function (r) {
          if (!r || !r.ok) {
            okBtn.disabled = false;
            showError(REASON[r && r.reason] || '所选路径不可用');
            return;
          }
          finish({ canceled: false, paths: r.paths });
        }, function (err) {
          okBtn.disabled = false;
          showError('选择失败：' + ((err && (err.code || err.message)) || '未知错误'));
        });
      }
      function onKey(ev) {
        // 弹窗打开期间按键不交给应用（避免触发应用快捷键）
        if (ev.key === 'Escape') {
          ev.preventDefault();
          finish({ canceled: true, paths: [] });
        } else if (ev.key === 'Enter') {
          ev.preventDefault();
          if (sh.activeElement === input) load(input.value);
          else if (!okBtn.disabled) confirm();
        } else if (ev.key === 'Backspace' && sh.activeElement !== input && cur && cur.parent) {
          ev.preventDefault();
          load(cur.parent);
        }
        ev.stopPropagation();
      }

      up.addEventListener('click', function () { if (cur && cur.parent) load(cur.parent); });
      homeBtn.addEventListener('click', function () { load(cur && cur.home ? cur.home : ''); });
      goBtn.addEventListener('click', function () { load(input.value); });
      hiddenBox.addEventListener('change', function () { load(cur ? cur.path : input.value); });
      cancelBtn.addEventListener('click', function () { finish({ canceled: true, paths: [] }); });
      okBtn.addEventListener('click', confirm);
      mask.addEventListener('mousedown', function (ev) {
        if (ev.target === mask) finish({ canceled: true, paths: [] });
      });
      doc.addEventListener('keydown', onKey, true);
      (doc.body || doc.documentElement).appendChild(host);

      var start = o.defaultPath || '';
      if (!start) {
        try { start = root.localStorage.getItem(LAST_KEY) || ''; } catch (e) { start = ''; }
      }
      // 起点不可用（例如上次的目录已被删除）时回到主目录
      load(start, true);
      input.focus();
    });
  }

  /** 轻提示：用于“此操作会在 Mac 上弹原生对话框，远程网页不支持”。 */
  function toast(text) {
    var host = el('div');
    var sh = host.attachShadow({ mode: 'open' });
    var box = el('div', null, text);
    box.setAttribute('role', 'status');
    box.style.cssText = 'position:fixed;left:50%;top:24px;transform:translateX(-50%);z-index:2147483647;max-width:min(560px,90vw);'
      + 'background:#2b2f3e;color:#fff;padding:10px 16px;border-radius:10px;box-shadow:0 8px 30px rgba(0,0,0,.25);'
      + 'font:13px/1.5 -apple-system,BlinkMacSystemFont,"PingFang SC","Microsoft YaHei",sans-serif';
    sh.appendChild(box);
    (doc.body || doc.documentElement).appendChild(host);
    root.setTimeout(function () { host.remove(); }, 5000);
  }

  root.__mmabQwPicker = Object.freeze({ open: open, toast: toast });
})(globalThis);
