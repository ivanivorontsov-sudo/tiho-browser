# Тихо — лёгкий приватный браузер для Android

Kotlin + Jetpack Compose (Material 3) поверх Android System WebView. Блокировщик рекламы — **Brave adblock-rust** (Rust, JNI через cargo-ndk)
со списками EasyList, EasyPrivacy, RU AdList, uBlock filters/privacy/unbreak/quick-fixes/badware, Peter Lowe, HaGeZi Multi Light, AdGuard URL Tracking и AdGuard CNAME (CNAME-клоакинг),
плюс скриптлеты и redirect-ресурсы uBlock Origin. Без аналитики, телеметрии, Google Play Services и аккаунтов.

* Пакет: `org.ivanivorontsov.tiho`, minSdk 26, target 35, ABI arm64-v8a + armeabi-v7a, R8.
* Сборка: `.github/workflows/build.yml` — Rust-тесты, JNI-библиотеки, JVM unit-тесты, подписанный APK, GitHub Release.

## Возможности

**Браузер:** вкладки и инкогнито-вкладки (отдельный профиль WebView), закладки, загрузки (включая blob:/data:), поиск на странице,
версия для ПК, тёмная тема (системная/светлая/тёмная, Material You), «Поделиться» (с очищенной ссылкой), открытие ссылок из других приложений,
полноэкранное видео, выбор файлов, разрешения камеры/микрофона/геолокации только по запросу.

**Блокировка:** adblock-rust (сетевые правила, $removeparam, redirect-ресурсы, косметика: CSS, generic hide, процедурные фильтры,
скриптлеты uBO, в том числе YouTube), счётчик на щите и общая статистика, исключения по сайтам, свои правила, дополнительные списки,
обновление списков по кнопке (без фоновых запросов), блокировка всплывающих окон и авто-переадресаций.

**Приватность:** стирание всех данных при выходе и при старте после убийства процесса; очистка данных сайта при закрытии вкладки;
сторонние cookies выключены; HTTPS-only с предупреждением; удаление трекинговых параметров (~150 + per-site) и разворачивание
редиректов/AMP; блокировка `<a ping>` и `sendBeacon`; GPC + DNT; строгий Referer (опция); сброс `window.name` между сайтами;
WebRTC выключен / только relay; урезанный User-Agent («Android 10; K») и урезанные Client Hints; защита от отпечатков
(шум Canvas/Audio/WebGL/measureText/ClientRects по сессионному ключу и сайту, как farbling в Brave), подмена
hardwareConcurrency/deviceMemory/платформы/сети, удаление Battery/Gamepad/WebGPU/IdleDetection/Keyboard/InstalledRelatedApps/датчиков;
опционально: UTC как в Tor Browser, отключение WebGL, DNS-over-HTTPS (локальный прокси), FLAG_SECURE.
WebView: `MetricsOptOut`, `EnableSafeBrowsing=false`, пустой allowlist для `X-Requested-With`, запрет file/content-доступа.

**Расширения:** встроенные (блокировщик, «Без cookie-баннеров» с авто-отказом, «YouTube без рекламы», «Тёмные сайты»)
и userscripts Greasemonkey/Tampermonkey (@match/@include/@exclude/@run-at/@require/@noframes, GM_* API), установка по ссылке
на `.user.js` или из Greasy Fork. Расширения Chrome Web Store не поддерживаются: в WebView нет среды WebExtensions.

## Новое в 1.1.0: безопасность

**Безопасный режим оплаты.** Банки, СБП/НСПК, ЮMoney, платёжные шлюзы (CloudPayments, Robokassa, Stripe, PayPal, Adyen…) и серверы 3-D Secure (ACS)
определяются по встроенному списку (~140 доменов). Подписанные пакеты могут его только дополнять, удалить из него ничего нельзя.
Также работает эвристика страницы: поля `cc-*`, CVC, iframe платёжных шлюзов, PaymentRequest.

Такие сайты открываются в отдельной вкладке «Банк»:
- отдельный профиль WebView (MULTI_PROFILE, если WebView его поддерживает);
- FLAG_SECURE и скрытие чужих оверлеев (Android 12+);
- `filterTouchesWhenObscured`;
- реальный UA без шумов и подмен;
- никакого JS-моста;
- userscripts запускаются только с явной отметкой «доверять на оплате», и доверие закреплено за SHA-256 кода;
- антифрод- и 3DS-скрипты не блокируются (`payment-unbreak`);
- сторонние cookies включаются для вкладки (нужно 3-D Secure).

Защитные шимы сами проверяют, не является ли страница или любой её предок (`location.ancestorOrigins`) платёжной, и в этом случае отключаются.

**Фишинг и вредоносные сайты.** Используются локальные списки malware-filter (phishing-filter, URLhaus), около 78 тыс. записей.
Перед загрузкой опасной страницы показывается предупреждение. Запросов наружу нет.
Также есть:
- IDN-гомографы: смешанные алфавиты и кириллица, похожая на латиницу. Подозрительный адрес показывается в punycode;
- предупреждения о загрузке APK/EXE/скриптов и о скачивании по HTTP;
- запрет схем `javascript:`/`file:`/`content:`/`data:`/`intent:` из адресной строки и чужих приложений;
- показ целевого приложения для `intent://`;
- отказ в камере, микрофоне и геолокации сторонним iframe.

**Мост JS ↔ приложение.** Используется `WebViewCompat.addWebMessageListener` с правилами `https://*`/`http://*` и сессионным токеном.
Платёжные origin игнорируются. Ответы асинхронные. На старом WebView используется прежний интерфейс.
Сервисы сообщают о зависшем рендерере.

**Userscripts.** Скрипты и `@require` загружаются только по HTTPS. SHA-256 показывается и закрепляется при установке.
Если код изменился, установка требует нового подтверждения. Изменённый на диске код отключается.
Есть каталог скриптов с закреплёнными хэшами. Для сторонних списков фильтров действует то же закрепление SHA-256.

**Подписанные обновления** (публичный репозиторий [tiho-updates](https://github.com/ivanivorontsov-sudo/tiho-updates)):
- **Пакеты содержимого:** списки фильтров, фишинг-списки, JS-шимы, `payment-domains.json`, каталог userscripts, `min_webview_major`.
  Проверки:
  - подпись minisign/Ed25519 одним из двух вшитых ключей (основной `ED285F174C49C57B`, резервный `5A83C100C35A8CFA`); эти ключи не совпадают с ключом APK;
  - монотонная версия (защита от отката и повтора), срок действия, SHA-256 каждого файла;
  - атомарная замена и автоматический откат, если блокировщик не собрался.
  Проверка идёт раз в сутки через JobScheduler (по умолчанию только Wi-Fi) или по кнопке.
  Опция «Только списки, без кода» отключает JS из пакетов.
- **Обновление приложения:** подписанный `app/update.json`, затем скачивание APK по HTTPS с GitHub.
  Сверяются SHA-256, имя пакета, versionCode и сертификат подписи (`F1:72:85:…:D6:D0`). Установка через PackageInstaller, только после подтверждения пользователя.
- **Движок:** если версия WebView ниже безопасного минимума (встроено 140; пакет может только поднять, сейчас 148), показывается баннер «Движок устарел» со ссылкой на обновление.

Пакеты публикуются утилитой `tools/tiho-publish.py` (команды `keygen` / `pack` / `update` / `verify`).

## Исследование приватности: что применено

Изучены патчи Cromite/Bromite, Mull и arkenfox user.js, Brave Shields (farbling), Tor Browser (RFP), DuckDuckGo,
рекомендации privacyguides.org, методики EFF Cover Your Tracks, CreepJS и BrowserLeaks.

| Техника (источник) | В «Тихо» |
|---|---|
| Блокировка рекламы/трекеров, косметика, скриптлеты (Brave, uBO) | ✅ adblock-rust + uBO-ресурсы, блок fetch/XHR/подресурсов |
| CNAME-клоакинг (AdGuard, HaGeZi) | ✅ списком AdGuard CNAME + HaGeZi (DNS-уровень в WebView недоступен) |
| Удаление трекинговых параметров, AMP/redirect-разворачивание (Firefox, DDG, Brave) | ✅ |
| `<a ping>`, `navigator.sendBeacon` (arkenfox, Cromite) | ✅ блокируются |
| Referer: обрезка cross-site до origin (arkenfox XOrigin) | ✅ `document.referrer` + навигации; строгий режим — опция |
| GPC / DNT (Cromite, DDG) | ✅ `navigator.globalPrivacyControl` + заголовки основных запросов |
| Сторонние cookies (Brave, Cromite) | ✅ выключены; инкогнито — отдельный профиль WebView |
| Очистка при закрытии вкладки/выходе (arkenfox sanitizeOnShutdown) | ✅ |
| Canvas/Audio/WebGL/measureText/ClientRects — шум по сессии+сайту (Brave farbling, Cromite) | ✅ |
| WebGL vendor/renderer — единое распространённое значение (Tor-подход) | ✅ «Qualcomm / Adreno (TM) 640»; WebGL можно выключить |
| hardwareConcurrency/deviceMemory/platform/connection (Brave, Tor) | ✅ 8 / 4 / «Linux armv8l» |
| screen = окно, colorDepth 24 (Brave) | ✅ |
| Часовой пояс UTC (Tor RFP) | ✅ опция (по умолчанию выкл — ломает сайты) |
| UA без модели и версии + Client Hints low-entropy (Cromite, Chrome UA reduction) | ✅ «Android 10; K», Chrome/NN.0.0.0; UserAgentMetadata где WebView поддерживает |
| Battery, Gamepad, WebGPU, IdleDetection, Keyboard, getInstalledRelatedApps, датчики, MIDI, SpeechSynthesis-голоса (Cromite) | ✅ удалены/пустые |
| WebRTC (Cromite, Tor) | ✅ выключен по умолчанию; режим «только relay» |
| Cookie-баннеры авто-отказ (DDG, Brave) | ✅ встроенное расширение |
| HTTPS-only (Tor, Brave) | ✅ с предупреждением |
| Телеметрия WebView, Safe Browsing (Cromite) | ✅ MetricsOptOut, SafeBrowsing off, X-Requested-With пустой |
| Letterboxing, allowlist шрифтов (Tor) | ❌ невозможно в WebView без форка движка |
| DNT/GPC/Referer на подресурсах, заголовки Client Hints | ❌ WebView не даёт менять заголовки подзапросов |
| Партиционирование хранилища по eTLD+1 (Brave, Firefox TCP) | ⚠️ частично: встроенное в Chromium + очистка; per-tab профили — нет |
| Защита в Web Workers, невидимая для CreepJS | ❌ JS-подмена детектируется |

## Результаты тестов (эмулятор Android 11, WebView 83)

* adblock.turtlecute.org (продолжение d3ward/toolz): **95 %** (126/132) в 1.1.0 с пакетом v1; в 1.0.1 было 91 % (120/132).
* EFF Cover Your Tracks: **«strong protection against Web tracking»**; Canvas и AudioContext — «randomized».
* BrowserLeaks: WebRTC — **No Leak** (RTCPeerConnection отсутствует); Canvas — подпись уникальна в каждой сессии/на каждом сайте (шум);
  WebGL — Unmasked Vendor «Qualcomm»; navigator — «Linux armv8l», 8 ядер, 4 ГБ; screen = окно.

## Сборка локально

```bash
cd rust/tihoadblock
cargo test --release --lib && cargo build --release --lib
cargo ndk -t arm64-v8a -t armeabi-v7a -P 26 -o ../../app/src/main/jniLibs build --release --lib
cd ../.. && ./gradlew :app:testReleaseUnitTest :app:assembleRelease
```

Подпись: `keystore/privbrowser-release.p12` + `keystore.properties` (storePassword/keyAlias/keyPassword) или переменные
`TIHO_KEYSTORE_PASSWORD`, `TIHO_KEY_ALIAS`, `TIHO_KEY_PASSWORD`. Для эмулятора x86_64: `-PwithX86`.

Ресурсы скриптлетов пересобираются `node tools/gen-resources.mjs` из исходников uBlock Origin.

## Честные ограничения

* Движок — System WebView: качество изоляции сайтов и обновления безопасности зависят от установленного WebView.
* Защита от отпечатков работает на уровне JavaScript (как расширения), а не в движке, как в Brave/Cromite/Tor: не покрывает
  Web Workers, определяется продвинутыми тестами (CreepJS видит подмену), шрифты не подменяются (screen = размер окна, как в Brave).
* Referer сторонних подресурсов и заголовки Client Hints регулирует сам WebView (по умолчанию `strict-origin-when-cross-origin`).
* WebView не даёт перехватывать тело POST-запросов и WebSocket, поэтому часть запросов блокируется только по URL.
* DNS-over-HTTPS — экспериментально, через локальный прокси; WebRTC UDP идёт мимо прокси (поэтому WebRTC по умолчанию выключен).
* Нет WebExtensions (Chrome Web Store), нет синхронизации, нет Safe Browsing (намеренно; фишинг-списки локальные).
* В публичном API WebView нет изолированных миров для скриптов, как у расширений. Шимы и userscripts выполняются в мире страницы.
  Защита строится на захвате ссылок до запуска страничного кода и на сессионном токене.
* Отдельный профиль «Банк» (MULTI_PROFILE) и `addWebMessageListener` зависят от версии WebView.
  На старом WebView вкладка оплаты использует общий профиль (cookies очищаются при закрытии), а мост — прежний интерфейс.
* Блокировка `fetch`/XHR и косметика запрашиваются у приложения асинхронно. Значения GM_* — снимок на момент загрузки страницы.
* В режиме оплаты нельзя скачать файлы blob:. Переход на платёжный сайт внутри страницы (POST, OAuth, 3-D Secure)
  остаётся в той же вкладке с защитой «на месте», а не переносится во вкладку «Банк»: иначе ломаются cookies сессии.
* Сайты на сертификатах «Russian Trusted Root CA» (часть доменов Сбера и др.) WebView не открывает: Android не доверяет этому УЦ.

Лицензия: GPL-3.0 (используются скриптлеты uBlock Origin и списки под GPL-3.0). adblock-rust — MPL-2.0.
