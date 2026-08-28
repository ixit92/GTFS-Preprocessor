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

The contribution license cannot be finalized until the repository owner has
selected the project license. Pull requests should therefore remain disabled
or clearly marked as not accepted until that decision is recorded.

