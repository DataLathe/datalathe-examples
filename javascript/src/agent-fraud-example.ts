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

const DATA_DIR = process.env.DATA_DIR
  ?? path.resolve(import.meta.dirname, "../../java/testdata/fraud");

/**
 * An open-ended fraud audit. The agent has a payments dataset (users,
 * transactions, chargebacks) with several different anomaly patterns
 * deliberately seeded into the data. The user question is the kind a
 * human analyst would actually ask: "audit this for suspicious activity"
 * — no SQL, no specific entities, no hint about which patterns to look for.
 *
 * Compared to {@link agent-example.ts} (a single concrete analytical
 * question), this rewards multi-round exploration:
 *   - distinct_values to learn the status / reason_code / category enums
 *   - column_stats to find amount outliers
 *   - per-user, per-merchant, and per-device aggregates via run_sql
 *   - cross-table joins to match transactions against chargebacks
 *
 * Heads-up: the open-ended question + larger dataset can hit Tier-1
 * Anthropic rate limits on Sonnet. If you see a 429, either wait or set
 * DATALATHE_AI_MODEL=claude-haiku-4-5-20251001.
 *
 * Usage:
 *   DATALATHE_AI_KEY=sk-... npx ts-node src/agent-fraud-example.ts
 */
async function main() {
  if (!API_KEY) {
    console.error("ERROR: DATALATHE_AI_KEY is required.");
    process.exit(1);
  }

  const client = new DatalatheClient(BASE_URL);

  console.log("Loading users/transactions/chargebacks...");
  const usersChipId = await client.chips.createFromFile(
    path.join(DATA_DIR, "users.csv"), "users");
  const txnsChipId = await client.chips.createFromFile(
    path.join(DATA_DIR, "transactions.csv"), "transactions");
  const cbChipId = await client.chips.createFromFile(
    path.join(DATA_DIR, "chargebacks.csv"), "chargebacks");

  const credential = await client.ai.registerCredential({
    name: "agent-fraud-example-key",
    provider: PROVIDER,
    apiKey: API_KEY,
    defaultModel: MODEL,
    region: REGION,
  });
  console.log(`Credential: ${credential.credentialId} (provider=${PROVIDER}, model=${MODEL})`);

  // Deliberately sparse column descriptions. The agent has to use
  // distinct_values / sample_rows / column_stats to learn what the
  // undocumented columns (status, reason_code, merchant_category,
  // payment_method, device_fingerprint) actually contain.
  const context = await client.ai.registerContext({
    name: "Payments Fraud Audit",
    chipIds: [usersChipId, txnsChipId, cbChipId],
    columnDescriptions: {
      users: {
        user_id: "Unique user identifier",
        signup_country: "Country where the user registered (ISO 2-letter)",
        email_domain: "Email provider domain",
      },
      transactions: {
        user_id: "Foreign key -> users.user_id",
        amount: "Transaction amount in USD",
        ip_country: "Country the request came from at txn time",
      },
      chargebacks: {
        txn_id: "Foreign key -> transactions.txn_id",
        amount: "Disputed amount in USD",
      },
    },
    dataRelationshipPrompt: [
      "transactions.user_id -> users.user_id",
      "chargebacks.txn_id   -> transactions.txn_id",
      "",
      "A 'chargeback' means the user (or their bank) reversed the transaction "
        + "after the fact — a strong fraud signal but not a guaranteed one.",
      "",
      "The columns `status`, `payment_method`, `merchant_category`, and `reason_code` "
        + "are enums — use distinct_values to see what values they can take.",
      "`device_fingerprint` is a hash that identifies the physical device used; "
        + "two distinct user_ids sharing the same fingerprint usually means "
        + "one operator running multiple accounts.",
    ].join("\n"),
  });
  console.log(`AI context: ${context.contextId}`);

  try {
    const request: AgentRequest = {
      contextId: context.contextId,
      credentialId: credential.credentialId,
      userQuestion:
        "This is recent payment data from our platform. Audit it for "
        + "suspicious activity. For each user you suspect of fraud, list "
        + "them with the specific patterns that make them suspicious — be "
        + "concrete (timestamps, amounts, countries, device overlaps, etc.). "
        + "If you spot a non-user pattern worth investigating (e.g. a "
        + "merchant or payment method), flag that too. Attach summary tables "
        + "for the suspect users and any flagged merchants so an investigator "
        + "can drill in.",
      agentOptions: {
        maxIterations: 20,
        maxToolCalls: 50,
        maxWallClockSecs: 300,
        runSqlRowCap: 1000,
      },
    };

    console.log("\n=== Audit question ===");
    console.log(request.userQuestion);

    const response = await client.ai.agent(request);

    console.log("\n=== Final answer ===");
    console.log(response.answer ?? "(no answer)");
    if (response.stopReason) console.log(`Stop reason: ${response.stopReason}`);
    if (response.error) {
      const code = response.errorCode ? ` [${response.errorCode}]` : "";
      console.log(`Error: ${response.error}${code} (request_id=${response.requestId})`);
    }

    printTrace(response);

    if (response.attachments.length > 0) {
      console.log("\n=== Attachments ===");
      for (const a of response.attachments) {
        console.log(`\n[${a.caption}]`);
        printTable(a.data);
      }
    }

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
    await client.chips.delete(usersChipId);
    await client.chips.delete(txnsChipId);
    await client.chips.delete(cbChipId);
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
