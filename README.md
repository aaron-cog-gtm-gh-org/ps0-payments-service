# Acme Bank Payments Service

The Payments Service settles payments by converting amounts through the FX rates API, subtracting a flat 2.50 fee in the settlement currency, and posting the net amount to the ledger.

## Components

- `FxRateClient` retrieves currency conversion rates.
- `LedgerClient` submits settlement postings.
- `SettlementService` applies conversion, rounding, and fee rules.

## Downstream endpoints

- FX rates: `GET /v1/rates/{from}/{to}`
- Ledger postings: `POST /v1/postings`

## Settlement console

Open [http://localhost:8090/](http://localhost:8090/) to submit settlements and review balances. The settlement panel calls `POST /v1/settlements` and proxies indicative rates through `GET /console/api/rates/{from}/{to}`. The account balance panel calls `GET /console/api/accounts/{id}/balance`.

## Configuration

| Property | Description | Default |
| --- | --- | --- |
| `acme.payments.fx-base-url` | Base URL for the FX rates API | `http://localhost:8091` |
| `acme.payments.ledger-base-url` | Base URL for the ledger API | `http://localhost:8092` |
| `acme.payments.timeout` | Connect and socket timeout | `5s` |

Amounts are rounded to two decimal places using HALF_EVEN rounding. The flat settlement fee is 2.50 in the settlement currency.

## Build

Requirements: Java 17 and Maven 3.9.

```sh
ARTIFACTORY_URL=http://localhost:8081/artifactory/maven-virtual mvn -B -s .mvn/settings.xml verify
```

Dependencies resolve through the Acme Artifactory maven-virtual mirror.

## End-to-end verification console

`e2e/stack_console.py` (Python 3 stdlib only) starts local rate-provider (`:9101`) and core-banking (`:9102`) stubs, launches the three service jars wired to them and to each other, and serves a browser console on `http://localhost:8099`:

```sh
python3 e2e/stack_console.py \
  --payments-jar target/payments-service-0.1.0-SNAPSHOT.jar \
  --fx-jar ../ps0-fx-gateway/target/fx-gateway-0.1.0-SNAPSHOT.jar \
  --ledger-jar ../ps0-ledger-service/target/ledger-service-0.1.0-SNAPSHOT.jar
```

Omit a `--*-jar` flag to use a service that is already running on its port. In the console, **Run full verification** checks, with PASS/FAIL for each step:

- that fx-gateway returns the EUR→USD quote;
- that `POST /v1/settlements` returns a ledger posting receipt;
- that the payments-service log shows the rate, fees and net amount;
- that the ledger balance is served live from core banking;
- that the ledger falls back to the cached balance once the core-banking stub is stopped;
- that an uncached account returns an error rather than stale data;
- that the balance is live again after the stub restarts.

The manual panels run single calls. Every call is recorded with its exact request, response and the service log lines it produced. Export the record as Markdown with **Copy as Markdown** or from `/api/transcript.md`. Service logs are also written to `e2e-logs/`.
