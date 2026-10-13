# TG WS Proxy for Android

[![Platform](https://img.shields.io/badge/Platform-Android%207.0%2B%20(API%2024%2B)-brightgreen.svg)](https://android.com)
[![Kotlin](https://img.shields.io/badge/Kotlin-1.9-7F52FF?logo=kotlin&logoColor=white)](https://kotlinlang.org)
[![Go](https://img.shields.io/badge/Go-1.22%2B-00ADD8?logo=go&logoColor=white)](https://golang.org)
[![Architecture](https://img.shields.io/badge/Arch-arm64--v8a%20%7C%20armeabi--v7a%20%7C%20x86__64-orange.svg)](#)
[![License](https://img.shields.io/badge/License-MIT-purple.svg)](LICENSE)

Нативный Android-клиент и фоновый сервис для обхода замедления и блокировок Telegram (медиафайлы, голосовые, видеосообщения, каналы) без использования стороннего VPN.

Основан на логике проекта [Flowseal/tg-ws-proxy](https://github.com/Flowseal/tg-ws-proxy). Сетевое ядро переписано на **Go** и скомпилировано в разделяемую библиотеку (`libtgproxy.so`), поверх которой работает Android-клиент на **Kotlin & Material 3**.

---

## Скриншоты

<p align="center">
  <img src="docs/screenshots/screen_main.png" width="320" alt="Главный экран">
  &nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;
  <img src="docs/screenshots/screen_settings.png" width="320" alt="Умные настройки">
</p>

---

## Принцип работы

```
Telegram Client (на устройстве)
      │
      ▼ (MTProto TCP handshake с Fake TLS)
Локальный прокси: 127.0.0.1:1443 (Go Core в Foreground Service)
      │
      ├─► Валидация Fake TLS HMAC (маскировка под google.com, apple.com и др.)
      ├─► Извлечение DC ID (DC1..DC5) и типа сессии (media / non-media)
      ├─► Инкапсуляция пакетов в WebSocket over TLS (WSS)
      │
      ▼ (WSS по порту 443)
Telegram Web Gateways (kws*.noskomnadzor.co.uk / web.telegram.org)
      │ (при блокировке прямого WS — автоматический Fallback через Cloudflare)
      ▼
Telegram Data Centers (DC 1..5)
```

Маскировка выполняется локально: для интернет-провайдера и ТСПУ трафик выглядит как стандартное HTTPS/TLS-обращение к доверенному домену (Google, Apple iCloud и др.).

---

## Возможности

* **Быстрое подключение:** Запуск сервиса и автоматический импорт настроек в Telegram в два нажатия.
* **Нативный интерфейс:** Тёмная тема в палитре Telegram Android, встроенный мониторинг сессий и скорости в реальном времени.
* **Умные настройки:**
  * Пресеты SNI-маскировки: Google, Apple, Cloudflare, Microsoft, Яндекс.
  * Автоматическая генерация валидных Fake TLS ключей (`ee<32 hex><domain in hex>`).
  * Настройка локального порта и переключение Cloudflare Fallback.
* **Низкое потребление ресурсов:**
  * Оперативная память: ~10–14 МБ RAM.
  * Минимальная нагрузка на батарею благодаря горутинам Go.
* **Фоновый режим:**
  * Foreground Service с типами `specialUse`, `WakeLock` и `WifiLock`.
  * Плитка в шторке быстрых настроек Android (Quick Settings Tile).
  * Встроенные Keep-Alive пинги (30 сек) для предотвращения разрыва сессий.
  * Локальный DNS-кэш и параллельный опрос независимых DNS-серверов.

---

## Сборка

### Android APK

Требуется Android SDK и JDK 21+:

```bash
cd android
./gradlew assembleRelease
```

Собранный файл:
`android/app/build/outputs/apk/release/app-release.apk`

### Нативное ядро (Go)

Кросс-компиляция библиотеки под Android выполняется через Android NDK Toolchain:

```bash
cd core

# arm64-v8a
$env:CGO_ENABLED="1"; $env:GOOS="android"; $env:GOARCH="arm64"; $env:CC="<NDK_PATH>/aarch64-linux-android35-clang"
go build -buildmode=c-shared -ldflags="-s -w" -o ../android/app/src/main/jniLibs/arm64-v8a/libtgproxy.so ./cmd/proxy

# armeabi-v7a
$env:CGO_ENABLED="1"; $env:GOOS="android"; $env:GOARCH="arm"; $env:GOARM="7"; $env:CC="<NDK_PATH>/armv7a-linux-androideabi35-clang"
go build -buildmode=c-shared -ldflags="-s -w" -o ../android/app/src/main/jniLibs/armeabi-v7a/libtgproxy.so ./cmd/proxy
```

---

## Лицензия

Проект распространяется под лицензией MIT. Подробнее см. в файле [LICENSE](LICENSE).  
Оригинальная идея и протокол: [Flowseal/tg-ws-proxy](https://github.com/Flowseal/tg-ws-proxy).
