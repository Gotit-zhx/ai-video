package com.yingdao.aicommerce.finance;

import com.yingdao.aicommerce.ops.MaintenanceGate;
import java.time.Duration;
import java.util.Objects;
import java.util.UUID;
import java.util.function.LongSupplier;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import static com.yingdao.aicommerce.finance.FinanceFirstSettlementFacts.*;

/** Explicit prototype worker; no component registration or automatic scheduler. */
final class FinanceFirstSettlementWorker {
    private final FinanceFirstSettlementFacts facts;
    private final FinanceFirstSettlementInitialization initialization;
    private final int batchSize;
    private final int leaseSeconds;
    private final int companyLimit;
    private final long tickNanos;
    private final LongSupplier nanoTime;
    private final MaintenanceGate gate;
    private String companyAfter;

    FinanceFirstSettlementWorker(FinanceFirstSettlementFacts facts,
            FinanceFirstSettlementInitialization initialization, int batchSize, int leaseSeconds) {
        this(facts, initialization, batchSize, leaseSeconds, 8, Duration.ofSeconds(2), System::nanoTime, MaintenanceGate.instance());
    }

    FinanceFirstSettlementWorker(FinanceFirstSettlementFacts facts,
            FinanceFirstSettlementInitialization initialization, int batchSize, int leaseSeconds,
            int companyLimit, Duration tickBudget, LongSupplier nanoTime, MaintenanceGate gate) {
        this.facts = Objects.requireNonNull(facts); this.initialization = Objects.requireNonNull(initialization);
        this.nanoTime = Objects.requireNonNull(nanoTime); this.gate = Objects.requireNonNull(gate);
        if (batchSize < 1 || batchSize > 256 || leaseSeconds < 1 || leaseSeconds > 300 || companyLimit < 1 || companyLimit > 32
            || tickBudget == null || tickBudget.isNegative() || tickBudget.isZero() || tickBudget.compareTo(Duration.ofSeconds(30)) > 0)
            throw new IllegalArgumentException("Invalid first-settlement worker bounds");
        this.batchSize = batchSize; this.leaseSeconds = leaseSeconds; this.companyLimit = companyLimit; this.tickNanos = tickBudget.toNanos();
    }

    /** One explicit single lane. Each unit commits before a deadline/gate check admits the next. */
    synchronized Tick tick() {
        try (var permit = gate.enter(true)) {
            if (permit == null) return new Tick(0, 0, 0);
            long started = nanoTime.getAsLong();
            int claimed = 0, published = 0, rejected = 0;
            if (!open(started)) return new Tick(0, 0, 0);
            // Initialization retains its independently bounded, short transactions and durable cursors.
            initialization.discoverCompanies(companyLimit);
            if (!open(started)) return new Tick(0, 0, 0);
            var tx = new TransactionTemplate(new DataSourceTransactionManager(Objects.requireNonNull(facts.repo.jdbc().getDataSource())));
            tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW); tx.setReadOnly(true); tx.setTimeout(timeout(started));
            var companies = tx.execute(status -> companyAfter == null
                ? facts.repo.jdbc().queryForList("SELECT organization_id FROM finance_first_settlement_initialization ORDER BY organization_id LIMIT ?", String.class, companyLimit)
                : facts.repo.jdbc().queryForList("SELECT organization_id FROM finance_first_settlement_initialization WHERE organization_id>? ORDER BY organization_id LIMIT ?", String.class, companyAfter, companyLimit));
            if (companies.isEmpty()) { companyAfter = null; return new Tick(0, 0, 0); }
            for (String organization : companies) {
                if (!open(started)) break;
                companyAfter = organization;
                var guard = tx.execute(status -> facts.guard(organization));
                if (guard.isEmpty()) continue;
                if (!open(started)) break;
                initialization.seed(guard.get(), batchSize);
                if (!open(started)) break;
                initialization.requeue(guard.get(), batchSize);
                if (!open(started)) break;
                initialization.complete(guard.get());
                for (int owner = 0; owner < batchSize && open(started); owner++) {
                    var lease = facts.claim(organization, UUID.randomUUID().toString(), leaseSeconds, timeout(started));
                    if (lease.isEmpty()) break;
                    claimed++;
                    if (!open(started)) { rejected++; break; }
                    try {
                        var computed = facts.calculate(lease.get(), timeout(started));
                        if (open(started) && facts.publish(computed, timeout(started))) published++;
                        else rejected++;
                    } catch (CalculationRejected stale) { rejected++; }
                }
            }
            return new Tick(claimed, published, rejected);
        }
    }

    private boolean open(long started) {
        return !Thread.currentThread().isInterrupted() && !gate.closed() && nanoTime.getAsLong() - started < tickNanos;
    }

    private int timeout(long started) {
        long remaining = Math.max(1, tickNanos - (nanoTime.getAsLong() - started));
        return (int) Math.min(3, Math.max(1, (remaining + 999_999_999L) / 1_000_000_000L));
    }
}
