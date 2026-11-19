package com.yingdao.aicommerce.finance;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import static com.yingdao.aicommerce.finance.FinanceFirstSettlementFacts.*;

/** Explicit background/maintenance operations; constructing this class performs no database work. */
final class FinanceFirstSettlementInitialization {
    private final FinanceRepository repo;
    private final JdbcTemplate db;
    private final FinanceFirstSettlementFacts facts;
    private String companyAfter;

    FinanceFirstSettlementInitialization(FinanceRepository repo) {
        this(repo, SUPPORTED_SCHEMA_VERSION);
    }

    FinanceFirstSettlementInitialization(FinanceRepository repo, int supportedSchemaVersion) {
        this.repo = Objects.requireNonNull(repo);
        this.db = repo.jdbc();
        this.facts = new FinanceFirstSettlementFacts(repo, supportedSchemaVersion);
    }

    private record Company(Guard guard, String definitionSha, String boundary,
                           String shopAfter, String currentShop, String orderAfter, boolean shopsComplete,
                           String requeueShopAfter, String requeueOrderAfter, boolean requeueComplete) {
        boolean matches(Registration proof) {
            return guard.generation() > 0 && guard.targetSchemaVersion() == proof.schemaVersion()
                && guard.registrationVersion() == proof.registrationVersion()
                && proof.definitionSha().equals(definitionSha) && proof.boundary().equals(boundary);
        }
        MaintenanceCompanyState maintenance() {
            return new MaintenanceCompanyState(guard.organizationId(),guard.generation(),
                guard.targetSchemaVersion(),guard.registrationVersion());
        }
    }

    /** In-memory cursor wraps at the end; restart may safely rescan existing companies. */
    synchronized Batch discoverCompanies(int limit) {
        requireLimit(limit);
        List<String> organizations = repo.transaction(() -> {
            var proof = facts.lockRegistration();
            var args = new ArrayList<Object>();
            String after = companyAfter == null ? "" : " WHERE organization_id>?";
            if (companyAfter != null) args.add(companyAfter);
            args.add(limit);
            var found = db.queryForList("""
                SELECT organization_id FROM (
                  SELECT organization_id FROM finance_records
                  UNION SELECT organization_id FROM finance_connections
                ) companies
                """ + after + " ORDER BY organization_id LIMIT ?", String.class, args.toArray());
            for (String organization : found) {
                db.update("""
                    INSERT INTO finance_first_settlement_initialization(organization_id) VALUES(?)
                    ON DUPLICATE KEY UPDATE organization_id=organization_id
                    """, organization);
                if (proof.isPresent()) {
                    var target = proof.get();
                    db.update("""
                        UPDATE finance_first_settlement_initialization
                        SET generation=1,target_schema_version=?,registration_version=?,
                          registration_definition_sha=?,registration_boundary=?,seed_complete=FALSE,
                          shop_after=NULL,current_shop=NULL,order_after=NULL,shops_complete=FALSE,
                          requeue_shop_after=NULL,requeue_order_after=NULL,requeue_complete=FALSE,
                          updated_at=UTC_TIMESTAMP(6)
                        WHERE organization_id=? AND generation=0
                          AND target_schema_version<=? AND registration_version<=?
                        """, target.schemaVersion(),target.registrationVersion(),target.definitionSha(),target.boundary(),
                        organization,target.schemaVersion(),target.registrationVersion());
                }
            }
            return found;
        });
        companyAfter = organizations.isEmpty() ? null : organizations.getLast();
        return new Batch(organizations.size(),organizations.isEmpty());
    }

    Batch seed(Guard expected, int ownerLimit) {
        requireLimit(ownerLimit);
        return repo.transaction(() -> {
            var locked = lockExpected(expected);
            if (locked.isEmpty()) return new Batch(0,false);
            var company = locked.get();
            if (company.shopsComplete()) return new Batch(0,true);
            String shop = company.currentShop();
            if (shop == null) {
                var args = new ArrayList<Object>(); args.add(expected.organizationId());
                String after = company.shopAfter() == null ? "" : " AND shop_id>?";
                if (company.shopAfter() != null) args.add(company.shopAfter());
                var shops = db.queryForList("""
                    SELECT DISTINCT shop_id FROM finance_records
                    WHERE organization_id=?
                    """ + after + " ORDER BY shop_id LIMIT 1",String.class,args.toArray());
                if (shops.isEmpty()) {
                    db.update("UPDATE finance_first_settlement_initialization SET shops_complete=TRUE,updated_at=UTC_TIMESTAMP(6) WHERE organization_id=?",expected.organizationId());
                    return new Batch(0,true);
                }
                shop = shops.getFirst();
                db.update("UPDATE finance_first_settlement_initialization SET current_shop=?,order_after=NULL,updated_at=UTC_TIMESTAMP(6) WHERE organization_id=?",shop,expected.organizationId());
            }
            var args = new ArrayList<Object>(); args.add(expected.organizationId()); args.add(shop);
            String after = company.orderAfter() == null ? "" : " AND r.order_id>?";
            if (company.orderAfter() != null) args.add(company.orderAfter());
            args.add(ownerLimit);
            var orders = db.queryForList("""
                SELECT DISTINCT r.order_id FROM finance_records r FORCE INDEX(idx_finance_first_settlement)
                WHERE r.organization_id=? AND r.shop_id=? AND
                """ + eligible("r") + after + " ORDER BY r.order_id LIMIT ?",String.class,args.toArray());
            for (String order : orders) {
                db.update("""
                    INSERT INTO finance_first_settlement_facts(organization_id,shop_id,order_id)
                    VALUES(?,?,?) ON DUPLICATE KEY UPDATE order_id=order_id
                    """,expected.organizationId(),shop,order);
            }
            if (orders.isEmpty()) {
                db.update("""
                    UPDATE finance_first_settlement_initialization
                    SET shop_after=?,current_shop=NULL,order_after=NULL,updated_at=UTC_TIMESTAMP(6)
                    WHERE organization_id=?
                    """,shop,expected.organizationId());
            } else {
                db.update("UPDATE finance_first_settlement_initialization SET order_after=?,updated_at=UTC_TIMESTAMP(6) WHERE organization_id=?",orders.getLast(),expected.organizationId());
            }
            return new Batch(orders.size(),false);
        });
    }

    Batch requeue(Guard expected, int ownerLimit) {
        requireLimit(ownerLimit);
        return repo.transaction(() -> {
            var locked = lockExpected(expected);
            if (locked.isEmpty()) return new Batch(0,false);
            var company = locked.get();
            if (company.requeueComplete()) return new Batch(0,true);
            var args = new ArrayList<Object>(); args.add(expected.organizationId());
            String after = company.requeueShopAfter() == null ? "" : " AND (shop_id>? OR (shop_id=? AND order_id>?))";
            if (company.requeueShopAfter() != null) {
                args.add(company.requeueShopAfter());args.add(company.requeueShopAfter());args.add(company.requeueOrderAfter());
            }
            args.add(ownerLimit);
            var owners = db.query("""
                SELECT shop_id,order_id FROM finance_first_settlement_facts
                WHERE organization_id=?
                """ + after + " ORDER BY shop_id,order_id LIMIT ? FOR UPDATE",
                (rs,row) -> new Owner(expected.organizationId(),rs.getString("shop_id"),rs.getString("order_id")),args.toArray());
            for (var owner : owners) {
                db.update("""
                    UPDATE finance_first_settlement_facts
                    SET requested_revision=requested_revision+IF(schema_version<>? OR applied_generation<>?
                        OR applied_registration_version<>?,1,0),
                      queued_generation=?,updated_at=UTC_TIMESTAMP(6)
                    WHERE organization_id=? AND shop_id=? AND order_id=? AND queued_generation<?
                    """,expected.targetSchemaVersion(),expected.generation(),expected.registrationVersion(),expected.generation(),
                    owner.organizationId(),owner.shopId(),owner.orderId(),expected.generation());
            }
            if (owners.isEmpty()) {
                db.update("UPDATE finance_first_settlement_initialization SET requeue_complete=TRUE,updated_at=UTC_TIMESTAMP(6) WHERE organization_id=?",expected.organizationId());
            } else {
                var last=owners.getLast();
                db.update("""
                    UPDATE finance_first_settlement_initialization
                    SET requeue_shop_after=?,requeue_order_after=?,updated_at=UTC_TIMESTAMP(6)
                    WHERE organization_id=?
                    """,last.shopId(),last.orderId(),expected.organizationId());
            }
            return new Batch(owners.size(),owners.isEmpty());
        });
    }

    boolean complete(Guard expected) {
        return repo.transaction(() -> {
            var locked = lockExpected(expected);
            if (locked.isEmpty() || !locked.get().shopsComplete() || !locked.get().requeueComplete()) return false;
            if (locked.get().guard().seedComplete()) return true;
            return db.update("""
                UPDATE finance_first_settlement_initialization SET seed_complete=TRUE,updated_at=UTC_TIMESTAMP(6)
                WHERE organization_id=? AND generation=? AND target_schema_version=? AND registration_version=?
                  AND shops_complete=TRUE AND requeue_complete=TRUE AND seed_complete=FALSE
                """,expected.organizationId(),expected.generation(),expected.targetSchemaVersion(),expected.registrationVersion()) == 1;
        });
    }

    /** Deliberately reads persisted old fields without requiring the new proof to match. */
    Optional<MaintenanceCompanyState> maintenanceState(String organizationId) {
        return company(organizationId,false).map(Company::maintenance);
    }

    AdvanceResult advance(MaintenanceCompanyState expected, Registration target) {
        Objects.requireNonNull(expected); Objects.requireNonNull(target);
        return repo.transaction(() -> {
            var proof = facts.lockRegistration();
            if (proof.isEmpty() || !proof.get().equals(target)) return AdvanceResult.REJECTED;
            var locked = company(expected.organizationId(),true);
            if (locked.isEmpty()) return AdvanceResult.REJECTED;
            var current=locked.get(); var guard=current.guard();
            if (guard.targetSchemaVersion()==target.schemaVersion() && guard.registrationVersion()==target.registrationVersion()) {
                return current.matches(target) ? AdvanceResult.ALREADY_TARGET : AdvanceResult.REJECTED;
            }
            if (!current.maintenance().equals(expected)) return AdvanceResult.STALE;
            if (target.schemaVersion()<guard.targetSchemaVersion() || target.registrationVersion()<=guard.registrationVersion()) return AdvanceResult.REJECTED;
            int updated=db.update("""
                UPDATE finance_first_settlement_initialization
                SET generation=generation+1,target_schema_version=?,registration_version=?,
                  registration_definition_sha=?,registration_boundary=?,seed_complete=FALSE,
                  shop_after=NULL,current_shop=NULL,order_after=NULL,shops_complete=FALSE,
                  requeue_shop_after=NULL,requeue_order_after=NULL,requeue_complete=FALSE,
                  updated_at=UTC_TIMESTAMP(6)
                WHERE organization_id=? AND generation=? AND target_schema_version=? AND registration_version=?
                """,target.schemaVersion(),target.registrationVersion(),target.definitionSha(),target.boundary(),
                expected.organizationId(),expected.generation(),expected.targetSchemaVersion(),expected.registrationVersion());
            return updated==1 ? AdvanceResult.ADVANCED : AdvanceResult.STALE;
        });
    }

    private Optional<Company> lockExpected(Guard expected) {
        Objects.requireNonNull(expected);
        var proof=facts.lockRegistration();
        if (proof.isEmpty()) return Optional.empty();
        return company(expected.organizationId(),true)
            .filter(company -> company.matches(proof.get()) && company.guard().equals(expected));
    }

    private Optional<Company> company(String organizationId, boolean lock) {
        return db.query("SELECT * FROM finance_first_settlement_initialization WHERE organization_id=?"
            + (lock ? " FOR UPDATE" : ""), (rs,row) -> new Company(
                new Guard(rs.getString("organization_id"),rs.getLong("generation"),rs.getInt("target_schema_version"),
                    rs.getInt("registration_version"),rs.getBoolean("seed_complete")),
                rs.getString("registration_definition_sha"),rs.getString("registration_boundary"),
                rs.getString("shop_after"),rs.getString("current_shop"),rs.getString("order_after"),rs.getBoolean("shops_complete"),
                rs.getString("requeue_shop_after"),rs.getString("requeue_order_after"),rs.getBoolean("requeue_complete")),organizationId)
            .stream().findFirst();
    }

    private static void requireLimit(int limit) {
        if (limit<=0) throw new IllegalArgumentException("Batch limit must be positive");
    }
}
