# Third-Party Notices

## Bundled Data

`src/main/resources/vbb-route-colors-20260428.csv` is a reduced representation
of the Verkehrsverbund Berlin-Brandenburg open-data dataset `Linienfarben`,
dated 2026-04-28 and attributed to Verkehrsverbund Berlin-Brandenburg. The
source project identifies that dataset as licensed under CC BY 4.0.

`src/main/resources/rnv-route-colors-20260718.csv` is a reduced route-color
catalog dated 2026-07-18 and attributed to Rhein-Neckar-Verkehr GmbH. The
repository owner has confirmed that the catalog is available under CC BY 4.0.
Preserve this attribution and indicate modifications when redistributing it.

## Maven Dependencies

The project and shaded JAR use the following runtime dependencies. Their
licenses are not replaced by the project license.

| Dependency | Version | License |
| --- | --- | --- |
| Xerial SQLite JDBC | 3.46.1.0 | Apache-2.0 |
| SLF4J API / NOP | 1.7.36 | MIT |
| Jackson Databind / Core / Annotations | 2.18.2 | Apache-2.0 |
| LocationTech JTS Core / I/O Common | 1.20.0 | EPL-2.0 or EDL-1.0 |
| json-simple | 1.1.1 | Apache-2.0 |

Canonical license references:

- Apache-2.0: https://www.apache.org/licenses/LICENSE-2.0
- MIT: https://opensource.org/license/mit
- EPL-2.0: https://www.eclipse.org/legal/epl-2.0/
- EDL-1.0: https://www.eclipse.org/org/documents/edl-v10.php

Redistributors of the shaded JAR must preserve all applicable copyright,
license, and notice files from these dependencies.
