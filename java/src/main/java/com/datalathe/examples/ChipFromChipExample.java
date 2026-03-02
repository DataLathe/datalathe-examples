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
import java.util.Collections;
import java.util.List;

/**
 * Example: create a new chip from an existing chip using SourceType.CACHE.
 *
 * Usage:
 *   mvn compile exec:java -Dexec.mainClass="com.datalathe.examples.ChipFromChipExample" \
 *       -Durl=http://localhost:3000 -DfilePath=./testdata/employees.csv
 */
public class ChipFromChipExample {
    private static final Logger logger = LogManager.getLogger(ChipFromChipExample.class);

    public static void main(String[] args) throws IOException, SQLException {
        Configurator.setRootLevel(Level.valueOf(System.getProperty("log.level", "INFO")));

        String url = System.getProperty("url", "http://localhost:3000");
        String filePath = Path.of(System.getProperty("filePath", "./testdata/employees.csv"))
                .toAbsolutePath().toString();

        DatalatheClient client = new DatalatheClient(url);

        // 1. Create a source chip from a file
        logger.info("Creating source chip from file: {}", filePath);
        String sourceChipId = client.createChipFromFile(filePath, "employees");
        logger.info("Source chip created: {}", sourceChipId);

        // 2. Create a filtered chip from the source chip
        logger.info("Creating filtered chip (Engineering only)...");
        String filteredChipId = client.createChipFromChip(
                Collections.singletonList(sourceChipId),
                "SELECT name, salary FROM employees WHERE department = 'Engineering'",
                "engineering_salaries");
        logger.info("Filtered chip created: {}", filteredChipId);

        // 3. Query the new chip
        List<String> queries = Collections.singletonList(
                "SELECT * FROM engineering_salaries ORDER BY salary DESC");

        GenerateReportResult report = client.generateReport(
                Collections.singletonList(filteredChipId), queries, null, null);

        GenerateReportCommand.Response.Result result = report.getResults().get(0);
        if (result.getError() != null) {
            System.out.println("Error: " + result.getError());
        } else {
            ResultSet rs = result.getResultSet();
            ResultSetMetaData meta = rs.getMetaData();

            for (int c = 1; c <= meta.getColumnCount(); c++) {
                if (c > 1) System.out.print(" | ");
                System.out.print(meta.getColumnName(c));
            }
            System.out.println();

            while (rs.next()) {
                for (int c = 1; c <= meta.getColumnCount(); c++) {
                    if (c > 1) System.out.print(" | ");
                    System.out.print(rs.getString(c));
                }
                System.out.println();
            }
        }

        // 4. Clean up
        client.deleteChip(filteredChipId);
        client.deleteChip(sourceChipId);
        logger.info("Done.");
    }
}
