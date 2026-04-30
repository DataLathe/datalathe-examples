import {
  DatalatheClient,
  type AgentRequest,
  type AgentResponse,
  type AiQueryResultData,
} from "@datalathe/client";
import path from "node:path";

const BASE_URL = process.env.DATALATHE_URL ?? "http://localhost:3000/lathe";
const API_KEY = process.env.DATALATHE_AI_KEY;
const PROVIDER = process.env.DATALATHE_AI_PROVIDER ?? "anthropic";
const REGION = process.env.DATALATHE_AI_REGION;
const MODEL = process.env.DATALATHE_AI_MODEL
  ?? (PROVIDER === "bedrock"
    ? "anthropic.claude-sonnet-4-5-20250929-v1:0"
    : "claude-sonnet-4-5-20250929");

// CSVs ship with the Java example; reuse them so we don't duplicate fixtures.
const DATA_DIR = process.env.DATA_DIR
  ?? path.resolve(import.meta.dirname, "../../java/testdata");

/**
 * Where ai.query() generates a single SQL statement, ai.agent() lets the
 * model peek at the data first (list_tables, sample_rows, column_stats…)
 * before deciding what to do. That works well for open-ended questions
 * where you don't know up front which table or column matters.
 *
 * Usage:
 *   DATALATHE_AI_KEY=sk-... npx ts-node src/agent-example.ts
 *
 * Bedrock instead of direct Anthropic:
 *   DATALATHE_AI_PROVIDER=bedrock DATALATHE_AI_REGION=us-west-2 \
 *     DATALATHE_AI_KEY=... npx ts-node src/agent-example.ts
 */
async function main() {
  if (!API_KEY) {
    console.error("ERROR: DATALATHE_AI_KEY is required.");
    process.exit(1);
  }

  const client = new DatalatheClient(BASE_URL);

  // 1. Load three related CSVs
  console.log("Loading customers/orders/products...");
  const customersChipId = await client.chips.createFromFile(
    path.join(DATA_DIR, "customers.csv"), "customers");
  const ordersChipId = await client.chips.createFromFile(
    path.join(DATA_DIR, "orders.csv"), "orders");
  const productsChipId = await client.chips.createFromFile(
    path.join(DATA_DIR, "products.csv"), "products");

  // 2. Register an AI credential. defaultModel is required, and bedrock
  // additionally needs a region.
  const credential = await client.ai.registerCredential({
    name: "agent-example-key",
    provider: PROVIDER,
    apiKey: API_KEY,
    defaultModel: MODEL,
    region: REGION,
  });
  console.log(`Credential: ${credential.credentialId} (provider=${PROVIDER}, model=${MODEL})`);

  // 3. AI context with column descriptions and join hints. The agent CAN
  // figure out the schema on its own via tools, but descriptions reduce
  // wasted iterations.
  const context = await client.ai.registerContext({
    name: "Sales Analysis (Agent)",
    chipIds: [customersChipId, ordersChipId, productsChipId],
    columnDescriptions: {
      customers: {
        customer_id: "Unique customer identifier (e.g. C001)",
        name: "Company name",
        region: "Sales region: West, East, or Central",
      },
      orders: {
        order_id: "Unique order identifier",
        customer_id: "Foreign key -> customers.customer_id",
        product_id: "Foreign key -> products.product_id",
        quantity: "Number of units ordered",
        unit_price: "Price per unit at time of order",
        status: "Order status: completed, pending, or refunded",
      },
      products: {
        product_id: "Unique product identifier (e.g. P10)",
        product_name: "Human-readable product name",
        cost: "Internal cost per unit",
      },
    },
    dataRelationshipPrompt: [
      "orders.customer_id -> customers.customer_id",
      "orders.product_id  -> products.product_id",
      "Revenue = orders.quantity * orders.unit_price",
      "Profit  = orders.quantity * (orders.unit_price - products.cost)",
      "Only include orders with status = 'completed' for revenue/profit unless asked otherwise.",
    ].join("\n"),
  });
  console.log(`AI context: ${context.contextId}`);

  try {
    // 4. Ask an open-ended question. Agent mode shines here — the model
    // has to figure out which tables matter, peek at the data, and decide
    // how to slice it.
    const request: AgentRequest = {
      contextId: context.contextId,
      credentialId: credential.credentialId,
      userQuestion:
        "Which customer has the most concentrated spend on a single product? "
        + "Answer with the customer name, the product they spend the most on, "
        + "and what fraction of their total spend that product represents.",
      // 5. Per-request budget caps override server defaults.
      agentOptions: {
        maxIterations: 8,
        maxToolCalls: 15,
        maxWallClockSecs: 60,
        runSqlRowCap: 500,
      },
    };

    console.log("\n=== Agent question ===");
    console.log(request.userQuestion);

    const response = await client.ai.agent(request);

    // 6. Answer first — it's the only thing most users care about.
    console.log("\n=== Final answer ===");
    console.log(response.answer ?? "(no answer)");
    if (response.stopReason) console.log(`Stop reason: ${response.stopReason}`);

    // 7. The trace tells you WHAT the model did. Useful for debugging
    // poor answers and understanding token cost.
    printTrace(response);

    // 8. Attachments are tabular data the agent decided to surface.
    if (response.attachments.length > 0) {
      console.log("\n=== Attachments ===");
      for (const a of response.attachments) {
        console.log(`\n[${a.caption}]`);
        console.log(`SQL: ${a.sql}`);
        printTable(a.data);
      }
    }

    // 9. Token + iteration usage so you know what this cost.
    if (response.usage) {
      const u = response.usage;
      console.log("\n=== Usage ===");
      console.log(
        `Iterations: ${u.iterations}  Tool calls: ${u.toolCalls}  `
        + `Tokens: ${u.inputTokens} in / ${u.outputTokens} out  Model: ${u.model}`,
      );
    }
  } finally {
    await client.ai.deleteContext(context.contextId);
    await client.ai.deleteCredential(credential.credentialId);
    await client.chips.delete(customersChipId);
    await client.chips.delete(ordersChipId);
    await client.chips.delete(productsChipId);
    console.log("\nCleaned up.");
  }
}

function printTrace(response: AgentResponse) {
  const iterations = response.usage?.iterations ?? 0;
  if (iterations === 0) return;
  console.log("\n=== Reasoning trace ===");
  for (let i = 1; i <= iterations; i++) {
    for (const n of response.narration) {
      if (n.iteration === i) console.log(`[iter ${i}] ${n.text}`);
    }
    for (const t of response.toolCalls) {
      if (t.iteration !== i) continue;
      const flag = t.isError ? ", ERROR" : "";
      console.log(`[iter ${i}] tool: ${t.tool} (${t.durationMs}ms${flag}) -> ${t.resultSummary}`);
    }
  }
}

function printTable(data: AiQueryResultData) {
  console.log(data.columns.map((c) => c.name).join(" | "));
  for (const row of data.rows) {
    console.log(row.map((v) => v ?? "").join(" | "));
  }
  console.log(`(${data.rows.length} rows)`);
}

main().catch((err) => {
  console.error("Error:", err);
  process.exit(1);
});
