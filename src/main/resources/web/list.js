'use strict';

(function () {
  const AR = window.AR;
  const call = AR.call;
  const h = AR.h;
  const esc = AR.esc;
  const icon = AR.icon;
  const fail = AR.fail;
  const extra = AR.BOOT.extra || {};

  const FILTERS = [
    ['open', 'Открытые'],
    ['questions', 'С вопросами'],
    ['done', 'Выполненные'],
    ['closed', 'Завершённые'],
    ['all', 'Все'],
  ];

  const L = {
    comments: [],
    error: null,
    filter: FILTERS.some(function (f) { return f[0] === extra.filter; }) ? extra.filter : 'open',
    composer: null,
    confirmClear: false,
    focusId: null,
    loaded: false,
    live: false,
  };

  if (extra.snip === 'hidden' || extra.snip === 'full') AR.ui.snipDefault = extra.snip;

  const app = document.getElementById('app');
  app.innerHTML = '<div class="lt-bar" id="bar"></div><div class="lt-body" id="body"></div>';
  const bar = document.getElementById('bar');
  const body = document.getElementById('body');

  function matches(c, filter) {
    if (filter === 'open') return AR.isOpen(c);
    if (filter === 'questions') return c.status === 'HAS_QUESTIONS';
    if (filter === 'done') return c.status === 'DONE' || c.status === 'CANCELED';
    if (filter === 'closed') return c.status === 'CLOSED';
    return true;
  }

  function counts() {
    const res = {};
    FILTERS.forEach(function (f) {
      res[f[0]] = L.comments.filter(function (c) { return matches(c, f[0]); }).length;
    });
    return res;
  }

  function renderBar() {
    const n = counts();
    bar.innerHTML =
      '<button class="primary" id="newBtn" title="Общий комментарий, не привязанный к коду">' + icon('plus') + ' Комментарий</button>' +
      '<span class="seg" id="filters">' + FILTERS.map(function (f) {
        return '<button data-f="' + f[0] + '" class="' + (L.filter === f[0] ? 'on' : '') + '">' + f[1] + '<span class="cnt">' + n[f[0]] + '</span></button>';
      }).join('') + '</span>' +
      '<button class="ghost" id="foldCode" title="Свернуть код во всех карточках">' + icon('up') + ' Свернуть весь код</button>' +
      '<button class="ghost" id="unfoldCode" title="Развернуть код во всех карточках полностью">' + icon('down') + ' Развернуть весь код</button>' +
      '<span class="sp"></span>' +
      (L.live
        ? '<span class="live" title="Сессия Claude Code следит за комментариями и отвечает сразу">● Claude на связи</span>'
        : '<span class="hint dim" title="/review_live — Claude следит и отвечает сразу; /review_comments — разобрать всё пачкой">' +
          'Claude: <code>/review_live</code> или <code>/review_comments</code></span>') +
      (L.filter === 'done' && n.done
        ? '<button class="ghost" id="closeAllBtn" title="Перенести все выполненные и отменённые в «Завершённые»">' + icon('archive') + ' Завершить все</button>'
        : '') +
      '<button class="ghost' + (L.confirmClear ? ' danger' : '') + '" id="clearBtn"' + (n.closed ? '' : ' disabled') + '>' +
      icon('trash') + (L.confirmClear ? ' Удалить ' + n.closed + ' — точно?' : ' Очистить завершённые') + '</button>' +
      '<button class="ghost ib" id="jsonBtn" title="Открыть .ai-review-comments.json">' + icon('file') + '</button>';

    document.getElementById('newBtn').onclick = function () {
      if (!L.composer) L.composer = { text: '', justOpened: true };
      else L.composer.justOpened = true;
      renderBody();
    };
    const setAllCode = function (mode) {
      AR.ui.snipDefault = mode;
      AR.ui.snip.clear();
      call('savePrefs', { snip: mode }).catch(function () {});
      renderBody();
    };
    document.getElementById('foldCode').onclick = function () { setAllCode('hidden'); };
    document.getElementById('unfoldCode').onclick = function () { setAllCode('full'); };
    document.getElementById('filters').onclick = function (e) {
      const b = e.target.closest('button');
      if (!b) return;
      L.filter = b.dataset.f;
      call('savePrefs', { filter: L.filter }).catch(function () {});
      render();
    };
    document.getElementById('clearBtn').onclick = function () {
      if (!L.confirmClear) {
        L.confirmClear = true;
        renderBar();
        setTimeout(function () { if (L.confirmClear) { L.confirmClear = false; renderBar(); } }, 4000);
        return;
      }
      L.confirmClear = false;
      call('clearClosed').catch(fail);
      renderBar();
    };
    const closeAll = document.getElementById('closeAllBtn');
    if (closeAll) closeAll.onclick = function () { call('closeDone').catch(fail); };
    document.getElementById('jsonBtn').onclick = function () { call('openJson').catch(fail); };
  }

  function composerEl() {
    const cmp = L.composer;
    const ta = h('textarea', { 'data-fk': 'general', rows: 3, placeholder: 'Общая задача для Claude, не привязанная к коду… (Ctrl+Enter — сохранить, Esc — отмена)' });
    ta.value = cmp.text;
    const save = function () {
      const text = ta.value.trim();
      if (!text || cmp.saving) return;
      cmp.saving = true;
      call('addGeneral', { text: text }).then(function () {
        if (L.composer === cmp) L.composer = null;
        renderBody();
      }, function (e) { cmp.saving = false; fail(e); });
    };
    const cancel = function () { L.composer = null; renderBody(); };
    ta.addEventListener('input', function () { cmp.text = ta.value; AR.autoGrow(ta, 360); });
    ta.addEventListener('keydown', function (e) {
      if (e.key === 'Enter' && (e.ctrlKey || e.metaKey)) { e.preventDefault(); save(); }
      else if (e.key === 'Escape') { e.preventDefault(); cancel(); }
    });
    setTimeout(function () { AR.autoGrow(ta, 360); }, 0);
    return h('div', { class: 'composer' }, ta, h('div', { class: 'row' },
      h('button', { class: 'primary', onclick: save }, 'Сохранить'),
      h('button', { onclick: cancel }, 'Отмена'),
      h('span', { class: 'dim' }, 'Ctrl+Enter · Esc')));
  }

  function emptyState() {
    const key = extra.shortcut ? '<kbd>' + esc(extra.shortcut) + '</kbd>' : 'сочетание «Комментарий для Claude»';
    const text = L.comments.length
      ? '<b>В этом фильтре пусто</b>'
      : '<b>Комментариев пока нет</b><ul>' +
        '<li>Выделите код (или просто поставьте курсор на строку) и нажмите ' + key + ' — либо ПКМ → «Комментарий для Claude…».</li>' +
        '<li>ПКМ по файлу или папке в дереве проекта — комментарий ко всему файлу или папке.</li>' +
        '<li>Explorer → вид «AI Review» — все изменения ветки; файл открывается в diff, комментарии пишутся прямо там.</li>' +
        '<li>Git Log → выделите один или два коммита → ПКМ → «AI Review: …».</li>' +
        '<li>Когда закончите — запустите <code>/review_comments</code> в Claude Code.</li></ul>';
    return h('div', { class: 'empty', html: text });
  }

  function groupKey(c) { return c.filePath || ''; }

  function renderBody() {
    AR.closeMenu();
    AR.preserveFocus(function () {
      body.innerHTML = '';
      const inner = h('div', { class: 'lt-inner' });
      body.append(inner);
      if (L.error) {
        inner.append(h('div', { class: 'banner' },
          h('span', null, 'Файл .ai-review-comments.json не читается: ' + L.error),
          h('span', { class: 'sp' }),
          h('button', { onclick: function () { call('openJson').catch(fail); } }, 'Открыть'),
          h('button', { onclick: function () { call('reload').catch(fail); } }, 'Перечитать')));
      }
      if (L.composer) inner.append(composerEl());

      const list = L.comments.filter(function (c) { return matches(c, L.filter) || c.id === L.focusId; });
      if (!list.length) { inner.append(emptyState()); return; }

      const groups = new Map();
      list.forEach(function (c) {
        const k = groupKey(c);
        if (!groups.has(k)) groups.set(k, []);
        groups.get(k).push(c);
      });
      const keys = Array.from(groups.keys()).sort(function (a, b) {
        if (!a) return -1;
        if (!b) return 1;
        return a.localeCompare(b, undefined, { sensitivity: 'base' });
      });
      keys.forEach(function (k) {
        const items = groups.get(k).sort(function (a, b) {
          const la = a.startLine == null ? -1 : a.startLine;
          const lb = b.startLine == null ? -1 : b.startLine;
          return la - lb || (a.createdAt || 0) - (b.createdAt || 0);
        });
        const group = h('div', { class: 'group' });
        const gh = h('div', { class: 'group-h' });
        if (!k) {
          gh.innerHTML = icon('comment') + '<span class="gname">Общие</span>';
          gh.append(h('span', { class: 'n' }, '· ' + items.length));
        } else {
          const kind = items[0]._kind;
          const clean = k.replace(/\/$/, '');
          const slash = clean.lastIndexOf('/');
          const dir = slash >= 0 ? clean.slice(0, slash) : '';
          const name = clean.slice(slash + 1) + (kind === 'dir' ? '/' : '');
          gh.innerHTML = icon(kind === 'dir' ? 'folder' : 'file');
          const first = items.find(function (c) { return c._kind !== 'lines'; }) || items[0];
          gh.append(h('a', {
            class: 'gname',
            title: 'Перейти: ' + k,
            onclick: function () { call('navigate', { id: first.id }).catch(fail); },
          }, name));
          gh.append(h('span', { class: 'n' }, '· ' + items.length));
          gh.append(h('span', { class: 'gpath', title: k, html: '<bdi>' + esc(dir) + '</bdi>' }));
        }
        group.append(gh);
        items.forEach(function (c) {
          group.append(AR.card(c, { location: k ? 'short' : false, code: true, rerender: renderBody }));
        });
        inner.append(group);
      });
    });

    if (L.composer && L.composer.justOpened) {
      L.composer.justOpened = false;
      const ta = body.querySelector('[data-fk="general"]');
      if (ta) ta.focus();
    }
    if (L.focusId) {
      const el = body.querySelector('[data-cid="' + CSS.escape(L.focusId) + '"]');
      if (el) AR.flash(el);
      L.focusId = null;
    }
  }

  function render() {
    renderBar();
    renderBody();
  }

  function applyState(s) {
    L.comments = (s && s.comments) || [];
    L.error = (s && s.error) || null;
    if (s && typeof s.live === 'boolean') L.live = s.live;
    render();
  }

  function focus(id) {
    const c = L.comments.find(function (x) { return x.id === id; });
    if (c && !matches(c, L.filter)) L.filter = 'all';
    L.focusId = id;
    render();
  }

  AR.on('comments', applyState);
  AR.on('live', function (d) { L.live = !!(d && d.alive); renderBar(); });
  AR.on('focusComment', function (d) { if (d && d.id) focus(d.id); });

  render();
  call('init').then(function (s) {
    L.loaded = true;
    applyState(s);
    if (s.focus) focus(s.focus);
  }, fail);
})();
