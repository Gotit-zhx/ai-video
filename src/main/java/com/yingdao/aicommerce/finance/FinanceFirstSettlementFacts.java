package com.yingdao.aicommerce.finance;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Objects;
import java.util.Optional;

final class FinanceFirstSettlementFacts {
    static final int SUPPORTED_SCHEMA_VERSION = 1;
    // SHA-256 of sorted trigger-name -> SHA(name, timing, event, action statement).
    // Pinned to the independently reviewed prototype; never learned from its proof row.
    static final String SUPPORTED_DEFINITION_SHA =
        "b9b0139f6e10992d2d69d7c27a59101934e86816779e652ed2acaaa5f32fd157";
    final FinanceRepository repo;
    private final int supportedSchemaVersion;

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
        return repo.jdbc().query("""
            SELECT i.*,r.schema_version proof_schema,r.registration_version proof_registration,
              r.definition_sha proof_sha,r.installation_boundary proof_boundary,r.enabled proof_enabled
            FROM finance_first_settlement_initialization i
            JOIN finance_first_settlement_registration r ON r.id=1
            WHERE i.organization_id=?
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
            }, organizationId).stream().filter(Objects::nonNull).findFirst();
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
