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
 * Example: ask the agent endpoint a question that benefits from exploration.
 *
 * Where {@code aiQuery} generates a single SQL statement and runs it, the
 * agent loop lets the model peek at the data first — listing tables,
 * sampling rows, computing column stats — before deciding what to do.
 * That works well for questions where you don't know up front which
 * table or which column is interesting.
 *
 * Usage:
 *   mvn compile exec:java \
 *     -Dexec.mainClass="com.datalathe.examples.AiAgentExample" \
 *     -DapiKey=YOUR_API_KEY \
 *     -Dprovider=anthropic \
 *     -Durl=http://localhost:3000
 *
 * Bedrock instead of direct Anthropic:
 *     -Dprovider=bedrock -Dregion=us-west-2 -Dmodel=anthropic.claude-sonnet-4-5-20250929-v1:0
 */
public class AiAgentExample {
    private static final Logger logger = LogManager.getLogger(AiAgentExample.class);

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

        String customersFile = Path.of("./testdata/customers.csv").toAbsolutePath().toString();
        String ordersFile = Path.of("./testdata/orders.csv").toAbsolutePath().toString();
        String productsFile = Path.of("./testdata/products.csv").toAbsolutePath().toString();

        // 1. Load three related CSVs into chips
        logger.info("Loading customers/orders/products...");
        String customersChipId = client.createChipFromFile(customersFile, "customers");
        String ordersChipId = client.createChipFromFile(ordersFile, "orders");
        String productsChipId = client.createChipFromFile(productsFile, "products");

        // 2. Register the AI credential. defaultModel is required, and bedrock
        // additionally needs a region — the request-builder overload covers both.
        AiCredential credential = client.createAiCredential(CreateAiCredentialRequest.builder()
                .name("agent-example-key")
                .provider(provider)
                .apiKey(apiKey)
                .defaultModel(model)
                .region(region)
                .build());
        logger.info("Credential: {} (provider={}, model={})",
                credential.getCredentialId(), provider, model);

        // 3. Create an AI context with column descriptions and join hints.
        // The agent CAN figure out the schema on its own via tools, but
        // descriptions reduce wasted iterations.
        Map<String, Map<String, String>> columnDescriptions = Map.of(
                "customers", Map.of(
                        "customer_id", "Unique customer identifier (e.g. C001)",
                        "name", "Company name",
                        "region", "Sales region: West, East, or Central"),
                "orders", Map.of(
                        "order_id", "Unique order identifier",
                        "customer_id", "Foreign key -> customers.customer_id",
                        "product_id", "Foreign key -> products.product_id",
                        "quantity", "Number of units ordered",
                        "unit_price", "Price per unit at time of order",
                        "status", "Order status: completed, pending, or refunded"),
                "products", Map.of(
                        "product_id", "Unique product identifier (e.g. P10)",
                        "product_name", "Human-readable product name",
                        "cost", "Internal cost per unit"));

        String relationshipPrompt = String.join("\n", List.of(
                "orders.customer_id -> customers.customer_id",
                "orders.product_id  -> products.product_id",
                "Revenue = orders.quantity * orders.unit_price",
                "Profit  = orders.quantity * (orders.unit_price - products.cost)",
                "Only include orders with status = 'completed' for revenue/profit unless asked otherwise."));

        AiContext context = client.createAiContext(
                "Sales Analysis (Agent)",
                null,
                List.of(customersChipId, ordersChipId, productsChipId),
                columnDescriptions,
                relationshipPrompt);
        logger.info("AI context: {}", context.getContextId());

        try {
            // 4. Ask an open-ended question. Agent mode shines here — the
            // model has to figure out which tables matter, peek at the data,
            // and decide how to slice it.
            AgentRequest request = AgentRequest.builder()
                    .contextId(context.getContextId())
                    .credentialId(credential.getCredentialId())
                    .userQuestion(
                            "Which customer has the most concentrated spend on a single product? "
                                    + "Answer with the customer name, the product they spend the most on, "
                                    + "and what fraction of their total spend that product represents.")
                    // 5. Per-request budget caps. These override server defaults.
                    .agentOptions(AgentOptions.builder()
                            .maxIterations(8)
                            .maxToolCalls(15)
                            .maxWallClockSecs(60L)
                            .runSqlRowCap(500)
                            .build())
                    .build();

            System.out.println("\n=== Agent question ===");
            System.out.println(request.getUserQuestion());

            AgentResponse response = client.aiAgent(request);

            // 6. Print the answer first — it's the only thing most users care about.
            System.out.println("\n=== Final answer ===");
            System.out.println(response.getAnswer() != null ? response.getAnswer() : "(no answer)");
            if (response.getStopReason() != null) {
                System.out.println("Stop reason: " + response.getStopReason());
            }

            // 7. The trace tells you WHAT the model did to arrive at the answer.
            // Useful for debugging poor answers and understanding token cost.
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

            // 8. Attachments are tabular data the agent decided to surface.
            // You'd typically render these alongside the answer.
            if (!response.getAttachments().isEmpty()) {
                System.out.println("\n=== Attachments ===");
                for (AgentResponse.Attachment a : response.getAttachments()) {
                    System.out.println("\n[" + a.getCaption() + "]");
                    System.out.println("SQL: " + a.getSql());
                    printTable(a.getData());
                }
            }

            // 9. Token + iteration usage so you know what this cost.
            if (response.getUsage() != null) {
                AgentResponse.AgentUsage u = response.getUsage();
                System.out.println("\n=== Usage ===");
                System.out.printf("Iterations: %d  Tool calls: %d  Tokens: %d in / %d out  Model: %s%n",
                        u.getIterations(), u.getToolCalls(),
                        u.getInputTokens(), u.getOutputTokens(), u.getModel());
            }
        } finally {
            client.deleteAiContext(context.getContextId());
            client.deleteAiCredential(credential.getCredentialId());
            client.deleteChip(customersChipId);
            client.deleteChip(ordersChipId);
            client.deleteChip(productsChipId);
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
