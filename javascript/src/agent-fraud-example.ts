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
 * An open-ended fraud audit, then a follow-up that reuses the same
 * session. The agent has a payments dataset (users, transactions,
 * chargebacks) with several different anomaly patterns deliberately
 * seeded into the data. The first question is the kind a human analyst
 * would actually ask: "audit this for suspicious activity" — no SQL, no
 * specific entities, no hint about which patterns to look for.
 *
 * The second turn reuses `session_id` from the first response so the
 * agent sees the prior user/assistant turns as conversation history.
 * That means "the suspects you just flagged" resolves on the server
 * without the client re-stating them — demonstrating multi-turn use.
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

  // The audit turn can spend several minutes in the agent loop (multiple
  // tool rounds + LLM calls). Default 30s timeout would abort it.
  const client = new DatalatheClient(BASE_URL, { timeout: 600_000 });

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
    const auditRequest: AgentRequest = {
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

    const auditResponse = await client.ai.agent(auditRequest);
    printTurn("Audit", auditRequest, auditResponse);

    // Follow-up turn: reuse the session_id the engine returned. The agent
    // sees the prior user/assistant turns as conversation history, so
    // "the suspects you just flagged" resolves without re-stating them.
    if (!auditResponse.sessionId) {
      console.log("\nNo session_id returned — skipping follow-up.");
      return;
    }

    const followUpRequest: AgentRequest = {
      contextId: context.contextId,
      credentialId: credential.credentialId,
      sessionId: auditResponse.sessionId,
      userQuestion:
        "Of the suspects you just flagged, pick the single highest-priority "
        + "one and build a case file: a chronological timeline of every "
        + "transaction (timestamp, amount, status, ip_country, payment_method, "
        + "device_fingerprint) plus any matching chargebacks. Attach the "
        + "timeline as a table. Be explicit about why this user is the top "
        + "priority over the others you flagged.",
      agentOptions: {
        maxIterations: 10,
        maxToolCalls: 20,
        maxWallClockSecs: 180,
        runSqlRowCap: 1000,
      },
    };

    const followUpResponse = await client.ai.agent(followUpRequest);
    printTurn("Follow-up", followUpRequest, followUpResponse);
  } finally {
    await client.ai.deleteContext(context.contextId);
    await client.ai.deleteCredential(credential.credentialId);
    await client.chips.delete(usersChipId);
    await client.chips.delete(txnsChipId);
    await client.chips.delete(cbChipId);
    console.log("\nCleaned up.");
  }
}

function printTurn(label: string, request: AgentRequest, response: AgentResponse) {
  console.log(`\n========== ${label} ==========`);
  console.log(`\n=== Question ===`);
  console.log(request.userQuestion);
  if (request.sessionId) console.log(`(session_id=${request.sessionId})`);

  console.log("\n=== Final answer ===");
  console.log(response.answer ?? "(no answer)");
  if (response.stopReason) console.log(`Stop reason: ${response.stopReason}`);
  if (response.sessionId) console.log(`Session: ${response.sessionId}`);
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
