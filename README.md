# WorldTrim

Paper-Plugin (1.21.x, Java 21), das ungenutzte Chunks löscht, um Speicher zu sparen.
Ein Chunk wird nur gelöscht, wenn er kaum besucht wurde (`InhabitedTime`), ausschließlich
natürliche Blöcke enthält (keine Bauten, keine Rüstungsständer/Itemrahmen/benannten Tiere)
und nicht im Schutzbereich liegt. Gelöschte Chunks werden beim nächsten Besuch neu generiert.

## Bauen
```
mvn clean package
```
Die JAR liegt danach in `target/WorldTrim-1.0.0.jar`.

## Benutzung
1. JAR in `plugins/` legen, Server starten, `plugins/WorldTrim/config.yml` anpassen
   (vor allem `protect` um den Spawn/die Bauten herum).
2. `/worldtrim analyse` – zeigt, wie viel Platz frei würde. Ändert nichts.
3. **Backup machen.**
4. `/worldtrim schedule` – beim nächsten Neustart wird bereinigt, bevor die Welten laden.
5. Server neu starten. Bericht: `plugins/WorldTrim/last-report.txt`.

`/worldtrim cancel` bricht eine geplante Bereinigung ab.
