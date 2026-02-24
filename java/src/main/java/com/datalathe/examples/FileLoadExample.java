package com.datalathe.examples;

import com.datalathe.client.DatalatheClient;
import com.datalathe.client.GenerateReportResult;
import com.datalathe.client.command.impl.GenerateReportCommand;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.core.config.Configurator;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;

/**
 * Example: load a CSV file into Datalathe and query it back.
 *
 * Usage:
 *   mvn compile exec:java -Dexec.mainClass="com.datalathe.examples.FileLoadExample" \
 *       -Durl=http://localhost:3000 -DfilePath=./testdata/employees.csv
 */
public class FileLoadExample {
    private static final Logger logger = LogManager.getLogger(FileLoadExample.class);

    public static void main(String[] args) throws IOException, SQLException {
        Configurator.setRootLevel(Level.valueOf(System.getProperty("log.level", "INFO")));

        String url = System.getProperty("url", "http://localhost:3000");
        // Resolve to absolute path so the engine can find the file
        String filePath = Path.of(System.getProperty("filePath", "./testdata/employees.csv"))
                .toAbsolutePath().toString();

        logger.info("Connecting to: {}", url);
        logger.info("Loading file: {}", filePath);

        DatalatheClient client = new DatalatheClient(url);

        // 1. Load file into a chip
        logger.info("Creating chip from file...");
        String chipId = client.createChipFromFile(filePath, "employees");
        logger.info("Chip created: {}", chipId);

        List<String> chipIds = List.of(chipId);

        // 2. Query it back
        List<String> queries = Arrays.asList(
                "SELECT * FROM employees",
                "SELECT department, COUNT(*) as headcount, AVG(salary) as avg_salary FROM employees GROUP BY department ORDER BY department",
                "SELECT name, salary FROM employees WHERE is_active = true ORDER BY salary DESC LIMIT 5");

        logger.info("Executing {} queries...", queries.size());

        GenerateReportResult report = client.generateReport(chipIds, queries, null, null);

        // 3. Print timing
        if (report.getTiming() != null) {
            logger.info("Timing — total: {}ms, chip attach: {}ms, query execution: {}ms",
                    report.getTiming().getTotalMs(),
                    report.getTiming().getChipAttachMs(),
                    report.getTiming().getQueryExecutionMs());
        }

        // 4. Print each result
        for (int i = 0; i < queries.size(); i++) {
            System.out.println("\n--- Query " + i + " ---");
            System.out.println("SQL: " + queries.get(i));

            GenerateReportCommand.Response.Result result = report.getResults().get(i);
            if (result == null) {
                System.out.println("  (no result)");
                continue;
            }
            if (result.getError() != null) {
                System.out.println("  Error: " + result.getError());
                continue;
            }

            ResultSet rs = result.getResultSet();
            ResultSetMetaData meta = rs.getMetaData();

            // Print header
            StringBuilder header = new StringBuilder();
            for (int c = 1; c <= meta.getColumnCount(); c++) {
                if (c > 1) header.append(" | ");
                header.append(meta.getColumnName(c));
            }
            System.out.println(header);

            // Print rows
            int rowCount = 0;
            while (rs.next()) {
                StringBuilder row = new StringBuilder();
                for (int c = 1; c <= meta.getColumnCount(); c++) {
                    if (c > 1) row.append(" | ");
                    row.append(rs.getString(c));
                }
                System.out.println(row);
                rowCount++;
            }
            System.out.println("(" + rowCount + " rows)");
        }
    }
}
