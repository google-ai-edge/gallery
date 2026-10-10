# Galerie: alles auf dem Handy, Mac nur als Beschleuniger

Entscheidung (Pascal, 2026-10-10): Verarbeitung bevorzugt auf dem Gerät (offline, GPU), der Server nur für die
Erstindexierung bzw. wenn viel zu tun ist (parallel). Transkripte nicht für alle Videos, nur auf Abruf, lokal.

## Messung (S26 Ultra, SM8850, Android 16, 50 eigene Fotos, 70 Begriffe)

- Handy-Anfrage mit Präfix `task: search result | query: ` gegen Mac-Vektoren (560): 98 % gleiche beste
  Kategorie wie der Server, 96 % gleiche Top-5. Ohne Präfix 72 % / 78 %. -> Vektoren mischbar, Präfix Pflicht.
- Handy-Bildbudget endet bei 280 Tokens (560 = 280). GPU: 70 Tokens 135 ms/Foto, 280 Tokens 353 ms/Foto.
  NPU langsamer (284 ms bei 70), Modell nicht für den NPU übersetzt -> GPU.

## Phasen

### Phase 1: Lokale Suche über heruntergeladene Vektoren
- [x] 1.1 Server: `GET /api/mediasearch/vectors` (seitenweise, int8, inkl. Szenen)
- [x] 1.2 App: lokaler Vektorspeicher, Download nach dem Abgleich (nur Neues)
- [x] 1.3 Suchmodell auf dem Handy Pflicht (Download beim ersten Start), Anfrage mit Präfix
- [x] 1.4 Suche, Ähnliche, Szenen-Treffer lokal; Server nicht mehr für die Suche

### Phase 2: Bubbles, Alben, Aufräumen, Kategorien lokal
### Phase 3: Handy rechnet neue Medien selbst (280 Tokens), Mac parallel nur bei großem Rückstand
### Phase 4: "Tiefer suchen" in Videos auf dem Handy (2-s-Fenster Bild+Ton)
### Phase 5: Transkripte auf Abruf mit Gemma 4 E2B auf dem Handy

## Implementation Log

- **Phase 1**: Server-Endpoint in KuhlerBuhler/Interface#816 (mit `since` fuer nur Neues).
- **Phase 1**: Vektoren liegen int8 in SQLite (`vectors.db`), die Suche rechnet im Speicher (ein Block, ~19 MB bei 25k Eintraegen). Herunterladen beim Abgleich (nur WLAN), vor dem Hochladen und danach.
- **Phase 1**: Der alte 70-Token-Index der Original-App (eigene IDs) wird nicht mehr geplant; der Worker wird abgemeldet.
- **Phase 1**: Ausnahmen bleiben vorerst beim Server: "Nur passende Bubbles" (bis Phase 2) und Gesprochenes (bis Phase 5, kommt nachgeladen dazu).
- **Phase 1**: Ohne Vektoren auf dem Handy (frisch installiert, vor dem ersten Abgleich) fragt die App wie bisher morgenschiss.

