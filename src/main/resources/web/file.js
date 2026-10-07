'use strict';

(function () {
  const AR = window.AR;
  const call = AR.call;
  const h = AR.h;
  const esc = AR.esc;
  const icon = AR.icon;
  const fail = AR.fail;

  const EXT_LANG = {
    cs: 'csharp', java: 'java', kt: 'kotlin', kts: 'kotlin', gradle: 'kotlin', js: 'javascript', mjs: 'javascript',
    cjs: 'javascript', jsx: 'javascript', ts: 'typescript', tsx: 'typescript', json: 'json', asmdef: 'json',
    asmref: 'json', jsonc: 'json', inputactions: 'json', xml: 'xml', csproj: 'xml', props: 'xml', targets: 'xml',
    uxml: 'xml', xaml: 'xml', html: 'xml', htm: 'xml', svg: 'xml', config: 'xml', plist: 'xml', resx: 'xml',
    uss: 'css', css: 'css', scss: 'scss', less: 'less', yaml: 'yaml', yml: 'yaml', unity: 'yaml', prefab: 'yaml',
    asset: 'yaml', mat: 'yaml', meta: 'yaml', controller: 'yaml', anim: 'yaml', overridecontroller: 'yaml',
    physicmaterial: 'yaml', mask: 'yaml', playable: 'yaml', lighting: 'yaml', spriteatlas: 'yaml', md: 'markdown',
    sh: 'bash', bash: 'bash', py: 'python', shader: 'cpp', hlsl: 'cpp', cginc: 'cpp', compute: 'cpp', glsl: 'cpp',
    shadergraph: 'json', c: 'c', h: 'cpp', cpp: 'cpp', hpp: 'cpp', cc: 'cpp', go: 'go', rs: 'rust', lua: 'lua',
    sql: 'sql', ini: 'ini', cfg: 'ini', toml: 'ini', editorconfig: 'ini', diff: 'diff', patch: 'diff', php: 'php',
    rb: 'ruby', swift: 'swift', m: 'objectivec', r: 'r', vb: 'vbnet', makefile: 'makefile',
  };
  const MAX_HIGHLIGHT_CHARS = 400000;
  const STATUS_LETTER = { A: 'A', M: 'M', D: 'D', R: 'R', C: 'C', T: 'T', U: '!', '?': 'N' };
  const STATUS_NAME = {
    A: 'добавлен', M: 'изменён', D: 'удалён', R: 'переименован', C: 'скопирован', T: 'сменился тип',
    U: 'конфликт', '?': 'новый, ещё не в git',
  };

  const P = Object.assign(
    { context: 3, view: 'unified', ignoreWs: false, wrap: true, showDone: false, showComments: true },
    (AR.BOOT.extra && AR.BOOT.extra.prefs) || {});
  const persist = AR.debounce(function () {
    call('savePrefs', {
      context: P.context, view: P.view, ignoreWs: P.ignoreWs, wrap: P.wrap, showDone: P.showDone, showComments: P.showComments,
    }).catch(function () {});
  }, 300);

  const S = {
    info: null, path: null, file: null, payload: null, models: new Map(), model: null, rows: null,
    comments: [], drag: null, lastClick: null, composer: null, reveal: new Set(),
    textSel: null, focusId: null, loadSeq: 0,
  };

  // ------------------------------------------------------------------ shell
  const app = document.getElementById('app');
  app.innerHTML = '<div class="view-bar" id="viewBar"></div><div class="file-body" id="fileBody" tabindex="-1"></div>';

  function byId(id) { return document.getElementById(id); }
  const viewBar = byId('viewBar');
  const body = byId('fileBody');
  document.body.classList.toggle('nowrap', !P.wrap);

  const floatBtn = h('button', { class: 'primary float-btn', hidden: true });
  floatBtn.innerHTML = icon('comment') + ' Комментировать <span style="opacity:.7">c</span>';
  document.body.append(floatBtn);
  floatBtn.addEventListener('mousedown', function (e) { e.preventDefault(); e.stopPropagation(); });
  floatBtn.addEventListener('click', function () { commentFromTextSelection(); });
  let pressInCode = false;
  document.addEventListener('mousedown', function (e) {
    if (floatBtn.contains(e.target)) return;
    hideFloat();
    pressInCode = e.button === 0 && !!(e.target.closest && e.target.closest('table.diff tr.r td.cd'));
  }, true);
  document.addEventListener('selectionchange', function () {
    if (floatBtn.hidden) return;
    const sel = window.getSelection();
    if (!sel || sel.isCollapsed) hideFloat();
  });
  window.addEventListener('blur', function () { hideFloat(); });
  AR.on('outside', function () { hideFloat(); });
  document.addEventListener('visibilitychange', function () { if (document.hidden) hideFloat(); });

  // ------------------------------------------------------------------ utils
  function plural(n, one, few, many) {
    const a = Math.abs(n) % 100;
    const b = a % 10;
    if (a > 10 && a < 20) return many;
    if (b > 1 && b < 5) return few;
    if (b === 1) return one;
    return many;
  }

  function fmtSize(n) {
    if (n == null) return '—';
    if (n < 1024) return n + ' Б';
    if (n < 1024 * 1024) return (n / 1024).toFixed(n < 10240 ? 1 : 0) + ' КБ';
    return (n / 1024 / 1024).toFixed(1) + ' МБ';
  }

  function baseName(path) { return path.slice(path.lastIndexOf('/') + 1); }

  function langFor(path) {
    const name = baseName(path).toLowerCase();
    if (name === 'makefile') return 'makefile';
    if (name === 'dockerfile') return 'bash';
    const dot = name.lastIndexOf('.');
    return dot < 0 ? null : (EXT_LANG[name.slice(dot + 1)] || null);
  }

  function pkey(path) { return path + '|' + (P.ignoreWs ? 1 : 0); }

  function isOpen(c) { return AR.isOpen(c); }

  function fileComments(f) {
    return S.comments.filter(function (c) {
      return c.filePath && (c.filePath === f.projectPath || (f.projectOldPath && c.filePath === f.projectOldPath));
    });
  }

  function numsHtml(f) {
    if (f.status === '?') return '<span class="add">new</span>';
    if (f.binary) return '<span class="dim">bin</span>';
    return (f.added ? '<span class="add">+' + f.added + '</span>' : '') + (f.deleted ? '<span class="del">−' + f.deleted + '</span>' : '');
  }

  function msg(title, text, extra) {
    return '<div class="msg"><b>' + esc(title) + '</b>' + (text ? '<span>' + esc(text) + '</span>' : '') + (extra || '') + '</div>';
  }

  // ------------------------------------------------------------------ loading
  function init(keepScroll) {
    const seq = ++S.loadSeq;
    call('init').then(function (d) {
      if (seq !== S.loadSeq) return;
      const samePath = S.path === d.path;
      if (!samePath) {
        S.composer = null;
        S.reveal = new Set();
        S.lastClick = null;
        S.models.clear();
        S.payload = null;
      }
      S.info = d;
      S.path = d.path;
      S.file = d.file;
      S.comments = d.comments || [];
      if (d.focus) S.focusId = d.focus;
      renderHead();
      renderViewBar();
      if (!d.file) {
        S.payload = null;
        body.innerHTML = d.error
          ? msg('Не удалось получить изменения', d.error, '<button data-act="reload">' + icon('refresh') + ' Повторить</button>')
          : msg('Файла нет в текущем сравнении', 'Он не изменён относительно выбранной базы.');
        return;
      }
      loadPayload(keepScroll && samePath, false, function () {
        if (d.fileComment) openFileComposer();
      });
    }, function (e) {
      body.innerHTML = msg('Не удалось загрузить', e.message);
    });
  }

  function loadPayload(keepScroll, force, then) {
    const seq = ++S.loadSeq;
    const spin = setTimeout(function () {
      if (seq === S.loadSeq) body.innerHTML = '<div class="msg"><span><span class="spinner"></span> Загрузка…</span></div>';
    }, 150);
    call('file', { ignoreWs: P.ignoreWs, force: !!force }).then(function (p) {
      if (seq !== S.loadSeq) return;
      S.payload = p;
      S.models.clear();
      renderFile({ keepScroll: keepScroll });
      if (then) then();
    }, function (e) {
      if (seq === S.loadSeq) body.innerHTML = msg('Не удалось загрузить файл', e.message);
    }).finally(function () { clearTimeout(spin); });
  }

  // ------------------------------------------------------------------ header
  function renderHead() {
    renderViewBar();
  }

  function renderViewBar() {
    const ctx = [[3, '±3 строки'], [5, '±5 строк'], [10, '±10 строк'], [25, '±25 строк'], [-1, 'весь файл']];
    const f = S.file;
    viewBar.innerHTML =
      (f ? '<button class="ghost" id="fileComment" title="Комментарий ко всему файлу">' + icon('comment') + ' К файлу</button>' : '') +
      (f && f.status !== 'D' ? '<button class="ghost" id="openFile" title="Открыть файл в редакторе">' + icon('open') + ' Открыть</button>' : '') +
      (f ? '<span class="vsep"></span>' : '') +
      '<span class="tb-group"><span class="lbl">Контекст</span><select id="ctxSel">' + ctx.map(function (c) {
        return '<option value="' + c[0] + '"' + (Number(P.context) === c[0] ? ' selected' : '') + '>' + c[1] + '</option>';
      }).join('') + '</select></span>' +
      '<span class="seg" id="viewSeg"><button data-v="unified" class="' + (P.view !== 'split' ? 'on' : '') +
      '">Одна колонка</button><button data-v="split" class="' + (P.view === 'split' ? 'on' : '') + '">Две колонки</button></span>' +
      '<label class="chk" title="Не показывать изменения только в пробелах и отступах"><input type="checkbox" id="wsChk"' +
      (P.ignoreWs ? ' checked' : '') + '> без пробелов</label>' +
      '<label class="chk" title="Переносить длинные строки"><input type="checkbox" id="wrapChk"' + (P.wrap ? ' checked' : '') + '> перенос</label>' +
      '<label class="chk" title="Показывать комментарии в diff (отметки на полях видны всегда)"><input type="checkbox" id="commentsChk"' +
      (P.showComments ? ' checked' : '') + '> комментарии</label>' +
      '<label class="chk" title="Показывать выполненные и отменённые комментарии"><input type="checkbox" id="doneChk"' +
      (P.showDone ? ' checked' : '') + (P.showComments ? '' : ' disabled') + '> выполненные</label>' +
      '<span class="sp"></span>' +
      '<button class="ghost ib" id="refreshBtn" title="Обновить (r)">' + icon('refresh') + '</button>' +
      '<button class="ghost ib" id="helpBtn" title="Горячие клавиши (?)">' + icon('help') + '</button>';
    const fileComment = byId('fileComment');
    if (fileComment) fileComment.onclick = openFileComposer;
    const openBtn = byId('openFile');
    if (openBtn) openBtn.onclick = function () { call('openInEditor', { line: firstChangedLine() }).catch(fail); };
    byId('ctxSel').onchange = function (e) { P.context = Number(e.target.value); persist(); renderFile({}); };
    byId('viewSeg').onclick = function (e) {
      const b = e.target.closest('button');
      if (!b || b.dataset.v === P.view) return;
      P.view = b.dataset.v;
      S.lastClick = null;
      persist();
      renderViewBar();
      renderFile({ keepScroll: true });
    };
    byId('wsChk').onchange = function (e) { P.ignoreWs = e.target.checked; persist(); if (S.file) loadPayload(true, false); };
    byId('wrapChk').onchange = function (e) { P.wrap = e.target.checked; persist(); document.body.classList.toggle('nowrap', !P.wrap); };
    byId('doneChk').onchange = function (e) { P.showDone = e.target.checked; persist(); renderFile({ keepScroll: true }); };
    byId('commentsChk').onchange = function (e) {
      P.showComments = e.target.checked;
      persist();
      renderViewBar();
      renderFile({ keepScroll: true });
    };
    byId('refreshBtn').onclick = function () { reload(); };
    byId('helpBtn').onclick = toggleHelp;
  }

  function reload() {
    call('reload').catch(fail);
    init(true);
  }

  let helpEl = null;
  function toggleHelp() {
    if (helpEl) { helpEl.remove(); helpEl = null; return; }
    const keys = [
      ['j / k', 'следующий / предыдущий файл'],
      ['n / p', 'следующее / предыдущее изменение'],
      ['клик по номеру строки', 'комментарий к строке (Shift — диапазон, можно тянуть)'],
      ['выделить код, затем c', 'комментарий к выделенному'],
      ['Ctrl + клик по номеру', 'открыть строку в редакторе'],
      ['Ctrl+Enter / Esc', 'сохранить / отменить комментарий'],
      ['r', 'обновить'],
    ];
    helpEl = h('div', { class: 'help', html: '<table>' + keys.map(function (k) {
      return '<tr><td><kbd>' + esc(k[0]) + '</kbd></td><td>' + esc(k[1]) + '</td></tr>';
    }).join('') + '</table>' });
    document.body.append(helpEl);
  }

  function firstChangedLine() {
    const rows = S.rows || [];
    for (let i = 0; i < rows.length; i++) {
      if (rows[i].t !== 'c' && rows[i].n != null) return rows[i].n + 1;
    }
    return 1;
  }

  // ------------------------------------------------------------------ model
  function splitLines(text) {
    if (!text) return [];
    const lines = text.split('\n');
    if (lines[lines.length - 1] === '') lines.pop();
    return lines;
  }

  function model(p) {
    const key = pkey(p.path);
    let m = S.models.get(key);
    if (m && m.p === p) return m;
    m = { p: p, oldL: splitLines(p.oldText), newL: splitLines(p.newText), lang: langFor(p.path), showAll: false };
    const n1 = m.oldL.length;
    const n2 = m.newL.length;
    const frags = [];
    (p.fragments || []).forEach(function (f) {
      const s1 = Math.min(f[0], n1);
      const e1 = Math.min(f[1], n1);
      const s2 = Math.min(f[2], n2);
      const e2 = Math.min(f[3], n2);
      if (s1 === e1 && s2 === e2) return;
      frags.push([s1, e1, s2, e2]);
    });
    m.frags = frags;
    m.words = p.words || { old: {}, new: {} };

    const U = [];
    const SP = [];
    let i1 = 0;
    let i2 = 0;
    function context(to1, to2) {
      while (i1 < to1 && i2 < to2) {
        U.push({ t: 'c', o: i1, n: i2 });
        SP.push({ t: 'c', o: i1, n: i2 });
        i1++; i2++;
      }
      while (i1 < to1) { U.push({ t: 'd', o: i1, n: null }); SP.push({ t: 'x', o: i1, n: null }); i1++; }
      while (i2 < to2) { U.push({ t: 'a', o: null, n: i2 }); SP.push({ t: 'x', o: null, n: i2 }); i2++; }
    }
    frags.forEach(function (f) {
      context(f[0], f[2]);
      for (let k = f[0]; k < f[1]; k++) U.push({ t: 'd', o: k, n: null });
      for (let k = f[2]; k < f[3]; k++) U.push({ t: 'a', o: null, n: k });
      const d = f[1] - f[0];
      const a = f[3] - f[2];
      for (let k = 0; k < Math.max(d, a); k++) SP.push({ t: 'x', o: k < d ? f[0] + k : null, n: k < a ? f[2] + k : null });
      i1 = f[1];
      i2 = f[3];
    });
    context(n1, n2);
    m.U = U;
    m.SP = SP;
    S.models.set(key, m);
    return m;
  }

  function highlightLines(text, lang) {
    if (lang && window.hljs && text.length <= MAX_HIGHLIGHT_CHARS && window.hljs.getLanguage(lang)) {
      try {
        return htmlToLines(window.hljs.highlight(text, { language: lang, ignoreIllegals: true }).value);
      } catch (e) { /* fall back to plain text */ }
    }
    return text.split('\n').map(function (l) { return l ? [[null, l]] : []; });
  }

  function htmlToLines(html) {
    const tpl = document.createElement('template');
    tpl.innerHTML = html;
    const lines = [[]];
    (function walk(node, cls) {
      node.childNodes.forEach(function (ch) {
        if (ch.nodeType === 3) {
          ch.nodeValue.split('\n').forEach(function (part, k) {
            if (k > 0) lines.push([]);
            if (part) lines[lines.length - 1].push([cls, part]);
          });
        } else if (ch.nodeType === 1) {
          walk(ch, cls ? cls + ' ' + ch.className : ch.className);
        }
      });
    })(tpl.content, '');
    return lines;
  }

  function lineHtml(m, side, idx) {
    const key = side === 'o' ? 'hlO' : 'hlN';
    if (!m[key]) m[key] = highlightLines((side === 'o' ? m.p.oldText : m.p.newText) || '', m.lang);
    const segs = m[key][idx] || [[null, (side === 'o' ? m.oldL : m.newL)[idx] || '']];
    const words = side === 'o' ? m.words.old : m.words.new;
    return renderSegs(segs, words && words[idx], side === 'o' ? 'w-d' : 'w-a');
  }

  function renderSegs(segs, ranges, wordClass) {
    let out = '';
    let pos = 0;
    let ri = 0;
    for (let s = 0; s < segs.length; s++) {
      const cls = segs[s][0];
      const text = segs[s][1];
      let i = 0;
      while (i < text.length) {
        const abs = pos + i;
        while (ranges && ri < ranges.length && ranges[ri][1] <= abs) ri++;
        const r = ranges && ri < ranges.length ? ranges[ri] : null;
        const inside = !!r && r[0] <= abs;
        let end = r ? Math.min(text.length, (inside ? r[1] : r[0]) - pos) : text.length;
        if (end <= i) end = text.length;
        let piece = esc(text.slice(i, end));
        if (cls) piece = '<span class="' + cls + '">' + piece + '</span>';
        if (inside) piece = '<span class="' + wordClass + '">' + piece + '</span>';
        out += piece;
        i = end;
      }
      pos += text.length;
    }
    return out;
  }

  // ------------------------------------------------------------------ comments on rows

  // ------------------------------------------------------------------ comments on rows
  function anchorComments(f, rows) {
    const ends = (S.info && S.info.ends) || {};
    const base = ends.base;
    const target = ends.target;
    const res = { top: [], byRow: new Map(), forced: new Set(), marks: new Map() };
    fileComments(f).sort(function (a, b) { return (a.createdAt || 0) - (b.createdAt || 0); }).forEach(function (c) {
      const open = isOpen(c);
      if (!open && (!P.showDone || c.status === 'CLOSED') && c.id !== S.focusId) return;
      if (c.startLine == null) { res.top.push({ c: c }); return; }
      const draft = AR.ui.editing.has(c.id) ? AR.ui.editRange.get(c.id) : null;
      let side = null;
      let s = c.startLine - 1;
      let e = (c.endLine || c.startLine) - 1;
      if (draft && draft.visual && draft.side) {
        side = draft.side;
        s = Math.min(draft.start, draft.end) - 1;
        e = Math.max(draft.start, draft.end) - 1;
      } else if (c.revision) {
        if (target && c.revision === target && c.filePath === f.projectPath) side = 'n';
        else if (c.revision === base && c.filePath === (f.projectOldPath || f.projectPath)) side = 'o';
      } else if (!target && c.filePath === f.projectPath) {
        side = 'n';
      }
      const lines = 'строки ' + AR.lineLabel(c);
      if (!side) {
        res.top.push({ c: c, note: c.revision ? 'К ' + lines + ' версии ' + c.revision.slice(0, 9) + ' — в этом сравнении её нет' : 'К ' + lines + ' рабочей копии' });
        return;
      }
      if (!rows.length) { res.top.push({ c: c }); return; }
      let last = -1;
      for (let i = 0; i < rows.length; i++) {
        const v = side === 'o' ? rows[i].o : rows[i].n;
        if (v != null && v >= s && v <= e) {
          last = i;
          res.forced.add(i);
          if (open) {
            const prev = res.marks.get(i);
            res.marks.set(i, { q: c.status === 'HAS_QUESTIONS' || !!(prev && prev.q), side: side });
          }
        }
      }
      if (last < 0) { res.top.push({ c: c, note: 'К ' + lines + ' — таких строк в файле нет' }); return; }
      if (!res.byRow.has(last)) res.byRow.set(last, []);
      res.byRow.get(last).push({ c: c, side: side });
    });
    return res;
  }

  function anchorFromRows(m, rows, a, b, side, fixed) {
    const lo = Math.max(0, Math.min(a, b));
    const hi = Math.min(rows.length - 1, Math.max(a, b));
    const sel = rows.slice(lo, hi + 1);
    const olds = sel.map(function (r) { return r.o; }).filter(function (v) { return v != null; });
    const news = sel.map(function (r) { return r.n; }).filter(function (v) { return v != null; });
    if (!fixed) side = news.length ? 'n' : 'o';
    else if (side === 'n' && !news.length) side = 'o';
    else if (side === 'o' && !olds.length) side = 'n';
    const nums = side === 'n' ? news : olds;
    if (!nums.length) return null;
    const range = function (arr) { return arr.length ? [Math.min.apply(null, arr), Math.max.apply(null, arr)] : null; };
    const oR = range(olds);
    const nR = range(news);
    const lines = side === 'n' ? m.newL : m.oldL;
    const start = Math.min.apply(null, nums);
    const end = Math.max.apply(null, nums);
    return {
      side: side, start: start, end: end, fixed: !!fixed,
      oR: fixed ? (side === 'o' ? oR : null) : oR,
      nR: fixed ? (side === 'n' ? nR : null) : nR,
      text: lines.slice(start, end + 1).join('\n'),
      snippet: sel.some(function (r) { return r.t !== 'c'; }) ? snippet(m, oR, nR) : null,
    };
  }

  function snippet(m, oR, nR) {
    const out = [];
    m.U.forEach(function (r) {
      const inO = oR && r.o != null && r.o >= oR[0] && r.o <= oR[1];
      const inN = nR && r.n != null && r.n >= nR[0] && r.n <= nR[1];
      if (!inO && !inN) return;
      if (r.t === 'd') out.push('-' + m.oldL[r.o]);
      else if (r.t === 'a') out.push('+' + m.newL[r.n]);
      else out.push(' ' + m.newL[r.n]);
    });
    return out.join('\n');
  }

  function rowsInAnchor(rows, anc) {
    const set = new Set();
    rows.forEach(function (r, i) {
      const inO = anc.oR && r.o != null && r.o >= anc.oR[0] && r.o <= anc.oR[1];
      const inN = anc.nR && r.n != null && r.n >= anc.nR[0] && r.n <= anc.nR[1];
      if (inO || inN) set.add(i);
    });
    return set;
  }

  // ------------------------------------------------------------------ rendering a file

  // ------------------------------------------------------------------ rendering a file
  const cardOpts = {
    compactDone: true,
    rerender: function () { renderFile({ keepScroll: true }); },
    rangeHint: 'или выделите строки в diff — комментарий переедет на них',
    onRangeInput: function (c, d) { applyRangeNumbers(c, d); },
    saveRange: function (c, d, text) {
      const params = {
        id: c.id,
        text: text,
        side: d.side || 'n',
        path: d.path || (S.file && S.file.path),
        startLine: Math.min(d.start, d.end),
        endLine: Math.max(d.start, d.end),
        selectedText: d.text,
      };
      if (d.snippet) params.diffSnippet = d.snippet;
      S.focusId = c.id;
      call('updateRange', params).catch(fail);
    },
  };

  /** The comment of this file whose line range is being edited right now, if any. */
  function activeEdit() {
    const id = AR.ui.activeEdit;
    if (!id || !AR.ui.editing.has(id) || !S.file) return null;
    return fileComments(S.file).find(function (c) { return c.id === id && c.startLine != null; }) || null;
  }

  function sideOf(c) {
    const ends = (S.info && S.info.ends) || {};
    if (c.revision && c.revision === ends.base && c.revision !== ends.target) return 'o';
    return 'n';
  }

  function setDraft(c, anchor, exactText) {
    const f = S.file;
    AR.ui.editRange.set(c.id, {
      visual: true,
      start: anchor.start + 1,
      end: anchor.end + 1,
      side: anchor.side,
      fixed: anchor.fixed,
      text: exactText && exactText.trim() ? exactText : anchor.text,
      snippet: anchor.snippet,
      oR: anchor.oR,
      nR: anchor.nR,
      path: anchor.side === 'o' ? (f.oldPath || f.path) : f.path,
    });
    renderFile({ keepScroll: true });
    const card = body.querySelector('[data-cid="' + CSS.escape(c.id) + '"]');
    if (card) card.scrollIntoView({ block: 'nearest' });
    const ta = body.querySelector('[data-fk="' + CSS.escape('edit:' + c.id) + '"]');
    if (ta) ta.focus({ preventScroll: true });
  }

  function applyRangeNumbers(c, d) {
    const m = S.model;
    const a = Math.min(d.start, d.end);
    const b = Math.max(d.start, d.end);
    if (!m || !(a > 0) || !(b > 0)) { AR.ui.editRange.set(c.id, d); return; }
    const side = d.side || sideOf(c);
    const lines = side === 'o' ? m.oldL : m.newL;
    if (b > lines.length) { AR.ui.editRange.set(c.id, d); return; }
    const range = [a - 1, b - 1];
    const oR = side === 'o' ? range : null;
    const nR = side === 'n' ? range : null;
    const changed = m.U.some(function (r) {
      if (r.t === 'c') return false;
      return side === 'o' ? (r.o != null && r.o >= range[0] && r.o <= range[1]) : (r.n != null && r.n >= range[0] && r.n <= range[1]);
    });
    AR.ui.editRange.set(c.id, {
      visual: true, start: d.start, end: d.end, side: side, fixed: true, oR: oR, nR: nR,
      text: lines.slice(a - 1, b).join('\n'),
      snippet: changed ? snippet(m, oR, nR) : null,
      path: side === 'o' ? (S.file.oldPath || S.file.path) : S.file.path,
    });
    renderFile({ keepScroll: true });
  }

  function renderFile(opts) {
    opts = opts || {};
    const f = S.file;
    const p = S.payload;
    if (!f || !p) return;
    hideFloat();
    AR.closeMenu();
    const prevScroll = body.scrollTop;
    const m = p.kind === 'text' ? model(p) : null;
    const showTable = !!m && (m.frags.length > 0 || m.showAll || Number(P.context) < 0);
    const rows = showTable ? (P.view === 'split' ? m.SP : m.U) : [];
    S.model = m;
    S.rows = rows;
    const anchors = anchorComments(f, rows);
    if (!P.showComments) hideComments(anchors);
    if (!showTable) anchors.byRow.forEach(function (list) { list.forEach(function (it) { anchors.top.push({ c: it.c }); }); });

    let selection = null;
    let selectionSide = null;
    let selectionFixed = false;
    let composerRow = -1;
    const cmp = S.composer;
    if (cmp && cmp.kind === 'line' && showTable) {
      selection = rowsInAnchor(rows, cmp.anchor);
      selectionSide = cmp.anchor.side;
      selectionFixed = !!cmp.anchor.fixed;
      selection.forEach(function (i) { composerRow = Math.max(composerRow, i); });
    }
    const editing = activeEdit();
    const draft = editing && AR.ui.editRange.get(editing.id);
    if (draft && draft.visual && showTable && (draft.oR || draft.nR)) {
      selection = rowsInAnchor(rows, { oR: draft.oR, nR: draft.nR });
      selectionSide = draft.side;
      selectionFixed = !!draft.fixed;
    }
    const composerOnTop = cmp && (cmp.kind === 'file' || composerRow < 0);

    AR.preserveFocus(function () {
      body.innerHTML = '';
      const top = h('div', { class: 'top-comments' });
      if (composerOnTop) top.append(composerEl(cmp));
      anchors.top.forEach(function (it) {
        if (it.note) top.append(h('div', { class: 'note' }, it.note));
        top.append(AR.card(it.c, Object.assign({ location: 'short', onLocate: locateTopComment }, cardOpts)));
      });
      body.append(top);
      if (!m) body.append(special(p));
      else if (!showTable) body.append(noChanges(f, p));
      else body.append(diffTable(m, rows, anchors, selection, composerRow, cmp, selectionSide, selectionFixed));
    });

    if (opts.keepScroll) body.scrollTop = prevScroll;
    else {
      body.scrollTop = 0;
      const composing = cmp && cmp.justOpened;
      if (!composing && Number(P.context) < 0 && m && m.frags.length) setTimeout(function () { gotoChange(1, true); }, 0);
    }
    if (cmp && cmp.justOpened) {
      cmp.justOpened = false;
      const ta = body.querySelector('[data-fk="composer"]');
      if (ta) {
        ta.focus({ preventScroll: true });
        const r = ta.getBoundingClientRect();
        const br = body.getBoundingClientRect();
        if (r.bottom > br.bottom - 10 || r.top < br.top) body.scrollTop += r.top - br.top - br.height / 2;
      }
    }
    if (S.focusId) {
      const el = body.querySelector('[data-cid="' + CSS.escape(S.focusId) + '"]');
      if (el) { AR.flash(el); S.focusId = null; }
    }
  }

  /** Hidden comments keep their marks on the gutter; only the comment being edited or focused stays visible. */
  function hideComments(anchors) {
    const keep = function (c) { return AR.ui.editing.has(c.id) || c.id === S.focusId; };
    anchors.top = anchors.top.filter(function (it) { return keep(it.c); });
    const byRow = new Map();
    anchors.byRow.forEach(function (list, row) {
      const kept = list.filter(function (it) { return keep(it.c); });
      if (kept.length) byRow.set(row, kept);
    });
    anchors.byRow = byRow;
    anchors.forced = new Set(byRow.keys());
  }

  function locateTopComment(c) {
    if (c.startLine == null) {
      if (S.file && S.file.status !== 'D') call('openInEditor', { line: 1 }).catch(fail);
      return;
    }
    call('navigate', { id: c.id }).catch(fail);
  }

  function special(p) {
    if (p.kind === 'image') {
      const box = h('div', { class: 'img-cmp' });
      if (p.oldImage) box.append(h('figure', null, h('figcaption', null, 'Было · ' + fmtSize(p.oldSize)), h('img', { src: p.oldImage })));
      if (p.newImage) box.append(h('figure', null, h('figcaption', null, 'Стало · ' + fmtSize(p.newSize)), h('img', { src: p.newImage })));
      return box;
    }
    const sizes = (p.oldExists ? fmtSize(p.oldSize) : '—') + ' → ' + (p.newExists ? fmtSize(p.newSize) : '—');
    let html;
    if (p.kind === 'binary') html = '<b>Бинарный файл</b><span>' + sizes + '</span>';
    else if (p.kind === 'tooLarge') {
      html = '<b>Файл слишком большой для показа</b><span>' + sizes + '</span>' +
        (p.canForce ? '<button data-act="force">Показать всё равно</button>' : '');
    } else if (p.kind === 'empty') html = '<b>Нет содержимого</b><span>Возможно, это подмодуль или пустой файл.</span>';
    else html = '<b>Не удалось показать файл</b>';
    return h('div', { class: 'msg', html: html });
  }

  function noChanges(f, p) {
    let text = 'Содержимое не изменилось';
    if (p.ignoreWs) text += ' (без учёта пробелов)';
    if (f.oldPath && f.oldPath !== f.path) text = 'Файл переименован, содержимое не изменилось';
    if (f.status === 'A' || f.status === '?') text = 'Пустой файл';
    return h('div', { class: 'msg', html: '<b>' + esc(text) + '</b><button data-act="showall">Показать файл</button>' });
  }

  function visibility(m, rows, forced) {
    const n = rows.length;
    const vis = new Uint8Array(n);
    const k = Number(P.context);
    if (k < 0 || m.showAll || !m.frags.length) { vis.fill(1); return vis; }
    let last = -1e9;
    for (let i = 0; i < n; i++) {
      if (rows[i].t !== 'c') { vis[i] = 1; last = i; } else if (i - last <= k) vis[i] = 1;
    }
    last = 1e9;
    for (let i = n - 1; i >= 0; i--) {
      if (rows[i].t !== 'c') last = i; else if (last - i <= k) vis[i] = 1;
    }
    forced.forEach(function (i) { if (i >= 0 && i < n) vis[i] = 1; });
    const rev = S.reveal;
    for (let i = 0; i < n; i++) if (!vis[i] && rows[i].o != null && rev.has(rows[i].o)) vis[i] = 2;
    for (let i = 0; i < n;) {
      if (vis[i]) { i++; continue; }
      let j = i;
      while (j < n && !vis[j]) j++;
      if (j - i <= 2) for (let t = i; t < j; t++) vis[t] = 1;
      i = j;
    }
    return vis;
  }

  function gapRow(i, j, n) {
    const count = j - i;
    const STEP = 20;
    const btn = function (from, to, label, title) {
      return '<button data-reveal="' + from + ',' + to + '" title="' + title + '">' + label + '</button>';
    };
    let inner = '<span>⋯ скрыто ' + count + ' ' + plural(count, 'строка', 'строки', 'строк') + '</span>';
    if (count <= STEP * 2) inner += btn(i, j, 'показать', 'Показать скрытые строки');
    else {
      if (i > 0) inner += btn(i, i + STEP, icon('down') + ' ещё ' + STEP, 'Показать строки ниже');
      if (j < n) inner += btn(j - STEP, j, icon('up') + ' ещё ' + STEP, 'Показать строки выше');
      inner += btn(i, j, 'все', 'Показать все скрытые строки');
    }
    return '<tr class="gap"><td colspan="4"><div class="gap-in">' + inner + '</div></td></tr>';
  }

  function diffTable(m, rows, anchors, selection, composerRow, cmp, selectionSide, selectionFixed) {
    const split = P.view === 'split';
    const forced = new Set(anchors.forced);
    if (selection) selection.forEach(function (i) { forced.add(i); });
    const vis = visibility(m, rows, forced);
    const selClass = split ? (selectionSide === 'o' ? 'sel-o' : 'sel-n') : selectionFixed ? 'usel-' + selectionSide : 'sel';
    const out = [];
    out.push(split
      ? '<colgroup><col class="c-ln"><col><col class="c-ln"><col></colgroup>'
      : '<colgroup><col class="c-ln"><col class="c-ln"><col class="c-sg"><col></colgroup>');
    let i = 0;
    while (i < rows.length) {
      if (!vis[i]) {
        let j = i;
        while (j < rows.length && !vis[j]) j++;
        out.push(gapRow(i, j, rows.length));
        i = j;
        continue;
      }
      if (vis[i] === 2 && (i === 0 || vis[i - 1] !== 2)) {
        let j = i;
        while (j < rows.length && vis[j] === 2) j++;
        out.push('<tr class="gap fold"><td colspan="4"><div class="gap-in"><button data-fold="' + i + ',' + j +
          '" title="Снова скрыть показанные строки">' + icon('up') + ' скрыть ' + (j - i) + ' ' + plural(j - i, 'строку', 'строки', 'строк') + '</button></div></td></tr>');
      }
      const r = rows[i];
      const cls = [];
      if (selection && selection.has(i)) cls.push(selClass);
      const mark = anchors.marks.get(i);
      if (mark) cls.push('mk', 'mk-' + mark.side, mark.q ? 'mq' : '');
      if (r.t !== 'c' && (i === 0 || rows[i - 1].t === 'c')) cls.push('cs');
      out.push(split ? splitRow(m, r, i, cls) : unifiedRow(m, r, i, cls));
      const list = anchors.byRow.get(i);
      if (list) {
        const side = list[0].side;
        out.push('<tr class="cm-row side-' + (split ? side : 'all') + '"><td colspan="4"><div class="cm-slot" data-slot="' + i + '"></div></td></tr>');
      }
      if (i === composerRow) {
        out.push('<tr class="cm-row side-' + (split ? cmp.anchor.side : 'all') + '"><td colspan="4"><div class="cm-slot" data-slot="composer"></div></td></tr>');
      }
      i++;
    }
    const table = h('table', { class: 'diff' + (split ? ' split' : ''), style: '--lnw:' + String(Math.max(m.oldL.length, m.newL.length, 1)).length });
    table.innerHTML = out.join('');
    table.querySelectorAll('.cm-slot').forEach(function (slot) {
      if (slot.dataset.slot === 'composer') { slot.append(composerEl(cmp)); return; }
      (anchors.byRow.get(Number(slot.dataset.slot)) || []).forEach(function (it) { slot.append(AR.card(it.c, cardOpts)); });
    });
    return table;
  }

  function unifiedRow(m, r, i, cls) {
    const code = r.t === 'd' ? lineHtml(m, 'o', r.o) : lineHtml(m, 'n', r.n);
    return '<tr class="r ' + r.t + ' ' + cls.join(' ') + '" data-i="' + i + '">' +
      '<td class="ln" data-s="o">' + (r.o != null ? r.o + 1 : '') + '</td>' +
      '<td class="ln" data-s="n">' + (r.n != null ? r.n + 1 : '') + '</td>' +
      '<td class="sg">' + (r.t === 'a' ? '+' : r.t === 'd' ? '−' : '') + '</td>' +
      '<td class="cd">' + code + '</td></tr>';
  }

  function splitRow(m, r, i, cls) {
    const changed = r.t !== 'c';
    const oc = r.o == null ? 'e' : changed ? 'd' : '';
    const nc = r.n == null ? 'e' : changed ? 'a' : '';
    return '<tr class="r ' + (changed ? 'x' : 'c') + ' ' + cls.join(' ') + '" data-i="' + i + '">' +
      '<td class="ln ' + oc + '" data-s="o">' + (r.o != null ? r.o + 1 : '') + '</td>' +
      '<td class="cd o ' + oc + '" data-s="o">' + (r.o != null ? lineHtml(m, 'o', r.o) : '') + '</td>' +
      '<td class="ln ' + nc + '" data-s="n">' + (r.n != null ? r.n + 1 : '') + '</td>' +
      '<td class="cd n ' + nc + '" data-s="n">' + (r.n != null ? lineHtml(m, 'n', r.n) : '') + '</td></tr>';
  }


  // ------------------------------------------------------------------ composer
  function composerEl(cmp) {
    let label;
    if (cmp.kind === 'file') {
      label = 'Комментарий ко всему файлу';
    } else {
      const a = cmp.anchor;
      label = (a.end > a.start ? 'Строки ' + (a.start + 1) + '–' + (a.end + 1) : 'Строка ' + (a.start + 1)) +
        (a.side === 'o' ? ' · старая версия' : a.fixed && P.view !== 'split' ? ' · новая версия' : '');
    }
    const ta = h('textarea', { 'data-fk': 'composer', rows: 3, placeholder: 'Что поменять или о чём спросить…' });
    ta.value = cmp.text || '';
    ta.addEventListener('input', function () { cmp.text = ta.value; AR.autoGrow(ta, 400); });
    ta.addEventListener('keydown', function (e) {
      if (e.key === 'Enter' && (e.ctrlKey || e.metaKey)) { e.preventDefault(); saveComposer(); }
      else if (e.key === 'Escape') { e.preventDefault(); e.stopPropagation(); cancelComposer(); }
    });
    setTimeout(function () { AR.autoGrow(ta, 400); }, 0);
    const title = h('div', { class: 'cmp-h', html: icon('comment') + ' ' + esc(label) });
    return h('div', { class: 'composer' }, title, ta, h('div', { class: 'cmp-actions' },
      h('button', { class: 'primary', onclick: saveComposer }, 'Сохранить'),
      h('button', { onclick: cancelComposer }, 'Отмена'),
      h('span', { class: 'dim' }, 'Ctrl+Enter — сохранить · Esc — отмена')));
  }

  function openLineComposer(a, b, side, exactText, byColumn) {
    const m = S.model;
    const rows = S.rows;
    if (!m || !rows || !rows.length) return;
    const anchor = anchorFromRows(m, rows, a, b, side, byColumn || P.view === 'split');
    if (!anchor) { renderFile({ keepScroll: true }); return; }
    if (exactText && exactText.trim()) anchor.text = exactText;
    const keep = S.composer ? S.composer.text : '';
    S.composer = { kind: 'line', anchor: anchor, text: keep || '', justOpened: true };
    renderFile({ keepScroll: true });
  }

  function openFileComposer() {
    if (!S.file) return;
    const keep = S.composer ? S.composer.text : '';
    S.composer = { kind: 'file', text: keep || '', justOpened: true };
    renderFile({ keepScroll: false });
  }

  function cancelComposer() {
    S.composer = null;
    renderFile({ keepScroll: true });
  }

  function saveComposer() {
    const cmp = S.composer;
    const f = S.file;
    if (!cmp || cmp.saving || !f) return;
    const text = (cmp.text || '').trim();
    if (!text) return;
    const params = { text: text };
    if (cmp.kind === 'file') {
      const deleted = f.status === 'D';
      params.side = deleted ? 'o' : 'n';
      params.path = deleted ? (f.oldPath || f.path) : f.path;
    } else {
      const a = cmp.anchor;
      params.side = a.side;
      params.path = a.side === 'o' ? (f.oldPath || f.path) : f.path;
      params.startLine = a.start + 1;
      params.endLine = a.end + 1;
      params.selectedText = a.text;
      if (a.snippet) params.diffSnippet = a.snippet;
    }
    cmp.saving = true;
    call('addComment', params).then(function () {
      if (S.composer === cmp) S.composer = null;
      renderFile({ keepScroll: true });
    }, function (e) {
      cmp.saving = false;
      fail(e);
    });
  }

  // ------------------------------------------------------------------ mouse: line selection
  body.addEventListener('mousedown', function (e) {
    if (e.button !== 0) return;
    const td = e.target.closest('td.ln');
    if (!td || !td.textContent) return;
    const tr = td.parentElement;
    const i = Number(tr.dataset.i);
    const side = td.dataset.s;
    e.preventDefault();
    if (e.ctrlKey || e.metaKey) { openInEditorAt(i, side); return; }
    const sel = window.getSelection();
    if (sel) sel.removeAllRanges();
    hideFloat();
    const split = P.view === 'split';
    if (e.shiftKey && S.lastClick && (!split || S.lastClick.side === side)) S.drag = { a: S.lastClick.i, b: i, side: S.lastClick.side };
    else S.drag = { a: i, b: i, side: side };
    S.lastClick = { i: S.drag.a, side: side };
    paintDrag();
  });

  document.addEventListener('mousemove', function (e) {
    if (!S.drag) return;
    const tr = e.target && e.target.closest ? e.target.closest('tr.r') : null;
    if (!tr || !body.contains(tr)) return;
    const i = Number(tr.dataset.i);
    if (i !== S.drag.b) { S.drag.b = i; paintDrag(); }
  });

  document.addEventListener('mouseup', function (e) {
    if (S.drag) {
      const d = S.drag;
      S.drag = null;
      const editing = activeEdit();
      if (editing) moveEditTo(editing, d.a, d.b, d.side, null, true);
      else openLineComposer(d.a, d.b, d.side, null, true);
      return;
    }
    const fromCode = pressInCode;
    pressInCode = false;
    if (e.button === 0 && fromCode) setTimeout(function () { checkTextSelection(e); }, 0);
  });

  function paintDrag() {
    const d = S.drag;
    const lo = Math.min(d.a, d.b);
    const hi = Math.max(d.a, d.b);
    const cls = (P.view === 'split' ? 'sel-' : 'usel-') + (d.side === 'o' ? 'o' : 'n');
    body.querySelectorAll('tr.r').forEach(function (tr) {
      const i = Number(tr.dataset.i);
      tr.classList.remove('sel', 'sel-o', 'sel-n', 'usel-o', 'usel-n');
      if (i >= lo && i <= hi) tr.classList.add(cls);
    });
  }

  function openInEditorAt(i, side) {
    const f = S.file;
    const rows = S.rows || [];
    if (!f || f.status === 'D') return;
    let line = null;
    for (let k = i; k < rows.length && line == null; k++) line = rows[k].n;
    for (let k = i; k >= 0 && line == null; k--) line = rows[k].n;
    if (side === 'n' && rows[i] && rows[i].n != null) line = rows[i].n;
    call('openInEditor', { line: (line == null ? 0 : line) + 1 }).catch(fail);
  }

  // ------------------------------------------------------------------ text selection
  function rowOf(node) {
    const el = node && (node.nodeType === 3 ? node.parentElement : node);
    return el && el.closest ? el.closest('tr.r') : null;
  }

  function moveEditTo(c, a, b, side, exactText, byColumn) {
    const m = S.model;
    const rows = S.rows;
    if (!m || !rows || !rows.length) return;
    const anchor = anchorFromRows(m, rows, a, b, side, byColumn || P.view === 'split');
    if (anchor) setDraft(c, anchor, exactText);
  }

  function checkTextSelection(e) {
    const sel = window.getSelection();
    if (!sel || sel.isCollapsed || !sel.rangeCount) { hideFloat(); return; }
    const range = sel.getRangeAt(0);
    const table = body.querySelector('table.diff');
    if (!table || !table.contains(range.commonAncestorContainer)) { hideFloat(); return; }
    const tr0 = rowOf(range.startContainer);
    const tr1 = rowOf(range.endContainer);
    if (!tr0 || !tr1) { hideFloat(); return; }
    const startEl = range.startContainer.nodeType === 3 ? range.startContainer.parentElement : range.startContainer;
    const td = startEl.closest ? startEl.closest('td[data-s]') : null;
    S.textSel = { a: Number(tr0.dataset.i), b: Number(tr1.dataset.i), side: td ? td.dataset.s : 'n', text: String(sel) };
    const editing = activeEdit();
    if (editing) {
      const t = S.textSel;
      S.textSel = null;
      moveEditTo(editing, t.a, t.b, t.side, t.text);
      return;
    }
    floatBtn.hidden = false;
    floatBtn.style.left = Math.max(8, Math.min(e.clientX + 10, window.innerWidth - floatBtn.offsetWidth - 10)) + 'px';
    floatBtn.style.top = Math.max(8, Math.min(e.clientY + 14, window.innerHeight - 40)) + 'px';
  }

  function hideFloat() {
    floatBtn.hidden = true;
  }

  function commentFromTextSelection() {
    const t = S.textSel;
    if (!t) return;
    hideFloat();
    S.textSel = null;
    const sel = window.getSelection();
    if (sel) sel.removeAllRanges();
    const editing = activeEdit();
    if (editing) moveEditTo(editing, t.a, t.b, t.side, t.text);
    else openLineComposer(t.a, t.b, t.side, t.text);
  }

  body.addEventListener('scroll', function () { if (!floatBtn.hidden) hideFloat(); });

  // ------------------------------------------------------------------ clicks inside the body
  body.addEventListener('click', function (e) {
    const fold = e.target.closest('[data-fold]');
    if (fold) {
      const range = fold.dataset.fold.split(',').map(Number);
      const rows = S.rows || [];
      for (let i = Math.max(0, range[0]); i < Math.min(rows.length, range[1]); i++) if (rows[i].o != null) S.reveal.delete(rows[i].o);
      renderFile({ keepScroll: true });
      return;
    }
    const rv = e.target.closest('[data-reveal]');
    if (rv) {
      const parts = rv.dataset.reveal.split(',').map(Number);
      const rows = S.rows || [];
      for (let i = Math.max(0, parts[0]); i < Math.min(rows.length, parts[1]); i++) if (rows[i].o != null) S.reveal.add(rows[i].o);
      renderFile({ keepScroll: true });
      return;
    }
    const act = e.target.closest('[data-act]');
    if (!act) return;
    if (act.dataset.act === 'force') loadPayload(false, true);
    else if (act.dataset.act === 'reload') reload();
    else if (act.dataset.act === 'showall' && S.model) { S.model.showAll = true; renderFile({ keepScroll: true }); }
  });

  // ------------------------------------------------------------------ navigation between changes
  function gotoChange(dir, instant) {
    const starts = Array.from(body.querySelectorAll('tr.r.cs'));
    if (!starts.length) return;
    const bodyTop = body.getBoundingClientRect().top;
    const y = function (el) { return el.getBoundingClientRect().top - bodyTop + body.scrollTop; };
    const cur = body.scrollTop + 70;
    let target = null;
    if (dir > 0) target = starts.find(function (el) { return y(el) > cur + 4; });
    else target = starts.slice().reverse().find(function (el) { return y(el) < cur - 4; });
    if (!target) return;
    body.scrollTo({ top: Math.max(0, y(target) - 60), behavior: instant ? 'auto' : 'smooth' });
    target.classList.remove('flash');
    void target.offsetWidth;
    target.classList.add('flash');
  }

  // ------------------------------------------------------------------ keyboard
  document.addEventListener('keydown', function (e) {
    if (e.defaultPrevented) return;
    const t = e.target;
    const typing = t && (t.tagName === 'TEXTAREA' || t.tagName === 'INPUT' || t.tagName === 'SELECT');
    if (e.key === 'Escape' && !typing) {
      if (helpEl) toggleHelp();
      else if (S.composer) cancelComposer();
      hideFloat();
      return;
    }
    if (typing || e.ctrlKey || e.metaKey || e.altKey) return;
    switch (e.key) {
      case 'j': call('step', { dir: 1 }).catch(fail); break;
      case 'k': call('step', { dir: -1 }).catch(fail); break;
      case 'n': gotoChange(1); break;
      case 'p': gotoChange(-1); break;
      case 'c': if (S.textSel && !floatBtn.hidden) commentFromTextSelection(); else return; break;
      case 'r': reload(); break;
      case '?': toggleHelp(); break;
      default: return;
    }
    e.preventDefault();
  });

  // ------------------------------------------------------------------ IDE events
  AR.on('comments', function (list) {
    S.comments = list || [];
    if (S.payload) renderFile({ keepScroll: true });
  });
  AR.on('stale', function () { init(true); });
  AR.on('prefs', function (d) {
    if (!d) return;
    const wsChanged = typeof d.ignoreWs === 'boolean' && d.ignoreWs !== P.ignoreWs;
    ['context', 'view', 'ignoreWs', 'wrap', 'showDone', 'showComments'].forEach(function (k) { if (d[k] !== undefined) P[k] = d[k]; });
    document.body.classList.toggle('nowrap', !P.wrap);
    renderViewBar();
    if (wsChanged && S.file) loadPayload(true, false);
    else renderFile({ keepScroll: true });
  });
  AR.on('fileComment', function () { openFileComposer(); });
  AR.on('focusComment', function (d) {
    if (!d || !d.id) return;
    S.focusId = d.id;
    renderFile({ keepScroll: true });
  });

  viewBar.innerHTML = '<span class="dim"><span class="spinner"></span> Загрузка…</span>';
  init(false);
})();
