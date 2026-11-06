package com.yingdao.aicommerce.finance;

import java.sql.DriverManager;
import java.sql.Connection;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import tools.jackson.databind.ObjectMapper;
import static com.yingdao.aicommerce.finance.FinanceData.*;

/** Bounded, read-only release probe. Prints counts and booleans, never order data. */
public class FinanceBrushSourceSummaryVerify {
    private static void check(boolean ok, String message) {
        if (!ok) throw new IllegalStateException(message);
    }

    private static Map<String,Object> expected(JdbcTemplate db, String organization, String group, String order) {
        String where = "organization_id=? AND group_id=?" + (order == null ? "" : " AND order_id=?");
        Object[] args = order == null ? new Object[]{organization, group} : new Object[]{organization, group, order};
        return db.queryForMap("SELECT COUNT(*) records,COALESCE(SUM(CASE WHEN principal_native<>0 THEN 1 ELSE 0 END),0) principal_rows,"
            + "COALESCE(SUM(CASE WHEN service_fee_cny<>0 THEN service_fee_cny ELSE 0 END),0) service_fee_cny FROM finance_charge_rows WHERE " + where, args);
    }

    private static void compare(Map<String,Object> response, Map<String,Object> expected) {
        var summary = map(response.get("sourceSummary"));
        check(number(response.get("recordTotal")) == number(expected.get("records")), "Source query row total differs");
        check(number(summary.get("records")) == number(expected.get("records")), "Aggregate row total differs");
        check(number(summary.get("principal_rows")) == number(expected.get("principal_rows")), "Nonzero principal count differs");
        check(decimal(summary.get("service_fee_cny")).compareTo(decimal(expected.get("service_fee_cny"))) == 0, "CNY fee sum differs");
    }

    public static void main(String[] args) throws Exception {
        long started = System.nanoTime();
        int groupsChecked = 0, secondPages = 0, exactLookups = 0;
        Class.forName("com.mysql.cj.jdbc.Driver");
        try (var connection = DriverManager.getConnection(System.getenv("DB_URL"), System.getenv("DB_USERNAME"), System.getenv("DB_PASSWORD"))) {
            connection.setReadOnly(true);
            connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            connection.setAutoCommit(false);
            var db = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
            db.setQueryTimeout(5);
            db.execute("SET SESSION MAX_EXECUTION_TIME=5000");
            var repo = new FinanceRepository(db, new ObjectMapper());
            // The no-period workspace reads groups, source rows and existing markers.
            // It does not need authorization mutations, providers or a running worker.
            var charges = new FinanceOrderCharges(repo, null);
            var groups = db.queryForList("""
                SELECT g.organization_id,g.id FROM finance_groups g
                WHERE EXISTS(SELECT 1 FROM finance_charge_rows r WHERE r.organization_id=g.organization_id AND r.group_id=g.id)
                ORDER BY g.organization_id,g.id LIMIT 30
                """);
            for (var group : groups) {
                String organization = text(group.get("organization_id")), id = text(group.get("id"));
                var scope = new Scope(organization, "release-readonly");
                var oracle = expected(db, organization, id, null);
                var first = charges.workspace(scope, id, 1, null, "");
                compare(first, oracle);
                if (number(oracle.get("records")) > 50) {
                    compare(charges.workspace(scope, id, 2, null, ""), oracle);
                    secondPages++;
                }
                var sourceRows = rows(first.get("rows"));
                if (!sourceRows.isEmpty()) {
                    String order = text(sourceRows.getFirst().get("order_id"));
                    if (!order.isBlank()) {
                        compare(charges.workspace(scope, id, 1, null, order), expected(db, organization, id, order));
                        exactLookups++;
                    }
                }
                groupsChecked++;
            }
            check(connection.isReadOnly(), "Read-only connection guard changed");
            connection.rollback();
            System.out.println(new ObjectMapper().writeValueAsString(Map.of(
                "sourceGroupsVerified", groupsChecked, "secondPagesVerified", secondPages,
                "exactLookupsVerified", exactLookups, "groupLimit", 30,
                "readOnly", true, "sourceRecordsWritten", false, "aggregateChecksPassed", true,
                "elapsedMillis", (System.nanoTime() - started) / 1_000_000)));
        }
    }
}
