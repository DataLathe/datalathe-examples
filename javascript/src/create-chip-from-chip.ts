import { DatalatheClient } from "@datalathe/client";

const BASE_URL = process.env.DATALATHE_URL ?? "http://localhost:3000/lathe";

/**
 * Example: create a new chip from existing chip(s).
 *
 * This demonstrates the CACHE source type which lets you create derived
 * chips by running a SQL transformation against one or more source chips.
 *
 * Usage:
 *   npx ts-node src/create-chip-from-chip.ts
 */
async function main() {
  const client = new DatalatheClient(BASE_URL);

  // 1. Create a source chip from a file
  console.log("Creating source chip from file...");
  const sourceChipId = await client.createChipFromFile(
    process.env.FILE_PATH ?? "./testdata/employees.csv",
    "employees",
    undefined,
    "employees_source",
  );
  console.log(`Source chip created: ${sourceChipId}`);

  // 2. Create a filtered chip — only Engineering department, subset of columns
  console.log("\nCreating filtered chip from source chip...");
  const filteredChipId = await client.createChipFromChip(
    [sourceChipId],
    "SELECT name, salary FROM employees WHERE department = 'Engineering'",
    "engineering_salaries",
    "engineering_only",
  );
  console.log(`Filtered chip created: ${filteredChipId}`);

  // 3. Query the new chip to verify
  console.log("\nQuerying filtered chip...");
  const { results } = await client.generateReport(
    [filteredChipId],
    ["SELECT * FROM engineering_salaries ORDER BY salary DESC"],
  );

  const entry = results.get(0);
  if (entry?.error) {
    console.error(`Error: ${entry.error}`);
  } else if (entry?.result) {
    const schema = entry.schema ?? [];
    console.log(`Columns: ${schema.map((s) => s.name).join(", ")}`);
    console.log(`Rows: ${entry.result.length}`);
    for (const row of entry.result) {
      console.log(`  ${row.join(" | ")}`);
    }
  }

  // 4. Create a chip from source with no query (copies all data)
  console.log("\nCreating full copy chip (no query)...");
  const copyChipId = await client.createChipFromChip(
    [sourceChipId],
    undefined,
    "employees_copy",
    "full_copy",
  );
  console.log(`Copy chip created: ${copyChipId}`);

  // 5. Clean up
  console.log("\nCleaning up...");
  await client.deleteChip(copyChipId);
  await client.deleteChip(filteredChipId);
  await client.deleteChip(sourceChipId);
  console.log("Done.");
}

main().catch((err) => {
  console.error("Error:", err);
  process.exit(1);
});
