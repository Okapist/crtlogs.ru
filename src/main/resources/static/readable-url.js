'use strict';

// Переписывает адрес в строке браузера на читаемый: готовый адрес посчитан сервером
// и приходит атрибутом data-url (правило кодирования живёт только там). Параметры,
// не названные в data-keep (utm, yclid), переносятся как есть; replaceState не
// перезапрашивает страницу. Без JavaScript адрес просто остаётся закодированным.

(function () {
  // currentScript пуст у отложенного/переинжектированного скрипта — нечего читать
  const script = document.currentScript;
  if (!script) {
    return;
  }

  const ours = new Set(script.dataset.keep.split(','));
  const foreign = location.search.slice(1).split('&')
    .filter((pair) => pair && !ours.has(pair.split('=')[0]))
    .map((pair) => '&' + pair)
    .join('');

  history.replaceState(null, '', script.dataset.url + foreign);
})();
