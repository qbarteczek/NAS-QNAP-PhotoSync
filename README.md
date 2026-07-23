# 📱→🖥️ NAS QNAP PhotoSync

> Automatyczna synchronizacja zdjęć z telefonu Android na serwer QNAP NAS – przez Wi-Fi, w tle, bez duplikatów.

[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)](LICENSE)
[![Node.js](https://img.shields.io/badge/Node.js-20-green.svg)](https://nodejs.org)
[![Android](https://img.shields.io/badge/Android-8.0%2B-brightgreen.svg)](https://developer.android.com)
[![Docker](https://img.shields.io/badge/Docker-Compose-blue.svg)](https://docs.docker.com/compose/)

---

## 📖 Opis

**NAS QNAP PhotoSync** to dwuczęściowe rozwiązanie open-source do pełnej automatyzacji backupu zdjęć z telefonów z systemem **Android** na domowy serwer **QNAP NAS** – bez abonamentów, chmury publicznej ani ograniczeń pojemności.

### Jak to działa?

```
[Telefon Android]  ──(Wi-Fi)──►  [Serwer QNAP NAS]
   NAS QNAP PhotoSync App                  NAS QNAP PhotoSync Server
   (Kotlin + WorkManager)          (Node.js + Docker)
         │                                 │
         │  1. Skanuje folder DCIM         │
         │  2. Oblicza sumy MD5            │
         │  3. Pyta serwer o duplikaty ───►│  Sprawdza bazę SQLite
         │  4. Przesyła tylko nowe ───────►│  Weryfikuje MD5
         │  5. Zapisuje w lokalnej DB      │  Zapisuje plik + log
         │                                 │
         └──── Harmonogram: co 2h ─────────┘
```

### Kluczowe zalety
- ✅ **Bez duplikatów** – weryfikacja sum kontrolnych MD5 po stronie telefonu i serwera (pliki przesłane już raz nigdy nie są wysyłane ponownie)
- ✅ **Oszczędność baterii** – synchronizacja uruchamia się tylko podczas ładowania i połączenia z Wi-Fi
- ✅ **Odporność na błędy transmisji** – serwer weryfikuje MD5 odebranego pliku i odrzuca uszkodzone dane
- ✅ **Synchronizacja w tle** – oparty na AndroidX WorkManager, działa nawet gdy aplikacja jest zamknięta i po restarcie telefonu
- ✅ **Estetyczny panel Web** – ciemny motyw z glassmorphism, galeria masonry, wykresy pamięci NAS i parowanie QR
- ✅ **Łatwa instalacja na QNAP** – jeden plik Docker Compose uruchamia cały serwer

---

## 🏗️ Architektura projektu

```
qsyncphoto/
├── server/                          # Backend (QNAP NAS)
│   ├── src/
│   │   ├── index.ts                 # Serwer Express API (Upload, Parowanie, Galeria)
│   │   └── types.ts                 # Definicje typów TypeScript
│   ├── public/                      # Panel Web UI
│   │   ├── index.html               # Struktura HTML5 (Dashboard, Galeria, Urządzenia)
│   │   ├── style.css                # Glassmorphism Dark Theme + Animacje
│   │   └── app.js                   # Logika SPA (Polling, QR, Galeria, Lightbox)
│   ├── Dockerfile                   # Obraz Docker Node.js 20 Alpine
│   ├── docker-compose.yml           # Konfiguracja dla QNAP Container Station
│   └── package.json                 # Zależności (Express, Multer, SQLite3, CORS)
│
└── android/                         # Aplikacja Android (Kotlin)
    ├── app/
    │   ├── build.gradle.kts         # Zależności (Compose, Room, WorkManager, MLKit)
    │   └── src/main/
    │       ├── AndroidManifest.xml  # Uprawnienia: Aparat, Zdjęcia, Internet
    │       └── java/com/qsyncphoto/
    │           ├── MainActivity.kt      # UI (Compose M3) + QR Scanner + Harmonogram
    │           ├── data/
    │           │   ├── SyncedFile.kt    # Encja Room (ścieżka, hash MD5, rozmiar)
    │           │   └── AppDatabase.kt   # Baza Room + DAO (sprawdzanie duplikatów)
    │           ├── worker/
    │           │   └── SyncWorker.kt    # WorkManager + skanowanie DCIM + upload
    │           └── network/
    │               └── ApiService.kt    # Klient HTTP OkHttp3 (parowanie, upload, MD5)
    ├── build.gradle.kts             # Konfiguracja root projektu Gradle
    └── settings.gradle.kts          # Ustawienia modułów Gradle
```

---

## 🚀 Instalacja i uruchomienie

### Wymagania wstępne
- QNAP NAS z zainstalowaną aplikacją **Container Station** (Docker)
- Android **8.0+** (API 26)
- Android Studio (do kompilacji APK)
- Sieć lokalna Wi-Fi (telefon i NAS w tej samej sieci)

---

### ETAP 1: Serwer na QNAP NAS

**Krok 1:** Skopiuj katalog `server/` na swój QNAP NAS (np. do `/share/Public/qsyncphoto-server/`) za pomocą **File Station**.

**Krok 2:** *(Opcjonalnie)* Edytuj `docker-compose.yml`, aby zmienić ścieżkę zapisu zdjęć:
```yaml
volumes:
  - ./data:/app/data
  # Zmień ścieżkę poniżej na swój docelowy folder na NAS:
  - /share/Multimedia/ZdjeciaAndroid:/app/uploads
```

**Krok 3:** Uruchom kontener przez SSH lub Container Station:
```bash
docker-compose up -d --build
```

**Krok 4:** Otwórz panel Web UI w przeglądarce:
```
http://<IP_TWOJEGO_QNAP>:3000
```

---

### ETAP 2: Aplikacja Android

**Krok 1:** Otwórz katalog `android/` w **Android Studio**.

**Krok 2:** Poczekaj na synchronizację Gradle (pobierze wszystkie zależności automatycznie).

**Krok 3:** Kliknij **Run** lub zbuduj APK przez `Build → Generate Signed Bundle/APK`.

---

### ETAP 3: Parowanie telefonu z NAS

1. W panelu Web UI (na komputerze) kliknij przycisk **"Dodaj telefon"**
2. Wyświetli się okno z kodem QR i 6-cyfrowym kodem parowania (ważny 5 minut)
3. W aplikacji na telefonie kliknij **"Zeskanuj kod QR z QNAP"**
4. Nakieruj aparat na kod QR na monitorze
5. Telefon automatycznie połączy się i pobierze token bezpieczeństwa ✅

---

## 🔌 API Serwera

| Metoda | Endpoint | Opis | Auth |
|--------|----------|------|------|
| `GET` | `/api/auth/pairing-code` | Generuje 6-znakowy kod parowania + QR | Brak |
| `POST` | `/api/auth/pair` | Rejestruje telefon, zwraca token | Brak (kod) |
| `POST` | `/api/upload` | Przesyła zdjęcie z weryfikacją MD5 | Bearer token |
| `POST` | `/api/sync-check` | Sprawdza które MD5 już są na serwerze | Bearer token |
| `GET` | `/api/photos` | Lista zsynchronizowanych zdjęć (paginacja) | Brak |
| `GET` | `/api/devices` | Lista sparowanych telefonów | Brak |
| `DELETE`| `/api/devices/:id` | Usuwa urządzenie (unieważnia token) | Brak |
| `GET` | `/api/status` | Statystyki (pliki, urządzenia, dysk) | Brak |
| `GET` | `/photos/:filename` | Serwowanie pliku zdjęcia | Brak |

---

## 🛡️ Bezpieczeństwo

- Każdy telefon otrzymuje unikalny **token Bearer** (64 znaki hex) generowany losowo podczas parowania
- Kody parowania QR są **jednorazowe** i wygasają po **5 minutach**
- Weryfikacja integralności pliku przez **MD5** po stronie serwera (ochrona przed uszkodzeniem danych)
- W środowiskach produkcyjnych zalecane: HTTPS + reverse proxy (np. Nginx) lub VPN (Tailscale)

---

## 📱 Funkcje aplikacji Android

| Funkcja | Opis |
|---------|------|
| **Skanowanie QR** | Parowanie przez CameraX + Google ML Kit |
| **Tryb manualny** | Ręczne wprowadzenie IP serwera i kodu |
| **Synchronizacja natychmiastowa** | Przycisk "Synchronizuj teraz" |
| **Harmonogram automatyczny** | WorkManager, co 2 godziny |
| **Tylko Wi-Fi** | Przełącznik (domyślnie: włączony) |
| **Tylko podczas ładowania** | Przełącznik (domyślnie: włączony) |
| **Postęp synchronizacji** | Live progress bar z nazwą aktualnego pliku |
| **Ochrona przed duplikatami** | Lokalna baza Room + weryfikacja z serwerem |

---

## 🧰 Stos technologiczny

### Serwer (QNAP)
| Technologia | Wersja | Zastosowanie |
|-------------|--------|--------------|
| Node.js | 20 LTS | Środowisko uruchomieniowe |
| TypeScript | 5.4 | Typowanie i kompilacja |
| Express | 4.19 | Framework HTTP |
| Multer | 1.4 | Obsługa multipart/form-data (upload) |
| SQLite3 | 5.1 | Baza danych (urządzenia, logi, MD5) |
| Docker | - | Konteneryzacja |

### Klient (Android)
| Technologia | Wersja | Zastosowanie |
|-------------|--------|--------------|
| Kotlin | 1.9 | Język programowania |
| Jetpack Compose | BOM 2024.02 | Interfejs użytkownika |
| Material 3 | - | System designu |
| Room | 2.6 | Lokalna baza SQLite |
| WorkManager | 2.9 | Synchronizacja w tle |
| OkHttp3 | 4.12 | Klient HTTP |
| CameraX | 1.3 | Podgląd aparatu do QR |
| ML Kit Barcode | 17.2 | Skanowanie kodu QR |

---

## 📄 Licencja

Projekt dostępny na licencji **GPL-3.0** – możesz go swobodnie używać, modyfikować i dystrybuować pod warunkiem udostępniania kodu pochodnego na tej samej licencji.

---

## 🤝 Kontrybutorzy

Projekt wygenerowany z pomocą [Antigravity AI](https://antigravity.dev) – Google DeepMind.
