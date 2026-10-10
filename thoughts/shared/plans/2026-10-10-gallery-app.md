# Galerie-App mit morgenschiss-Suche (Fork google-ai-edge/gallery)

Quelle: `~/Desktop/repos/gallery-mediasearch-prompt.md`, Server-API: Interface `docs/mediasearch/api.md`.
Basis-Pfad im Code: `Android/src/app/src/main/java/com/google/ai/edge/gallery` (= `B`).

## Entscheidungen (Discover, 2026-10-10)

- Lokaler Index waechst im Hintergrund, nur beim Laden + idle (WorkManager-Constraints).
- Ordner flach nach `BUCKET_DISPLAY_NAME` (gruppiert per `BUCKET_ID`), sortiert nach neuestem Medium.
- Transkript ueber morgenschiss (Parakeet, neuer Endpoint `/api/mediasearch/transcribe`, Interface PR #811);
  ohne Login/Server ausgegraut. Audio Scribe (LLM) fliegt raus.
- Aufraeumen/Kategorien: Defaults von mir, in der App editierbar.
- iOS ignoriert.

## Architektur

- **Start:** neue Route `gallery` als `startDestination`; Home-Task-Liste, Chat, Prompt Lab, Agent Chat,
  Mobile Actions, Tiny Garden, Scrapbook, Example-Task, Benchmark entfernt (TaskModules loeschen).
  Smart Album bleibt als Infrastruktur (Embedder, Vektor-Store, Modell-Download), nicht als Task-Kachel.
- **Allowlist:** Upstream laedt sie zur Laufzeit von GitHub nach `versionName`. Fix: feste Datei
  `model_allowlists/1_0_20.json` aus unserem Fork (nur EmbeddingGemma-2) + Kopie als Asset als Fallback.
- **Medien:** eigenes `MediaRepository` (MediaStore Files: `_ID, MEDIA_TYPE, MIME_TYPE, SIZE, DATE_TAKEN,
  DATE_MODIFIED, DISPLAY_NAME, DURATION, BUCKET_ID, BUCKET_DISPLAY_NAME, RELATIVE_PATH`). Thumbnails mit Coil
  (schon Dependency), Video mit media3 ExoPlayer (schon Dependency).
- **IDs:** Fingerabdruck wie Interface (SHA-256 ueber `"<size>\n"` + Anfang/Mitte/Ende je 4 MiB, bis 12 MiB
  ganz). Cache-Tabelle (SQLite, `SQLiteOpenHelper`, keine neue Dependency) `mediaId -> (size, dateModified,
  fingerprint, serverIndexed)`; neu berechnet nur, wenn size/dateModified sich aendern.
- **Netz:** `HttpURLConnection` + kotlinx.serialization (vorhanden), kein OkHttp. Ein `MorgenschissClient`
  kapselt Session-Cookie, Content-Type-Pruefung (HTML = kein Recht), 403/302 = abgemeldet, 503 = lokal.
- **Token:** in `EncryptedSharedPreferences` (security-crypto vorhanden); Passwort wird nie gespeichert,
  nur SHA-256 beim Login gesendet.
- **Suche:** Server zuerst (3 s Timeout), Treffer-IDs -> lokale Medien via Fingerprint-Tabelle; bei
  503/Netz/Timeout lokale Suche im On-Device-Index (MediaPipe-Store, IDs = MediaStore `_ID` wie upstream,
  nicht gemischt).
- **3D-Karte:** eigene Projektion auf Compose `Canvas` (Rotation per Drag, Tiefensortierung, `drawCircle`
  je Punkt, 8700 Punkte). Keine 3D-Bibliothek: Filament/SceneView waeren Megabytes fuer Kugeln, die Canvas
  bei der Menge ohne Muehe zeichnet (Projektion ist 8700 x 9 Multiplikationen je Frame).
- **CI:** `build-apk.yml` aus `gallery-signing` (plus Platform 37), Signing aus Env, versionCode 100000+Run,
  Release mit R8 (eigene `proguard-rules.pro`), Upload nach morgenschiss. Upstream-Workflows entfernt.

## Phasen

### Phase 1: Installierbare Galerie (Ordner, Raster, Vollbild, Video) + CI
- [x] 1.1 `applicationId` `de.morgenschiss.gallery`, App-Name "Galerie", Signing-Snippet, R8 + proguard-rules
- [x] 1.2 Allowlist fest auf eigene Datei + Asset-Fallback
- [x] 1.3 Andere Tasks/Screens raus, Firebase-Telemetrie aus, Release-Polling raus, unnoetige Permissions raus
- [x] 1.4 MediaRepository (Ordner flach, Medien je Ordner, Alle)
- [x] 1.5 Startseite: Ordnerliste + Raster, Vollbild mit Wischen, Video-Wiedergabe, Teilen
- [x] 1.6 Workflow `build-apk.yml`, Upstream-Workflows weg
- Kriterien: `./gradlew assembleRelease` gruen; App startet im Emulator in die Galerie; Ordner zeigen Medien.

### Phase 2: Login, Server-Suche, Update-Hinweis
- [x] 2.1 Einstellungen: Login/Abmelden, Token verschluesselt, Status
- [x] 2.2 MorgenschissClient + Fehlerbilder (403/302/HTML/503/429)
- [x] 2.3 Fingerprint + Cache-Tabelle
- [x] 2.4 Suche im Ordner / ueberall (Server), Score-Schwelle wie upstream, "Aehnliche finden"
- [x] 2.5 Update-Hinweis aus `/apk/version`
- Kriterien: Unit-Tests Fingerprint (gleiche Werte wie Interface `fileFingerprint.js`), Client-Fehlerabbildung.

### Phase 3: Erstindexierung ueber den Mac
- [x] 3.1 Sync-Worker: `/ids` abgleichen, neue/geaenderte hochladen (16er Batches, nacheinander, 1536/512 px,
      JPEG 80, Video 2 Frames), `failed` merken, 503/429 -> Retry mit Backoff, fortsetzbar
- [x] 3.2 Geloeschte per `/remove`, verschobene neu senden (Ordner)
- [x] 3.3 Fortschritt in Einstellungen + Notification
- Kriterien: Unit-Tests Batch-Bildung/Diff; manuell gegen echten Server.

### Phase 4: Lokaler Index im Hintergrund (offline-Fallback)
- [x] 4.1 Modell-Download aus Einstellungen (EmbeddingGemma-2, ~485 MB)
- [x] 4.2 Indexing-Worker mit Constraints Laden + idle, periodisch
- [x] 4.3 Lokale Suche als Fallback verdrahtet, Hinweis "lokal gesucht"

### Phase 5: Aufraeumen
- [x] 5.1 Labels + Mindestalter + Schwellen editierbar (Defaults)
- [x] 5.2 `/classify` -> Raster vorausgewaehlt, abwaehlen, `createDeleteRequest`, danach `/remove`

### Phase 6: Kategorien-Analyse
- [x] 6.1 Labels editierbar, `/classify`, Anzahl/Anteil je Kategorie, nach Jahr

### Phase 7: Analyse 3D
- [x] 7.1 `/map`, Canvas-Wuerfel mit Kugeln (Groesse = size, Farbe = mime), Legende, Drag/Pinch, Tippen oeffnet

### Phase 8: Detail-Aktionen
- [x] 8.1 Transkript: Tonspur per media3 Transformer (nur Audio, AAC) -> `/transcribe` -> Saetze mit Zeiten,
      Tippen springt im Player
- [ ] 8.2 Video Moment Finder als Aktion im Video (vorhandene Logik, lokales Modell)

## Blind Spots & Open Unknowns

- HF-Gating von EmbeddingGemma-2 nicht geprueft (Phase 4).
- Ob R8 mit MediaPipe/LiteRT-LM ohne Keep-Regeln laeuft: konservative Keep-Regeln fuer `com.google.mediapipe`,
  `com.google.ai.edge` und JNI; Release im Emulator starten.
- Emulator hat keine echten 8700 Medien; Last nur am Geraet pruefbar.
- GitHub-Secrets brauchen Pascals OK.

## Implementation Log

> Abweichungen, Entdeckungen, Entscheidungen waehrend der Umsetzung.

- **Phase 1**: Allowlist nicht aus dem eigenen Repo geladen, sondern als Asset gebuendelt (kein Netz noetig, versionName egal).
- **Phase 1**: Release-APK war mit allen ABIs 159 MB; nur arm64-v8a -> 62 MB (Handy und Apple-Silicon-Emulator sind arm64).
- **Phase 1**: oss-licenses 0.11.0 bricht Debug-Builds unter Gradle 9 (`addDebugLicense`); Debug-Task abgeschaltet, Release sammelt Lizenzen weiter.
- **Phase 1**: llmchat/agent/mcp/skills bleiben im Code (ModelManagerViewModel und Runtime haengen daran), nur nicht mehr registriert; Telemetrie und FCM entfernt.
- **Phase 2**: Interface brauchte einen neuen Endpoint fuers Transkript (KuhlerBuhler/Interface#811, gemergt): rohes Audio, Job im Speicher, Polling.
- **Phase 2**: Suche ohne Server und ohne lokalen Index faellt auf Dateinamen zurueck und sagt das in der Oberflaeche.
- **Phase 7**: 3D mit Testdaten (8700 Punkte) im Emulator geprueft: dreht fluessig, Tippen oeffnet das Medium; Kugelgroesse skaliert mit der Punktzahl.
- **Phase 8**: 8.2 Video Moment Finder zurueckgestellt: der vorhandene VMF kopiert das ganze Video und braucht das lokale Modell; das Transkript deckt "Stelle im Video finden" ueber morgenschiss ab. Rueckfrage an Pascal.
- **Alle**: Server-Funktionen (Login, Sync, Suche, Aufraeumen, Kategorien, 3D, Transkript) nicht gegen den echten Server getestet, Login macht Pascal selbst.

## References

- `~/Desktop/repos/gallery-mediasearch-prompt.md`
- Interface `docs/mediasearch/api.md`, `client/Apps/Shared/fileFingerprint.js`
