# Acme Bank Payments Service

The Payments Service settles payments by converting amounts through the FX rates API, subtracting a flat 2.50 fee in the settlement currency, and posting the net amount to the ledger.

## Components

- `FxRateClient` retrieves currency conversion rates.
- `LedgerClient` submits settlement postings.
- `SettlementService` applies conversion, rounding, and fee rules.

## Downstream endpoints

- FX rates: `GET /v1/rates/{from}/{to}`
- Ledger postings: `POST /v1/postings`

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
