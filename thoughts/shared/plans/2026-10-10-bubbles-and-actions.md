# Galerie: Medien-Aktionen und Bubbles

Entscheidungen (Pascal, 2026-10-10): Ordner aus einer Bubble optional als echter Ordner (Dateien werden
verschoben), sonst Album in der App. Keine Farbe pro App. Die Galerie braucht Löschen/Verschieben.

## Phasen

### Phase 1: Medien-Aktionen
- [x] 1.1 Mehrfachauswahl im Raster (lange drücken), Teilen mehrerer
- [x] 1.2 Löschen über den Android-Papierkorb (createTrashRequest), auch im Vollbild
- [x] 1.3 In Ordner verschieben (bestehender oder neuer Ordner, createWriteRequest + RELATIVE_PATH)
- [x] 1.4 Favoriten (createFavoriteRequest), Favoriten-Kachel, Info (Größe, Auflösung, Pfad), Bearbeiten mit
- Kriterien: Emulator: löschen, verschieben, favorisieren funktionieren, Sync sendet verschobene Dateien neu.

### Phase 2: Bubbles am Server
- [x] 2.1 `/api/mediasearch/bubbles`: Gruppen im 768d-Raum (k-means, zweistufig), Mindestgröße, Kern, beste Bilder
- [x] 2.2 Namensvorschlag und Tags aus einer Label-Liste (Zero-Shot), Namen pro User speicherbar
- [x] 2.3 3D-Lage der Bubbles aus ihren Abständen (MDS), Punkte um ihre Bubble

### Phase 3: Bubble-Ansicht in der App
- [x] 3.1 Statische Kamera, Pfeile fliegen animiert zur nächsten Bubble, verdeckende Bubbles transparent
- [x] 3.2 Strich + Sprechblase mit den besten Bildern je Bubble
- [x] 3.3 Leiste unten: Name, Tags, Anzahl, Umbenennen; hochziehen = Raster, angesehenes Medium leuchtet
- [x] 3.4 Unter-Bubbles betreten und zurück

### Phase 4: Ordner und Alben aus Bubbles
- [x] 4.1 Album in der App aus einer Bubble (Kern gespeichert), neue Medien landen automatisch dort
- [x] 4.2 Option "als echter Ordner": Dateien nach Pictures/<Name>/ verschieben, neue später auch
- [x] 4.3 Tags in der Suche (Schalter "nur passende Bubbles")

### Phase 5: Gesprochenes durchsuchbar
- [x] 5.1 Videos beim Abgleich transkribieren, Text am Server, Suche mit Treffern im Gesagten

## Implementation Log

- **Phase 1**: Löschen geht in den Android-Papierkorb (30 Tage), nicht endgültig; Android fragt bei fremden Dateien selbst. Im Emulator geprüft: Favorit (is_favorite=1), Verschieben nach Pictures/Test/, Papierkorb, Mehrfachauswahl.
- **Phase 2**: Server KuhlerBuhler/Interface#813 gemergt; Review-Fix: Namen werden global zugeordnet (Schwelle 0,95), Umbenennen löscht nur den eigenen Namen.
- **Phase 3**: Statt der alten 3D-Punktwolke (Menüpunkt jetzt "Bubbles"). Mit 12 Test-Bubbles im Emulator geprüft: Fokus-Animation per Pfeil, Sprechblasen, Leiste hochziehen, Vorschau blättern hebt den Punkt hervor.
- **Phase 4**: Server KuhlerBuhler/Interface#814 gemergt (Review-Fix: Schwelle gegen den eigenen Kern, Alben überschneiden sich nicht). Neue Dateien für Ordner-Alben werden beim Abgleich vorgemerkt; verschieben geht nur mit Rückfrage von Android, deshalb ein Banner "Einsortieren" auf der Startseite statt stillem Verschieben im Hintergrund.
- **Phase 5**: Server KuhlerBuhler/Interface#815 gemergt (Review-Fix: int8 im Speicher, Zähler getrennt, Text bleibt bei fehlendem Mac "pending"). Abgleich transkribiert jedes Video einmal nach den Szenen; Videos ohne Tonspur werden nur lokal als erledigt markiert. Gegen echten Server nicht getestet.
