# Datalathe Examples

Example projects demonstrating how to use the Datalathe client libraries.

## Examples

### Java

Uses the `datalathe-client` Java library to stage data from multiple MySQL tables, execute analysis queries (including complex multi-join UNIONs), and print results.

```bash
cd java
mvn clean install
mvn exec:java -Dexec.mainClass="com.datalathe.examples.DatalatheJob" -Durl=http://localhost:3000
```

#### AI Agent — single analytical question

`AiAgentExample` calls the agent endpoint, which lets the model peek at the data with read-only tools (`list_tables`, `sample_rows`, `column_stats`, etc.) before answering. Reuses the customers/orders/products fixtures.

```bash
cd java
mvn compile exec:java \
  -Dexec.mainClass="com.datalathe.examples.AiAgentExample" \
  -DapiKey=$DATALATHE_AI_KEY \
  -Durl=http://localhost:3000
```

Bedrock instead of direct Anthropic:

```bash
mvn compile exec:java \
  -Dexec.mainClass="com.datalathe.examples.AiAgentExample" \
  -Dprovider=bedrock -Dregion=us-west-2 \
  -Dmodel=anthropic.claude-sonnet-4-5-20250929-v1:0 \
  -DapiKey=$BEDROCK_API_KEY
```

#### AI Agent — open-ended fraud audit

`AiAgentFraudExample` is the heavier sibling: a payments dataset under `testdata/fraud/` with several deliberately-seeded anomaly patterns (velocity attack, geo-mismatch, shared device fingerprint, large outlier, bad merchant) and an open-ended audit question. The agent has to discover the schema, identify the anomalies, and rank suspects on its own — typically 8–12 iterations and 30–50 tool calls.

```bash
cd java
mvn compile exec:java \
  -Dexec.mainClass="com.datalathe.examples.AiAgentFraudExample" \
  -DapiKey=$DATALATHE_AI_KEY
```

Heads-up: this example burns ~80–100k input tokens. On Tier-1 Anthropic accounts Sonnet may hit a per-minute rate limit; pass `-Dmodel=claude-haiku-4-5-20251001` to switch to Haiku (cheaper, higher rate limit, still finds all the patterns).

### JavaScript / TypeScript

Uses the `@datalathe/client` npm package to stage data, run queries, and inspect available databases.

```bash
cd javascript
npm install
npm run build
DATALATHE_URL=http://localhost:3000/lathe npm run run
```

#### AI Agent — single analytical question

```bash
cd javascript
npm install
DATALATHE_AI_KEY=sk-... npx ts-node src/agent-example.ts
```

For bedrock, set `DATALATHE_AI_PROVIDER=bedrock`, `DATALATHE_AI_REGION=us-west-2`, and `DATALATHE_AI_MODEL=anthropic.claude-sonnet-4-5-20250929-v1:0`. The example reuses the CSV fixtures under `java/testdata/`; override with `DATA_DIR` if needed.

#### AI Agent — open-ended fraud audit

```bash
cd javascript
DATALATHE_AI_KEY=sk-... npx ts-node src/agent-fraud-example.ts
```

If Sonnet rate-limits, set `DATALATHE_AI_MODEL=claude-haiku-4-5-20251001`.

## Database Setup

The `env/` directory contains SQL scripts to set up a sample MySQL database used by both examples:

1. `schema.sql` — Creates tables (`object028`, `object028_date000`, `object028_text009`–`text015`)
2. `generate_fake_data.sql` — Populates tables with 100 sample records for company 172
3. `run_query.sql` — Verifies the data with the analysis query

```bash
mysql -u username -p < env/schema.sql
mysql -u username -p < env/generate_fake_data.sql
```

## Prerequisites

- A running Datalathe Engine (default: `http://localhost:3000`)
- A MySQL database populated with the sample schema (see `env/`)
- **Java example**: Java 23+, Maven, `datalathe-client` 1.2.8+ installed locally (`mvn install` from `datalathe-client-java/`)
- **JavaScript example**: Node.js 18+, `datalathe-client-javascript` 1.4.3+ built locally
- **AI Agent examples**: an Anthropic or Bedrock API key in `DATALATHE_AI_KEY`. Bedrock additionally needs a region (`-Dregion=...` for Java, `DATALATHE_AI_REGION=...` for JS).
