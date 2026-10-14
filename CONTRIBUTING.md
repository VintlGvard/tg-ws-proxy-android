# Руководство по разработке (Contributing)

Мы рады любому вкладу в развитие **TG WS Proxy for Android**! Этот документ поможет вам развернуть проект и начать разработку.

---

## 🛠 Архитектура проекта

Проект состоит из двух ключевых компонентов:
1. **`core/`** — Сетевое ядро на языке **Go**. Реализует парсинг MTProto Fake TLS handshake, детекцию DC ID, упаковку трафика в WebSocket over TLS (WSS) и fallback через Cloudflare.
2. **`android/`** — Android-приложение на **Kotlin**. Реализует Foreground Service, Material 3 UI, Quick Settings Tile, автозапуск и управление настройками.

---

## 💻 Настройка окружения

Для сборки проекта требуются:
* **Go 1.22+**
* **JDK 21+**
* **Android SDK (API 35)**
* **Android NDK (версии r26+)** — для пересборки `.so` библиотек ядра

---

## 🔨 Сборка

### 1. Тестирование ядра Go
```bash
cd core
go test -v ./...
```

### 2. Кросс-компиляция native библиотек (`libtgproxy.so`)
Библиотеки компилируются под три архитектуры: `arm64-v8a`, `armeabi-v7a`, `x86_64`:

```bash
cd core

# arm64-v8a
$env:CGO_ENABLED="1"; $env:GOOS="android"; $env:GOARCH="arm64"; $env:CC="<NDK_PATH>/aarch64-linux-android35-clang"
go build -buildmode=c-shared -ldflags="-s -w" -o ../android/app/src/main/jniLibs/arm64-v8a/libtgproxy.so ./cmd/proxy

# armeabi-v7a
$env:CGO_ENABLED="1"; $env:GOOS="android"; $env:GOARCH="arm"; $env:GOARM="7"; $env:CC="<NDK_PATH>/armv7a-linux-androideabi35-clang"
go build -buildmode=c-shared -ldflags="-s -w" -o ../android/app/src/main/jniLibs/armeabi-v7a/libtgproxy.so ./cmd/proxy

# x86_64
$env:CGO_ENABLED="1"; $env:GOOS="android"; $env:GOARCH="amd64"; $env:CC="<NDK_PATH>/x86_64-linux-android35-clang"
go build -buildmode=c-shared -ldflags="-s -w" -o ../android/app/src/main/jniLibs/x86_64/libtgproxy.so ./cmd/proxy
```

### 3. Сборка Android приложения
```bash
cd android
./gradlew assembleDebug
```

---

## 🚀 Правила создания Pull Request

1. Создавайте отдельную ветку для изменений (`git checkout -b feat/my-feature`).
2. Убедитесь, что все тесты проходят (`go test ./...` и `./gradlew test`).
3. Придерживайтесь стиля кода Kotlin (Google Android Style Guide) и Effective Dart/Go.
4. Создайте PR с кратким описанием изменений.
