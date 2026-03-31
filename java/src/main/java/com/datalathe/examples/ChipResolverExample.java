package com.datalathe.examples;

import com.datalathe.client.DatalatheClient;
import com.datalathe.client.GenerateReportResult;
import com.datalathe.client.types.GenerateReportResponse;
import com.datalathe.client.resolver.ChipFactory;
import com.datalathe.client.resolver.ChipResolver;
import com.datalathe.client.resolver.ResolvedChips;
import com.datalathe.client.types.ChipSource;
import com.datalathe.client.types.SourceType;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.core.config.Configurator;

import java.io.IOException;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.time.YearMonth;
import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;

/**
 * Example: use ChipResolver for efficient, incremental chip management.
 *
 * ChipResolver searches the engine for existing chips before creating new ones,
 * so repeated runs reuse what's already there. For partitioned tables (e.g. monthly
 * snapshots), only the missing months get created — the rest are found via search.
 *
 * This example models a sales reporting scenario with:
 *   - orders (partitioned by month — one chip per month)
 *   - products (unpartitioned — one chip, reused across all months)
 *
 * Usage:
 *   mvn compile exec:java -Dexec.mainClass="com.datalathe.examples.ChipResolverExample" \
 *       -Durl=http://localhost:3000 -Dsource=my_database -Dtenant=store_42
 */
public class ChipResolverExample {
    private static final Logger logger = LogManager.getLogger(ChipResolverExample.class);

    public static void main(String[] args) throws IOException, SQLException {
        Configurator.setRootLevel(Level.valueOf(System.getProperty("log.level", "INFO")));

        String url = System.getProperty("url", "http://localhost:3000");
        String sourceName = System.getProperty("source", "my_database");
        String tenantId = System.getProperty("tenant", "store_42");

        DatalatheClient client = new DatalatheClient(url);
        ChipResolver resolver = new ChipResolver(client);

        // Define which tables are partitioned and how to build chip sources
        Set<String> partitionedTables = Set.of("orders");

        ChipFactory factory = new ChipFactory() {
            @Override
            public boolean isPartitioned(String table) {
                return partitionedTables.contains(table);
            }

            @Override
            public ChipSource buildSource(String table, String partitionValue) {
                String sql = "SELECT * FROM " + table
                        + " WHERE tenant_id = '" + tenantId + "'"
                        + (partitionValue != null ? " AND month = '" + partitionValue + "'" : "");

                return ChipSource.builder()
                        .sourceType(SourceType.MYSQL)
                        .databaseName(sourceName)
                        .tableName(table)
                        .query(sql)
                        .partition(partitionValue != null
                                ? ChipSource.Partition.builder()
                                        .partitionBy("month")
                                        .partitionValues(List.of(partitionValue))
                                        .build()
                                : null)
                        .build();
            }
        };

        // --- Run 1: March report (6-month trend) ---
        // First run creates all chips: 1 unpartitioned (products) + 6 partitioned (orders)
        YearMonth march = YearMonth.of(2026, 3);
        List<String> marchMonths = monthRange(march, 6);

        System.out.println("=== March report ===");
        System.out.println("Months: " + marchMonths);

        ResolvedChips marchChips = resolver.resolveForTables(
                Set.of("orders", "products"),
                marchMonths,
                "tenant", tenantId,
                factory);

        System.out.println("Unpartitioned chips: " + marchChips.unpartitionedChipIds().size()
                + " (products — created once, reused every run)");
        System.out.println("Partitioned chips:   " + marchChips.partitionedChipIds().size()
                + " (orders — one per month)");
        System.out.println("Total chips:         " + marchChips.size());

        // Run a report against the resolved chips
        List<String> queries = List.of(
                "SELECT o.month, p.product_name, SUM(o.quantity) as total_qty, SUM(o.amount) as total_amount "
                        + "FROM orders o JOIN products p ON o.product_id = p.product_id "
                        + "GROUP BY o.month, p.product_name "
                        + "ORDER BY o.month, total_amount DESC");

        GenerateReportResult report = client.generateReport(
                marchChips.allChipIds(), queries, null, null);
        printReport(queries, report);

        // --- Run 2: April report (6-month trend, slides forward one month) ---
        // Only 1 new partitioned chip created (April) — the other 5 months + products already exist
        YearMonth april = YearMonth.of(2026, 4);
        List<String> aprilMonths = monthRange(april, 6);

        System.out.println("\n=== April report (incremental) ===");
        System.out.println("Months: " + aprilMonths);

        ResolvedChips aprilChips = resolver.resolveForTables(
                Set.of("orders", "products"),
                aprilMonths,
                "tenant", tenantId,
                factory);

        System.out.println("Total chips: " + aprilChips.size()
                + " (only the new month was created — rest reused from March run)");
    }

    /** Returns a list of month strings going back {@code count} months from {@code end}. */
    private static List<String> monthRange(YearMonth end, int count) {
        return IntStream.range(0, count)
                .mapToObj(i -> end.minusMonths(i).toString())
                .toList();
    }

    private static void printReport(List<String> queries, GenerateReportResult report)
            throws SQLException {
        if (report.getTiming() != null) {
            logger.info("Timing — total: {}ms, chip attach: {}ms, query execution: {}ms",
                    report.getTiming().getTotalMs(),
                    report.getTiming().getChipAttachMs(),
                    report.getTiming().getQueryExecutionMs());
        }

        for (int i = 0; i < queries.size(); i++) {
            System.out.println("\n--- Query " + i + " ---");
            System.out.println("SQL: " + queries.get(i));

            GenerateReportResponse.Result result = report.getResults().get(i);
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

            StringBuilder header = new StringBuilder();
            for (int c = 1; c <= meta.getColumnCount(); c++) {
                if (c > 1) header.append(" | ");
                header.append(meta.getColumnName(c));
            }
            System.out.println(header);

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
