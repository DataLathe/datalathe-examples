import { DatalatheClient, SourceRequest, SourceType } from "@datalathe/client";

const BASE_URL = process.env.DATALATHE_URL ?? "http://localhost:3000/lathe";

async function main() {
  const client = new DatalatheClient(BASE_URL);

  // Stage data — create chips from multiple tables for company 172
  console.log("Staging data...");

  const sources: SourceRequest[] = [
    { databaseName: "local", tableName: "object028", query: "SELECT * FROM object028 WHERE companyid = 172" },
    { databaseName: "local", tableName: "object028_date000", query: "SELECT * FROM object028_date000 WHERE companyid = 172" },
    { databaseName: "local", tableName: "object028_text009", query: "SELECT * FROM object028_text009 WHERE companyid = 172" },
    { databaseName: "local", tableName: "object028_text010", query: "SELECT * FROM object028_text010 WHERE companyid = 172" },
    { databaseName: "local", tableName: "object028_text011", query: "SELECT * FROM object028_text011 WHERE companyid = 172" },
    { databaseName: "local", tableName: "object028_text013", query: "SELECT * FROM object028_text013 WHERE companyid = 172" },
    { databaseName: "local", tableName: "object028_text014", query: "SELECT * FROM object028_text014 WHERE companyid = 172" },
    { databaseName: "local", tableName: "object028_text015", query: "SELECT * FROM object028_text015 WHERE companyid = 172" },
  ];

  const chipIds = await client.createChips(sources);
  console.log(`Staged ${chipIds.length} chips: ${chipIds.join(", ")}`);

  // Run analysis queries
  console.log("\nExecuting queries...");

  const queries = [
    "SELECT * FROM object028",
    "SELECT COUNT(*) FROM object028",
    "SELECT COUNT(*) FROM object028_text009",
    "SELECT COUNT(*) FROM object028_date000",
  ];

  const report = await client.generateReport(chipIds, queries);

  // Print results
  for (const [idx, entry] of report.results) {
    console.log(`\n--- Query ${idx} ---`);
    console.log(`SQL: ${queries[idx]}`);

    if (entry.error) {
      console.log(`Error: ${entry.error}`);
      continue;
    }

    const data = entry.result ?? entry.data;
    if (entry.schema) {
      console.log(`Columns: ${entry.schema.map((s) => s.name).join(", ")}`);
    }
    if (data) {
      console.log(`Rows: ${data.length}`);
      // Print first 5 rows as a preview
      for (const row of data.slice(0, 5)) {
        console.log(`  ${row.join(" | ")}`);
      }
      if (data.length > 5) {
        console.log(`  ... and ${data.length - 5} more rows`);
      }
    }
  }

  // Demonstrate listing available databases
  console.log("\n--- Available Databases ---");
  const databases = await client.getDatabases();
  for (const db of databases) {
    if (!db.internal) {
      console.log(`  ${db.databaseName} (${db.type})`);
    }
  }
}

main().catch((err) => {
  console.error("Error:", err);
  process.exit(1);
});
