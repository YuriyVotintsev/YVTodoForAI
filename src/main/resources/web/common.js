'use strict';

window.AR = (function () {
  const BOOT = window.__BOOT || { page: '', theme: { dark: true, vars: {} }, extra: {} };

  // ---------------------------------------------------------------- RPC
  let seq = 0;
  const pending = new Map();
  const listeners = new Map();

  function call(method, params) {
    return new Promise(function (resolve, reject) {
      const id = ++seq;
      pending.set(id, { resolve: resolve, reject: reject });
      try {
        window.__ideSend(JSON.stringify({ id: id, method: method, params: params || {} }));
      } catch (e) {
        pending.delete(id);
        reject(e);
      }
    });
  }

  window.__ideResolve = function (id, ok, payload) {
    const p = pending.get(id);
    if (!p) return;
    pending.delete(id);
    if (ok) p.resolve(payload);
    else p.reject(new Error(typeof payload === 'string' ? payload : 'Ошибка'));
  };

  window.__ideEvent = function (name, payload) {
    (listeners.get(name) || []).forEach(function (fn) {
      try { fn(payload); } catch (e) { console.error(e); }
    });
  };

  function on(name, fn) {
    if (!listeners.has(name)) listeners.set(name, []);
    listeners.get(name).push(fn);
  }

  // ---------------------------------------------------------------- theme
  function applyTheme(theme) {
    if (!theme) return;
    const root = document.documentElement;
    Object.keys(theme.vars || {}).forEach(function (k) { root.style.setProperty('--' + k, theme.vars[k]); });
    document.body.classList.toggle('dark', !!theme.dark);
    document.body.classList.toggle('light', !theme.dark);
  }
  on('theme', applyTheme);

  // ---------------------------------------------------------------- DOM helpers
  function h(tag, props) {
    const el = document.createElement(tag);
    if (props) {
      Object.keys(props).forEach(function (k) {
        const v = props[k];
        if (v == null || v === false) return;
        if (k === 'class') el.className = v;
        else if (k === 'html') el.innerHTML = v;
        else if (k === 'value') el.value = v;
        else if (k.slice(0, 2) === 'on' && typeof v === 'function') el.addEventListener(k.slice(2), v);
        else el.setAttribute(k, v === true ? '' : v);
      });
    }
    for (let i = 2; i < arguments.length; i++) append(el, arguments[i]);
    return el;
  }

  function append(el, child) {
    if (child == null || child === false) return;
    if (Array.isArray(child)) { child.forEach(function (c) { append(el, c); }); return; }
    el.append(child instanceof Node ? child : document.createTextNode(String(child)));
  }

  function esc(s) {
    return String(s == null ? '' : s).replace(/[&<>"']/g, function (c) {
      return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c];
    });
  }

  const ICONS = {
    edit: '<path d="M10.5 3.5l2 2L6 12H4v-2z"/><path d="M9 5l2 2"/>',
    trash: '<path d="M3 4.5h10"/><path d="M6.5 4.5V3h3v1.5"/><path d="M4.5 4.5l.6 8.5h5.8l.6-8.5"/>',
    comment: '<path d="M2.5 3.5h11v7.5h-6l-3 2.5V11h-2z"/>',
    check: '<path d="M3 8.5l3 3 7-7"/>',
    refresh: '<path d="M13 8a5 5 0 1 1-1.6-3.7"/><path d="M13.2 2.5v3h-3"/>',
    branch: '<circle cx="5" cy="3.5" r="1.5"/><circle cx="5" cy="12.5" r="1.5"/><circle cx="11" cy="5" r="1.5"/><path d="M5 5v6"/><path d="M11 6.5c0 3-6 2.5-6 4.5"/>',
    open: '<path d="M9 2.5h4.5V7"/><path d="M13.5 2.5L7.5 8.5"/><path d="M12 9.5v4H2.5V4h4"/>',
    up: '<path d="M4 10l4-4 4 4"/>',
    down: '<path d="M4 6l4 4 4-4"/>',
    left: '<path d="M10 3.5L5.5 8l4.5 4.5"/>',
    right: '<path d="M6 3.5L10.5 8 6 12.5"/>',
    chevRight: '<path d="M6.5 4.5L10 8l-3.5 3.5"/>',
    chevDown: '<path d="M4.5 6.5L8 10l3.5-3.5"/>',
    copy: '<rect x="5.5" y="5.5" width="8" height="8" rx="1.2"/><path d="M3.5 10.5h-1v-8h8v1"/>',
    plus: '<path d="M8 3v10"/><path d="M3 8h10"/>',
    folder: '<path d="M1.8 4.2c0-.5.4-.9.9-.9h3.4l1.4 1.5h5.8c.5 0 .9.4.9.9v6.6c0 .5-.4.9-.9.9H2.7c-.5 0-.9-.4-.9-.9z"/>',
    file: '<path d="M4 1.8h5l3 3v9.4H4z"/><path d="M9 1.8v3h3"/>',
    help: '<circle cx="8" cy="8" r="6"/><path d="M6.3 6.2a1.8 1.8 0 1 1 2.5 1.7c-.5.2-.8.6-.8 1.1v.5"/><path d="M8 11.4v.1"/>',
    x: '<path d="M4 4l8 8"/><path d="M12 4l-8 8"/>',
    archive: '<rect x="2" y="3" width="12" height="3" rx=".6"/><path d="M3 6v7h10V6"/><path d="M6.5 8.5h3"/>',
    list: '<path d="M2.5 3.5h11"/><path d="M5.5 8h8"/><path d="M5.5 12.5h8"/><path d="M2.5 8h.5"/><path d="M2.5 12.5h.5"/>',
  };

  function icon(name, cls) {
    return '<svg class="ic ' + (cls || '') + '" viewBox="0 0 16 16" aria-hidden="true">' + (ICONS[name] || '') + '</svg>';
  }

  function iconNode(name) {
    const t = document.createElement('template');
    t.innerHTML = icon(name);
    return t.content.firstChild;
  }

  function debounce(fn, ms) {
    let t = 0;
    return function () {
      const args = arguments;
      clearTimeout(t);
      t = setTimeout(function () { fn.apply(null, args); }, ms);
    };
  }

  let toastBox = null;
  function toast(message, kind) {
    if (!toastBox) { toastBox = h('div', { class: 'toasts' }); document.body.append(toastBox); }
    const el = h('div', { class: 'toast ' + (kind || '') }, message);
    toastBox.append(el);
    setTimeout(function () { el.remove(); }, kind === 'error' ? 7000 : 3500);
  }

  function fail(e) { toast((e && e.message) || String(e), 'error'); }

  function preserveFocus(fn) {
    const a = document.activeElement;
    const key = a && a.dataset ? a.dataset.fk : null;
    let s0 = null;
    let s1 = null;
    if (key && typeof a.selectionStart === 'number') { s0 = a.selectionStart; s1 = a.selectionEnd; }
    fn();
    if (!key) return;
    const el = document.querySelector('[data-fk="' + CSS.escape(key) + '"]');
    if (!el) return;
    el.focus({ preventScroll: true });
    if (s0 != null && el.setSelectionRange) { try { el.setSelectionRange(s0, s1); } catch (e) { /* ignore */ } }
  }

  function autoGrow(ta, max) {
    ta.style.height = 'auto';
    ta.style.height = Math.min(ta.scrollHeight + 2, max || 320) + 'px';
  }

  function fmtDate(ms) {
    if (!ms) return '';
    const d = new Date(ms);
    const now = new Date();
    const pad = function (n) { return String(n).padStart(2, '0'); };
    const time = pad(d.getHours()) + ':' + pad(d.getMinutes());
    if (d.toDateString() === now.toDateString()) return time;
    return pad(d.getDate()) + '.' + pad(d.getMonth() + 1) + (d.getFullYear() !== now.getFullYear() ? '.' + d.getFullYear() : '') + ' ' + time;
  }

  // ---------------------------------------------------------------- mouse wheel
  const wheelAnim = new WeakMap();

  function lineHeightIn(el) {
    const ref = el.querySelector ? el.querySelector('table.diff td.cd') || el : el;
    const cs = getComputedStyle(ref);
    const lh = parseFloat(cs.lineHeight);
    return isFinite(lh) && lh > 0 ? lh : 1.4 * (parseFloat(cs.fontSize) || 13);
  }

  function canScroll(el, dy) {
    if (!el || el.nodeType !== 1) return false;
    if (el !== document.scrollingElement && !/(auto|scroll)/.test(getComputedStyle(el).overflowY)) return false;
    const max = el.scrollHeight - el.clientHeight;
    if (max <= 1) return false;
    return dy < 0 ? el.scrollTop > 0 : el.scrollTop < max - 1;
  }

  function scrollTargetAt(x, y, dy) {
    let el = document.elementFromPoint(x, y);
    while (el && el !== document.documentElement) {
      if (canScroll(el, dy)) return el;
      el = el.parentElement;
    }
    return canScroll(document.scrollingElement, dy) ? document.scrollingElement : null;
  }

  function smoothScrollBy(el, dy) {
    let st = wheelAnim.get(el);
    if (!st) { st = { target: el.scrollTop, raf: 0 }; wheelAnim.set(el, st); }
    if (!st.raf) st.target = el.scrollTop;
    st.target = Math.max(0, Math.min(el.scrollHeight - el.clientHeight, st.target + Math.sign(dy) * Math.round(Math.abs(dy))));
    if (st.raf) return;
    const step = function () {
      const diff = st.target - el.scrollTop;
      if (Math.abs(diff) < 4) { el.scrollTop = st.target; st.raf = 0; return; }
      const before = el.scrollTop;
      el.scrollTop = before + diff * 0.35;
      if (el.scrollTop === before) { el.scrollTop = st.target; st.raf = 0; return; }
      st.raf = requestAnimationFrame(step);
    };
    st.raf = requestAnimationFrame(step);
  }

  if (BOOT.ideWheel) {
    window.addEventListener('wheel', function (e) {
      if (e.ctrlKey || e.shiftKey || Math.abs(e.deltaX) > Math.abs(e.deltaY)) return;
      e.preventDefault();
    }, { passive: false, capture: true });
    on('wheel', function (d) {
      if (!d || !d.lines) return;
      const el = scrollTargetAt(d.x, d.y, d.lines);
      if (!el) return;
      const dy = d.pages ? d.lines * el.clientHeight * 0.9 : d.lines * lineHeightIn(el);
      smoothScrollBy(el, dy);
    });
  }

  // ---------------------------------------------------------------- menus
  let openMenu = null;
  function closeMenu() { if (openMenu) { openMenu.remove(); openMenu = null; } }
  document.addEventListener('mousedown', function (e) { if (openMenu && !openMenu.contains(e.target)) closeMenu(); }, true);
  document.addEventListener('keydown', function (e) { if (e.key === 'Escape') closeMenu(); }, true);
  window.addEventListener('blur', closeMenu);
  on('outside', closeMenu);

  function showMenu(anchor, items) {
    closeMenu();
    const menu = h('div', { class: 'menu' });
    items.forEach(function (it) {
      menu.append(h('button', { onclick: function () { closeMenu(); it.action(); } }, it.label));
    });
    document.body.append(menu);
    const r = anchor.getBoundingClientRect();
    const mh = menu.offsetHeight;
    const top = r.bottom + 4 + mh > window.innerHeight ? Math.max(4, r.top - mh - 4) : r.bottom + 4;
    menu.style.left = Math.min(r.left, window.innerWidth - menu.offsetWidth - 8) + 'px';
    menu.style.top = top + 'px';
    openMenu = menu;
  }

  // ---------------------------------------------------------------- comments
  const STATUS = {
    PENDING: 'Новый',
    IN_PROGRESS: 'В работе',
    HAS_QUESTIONS: 'Есть вопросы',
    DONE: 'Выполнен',
    CANCELED: 'Отменён',
    CLOSED: 'Завершён',
  };

  const ui = {
    editing: new Map(),
    answering: new Map(),
    confirmDelete: new Set(),
    expanded: new Set(),
    editRange: new Map(),
    snip: new Map(),
    snipDefault: 'short',
    activeEdit: null,
    replying: new Map(),
  };

  function isOpen(c) { return c.status === 'PENDING' || c.status === 'IN_PROGRESS' || c.status === 'HAS_QUESTIONS'; }

  function lineLabel(c) {
    if (c.startLine == null) return '';
    const end = c.endLine || c.startLine;
    return end > c.startLine ? c.startLine + '–' + end : String(c.startLine);
  }

  function locationLabel(c, short) {
    const rev = c.revision ? ' @' + c.revision.slice(0, 9) : '';
    if (c._kind === 'general' || !c.filePath) return short ? '' : 'Общий комментарий';
    if (short) {
      if (c._kind === 'dir') return 'вся папка';
      if (c._kind === 'file') return 'весь файл' + rev;
      return 'строки ' + lineLabel(c) + rev;
    }
    if (c._kind === 'dir') return c.filePath.replace(/\/?$/, '/');
    if (c._kind === 'file') return c.filePath + rev;
    return c.filePath + ':' + lineLabel(c) + rev;
  }

  function linesWord(n) {
    const a = n % 100;
    const b = n % 10;
    const word = a > 10 && a < 20 ? 'строк' : b === 1 ? 'строка' : b > 1 && b < 5 ? 'строки' : 'строк';
    return n + ' ' + word;
  }

  function snippetNode(c, rerender) {
    const raw = c.diffSnippet || (c._kind === 'lines' ? c.selectedText : null);
    if (!raw) return null;
    const lines = raw.split('\n');
    const isDiff = !!c.diffSnippet;
    const mode = ui.snip.get(c.id) || ui.snipDefault;
    const setMode = function (m) { ui.snip.set(c.id, m); rerender(); };
    const count = linesWord(lines.length);
    const wrap = h('div', { class: 'snip-wrap' });
    const toggle = h('a', { class: 'snip-toggle', title: mode === 'hidden' ? 'Показать код' : 'Скрыть код' });
    toggle.innerHTML = icon(mode === 'hidden' ? 'chevRight' : 'chevDown') + ' ' + (isDiff ? 'фрагмент diff' : 'код') + ' · ' + count;
    toggle.addEventListener('click', function () { setMode(mode === 'hidden' ? 'short' : 'hidden'); });
    wrap.append(toggle);
    if (mode === 'hidden') return wrap;
    const LIMIT = 8;
    const shown = mode === 'full' ? lines : lines.slice(0, LIMIT);
    const pre = codePre(shown, isDiff);
    if (lines.length > LIMIT) {
      pre.append(mode === 'full'
        ? h('span', { class: 'more', onclick: function () { setMode('short'); } }, 'свернуть до ' + LIMIT + ' строк')
        : h('span', { class: 'more', onclick: function () { setMode('full'); } }, '… ещё ' + linesWord(lines.length - LIMIT)));
    }
    wrap.append(pre);
    return wrap;
  }

  function codePre(lines, isDiff) {
    const pre = h('pre', { class: 'snip' });
    lines.forEach(function (line) {
      const k = isDiff ? line.charAt(0) : '';
      pre.append(h('span', { class: k === '+' ? 'sa' : k === '-' ? 'sd' : '' }, line || ' '));
    });
    return pre;
  }

  function iconButton(name, title, action, extraClass) {
    const b = h('button', { class: 'ghost ib ' + (extraClass || ''), title: title, onclick: function (e) { e.stopPropagation(); action(e); } });
    b.innerHTML = icon(name);
    return b;
  }

  function sendAnswer(id, index, text, rerender) {
    text = (text || '').trim();
    if (!text) return;
    ui.answering.delete(id + ':' + index);
    call('answer', { id: id, index: index, answer: text }).catch(fail);
    rerender();
  }

  // opts: { location: 'full' | 'short' | false, code: bool, compactDone: bool, rerender: fn }
  function card(c, opts) {
    opts = opts || {};
    const rerender = opts.rerender || function () {};
    const open = isOpen(c);
    const compact = !!opts.compactDone && !open && !ui.expanded.has(c.id) && !ui.editing.has(c.id);
    const el = h('div', { class: 'card st-' + c.status + (compact ? ' compact' : ''), 'data-cid': c.id });

    const head = h('div', { class: 'card-h' });
    const chip = h('button', { class: 'chip st-' + c.status, title: 'Сменить статус' }, STATUS[c.status] || c.status);
    chip.addEventListener('click', function (e) {
      e.stopPropagation();
      showMenu(chip, Object.keys(STATUS).filter(function (s) { return s !== c.status; }).map(function (s) {
        return { label: STATUS[s], action: function () { call('setStatus', { id: c.id, status: s }).catch(fail); } };
      }));
    });
    head.append(chip);

    if (opts.location) {
      const label = locationLabel(c, opts.location === 'short');
      if (label) {
        head.append(h('a', {
          class: 'loc', title: c.filePath ? 'Перейти: ' + c.filePath : '',
          onclick: function (e) {
            e.preventDefault();
            if (opts.onLocate) opts.onLocate(c);
            else call('navigate', { id: c.id }).catch(fail);
          },
        }, label));
      }
    }
    if (compact) head.append(h('span', { class: 'card-preview' }, c.comment.split('\n')[0]));
    else head.append(h('span', { class: 'sp' }));
    head.append(h('span', { class: 'when dim', title: c.createdAt ? new Date(c.createdAt).toLocaleString() : '' }, fmtDate(c.createdAt)));

    if (compact) {
      head.append(iconButton('chevDown', 'Развернуть', function () { ui.expanded.add(c.id); rerender(); }));
      el.append(head);
      return el;
    }
    if (opts.compactDone && !open) head.append(iconButton('up', 'Свернуть', function () { ui.expanded.delete(c.id); rerender(); }));
    if (c.status === 'DONE' || c.status === 'CANCELED') {
      const close = h('button', { class: 'ghost sm close-btn', title: 'Проверено — убрать в «Завершённые»', onclick: function (e) {
        e.stopPropagation();
        call('setStatus', { id: c.id, status: 'CLOSED' }).catch(fail);
      } });
      close.innerHTML = icon('archive') + ' Завершить';
      head.append(close);
    }

    if (ui.confirmDelete.has(c.id)) {
      head.append(h('span', { class: 'confirm' }, 'Удалить?',
        h('button', { class: 'danger', onclick: function () { ui.confirmDelete.delete(c.id); call('deleteComment', { id: c.id }).catch(fail); } }, 'Да'),
        h('button', { onclick: function () { ui.confirmDelete.delete(c.id); rerender(); } }, 'Нет')));
    } else {
      head.append(iconButton('edit', 'Изменить', function () {
        ui.editing.set(c.id, c.comment);
        ui.activeEdit = c.id;
        rerender();
        const ta = document.querySelector('[data-fk="' + CSS.escape('edit:' + c.id) + '"]');
        if (ta) { ta.focus(); ta.setSelectionRange(ta.value.length, ta.value.length); autoGrow(ta); }
      }));
      head.append(iconButton('trash', 'Удалить', function () { ui.confirmDelete.add(c.id); rerender(); }));
    }
    el.append(head);

    const editing = ui.editing.has(c.id);
    if (editing) {
      const ta = h('textarea', { 'data-fk': 'edit:' + c.id, rows: 3 });
      ta.value = ui.editing.get(c.id);
      const hasLines = c._kind === 'lines' && c.startLine != null;
      const draft = hasLines ? ui.editRange.get(c.id) : null;
      const range = draft || (hasLines ? { start: c.startLine, end: c.endLine || c.startLine } : null);
      const cleanup = function () {
        ui.editing.delete(c.id);
        ui.editRange.delete(c.id);
        if (ui.activeEdit === c.id) ui.activeEdit = null;
      };
      const save = function () {
        const text = ta.value.trim();
        if (!text) return;
        const d = ui.editRange.get(c.id);
        cleanup();
        if (d && d.visual && opts.saveRange) {
          opts.saveRange(c, d, text);
        } else {
          const params = { id: c.id, text: text };
          if (d && d.start > 0 && d.end > 0) {
            params.startLine = Math.min(d.start, d.end);
            params.endLine = Math.max(d.start, d.end);
          }
          call('updateComment', params).catch(fail);
        }
        rerender();
      };
      const cancel = function () { cleanup(); rerender(); };
      ta.addEventListener('focus', function () { ui.activeEdit = c.id; });
      ta.addEventListener('input', function () { ui.editing.set(c.id, ta.value); autoGrow(ta); });
      ta.addEventListener('keydown', function (e) {
        if (e.key === 'Enter' && (e.ctrlKey || e.metaKey)) { e.preventDefault(); save(); }
        else if (e.key === 'Escape') { e.preventDefault(); e.stopPropagation(); cancel(); }
      });
      let rangeRow = null;
      let codeBox = null;
      if (range) {
        const num = function (key, value) {
          const inp = h('input', { type: 'number', min: 1, class: 'ln-in', 'data-fk': key + ':' + c.id, value: String(value) });
          inp.addEventListener('focus', function () { ui.activeEdit = c.id; });
          inp.addEventListener('input', function () {
            const v = parseInt(inp.value, 10);
            const d = Object.assign({ side: null }, ui.editRange.get(c.id) || { start: c.startLine, end: c.endLine || c.startLine });
            if (key === 'es') d.start = v; else d.end = v;
            d.visual = false;
            if (opts.onRangeInput) opts.onRangeInput(c, d);
            else ui.editRange.set(c.id, d);
          });
          inp.addEventListener('keydown', function (e) {
            if (e.key === 'Enter') { e.preventDefault(); save(); } else if (e.key === 'Escape') { e.preventDefault(); e.stopPropagation(); cancel(); }
          });
          return inp;
        };
        rangeRow = h('div', { class: 'row range-row' }, h('span', { class: 'dim' }, 'Строки'), num('es', range.start), '–', num('ee', range.end),
          opts.rangeHint ? h('span', { class: 'dim hint' }, opts.rangeHint) : null);
        const code = draft && draft.visual ? (draft.snippet || draft.text) : (c.diffSnippet || c.selectedText);
        const isDiff = draft && draft.visual ? !!draft.snippet : !!c.diffSnippet;
        if (code) {
          codeBox = h('div', { class: 'snip-wrap' },
            h('div', { class: 'snip-toggle static' }, draft && draft.visual ? 'новый код комментария' : 'код комментария'),
            codePre(code.split('\n'), isDiff));
        }
      }
      el.append(h('div', { class: 'edit-box' }, ta, rangeRow, codeBox, h('div', { class: 'row' },
        h('button', { class: 'primary', onclick: save }, 'Сохранить'),
        h('button', { onclick: cancel }, 'Отмена'),
        h('span', { class: 'dim' }, 'Ctrl+Enter · Esc'))));
      setTimeout(function () { autoGrow(ta); }, 0);
    } else {
      el.append(h('div', { class: 'card-text' }, c.comment));
    }

    if (opts.code && !editing) {
      const snip = snippetNode(c, rerender);
      if (snip) el.append(snip);
    }

    if (c.questions && c.questions.length) {
      const qs = h('div', { class: 'qs' });
      c.questions.forEach(function (q, i) {
        const key = c.id + ':' + i;
        const answered = q.answer != null && q.answer !== '';
        const qe = h('div', { class: 'q' + (answered && !ui.answering.has(key) ? ' answered' : '') });
        qe.append(h('div', { class: 'q-text' }, h('span', { class: 'q-mark' }, answered ? '✓' : '?'), q.question));
        if (answered && !ui.answering.has(key)) {
          qe.append(h('div', { class: 'q-ans' }, h('span', { class: 'dim' }, 'Ответ: '), q.answer,
            h('a', { onclick: function () { ui.answering.set(key, q.answer); rerender(); } }, 'изменить')));
        } else {
          if (q.options && q.options.length) {
            qe.append(h('div', { class: 'q-opts' }, q.options.map(function (o) {
              return h('button', { onclick: function () { sendAnswer(c.id, i, o, rerender); } }, o);
            })));
          }
          const ta = h('textarea', { 'data-fk': 'ans:' + key, rows: 1, placeholder: 'Свой ответ… (Ctrl+Enter)' });
          ta.value = ui.answering.get(key) || '';
          ta.addEventListener('input', function () { ui.answering.set(key, ta.value); autoGrow(ta, 200); });
          ta.addEventListener('keydown', function (e) {
            if (e.key === 'Enter' && (e.ctrlKey || e.metaKey)) { e.preventDefault(); sendAnswer(c.id, i, ta.value, rerender); }
            else if (e.key === 'Escape' && answered) { e.preventDefault(); e.stopPropagation(); ui.answering.delete(key); rerender(); }
          });
          qe.append(h('div', { class: 'q-own' }, ta,
            h('button', { class: 'primary', onclick: function () { sendAnswer(c.id, i, ta.value, rerender); } }, 'Ответить')));
          setTimeout(function () { autoGrow(ta, 200); }, 0);
        }
        qs.append(qe);
      });
      el.append(qs);
    }

    const messages = c.thread || [];
    if (messages.length) {
      const thread = h('div', { class: 'thread' });
      messages.forEach(function (m) {
        const mine = m.author === 'user';
        thread.append(h('div', { class: 'tmsg ' + (mine ? 'user' : 'claude') },
          h('div', { class: 'who' }, mine ? 'Вы' : 'Claude', m.at ? h('span', { class: 'when' }, fmtDate(m.at)) : null),
          h('div', { class: 'reply-text' }, m.text)));
      });
      el.append(thread);
    }
    if (c.status === 'IN_PROGRESS') el.append(h('div', { class: 'working' }, h('span', { class: 'spinner' }), ' Claude работает над этим…'));

    const lastFromClaude = messages.length > 0 && messages[messages.length - 1].author !== 'user';
    const unanswered = (c.questions || []).some(function (q) { return q.answer == null || q.answer === ''; });
    const replyOpen = ui.replying.has(c.id) || (c.status === 'HAS_QUESTIONS' && lastFromClaude && !unanswered);
    if (replyOpen) {
      const ta = h('textarea', { 'data-fk': 'reply:' + c.id, rows: 1, placeholder: 'Ответить Claude… (Ctrl+Enter)' });
      ta.value = ui.replying.get(c.id) || '';
      const send = function () {
        const text = ta.value.trim();
        if (!text) return;
        ui.replying.delete(c.id);
        call('reply', { id: c.id, text: text }).catch(fail);
        rerender();
      };
      ta.addEventListener('input', function () { ui.replying.set(c.id, ta.value); autoGrow(ta, 240); });
      ta.addEventListener('keydown', function (e) {
        if (e.key === 'Enter' && (e.ctrlKey || e.metaKey)) { e.preventDefault(); send(); }
        else if (e.key === 'Escape') { e.preventDefault(); e.stopPropagation(); ui.replying.delete(c.id); rerender(); }
      });
      el.append(h('div', { class: 'reply-box' }, ta, h('button', { class: 'primary', onclick: send }, 'Ответить')));
      setTimeout(function () { autoGrow(ta, 240); }, 0);
    } else if (!editing) {
      el.append(h('a', { class: 'reply-link', onclick: function () {
        ui.replying.set(c.id, '');
        rerender();
        const ta = document.querySelector('[data-fk="' + CSS.escape('reply:' + c.id) + '"]');
        if (ta) ta.focus();
      } }, messages.length ? 'Ответить' : 'Написать Claude'));
    }
    return el;
  }

  function flash(el) {
    if (!el) return;
    el.classList.remove('flash');
    void el.offsetWidth;
    el.classList.add('flash');
    el.scrollIntoView({ block: 'center', behavior: 'smooth' });
  }

  document.addEventListener('contextmenu', function (e) {
    const t = e.target;
    if (t && (t.tagName === 'TEXTAREA' || t.tagName === 'INPUT')) return;
    if (window.getSelection && String(window.getSelection())) return;
    e.preventDefault();
  });

  applyTheme(BOOT.theme);

  return {
    BOOT: BOOT, call: call, on: on, h: h, esc: esc, icon: icon, iconNode: iconNode, debounce: debounce,
    toast: toast, fail: fail, preserveFocus: preserveFocus, autoGrow: autoGrow, fmtDate: fmtDate,
    showMenu: showMenu, closeMenu: closeMenu, STATUS: STATUS, ui: ui, isOpen: isOpen, card: card,
    lineLabel: lineLabel, locationLabel: locationLabel, flash: flash,
  };
})();
