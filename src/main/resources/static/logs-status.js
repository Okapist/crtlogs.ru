'use strict';

// Виджет «Статус CT-логов»: одна реализация на все страницы (главная и результаты
// поиска). Находит контейнер #logs, сам рисует разметку и опрашивает публичный
// /api/status — без токена, поэтому без текстов ошибок (полные детали — /api/logs).

(function () {
  const box = document.getElementById('logs');
  if (!box) {
    return;
  }

  const esc = (s) => String(s ?? '').replace(/[&<>"']/g, (c) =>
    ({'&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;'}[c]));

  box.innerHTML = '<h2>Статус CT-логов</h2>'
    + '<div class="status" id="logsStatus">загрузка статуса…</div>'
    + '<div id="logsTable"></div>';

  const statusLine = document.getElementById('logsStatus');
  const table = document.getElementById('logsTable');
  let timer = null;

  async function refresh() {
    try {
      const resp = await fetch('/api/status');
      if (resp.status === 429) {
        statusLine.textContent = 'лимит запросов, повторим позже';
        return;
      }
      if (!resp.ok) {
        throw new Error(`HTTP ${resp.status}`);
      }
      render(await resp.json());
    } catch (err) {
      statusLine.textContent = 'статус недоступен: ' + err.message;
    } finally {
      // перепланируем в любом случае: одна ошибка или один 429 не должны
      // останавливать опрос навсегда — раньше таймер ставился только при успехе
      clearTimeout(timer);
      timer = setTimeout(refresh, 30000);
    }
  }

  function render(logs) {
    const rows = logs.map((l) => {
      const pct = l.tree_size ? Math.min(100, Math.round(100 * l.last_synced_index / l.tree_size)) : 0;
      const state = !l.ok ? '<span class="err">ошибка</span>'
        : (l.last_synced_index >= l.tree_size ? '<span class="ok">ок</span>' : 'синк…');
      return `<tr>
        <td>${esc(l.description)}<div class="muted">${esc(l.url)}</div></td>
        <td>${l.last_synced_index} / ${l.tree_size} <span class="bar"><i style="width:${pct}%"></i></span></td>
        <td>${state}</td>
        <td class="muted">${esc(l.updated_at || '')}</td>
      </tr>`;
    }).join('');
    table.innerHTML =
      `<table><tr><th>Лог</th><th>Записей</th><th>Состояние</th><th>Обновлён</th></tr>${rows}</table>`;
    statusLine.textContent = 'обновлено ' + new Date().toLocaleTimeString();
  }

  refresh();
})();
