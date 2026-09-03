// Счётчик Яндекс.Метрики. Отдельный файл, а не инлайн в каждом шаблоне HtmlView:
// иначе id счётчика и набор опций пришлось бы держать одинаковыми в трёх местах.
// <noscript>-пиксель остаётся в разметке страниц — внешний скрипт при выключенном
// JS не выполнится по определению.
(function (m, e, t, r, i, k, a) {
  m[i] = m[i] || function () { (m[i].a = m[i].a || []).push(arguments); };
  m[i].l = 1 * new Date();
  for (var j = 0; j < document.scripts.length; j++) {
    if (document.scripts[j].src === r) { return; }
  }
  k = e.createElement(t);
  a = e.getElementsByTagName(t)[0];
  k.async = 1;
  k.src = r;
  a.parentNode.insertBefore(k, a);
})(window, document, 'script', 'https://mc.yandex.ru/metrika/tag.js?id=112064937', 'ym');

ym(112064937, 'init', {
  ssr: true,
  webvisor: true,
  clickmap: true,
  ecommerce: 'dataLayer',
  referrer: document.referrer,
  url: location.href,
  accurateTrackBounce: true,
  trackLinks: true
});
