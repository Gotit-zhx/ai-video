package com.yingdao.aicommerce.product.application;

import java.util.*;
import java.security.SecureRandom;
import java.util.function.IntSupplier;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class ProductSkuNamingService {
    private final JdbcTemplate db;
    private final TransactionTemplate allocate;
    private final IntSupplier random;
    private static final SecureRandom RANDOM = new SecureRandom();

    @org.springframework.beans.factory.annotation.Autowired
    public ProductSkuNamingService(JdbcTemplate db, PlatformTransactionManager transactions) {
        this(db, transactions, () -> RANDOM.nextInt(26_000_000));
    }

    ProductSkuNamingService(JdbcTemplate db, PlatformTransactionManager transactions, IntSupplier random) {
        this.db = db;
        this.random = random;
        this.allocate = new TransactionTemplate(transactions);
        // Capture may perform model/image work. Keep code allocation in a short transaction.
        this.allocate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.allocate.setTimeout(10);
    }

    public List<Map<String,Object>> assign(String organization, String sourceIdentity,
                                          List<Map<String,Object>> requested, List<Map<String,Object>> previous) {
        var codes = new HashMap<String,String>();
        return ProductSkuNaming.assign(organization, sourceIdentity, requested, previous,
                identity -> codes.computeIfAbsent(identity, key -> resolve(organization, key)));
    }

    private String resolve(String organization, String identity) {
        for (int attempt = 0; attempt < 20; attempt++) {
            try {
                return Objects.requireNonNull(allocate.execute(status -> {
                    var existing = db.queryForList("""
                            SELECT style_code FROM product_sku_styles WHERE organization_id=? AND pattern_key=?
                            """, String.class, organization, identity);
                    if (!existing.isEmpty()) return existing.getFirst();
                    int number = random.getAsInt();
                    if (number < 0 || number >= 26_000_000) throw new IllegalStateException("随机款式编码不正确");
                    String result = (char) ('A' + number / 1_000_000)
                            + String.format(Locale.ROOT, "%06d", number % 1_000_000);
                    db.update("""
                            INSERT INTO product_sku_styles(organization_id,pattern_key,style_code) VALUES(?,?,?)
                            """, organization, identity, result);
                    return result;
                }));
            } catch (DuplicateKeyException collision) {
                // Same-pattern races resolve to the winner; actual random-code collisions get a new code.
            }
        }
        throw new IllegalStateException("款式编码冲突，请重试采集");
    }
}
