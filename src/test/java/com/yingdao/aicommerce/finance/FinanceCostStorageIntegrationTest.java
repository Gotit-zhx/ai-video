package com.yingdao.aicommerce.finance;

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FinanceCostStorageIntegrationTest extends FinanceActualCostsIntegrationTest {
    @Test void automaticRecalculationUpdatesCostsWithoutAppendingSnapshots() {
        fixture();
        addOrder("storage-regression", "COMPLETED");
        compute();
        equalMoney("20", total(9).get("amount_cny"));

        importData(evidence(List.of(shipment("storage-regression", "37.24")), List.of()));
        compute();
        equalMoney("37.24", total(9).get("amount_cny"));
        equalMoney("1.95", total(9).get("label_cost_cny"));
        assertEquals(1, db.queryForObject(
            "SELECT COUNT(*) FROM finance_workspace_audit WHERE entity_type='ACTUAL_COST_IMPORT'", Integer.class));
        assertEquals(0, db.queryForObject(
            "SELECT COUNT(*) FROM finance_workspace_audit WHERE entity_type='ORDER_STANDARD_COST'", Integer.class));

        var before = db.queryForList(
            "SELECT order_record_id,amount_cny,label_cost_cny,data_json,version FROM finance_order_costs");
        repo.transaction(() -> {
            new FinanceOrderCosts(repo).requestShop(owner.organizationId(), shop);
            return null;
        });
        compute();
        assertEquals(before, db.queryForList(
            "SELECT order_record_id,amount_cny,label_cost_cny,data_json,version FROM finance_order_costs"));
        assertEquals(0, db.queryForObject(
            "SELECT COUNT(*) FROM finance_order_cost_jobs", Integer.class));
        assertEquals(0, db.queryForObject(
            "SELECT COUNT(*) FROM finance_workspace_audit WHERE entity_type='ORDER_STANDARD_COST'", Integer.class));
    }
}
