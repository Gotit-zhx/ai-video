package com.yingdao.aicommerce.finance;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

final class FinanceFirstSettlementFacts {
    static final int SUPPORTED_SCHEMA_VERSION = 1;
    // SHA-256 of sorted trigger-name -> SHA(name, timing, event, action statement).
    // Pinned to the independently reviewed prototype; never learned from its proof row.
    static final String SUPPORTED_DEFINITION_SHA =
        "b9b0139f6e10992d2d69d7c27a59101934e86816779e652ed2acaaa5f32fd157";
    final FinanceRepository repo;
    private final int supportedSchemaVersion;
    private static final int CLAIM_WINDOW = 32;
    private static final int TRANSACTION_SECONDS = 3;
    private final Map<String, Owner> claimAfter = new HashMap<>();

    FinanceFirstSettlementFacts(FinanceRepository repo) {
        this(repo, SUPPORTED_SCHEMA_VERSION);
    }

    // Package-only version injection permits isolated upgrade compatibility tests.
    FinanceFirstSettlementFacts(FinanceRepository repo, int supportedSchemaVersion) {
        this.repo = Objects.requireNonNull(repo);
        this.supportedSchemaVersion = supportedSchemaVersion;
    }

    Optional<Registration> registration() {
        return registration(false);
    }

    /** Call inside a short write transaction, before locking company or owner rows. */
    Optional<Registration> lockRegistration() {
        return registration(true);
    }

    private Optional<Registration> registration(boolean lock) {
        return repo.jdbc().query("""
            SELECT schema_version,registration_version,definition_sha,installation_boundary,enabled
            FROM finance_first_settlement_registration WHERE id=1
            """ + (lock ? " FOR SHARE" : ""), (rs, row) -> new Registration(
                rs.getInt("schema_version"), rs.getInt("registration_version"),
                rs.getString("definition_sha"), rs.getString("installation_boundary"),
                rs.getBoolean("enabled"))).stream().filter(this::supported).findFirst();
    }

    private boolean supported(Registration proof) {
        return proof.enabled() && proof.schemaVersion() == supportedSchemaVersion
            && proof.registrationVersion() > 0
            && SUPPORTED_DEFINITION_SHA.equals(proof.definitionSha())
            && proof.boundary() != null && !proof.boundary().isBlank();
    }

    /** One SELECT keeps the ordinary read guard and installation proof in the same snapshot. */
    Optional<Guard> guard(String organizationId) {
        return guard(organizationId, organizationId);
    }

    private Optional<Guard> guard(String organizationId, String expectedOrganizationId) {
        return repo.jdbc().query("""
            SELECT i.*,r.schema_version proof_schema,r.registration_version proof_registration,
              r.definition_sha proof_sha,r.installation_boundary proof_boundary,r.enabled proof_enabled
            FROM finance_first_settlement_initialization i
            JOIN finance_first_settlement_registration r ON r.id=1
            WHERE i.organization_id=? AND i.organization_id=?
            """, (rs, row) -> {
                var proof = new Registration(rs.getInt("proof_schema"),rs.getInt("proof_registration"),
                    rs.getString("proof_sha"),rs.getString("proof_boundary"),rs.getBoolean("proof_enabled"));
                if (!supported(proof) || rs.getLong("generation") <= 0
                    || rs.getInt("target_schema_version") != proof.schemaVersion()
                    || rs.getInt("registration_version") != proof.registrationVersion()
                    || !proof.definitionSha().equals(rs.getString("registration_definition_sha"))
                    || !proof.boundary().equals(rs.getString("registration_boundary"))) return null;
                return new Guard(rs.getString("organization_id"),rs.getLong("generation"),
                    rs.getInt("target_schema_version"),rs.getInt("registration_version"),rs.getBoolean("seed_complete"));
            }, organizationId, expectedOrganizationId).stream().filter(Objects::nonNull).findFirst();
    }

    synchronized Optional<Lease> claim(String organizationId, String token, int leaseSeconds) {
        return claim(organizationId, token, leaseSeconds, TRANSACTION_SECONDS);
    }

    private record ClaimWindow(Optional<Lease> lease, Owner after, boolean scanned) {}

    /** Enumerate a bounded pending window before lease/lock exclusions, then probe exact primary keys. */
    synchronized Optional<Lease> claim(String organizationId, String token, int leaseSeconds, int timeoutSeconds) {
        Objects.requireNonNull(organizationId);
        if (token == null || token.isBlank() || token.length() > 36 || leaseSeconds < 1 || leaseSeconds > 300)
            throw new IllegalArgumentException("A bounded lease and a nonempty token are required");
        Owner after = claimAfter.get(organizationId);
        var result = write(timeoutSeconds, () -> {
            var proof = lockRegistration();
            if (proof.isEmpty()) return new ClaimWindow(Optional.empty(), null, false);
            var guard = currentGuard(organizationId, organizationId, proof.get());
            if (guard.isEmpty()) return new ClaimWindow(Optional.empty(), null, false);
            var args = new ArrayList<Object>(); args.add(organizationId);
            String keyset = after == null ? "" : " AND (shop_id>? OR (shop_id=? AND order_id>?))";
            if (after != null) { args.add(after.shopId()); args.add(after.shopId()); args.add(after.orderId()); }
            args.add(CLAIM_WINDOW);
            var owners = repo.jdbc().query("""
                SELECT organization_id,shop_id,order_id FROM finance_first_settlement_facts FORCE INDEX(idx_fsf_pending)
                WHERE organization_id=? AND pending=TRUE
                """ + keyset + " ORDER BY shop_id,order_id LIMIT ?",
                (rs, row) -> new Owner(rs.getString("organization_id"), rs.getString("shop_id"), rs.getString("order_id")), args.toArray());
            Owner examined = null;
            for (var owner : owners) {
                examined = owner;
                var available = repo.jdbc().query("""
                    SELECT pending,(lease_token IS NULL OR lease_until IS NULL OR lease_until<=UTC_TIMESTAMP(6)) available
                    FROM finance_first_settlement_facts FORCE INDEX(PRIMARY)
                    WHERE organization_id=? AND shop_id=? AND order_id=? FOR UPDATE SKIP LOCKED
                    """, (rs, row) -> rs.getBoolean("pending") && rs.getBoolean("available"),
                    owner.organizationId(), owner.shopId(), owner.orderId());
                if (available.isEmpty() || !available.getFirst()) continue;
                int changed = repo.jdbc().update("""
                    UPDATE finance_first_settlement_facts
                    SET lease_token=?,lease_until=TIMESTAMPADD(SECOND,?,UTC_TIMESTAMP(6)),updated_at=UTC_TIMESTAMP(6)
                    WHERE organization_id=? AND shop_id=? AND order_id=? AND pending=TRUE
                      AND (lease_token IS NULL OR lease_until IS NULL OR lease_until<=UTC_TIMESTAMP(6))
                    """, token, leaseSeconds, owner.organizationId(), owner.shopId(), owner.orderId());
                if (changed == 1) return new ClaimWindow(Optional.of(new Lease(owner, guard.get(), token)), examined, true);
            }
            return new ClaimWindow(Optional.empty(), examined, true);
        });
        // Do not advance a cursor on a failed/rolled-back claim transaction.
        if (result.scanned()) {
            if (result.after() == null) claimAfter.remove(organizationId);
            else claimAfter.put(organizationId, result.after());
        }
        return result.lease();
    }

    Computed calculate(Lease lease) { return calculate(lease, TRANSACTION_SECONDS); }

    Computed calculate(Lease lease, int timeoutSeconds) {
        Objects.requireNonNull(lease); Objects.requireNonNull(lease.owner()); Objects.requireNonNull(lease.guard());
        var tx = transaction(timeoutSeconds, true);
        return tx.execute(status -> {
            // The first ordinary SELECT establishes S. No current-read/FOR UPDATE may precede R or candidates.
            var observed = guard(lease.owner().organizationId(), lease.guard().organizationId());
            if (observed.isEmpty() || !sameVersion(observed.get(), lease.guard())) throw new CalculationRejected();
            var owner = lease.owner();
            var revisions = repo.jdbc().query("""
                SELECT requested_revision,lease_token,lease_until>UTC_TIMESTAMP(6) valid_lease
                FROM finance_first_settlement_facts WHERE organization_id=? AND shop_id=? AND order_id=?
                """, (rs, row) -> Objects.equals(lease.token(), rs.getString("lease_token")) && rs.getBoolean("valid_lease")
                    ? rs.getLong("requested_revision") : null, owner.organizationId(), owner.shopId(), owner.orderId());
            if (revisions.isEmpty() || revisions.getFirst() == null) throw new CalculationRejected();
            var first = repo.jdbc().query("""
                SELECT r.id,r.record_date,r.platform,r.currency
                FROM finance_records r FORCE INDEX(idx_finance_first_settlement)
                WHERE r.organization_id=? AND r.shop_id=? AND r.order_id=? AND
                """ + eligible("r") + " ORDER BY r.record_date,r.id LIMIT 1",
                (rs, row) -> new Candidate(rs.getString("id"), rs.getDate("record_date").toLocalDate(),
                    rs.getString("platform"), rs.getString("currency")), owner.organizationId(), owner.shopId(), owner.orderId());
            return new Computed(lease, revisions.getFirst(), first.stream().findFirst());
        });
    }

    boolean publish(Computed computed) { return publish(computed, TRANSACTION_SECONDS); }

    boolean publish(Computed computed, int timeoutSeconds) {
        Objects.requireNonNull(computed); Objects.requireNonNull(computed.lease()); Objects.requireNonNull(computed.first());
        var lease = computed.lease(); var expected = lease.guard(); var owner = lease.owner();
        if (computed.revision() <= 0) return false;
        return write(timeoutSeconds, () -> {
            var proof = lockRegistration();
            if (proof.isEmpty()) return false;
            var current = currentGuard(owner.organizationId(), expected.organizationId(), proof.get());
            if (current.isEmpty() || !sameVersion(current.get(), expected)) return false;
            Candidate first = computed.first().orElse(null);
            return repo.jdbc().update("""
                UPDATE finance_first_settlement_facts f
                SET has_settlement=?,first_record_id=?,first_record_date=?,first_platform=?,first_currency=?,
                  schema_version=?,applied_generation=?,applied_registration_version=?,applied_revision=?,
                  lease_token=NULL,lease_until=NULL,updated_at=UTC_TIMESTAMP(6)
                WHERE f.organization_id=? AND f.shop_id=? AND f.order_id=?
                  AND f.requested_revision=? AND BINARY f.lease_token=BINARY ? AND f.lease_until>UTC_TIMESTAMP(6)
                  AND EXISTS(SELECT 1 FROM finance_first_settlement_initialization i
                    JOIN finance_first_settlement_registration r ON r.id=1
                    WHERE i.organization_id=f.organization_id AND i.organization_id=? AND i.generation=? AND i.target_schema_version=?
                      AND i.registration_version=? AND i.target_schema_version=r.schema_version
                      AND i.registration_version=r.registration_version AND r.enabled=TRUE
                      AND r.schema_version=? AND r.registration_version=? AND r.definition_sha=?
                      AND i.registration_definition_sha=r.definition_sha AND i.registration_boundary=r.installation_boundary)
                """, first != null, first == null ? null : first.id(),
                first == null ? null : java.sql.Date.valueOf(first.date()), first == null ? null : first.platform(),
                first == null ? null : first.currency(), expected.targetSchemaVersion(), expected.generation(),
                expected.registrationVersion(), computed.revision(), owner.organizationId(), owner.shopId(), owner.orderId(),
                computed.revision(), lease.token(), expected.organizationId(), expected.generation(), expected.targetSchemaVersion(),
                expected.registrationVersion(), supportedSchemaVersion, expected.registrationVersion(), SUPPORTED_DEFINITION_SHA) == 1;
        });
    }

    private Optional<Guard> currentGuard(String organizationId, String expectedOrganizationId, Registration proof) {
        return repo.jdbc().query("""
            SELECT * FROM finance_first_settlement_initialization WHERE organization_id=? AND organization_id=? FOR UPDATE
            """, (rs, row) -> {
                if (rs.getLong("generation") <= 0 || rs.getInt("target_schema_version") != proof.schemaVersion()
                    || rs.getInt("registration_version") != proof.registrationVersion()
                    || !proof.definitionSha().equals(rs.getString("registration_definition_sha"))
                    || !proof.boundary().equals(rs.getString("registration_boundary"))) return null;
                return new Guard(rs.getString("organization_id"), rs.getLong("generation"), rs.getInt("target_schema_version"),
                    rs.getInt("registration_version"), rs.getBoolean("seed_complete"));
            }, organizationId, expectedOrganizationId).stream().filter(Objects::nonNull).findFirst();
    }

    /** Call only after SQL has bound owner and expected guard to the same company row. */
    private static boolean sameVersion(Guard current, Guard expected) {
        return current.generation() == expected.generation()
            && current.targetSchemaVersion() == expected.targetSchemaVersion()
            && current.registrationVersion() == expected.registrationVersion();
    }

    private TransactionTemplate transaction(int timeoutSeconds, boolean readOnly) {
        if (timeoutSeconds < 1 || timeoutSeconds > TRANSACTION_SECONDS) throw new IllegalArgumentException("Invalid transaction timeout");
        var tx = new TransactionTemplate(new DataSourceTransactionManager(Objects.requireNonNull(repo.jdbc().getDataSource())));
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tx.setIsolationLevel(readOnly ? TransactionDefinition.ISOLATION_REPEATABLE_READ : TransactionDefinition.ISOLATION_READ_COMMITTED);
        tx.setReadOnly(readOnly); tx.setTimeout(timeoutSeconds);
        return tx;
    }

    private <T> T write(int timeoutSeconds, Supplier<T> action) {
        for (int attempt = 1; ; attempt++) {
            try { return transaction(timeoutSeconds, false).execute(status -> action.get()); }
            catch (RuntimeException failure) {
                if (attempt == 3 || Thread.currentThread().isInterrupted() || !deadlock(failure)) throw failure;
            }
        }
    }

    private static boolean deadlock(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause())
            if (cause instanceof java.sql.SQLException sql && (sql.getErrorCode() == 1213 || "40001".equals(sql.getSQLState()))) return true;
        return false;
    }

    static final class CalculationRejected extends IllegalStateException {
        CalculationRejected() { super("First-settlement lease or version is no longer current"); }
    }

    record Owner(String organizationId, String shopId, String orderId) {}
    record Registration(int schemaVersion, int registrationVersion,
                        String definitionSha, String boundary, boolean enabled) {}
    record Guard(String organizationId, long generation, int targetSchemaVersion,
                 int registrationVersion, boolean seedComplete) {}
    record MaintenanceCompanyState(String organizationId, long generation,
                                   int targetSchemaVersion, int registrationVersion) {}
    enum AdvanceResult { ADVANCED, ALREADY_TARGET, STALE, REJECTED }
    record Lease(Owner owner, Guard guard, String token) {}
    record Candidate(String id, LocalDate date, String platform, String currency) {}
    record Computed(Lease lease, long revision, Optional<Candidate> first) {}
    record Tick(int claimed, int published, int rejected) {}
    record Batch(int visited, boolean complete) {}
    record Period(BigDecimal amountCny, long orders, long conflicts, long shipments) {}
    enum Mode { OFF, SHADOW, ACTIVE }
    record ApprovedRange(String organizationId, LocalDate start, LocalDate end,
                         Mode mode, int schemaVersion, int registrationVersion, String evidenceId) {}
    record CandidateBudget(long candidateMillis, long nativeReserveMillis, long completionReserveMillis) {}
    enum Route { NATIVE, FACTS }
    record Resolution(Route route, Period value, String fallbackReason) {}

    static String eligible(String alias) {
        return FinanceCostReport.confirmed(alias)
            + " AND (" + alias + ".platform<>'TIKTOK_SHOP' OR " + alias + ".category='ORDER')";
    }
}
