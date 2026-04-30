package com.datalathe.examples;

import com.datalathe.client.DatalatheClient;
import com.datalathe.client.types.AgentOptions;
import com.datalathe.client.types.AgentRequest;
import com.datalathe.client.types.AgentResponse;
import com.datalathe.client.types.AiContext;
import com.datalathe.client.types.AiCredential;
import com.datalathe.client.types.AiQueryResponse;
import com.datalathe.client.types.CreateAiCredentialRequest;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.core.config.Configurator;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Example: an open-ended fraud audit. The agent has a payments dataset
 * (users, transactions, chargebacks) with several different anomaly
 * patterns deliberately seeded into the data. The user question is the
 * kind a human analyst would actually ask: "audit this for suspicious
 * activity" — no SQL, no specific entities, no hint about which patterns
 * to look for.
 *
 * Compared to {@link AiAgentExample} (which has a single concrete
 * analytical question), this one rewards multi-round exploration:
 *   - distinct_values to learn the status / reason_code / category enums
 *   - column_stats to find amount outliers
 *   - per-user, per-merchant, and per-device aggregates via run_sql
 *   - cross-table joins to match transactions against chargebacks
 *
 * Most of the columns are intentionally NOT documented in the AI
 * context — the agent has to discover what the data means before it can
 * decide what's anomalous.
 *
 * Usage:
 *   mvn compile exec:java \
 *     -Dexec.mainClass="com.datalathe.examples.AiAgentFraudExample" \
 *     -DapiKey=$DATALATHE_AI_KEY \
 *     -Durl=http://localhost:3000
 */
public class AiAgentFraudExample {
    private static final Logger logger = LogManager.getLogger(AiAgentFraudExample.class);

    public static void main(String[] args) throws IOException {
        Configurator.setRootLevel(Level.valueOf(System.getProperty("log.level", "INFO")));

        String url = System.getProperty("url", "http://localhost:3000");
        String apiKey = System.getProperty("apiKey");
        String provider = System.getProperty("provider", "anthropic");
        String region = System.getProperty("region");
        String model = System.getProperty("model",
                provider.equals("bedrock")
                        ? "anthropic.claude-sonnet-4-5-20250929-v1:0"
                        : "claude-sonnet-4-5-20250929");
        if (apiKey == null || apiKey.isEmpty()) {
            System.err.println("ERROR: -DapiKey is required.");
            System.exit(1);
        }

        DatalatheClient client = new DatalatheClient(url);

        String dataDir = "./testdata/fraud";
        String usersFile = Path.of(dataDir, "users.csv").toAbsolutePath().toString();
        String txnsFile = Path.of(dataDir, "transactions.csv").toAbsolutePath().toString();
        String cbFile = Path.of(dataDir, "chargebacks.csv").toAbsolutePath().toString();

        logger.info("Loading users/transactions/chargebacks...");
        String usersChipId = client.createChipFromFile(usersFile, "users");
        String txnsChipId = client.createChipFromFile(txnsFile, "transactions");
        String cbChipId = client.createChipFromFile(cbFile, "chargebacks");

        AiCredential credential = client.createAiCredential(CreateAiCredentialRequest.builder()
                .name("agent-fraud-example-key")
                .provider(provider)
                .apiKey(apiKey)
                .defaultModel(model)
                .region(region)
                .build());
        logger.info("Credential: {} (provider={}, model={})",
                credential.getCredentialId(), provider, model);

        // Deliberately sparse column descriptions. The agent has to use
        // distinct_values / sample_rows / column_stats to learn what the
        // undocumented columns (status, reason_code, merchant_category,
        // payment_method, device_fingerprint) actually contain.
        Map<String, Map<String, String>> columnDescriptions = Map.of(
                "users", Map.of(
                        "user_id", "Unique user identifier",
                        "signup_country", "Country where the user registered (ISO 2-letter)",
                        "email_domain", "Email provider domain"),
                "transactions", Map.of(
                        "user_id", "Foreign key -> users.user_id",
                        "amount", "Transaction amount in USD",
                        "ip_country", "Country the request came from at txn time"),
                "chargebacks", Map.of(
                        "txn_id", "Foreign key -> transactions.txn_id",
                        "amount", "Disputed amount in USD"));

        String relationshipPrompt = String.join("\n", List.of(
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
                        + "one operator running multiple accounts."));

        AiContext context = client.createAiContext(
                "Payments Fraud Audit",
                null,
                List.of(usersChipId, txnsChipId, cbChipId),
                columnDescriptions,
                relationshipPrompt);
        logger.info("AI context: {}", context.getContextId());

        try {
            AgentRequest request = AgentRequest.builder()
                    .contextId(context.getContextId())
                    .credentialId(credential.getCredentialId())
                    .userQuestion(
                            "This is recent payment data from our platform. Audit it for "
                                    + "suspicious activity. For each user you suspect of fraud, "
                                    + "list them with the specific patterns that make them "
                                    + "suspicious — be concrete (timestamps, amounts, countries, "
                                    + "device overlaps, etc.). If you spot a non-user pattern "
                                    + "worth investigating (e.g. a merchant or payment method), "
                                    + "flag that too. Attach summary tables for the suspect "
                                    + "users and any flagged merchants so an investigator can "
                                    + "drill in.")
                    .agentOptions(AgentOptions.builder()
                            .maxIterations(20)
                            .maxToolCalls(50)
                            .maxWallClockSecs(300L)
                            .runSqlRowCap(1000)
                            .build())
                    .build();

            System.out.println("\n=== Audit question ===");
            System.out.println(request.getUserQuestion());

            AgentResponse response = client.aiAgent(request);

            System.out.println("\n=== Final answer ===");
            System.out.println(response.getAnswer() != null ? response.getAnswer() : "(no answer)");
            if (response.getStopReason() != null) {
                System.out.println("Stop reason: " + response.getStopReason());
            }
            if (response.getError() != null) {
                System.out.println("Error: " + response.getError()
                        + (response.getErrorCode() != null ? " [" + response.getErrorCode() + "]" : "")
                        + " (request_id=" + response.getRequestId() + ")");
            }

            System.out.println("\n=== Reasoning trace ===");
            int iterations = response.getUsage() != null ? response.getUsage().getIterations() : 0;
            for (int i = 1; i <= iterations; i++) {
                final int iter = i;
                response.getNarration().stream()
                        .filter(n -> n.getIteration() == iter)
                        .forEach(n -> System.out.println("[iter " + iter + "] " + n.getText()));
                response.getToolCalls().stream()
                        .filter(t -> t.getIteration() == iter)
                        .forEach(t -> System.out.println("[iter " + iter + "] tool: " + t.getTool()
                                + " (" + t.getDurationMs() + "ms"
                                + (t.isError() ? ", ERROR" : "") + ") -> " + t.getResultSummary()));
            }

            if (!response.getAttachments().isEmpty()) {
                System.out.println("\n=== Attachments ===");
                for (AgentResponse.Attachment a : response.getAttachments()) {
                    System.out.println("\n[" + a.getCaption() + "]");
                    printTable(a.getData());
                }
            }

            if (response.getUsage() != null) {
                AgentResponse.AgentUsage u = response.getUsage();
                System.out.println("\n=== Usage ===");
                System.out.printf(
                        "Iterations: %d  Tool calls: %d  Tokens: %d in / %d out  Model: %s%n",
                        u.getIterations(), u.getToolCalls(),
                        u.getInputTokens(), u.getOutputTokens(), u.getModel());
            }
        } finally {
            client.deleteAiContext(context.getContextId());
            client.deleteAiCredential(credential.getCredentialId());
            client.deleteChip(usersChipId);
            client.deleteChip(txnsChipId);
            client.deleteChip(cbChipId);
            logger.info("Cleaned up.");
        }
    }

    private static void printTable(AiQueryResponse.QueryResultData data) {
        if (data == null) return;
        List<AiQueryResponse.ColumnInfo> columns = data.getColumns();
        StringBuilder header = new StringBuilder();
        for (int i = 0; i < columns.size(); i++) {
            if (i > 0) header.append(" | ");
            header.append(columns.get(i).getName());
        }
        System.out.println(header);
        for (List<String> row : data.getRows()) {
            System.out.println(String.join(" | ", row));
        }
        System.out.println("(" + data.getRows().size() + " rows)");
    }
}
