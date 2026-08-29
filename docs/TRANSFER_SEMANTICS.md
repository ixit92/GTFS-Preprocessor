# IXIT GTFS Preprocessor v0.9.7 - Transfer Semantics and Footpath Hardening

Stand: 2026-08-23

Status: Real-Feed-Gate mit 3 GB und 2560 MB bestanden; Server-Aktivierung
gesperrt, bis Contract `0.8` vom Consumer ausdruecklich freigegeben ist.

## Ziel

Dieser Schutzschritt trennt drei bisher leicht verwechselbare Aussagen:

1. GTFS beschreibt eine Transferregel.
2. IXIT erkennt eine moegliche raeumliche Beziehung.
3. Ein spaeterer Router darf einen konkreten Fussweg verwenden.

Nur die dritte Aussage setzt `is_traversable = 1`. `parent_station`, gleiche
StopArea oder Luftliniennaehe allein beweisen keinen begehbaren Weg.

## Read-only Baseline-Audit

Die aktive Serverdatenbank wurde ausschliesslich lesend untersucht:

- Data-Version: `20260822T161013Z-de-ch-auto-25551dc39a`
- Preprocessor: `0.9.5`
- Contract: `0.7`
- GTFS-TransferRules: `878926`
- erzeugte Same-StopArea-Regeln: `283017`
- aktive TransferEdges: `1587538`
- davon GTFS-Edges nach bisheriger Area-Paar-Verdichtung: `16870`
- Same-StopArea-Edges mit bisher `0 m / 2 min`: `273552`

Die GTFS-Transferarten im aktiven kombinierten Feed:

| transfer_type | Zeilen | Scope-Befund |
| --- | ---: | --- |
| 1 | 292 | alle route-/trip-spezifisch |
| 2 | 633399 | 524839 route-/trip-spezifisch |
| 4 | 245235 | alle route-/trip-/service-spezifisch |

Insgesamt tragen `770366` von `878926` Rohzeilen Route-, Trip- oder
Service-Scope. Alle Typ-4-Zeilen sind In-Seat-Transfers und keine Fusswege.
Der bisherige Writer verlor diese Scope-Felder; v0.9.7 bewahrt sie vollstaendig.

Die aktiven DE_FULL- und CH-Quell-ZIPs enthalten weder `pathways.txt` noch
`levels.txt`. Same-Station-Wege koennen daher nur als explizit gekennzeichnete
Schaetzungen vorbereitet werden.

## Contract 0.8

`transfers` bewahrt jetzt pro Rohzeile:

- Stop-, Route-, Trip- und Service-Scope
- `transfer_type`
- `min_transfer_time`
- einen stabilen `transfer_id` innerhalb des Artefakts

`transfer_rules` referenziert die Rohzeile ueber `raw_transfer_id` und
klassifiziert `transfer_semantic`, `scope_type` und `pedestrian_usable`.

GTFS-Semantik gemaess der
[GTFS Schedule Reference](https://gtfs.org/documentation/schedule/reference/):

- Typ 0 oder leer: `RECOMMENDED`
- Typ 1: `TIMED`
- Typ 2: `MINIMUM_TIME`
- Typ 3: `PROHIBITED`
- Typ 4: `IN_SEAT_ALLOWED`
- Typ 5: `IN_SEAT_FORBIDDEN`

Typ 3, 4 und 5 werden nie als Fussweg geschrieben. Route-, Trip- oder
Service-spezifische Zeilen bleiben Regeln und werden nie zu einem generischen
Area-Edge verdichtet.

## TransferEdges

`transfer_edges` unterscheidet nun explizit:

- traversierbare, unscoped GTFS-Fusswegkandidaten
- nicht traversierbare `AREA_MEMBERSHIP_CANDIDATE`
- nicht traversierbare `NEARBY_AREA_CANDIDATE`

Jede Zeile traegt unter anderem `is_traversable`, `edge_kind`,
`transfer_semantic`, `scope_type`, `distance_model` und optional
`raw_transfer_id`. Luftlinienwerte sind eine untere Distanzgrenze, keine
vermessene Wegstrecke.

## StopFootpaths

`stop_footpaths` enthaelt gerichtete Paare konkreter Boarding-Stops innerhalb
einer StopArea. Die Erzeugung wird gestreamt, damit grosse Feeds nicht alle
Paare im Heap halten.

Aktuelles Schaetzmodell:

- Distanz: Luftlinie aus Stop-Koordinaten
- Zeit: `detour_1.35_speed_1.2mps_plus_60s_min_120s`
- bis 400 m: konservativer traversierbarer Kandidat
- ueber 400 m oder ohne Koordinaten: `UNKNOWN`, nicht traversierbar
- ab 700 m: zusaetzlich als extreme StopArea auffaellig

Im aktiven Bestand haben `283017` StopAreas mehr als einen Boarding-Stop. Das
ergibt bis zu `1719472` gerichtete konkrete Paare. Die raeumliche Streuung ist
nicht ueberall stationsartig: `3603` Areas liegen ueber 200 m, `548` ueber
400 m, `94` ueber 700 m und `16` ueber 1 km. Deshalb gibt es bewusst keinen
pauschalen 0-m-Fussweg fuer gleiche StopAreas.

## Fail-closed Audit

Die Contract-Validierung bricht ab, wenn:

- Typ 3, 4 oder 5 als GTFS-Fussweg auftaucht
- ein gescopter Transfer zu einem generischen GTFS-Edge wird
- Same-Area- oder Distanzheuristik-Edges traversierbar sind
- ein StopFootpath ueber der Distanzgrenze traversierbar ist
- ein traversierbarer StopFootpath keine plausible Mindestzeit hat
- in FULL/APP_RUNTIME eine Multi-Boarding-StopArea keine Footpath-Zeilen besitzt

Der JSON-Contract-Report enthaelt dazu `transfer_footpath_audit` mit Counts,
Semantikverteilung, auffaelligen Areas, Stichproben und Verletzungen.

## Aktivierungsgrenzen

Contract `0.8` ist absichtlich nicht als `0.7` kompatibel etikettiert. Vor einer
Server-, Android-, Core- oder Routing-Nutzung sind erforderlich:

1. Consumer akzeptiert Contract `0.8` explizit und fail-closed.
2. Consumer beachtet `is_traversable`, Stop-/Trip-/Service-Scope und konkrete
   `stop_id`-Mitglieder.
3. Ein isolierter DE/CH-Real-Feed-Neubau besteht Contract-, Footpath-, Heap- und
   Laufzeit-Audits.
4. Erst danach darf eine neue Data-Version kontrolliert aktiviert werden.

Dieser Sprint aendert keine Android-, Core- oder Routing-Logik.

Der vollstaendige DE/CH-Nachweis steht in
[`REAL_FEED_TRANSFER_FOOTPATH_AUDIT_v0_9_7.md`](REAL_FEED_TRANSFER_FOOTPATH_AUDIT_v0_9_7.md).

## Lokale Verifikation

Am 2026-08-23 wurde `mvn package` mit Java 21 erfolgreich ausgefuehrt:

- `PreprocessorSelfTest passed`
- FULL, CORE_ONLY und APP_RUNTIME durchlaufen
- Contract- und TransferFootpath-Audit PASS
- JAR: `target/gtfs-preprocessor-0.9.8.jar`

Der abschliessende Serverabgleich erfolgte mit SQLite `mode=ro&immutable=1`.
Er bestaetigte Contract `0.7`, Preprocessor `0.9.5`, die oben dokumentierten
RowCounts und die alte vier Spalten umfassende `transfers`-Tabelle. Es wurden
keine Serverdateien geaendert und kein Artefakt aktiviert.
