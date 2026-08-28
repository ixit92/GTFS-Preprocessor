# Contributing

Contributions should preserve the producer/consumer contract and the original
GTFS identity model.

Before opening a change:

```bash
mvn clean package
```

Please include a network-free self-test for behavioral changes. In particular,
changes involving time parsing must cover values above `24:00:00`; changes to
station grouping must preserve concrete `stop_id` references; transfer changes
must distinguish raw rules, candidate edges, and traversable footpaths.

Do not commit real GTFS feeds, generated databases, production reports,
credentials, private keys, or infrastructure-specific configuration.

Unless explicitly stated otherwise, intentionally submitted code contributions
are accepted under Apache-2.0. Documentation and route-color data contributions
are accepted under CC BY 4.0. Contributors must have the right to submit their
work under the applicable license and must preserve third-party attribution.
