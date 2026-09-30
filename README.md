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

* adblock.turtlecute.org (продолжение d3ward/toolz): **91 %** (120/132).
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
* Нет WebExtensions (Chrome Web Store), нет синхронизации, нет Safe Browsing (намеренно).

Лицензия: GPL-3.0 (используются скриптлеты uBlock Origin и списки под GPL-3.0). adblock-rust — MPL-2.0.
