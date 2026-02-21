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

### JavaScript / TypeScript

Uses the `@datalathe/client` npm package to stage data, run queries, and inspect available databases.

```bash
cd javascript
npm install
npm run build
DATALATHE_URL=http://localhost:3000/lathe npm run run
```

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
- **Java example**: Java 23+, Maven, `datalathe-client` installed locally (`mvn install` from `datalathe-client-java/`)
- **JavaScript example**: Node.js 18+, `datalathe-client-javascript` built locally
