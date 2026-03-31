package com.datalathe.examples;

import com.datalathe.client.DatalatheClient;
import com.datalathe.client.types.AiContext;
import com.datalathe.client.types.AiCredential;
import com.datalathe.client.types.AiQueryResponse;
import com.datalathe.client.types.ConversationTurn;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.core.config.Configurator;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Example: use AI Query to ask natural language questions about your data.
 *
 * This example loads a CSV file, creates an AI context with column descriptions,
 * then asks questions in plain English instead of writing SQL.
 *
 * Usage:
 *   mvn compile exec:java -Dexec.mainClass="com.datalathe.examples.AiQueryExample" \
 *       -Durl=http://localhost:3000 \
 *       -DfilePath=./testdata/employees.csv \
 *       -DapiKey=YOUR_ANTHROPIC_API_KEY
 */
public class AiQueryExample {
    private static final Logger logger = LogManager.getLogger(AiQueryExample.class);

    public static void main(String[] args) throws IOException {
        Configurator.setRootLevel(Level.valueOf(System.getProperty("log.level", "INFO")));

        String url = System.getProperty("url", "http://localhost:3000");
        String filePath = Path.of(System.getProperty("filePath", "./testdata/employees.csv"))
                .toAbsolutePath().toString();
        String apiKey = System.getProperty("apiKey");
        if (apiKey == null || apiKey.isEmpty()) {
            System.err.println("ERROR: -DapiKey is required. Pass your Anthropic API key.");
            System.exit(1);
        }

        DatalatheClient client = new DatalatheClient(url);

        // 1. Load a CSV file into a chip
        logger.info("Loading file: {}", filePath);
        String chipId = client.createChipFromFile(filePath, "employees");
        logger.info("Chip created: {}", chipId);

        // 2. Register an AI credential
        AiCredential credential = client.createAiCredential(
                "example-key", "anthropic", apiKey, null);
        logger.info("Credential created: {}", credential.getCredentialId());

        // 3. Create an AI context with column descriptions so the model understands the data
        Map<String, Map<String, String>> columnDescriptions = Map.of(
                "employees", Map.of(
                        "name", "Employee full name",
                        "department", "Department the employee belongs to",
                        "salary", "Annual salary in USD",
                        "is_active", "Whether the employee is currently active"));

        AiContext context = client.createAiContext(
                "Employee Analysis",
                null,
                List.of(chipId),
                columnDescriptions,
                "Single table of employee records with department and salary information.");
        logger.info("AI context created: {}", context.getContextId());

        try {
            // 4. Ask a simple question
            System.out.println("\n=== Single Question ===");
            AiQueryResponse response = client.aiQuery(
                    context.getContextId(),
                    credential.getCredentialId(),
                    "What is the average salary by department?");
            printResponse(response);

            // 5. Multi-turn conversation — follow up on the previous answer
            System.out.println("\n=== Follow-up Question ===");
            List<ConversationTurn> history = new ArrayList<>();
            history.add(ConversationTurn.builder()
                    .role("user")
                    .content("What is the average salary by department?")
                    .build());
            history.add(ConversationTurn.builder()
                    .role("assistant")
                    .content(response.getExplanation())
                    .build());

            AiQueryResponse followUp = client.aiQuery(
                    context.getContextId(),
                    credential.getCredentialId(),
                    "Which department has the biggest gap between its highest and lowest paid employee?",
                    history,
                    null);
            printResponse(followUp);

        } finally {
            // 6. Clean up
            client.deleteAiContext(context.getContextId());
            client.deleteAiCredential(credential.getCredentialId());
            client.deleteChip(chipId);
            logger.info("Cleaned up credential, context, and chip.");
        }
    }

    private static void printResponse(AiQueryResponse response) {
        if (response.getError() != null) {
            System.out.println("Error: " + response.getError());
            return;
        }

        // Generated SQL
        System.out.println("SQL: " + response.getGeneratedSql());

        // Explanation
        if (response.getExplanation() != null) {
            System.out.println("Explanation: " + response.getExplanation());
        }

        // Data
        if (response.getData() != null) {
            List<AiQueryResponse.ColumnInfo> columns = response.getData().getColumns();
            List<List<String>> rows = response.getData().getRows();

            // Header
            StringBuilder header = new StringBuilder();
            for (int i = 0; i < columns.size(); i++) {
                if (i > 0) header.append(" | ");
                header.append(columns.get(i).getName());
            }
            System.out.println(header);

            // Rows
            for (List<String> row : rows) {
                System.out.println(String.join(" | ", row));
            }
            System.out.println("(" + rows.size() + " rows)");
        }

        // Visualization hint
        if (response.getVisualization() != null) {
            System.out.println("Suggested visualization: " + response.getVisualization().getType()
                    + " — " + response.getVisualization().getTitle());
        }

        // Token usage
        if (response.getUsage() != null) {
            System.out.println("Tokens: " + response.getUsage().getInputTokens() + " in, "
                    + response.getUsage().getOutputTokens() + " out (model: "
                    + response.getUsage().getModel() + ")");
        }
    }
}
