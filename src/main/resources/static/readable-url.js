'use strict';

// Переписывает адрес в строке браузера на читаемый: адрес посчитан сервером и приходит атрибутом data-url. Параметры не из data-keep (utm, yclid) переносятся как есть.

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
