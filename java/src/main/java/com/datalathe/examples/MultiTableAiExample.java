package com.datalathe.examples;

import com.datalathe.client.AiConversation;
import com.datalathe.client.DatalatheClient;
import com.datalathe.client.types.AiContext;
import com.datalathe.client.types.AiCredential;
import com.datalathe.client.types.AiQueryResponse;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.core.config.Configurator;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Example: Multi-table AI query with join hints.
 *
 * Loads three related tables (customers, orders, products), creates an AI
 * context with column descriptions AND a data-relationship prompt that tells
 * the LLM how the tables join together. This lets the model generate correct
 * multi-table JOINs from plain English questions.
 *
 * Usage:
 *   mvn compile exec:java \
 *     -Dexec.mainClass="com.datalathe.examples.MultiTableAiExample" \
 *     -DapiKey=YOUR_API_KEY \
 *     -Dprovider=anthropic \
 *     -Durl=http://localhost:3000
 */
public class MultiTableAiExample {
    private static final Logger logger = LogManager.getLogger(MultiTableAiExample.class);

    public static void main(String[] args) throws IOException {
        Configurator.setRootLevel(Level.valueOf(System.getProperty("log.level", "INFO")));

        String url = System.getProperty("url", "http://localhost:3000");
        String apiKey = System.getProperty("apiKey");
        String provider = System.getProperty("provider", "anthropic");
        if (apiKey == null || apiKey.isEmpty()) {
            System.err.println("ERROR: -DapiKey is required.");
            System.exit(1);
        }

        String customersFile = Path.of(System.getProperty("filePath", "./testdata/customers.csv"))
                .toAbsolutePath().toString();
        String ordersFile = Path.of("./testdata/orders.csv").toAbsolutePath().toString();
        String productsFile = Path.of("./testdata/products.csv").toAbsolutePath().toString();

        DatalatheClient client = new DatalatheClient(url);

        // ── 1. Load three CSV files into separate chips ──
        logger.info("Loading customers...");
        String customersChipId = client.createChipFromFile(customersFile, "customers");
        logger.info("Chip: {}", customersChipId);

        logger.info("Loading orders...");
        String ordersChipId = client.createChipFromFile(ordersFile, "orders");
        logger.info("Chip: {}", ordersChipId);

        logger.info("Loading products...");
        String productsChipId = client.createChipFromFile(productsFile, "products");
        logger.info("Chip: {}", productsChipId);

        // ── 2. Register an AI credential ──
        AiCredential credential = client.createAiCredential(
                "multi-table-key", provider, apiKey, null);
        logger.info("Credential: {}", credential.getCredentialId());

        // ── 3. Create an AI context ──
        //
        // columnDescriptions tells the LLM what each column means.
        // dataRelationshipPrompt tells it HOW the tables relate — this is the
        // key to getting correct JOINs from natural language.
        //
        Map<String, Map<String, String>> columnDescriptions = Map.of(
                "customers", Map.of(
                        "customer_id", "Unique customer identifier (e.g. C001)",
                        "name", "Company name",
                        "email", "Primary contact email",
                        "region", "Sales region: West, East, or Central",
                        "signup_date", "Date the customer signed up"),
                "orders", Map.of(
                        "order_id", "Unique order identifier",
                        "customer_id", "Foreign key → customers.customer_id",
                        "product_id", "Foreign key → products.product_id",
                        "quantity", "Number of units ordered",
                        "unit_price", "Price per unit at time of order",
                        "order_date", "Date the order was placed",
                        "status", "Order status: completed, pending, or refunded"),
                "products", Map.of(
                        "product_id", "Unique product identifier (e.g. P10)",
                        "product_name", "Human-readable product name",
                        "category", "Product category: Hardware or Software",
                        "cost", "Internal cost per unit",
                        "list_price", "Published list price per unit"));

        String relationshipPrompt = String.join("\n", List.of(
                "These three tables form a simple sales data model:",
                "- orders.customer_id → customers.customer_id (each order belongs to one customer)",
                "- orders.product_id  → products.product_id  (each order line references one product)",
                "- Revenue = orders.quantity * orders.unit_price",
                "- Profit  = orders.quantity * (orders.unit_price - products.cost)",
                "- Only include orders with status = 'completed' when calculating revenue or profit,",
                "  unless the user explicitly asks about refunded or pending orders.",
                "- When asked about 'top customers', rank by total completed revenue unless specified otherwise."));

        AiContext context = client.createAiContext(
                "Sales Analysis",
                null,
                List.of(customersChipId, ordersChipId, productsChipId),
                columnDescriptions,
                relationshipPrompt);
        logger.info("AI context: {}", context.getContextId());

        try {
            // ── 4. Multi-turn conversation with AiConversation helper ──
            // AiConversation tracks turns locally — each ask() sends the
            // full history so the model can reference previous answers.
            AiConversation conversation = client.aiConversation(
                    context.getContextId(), credential.getCredentialId());

            // Simple two-table join
            System.out.println("\n=== Q1: Revenue by Region (customers ⟶ orders) ===");
            AiQueryResponse r1 = conversation.ask(
                    "What is the total revenue by region?");
            printResponse(r1);

            // Three-table join
            System.out.println("\n=== Q2: Revenue by Product (orders ⟶ products) ===");
            AiQueryResponse r2 = conversation.ask(
                    "Show me revenue and profit by product name.");
            printResponse(r2);

            // Complex analytical question
            System.out.println("\n=== Q3: Top Customers with Product Mix ===");
            AiQueryResponse r3 = conversation.ask(
                    "Who are the top 3 customers by revenue? Show their name, region, total revenue, and how many distinct products they ordered.");
            printResponse(r3);

            // Follow-up — the model knows about the previous answer
            System.out.println("\n=== Q4: Follow-up — drill into top customer ===");
            AiQueryResponse r4 = conversation.ask(
                    "For the top customer, break down their orders by product with quantity and total spent.");
            printResponse(r4);

            System.out.println("\nConversation turns: " + conversation.getHistory().size());

        } finally {
            // ── 8. Clean up ──
            client.deleteAiContext(context.getContextId());
            client.deleteAiCredential(credential.getCredentialId());
            client.deleteChip(customersChipId);
            client.deleteChip(ordersChipId);
            client.deleteChip(productsChipId);
            logger.info("Cleaned up.");
        }
    }

    private static void printResponse(AiQueryResponse response) {
        if (response.getError() != null) {
            System.out.println("Error: " + response.getError());
            return;
        }

        System.out.println("SQL: " + response.getGeneratedSql());
        if (response.getExplanation() != null) {
            System.out.println("Explanation: " + response.getExplanation());
        }

        if (response.getData() != null) {
            List<AiQueryResponse.ColumnInfo> columns = response.getData().getColumns();
            List<List<String>> rows = response.getData().getRows();

            StringBuilder header = new StringBuilder();
            for (int i = 0; i < columns.size(); i++) {
                if (i > 0) header.append(" | ");
                header.append(columns.get(i).getName());
            }
            System.out.println(header);
            for (List<String> row : rows) {
                System.out.println(String.join(" | ", row));
            }
            System.out.println("(" + rows.size() + " rows)");
        }

        if (response.getVisualization() != null) {
            System.out.println("Viz: " + response.getVisualization().getType()
                    + " — " + response.getVisualization().getTitle());
        }

        if (response.getUsage() != null) {
            System.out.println("Tokens: " + response.getUsage().getInputTokens() + " in, "
                    + response.getUsage().getOutputTokens() + " out");
        }
    }
}
