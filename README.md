# Weihnachtsmod (Fabric, serverseitig)

Clients brauchen die Mod nicht, sie läuft nur auf dem Server.

## Bauen
1. Java 25 installieren
2. `./gradlew build`
3. `build/libs/weihnachtsmod-1.0.0.jar` (NICHT die `-sources.jar`) in den `mods`-Ordner des Servers legen
4. Fabric Loader + Fabric API (passend zu Minecraft 26.3) auf dem Server installieren

## Befehle
- `/weihnachten`      Countdown bis Heiligabend
- `/adventskalender`  Tägliches Türchen (1.-24. Dezember)
- `/schneemann`       Baut vor dir einen Schneemann (30 s Cooldown)
- `/rahmen unsichtbar [radius]`  Macht Item Frames in der Nähe unsichtbar (Standard 5 Blöcke, höchstens 32). Das Item darin bleibt sichtbar. `/rahmen sichtbar [radius]` macht sie wieder sichtbar.
- `/teller`  Stellt an deiner Position einen unsichtbaren Item Frame mit Teller auf den Boden. Essen mit Rechtsklick hineinlegen. `/teller weg` entfernt den nächsten Teller.
- `/weihnachtslied`  Zufälliges Weihnachtslied für dich (Notenblock-Töne, kein Ressourcenpaket nötig)
- `/weihnachtslied <jingle-bells|stille-nacht|we-wish-you|morgen-kommt-der-weihnachtsmann|deck-the-halls|greensleeves|joy-to-the-world|ode-an-die-freude>`  Wunschlied aus der Liste
- `/weihnachtslied <lied> <spieler>`  Wunschlied nur für einen bestimmten Spieler
- `/weihnachtslied liste`  Alle verfügbaren Lieder anzeigen
- `/weihnachtslied skip [spieler]`  Nächstes Lied (bei Shuffle ein zufälliges, sonst das nächste der Liste)
- `/weihnachtslied shuffle`  Zufallswiedergabe ein/aus. Danach spielt sie dauerhaft Lied für Lied
- `/weihnachtslied shuffle <alle|noten|gesang> [spieler]`  Shuffle nur mit allen Liedern, nur Notenblock-Liedern oder nur Aufnahmen
- `/weihnachtslied stop [spieler]`  Musik stoppen

Das Lied hören nur die Spieler, für die es gestartet wurde.

## Eigene Lieder hinzufügen
Beim ersten Start legt die Mod die Datei `config/weihnachtsmod_lieder.json` an. Dort kannst du eigene Lieder eintragen, danach den Server neu starten:

```json
{
  "lieder": {
    "mein-lied": {
      "titel": "Mein Lied",
      "instrument": "bell",
      "tempo": 4,
      "noten": "E4:2 G4:2 C5:4 R:2 D5:2 E5:4"
    }
  }
}
```

- `noten`: durch Leerzeichen getrennt, Format `NOTE:DAUER`. NOTE = `C4`, `F#4`, `B3` ... oder `R` für Pause.
- `DAUER`: Länge in Einheiten. Eine Einheit dauert `tempo` Ticks (20 Ticks = 1 Sekunde).
- `dauer`: Länge einer Aufnahme in Sekunden. Nur nötig, damit Shuffle nach dem Ende einer Aufnahme von selbst weiterspielt. Ohne `dauer` hilft nur `/weihnachtslied skip`.
- `tempo`: Ticks pro Einheit (kleiner = schneller). Standard 4.
- `instrument`: `harp`, `bell`, `chime`, `flute`, `pling`, `guitar` oder `xylophone`.
- Der Name (hier `mein-lied`) ist der Befehlsname: `/weihnachtslied mein-lied`.
- Fehlerhafte Lieder werden übersprungen und im Server-Log gemeldet.
- Tonumfang wie beim Notenblock: Fis3 bis Fis5. Höhere/tiefere Töne rutschen automatisch eine Oktave.

## Echte Aufnahmen (gesungene Lieder)
Die Mod kann auch Audiodateien abspielen. Weil die Server-Mod keine Musik zu den Spielern schicken kann, braucht es ein **Server-Resourcepack**:

1. `weihnachtsmod-resourcepack-vorlage.zip` entpacken und die `.ogg`-Aufnahmen nach `assets/weihnachtsmod/sounds/` legen. Namen müssen zu `sounds.json` passen.
2. In `pack.mcmeta` den `pack_format` auf den Wert deiner Minecraft-Version setzen (der Wert in der Vorlage ist nur ein Platzhalter).
3. Pack wieder zippen, irgendwo öffentlich hochladen (z. B. GitHub-Release) und in der `server.properties` eintragen: `resource-pack=<Direktlink>` und `resource-pack-sha1=<SHA-1 der ZIP>`.
4. In `config/weihnachtsmod_lieder.json` ein Lied mit `"ton"` statt `"noten"` eintragen:

```json
"stille-nacht-gesang": { "titel": "Stille Nacht (gesungen)", "ton": "stille_nacht" }
```

Dann: `/weihnachtslied stille-nacht-gesang`. `/weihnachtslied stop` beendet auch Aufnahmen.

**Achtung Urheberrecht:** Die Lieder selbst sind gemeinfrei, **konkrete Aufnahmen** aber nicht automatisch. Nutze nur eigene Aufnahmen oder Aufnahmen mit freier Lizenz (z. B. gemeinfrei/CC0 oder CC BY mit Namensnennung).

## Lizenz
CC0
