package com.yingdao.aicommerce.finance;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Objects;
import java.util.Optional;

final class FinanceFirstSettlementFacts {
    final FinanceRepository repo;

    FinanceFirstSettlementFacts(FinanceRepository repo) {
        this.repo = Objects.requireNonNull(repo);
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
