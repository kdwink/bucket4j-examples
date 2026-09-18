# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Purpose

Exploratory project for [bucket4j](https://bucket4j.com/) — a Java rate-limiting library. Contains examples demonstrating bucket4j features and a sample webapp.

## Build & Test

```bash
mvn test                              # run all tests across all modules
mvn test -pl bucket4j                 # run only bucket4j module tests
mvn test -pl webapp                   # run only webapp module tests
mvn test -pl bucket4j -Dtest=BucketTest#concurrentConsumptionIsLimitedToCapacity  # single test method
```

Requires Java 17. Uses a parent POM (`com.redshiftsoft:redshift-pom:21.19`) which provides dependency management and test dependencies (JUnit 5, etc.).

All buckets live in Valkey, so every `bucket4j-webapp` test that starts a server needs the local cluster.
Those tests skip themselves when nothing is listening on 127.0.0.1:7001, so `mvn test` passes without Docker
— but then only the tests that touch no buckets (`EndpointTest`, `RateLimitPolicyTest`, and the
`bucket4j-examples` module) actually run. To run the whole suite:

```bash
docker/start-valkey-cluster.sh                # see docker/valkey-cluster.md
mvn test
docker/flush-valkey-cluster.sh                # clear buckets between experiments
```

`ValkeyTestCluster` (test sources) is what enforces this: `assumeAvailable()` skips the class when no cluster
is around, and `flush()` empties the cluster so each test class starts from full buckets. Because isolation
comes from flushing rather than per-run key prefixes, test classes must not run in parallel.

## CI

GitLab CI runs `mvn test` on every push using a Java 17 Maven image. Test reports are published as JUnit artifacts.

A `valkey` service container runs a 3-node cluster on 7001-7003 so the whole webapp suite runs in CI too.
`VALKEY_HOST=valkey` points the tests at it; because that variable is set, an unreachable cluster fails them
instead of letting them skip. See the CI section of `docker/valkey-cluster.md` for why CI announces a different
address than the local script does.

## Project Layout

Multi-module Maven project. Root POM (`bucket4j-parent`) is an aggregator.

- **`bucket4j-examples/`** — plain bucket4j examples with no servlet container. `Bucket_Concurrent_Test` verifies thread-safe token consumption; `Bucket_Refill_Test` covers refill behaviour.
- **`bucket4j-webapp/`** — simple servlet-based webapp (no Spring). `HelloServlet` serves an HTML page. `HelloServletTest` starts an embedded Jetty 12 server and verifies the response via `HttpClient`. `ThrottlingFilter` (in `limit/`) is the servlet filter that applies the limits.
- **`docker/`** — scripts for the local 3-node Valkey cluster that backs the buckets.

Buckets come from a `BucketRegistry`, and there is one implementation: `ValkeyBucketRegistry` (Lettuce +
Valkey cluster), so limits are always shared across app servers. `ValkeyBucketRegistry.connectDefault()`
targets `VALKEY_HOST` (default 127.0.0.1) on ports 7001-7003, and is what `ThrottlingFilter` uses when no
registry is passed to its constructor — a filter that builds its own registry also closes it in `destroy()`.
