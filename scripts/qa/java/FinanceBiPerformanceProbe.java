package com.yingdao.aicommerce.finance;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import tools.jackson.databind.ObjectMapper;
import static com.yingdao.aicommerce.finance.FinanceData.*;

/** Explicit read-only benchmark; only its random, bounded Redis namespace is written. */
public final class FinanceBiPerformanceProbe {
    static final class TimedJdbc extends JdbcTemplate {
        int ledgerScans, costScans;
        TimedJdbc(Connection connection) { super(new SingleConnectionDataSource(connection, true)); }
        @Override public List<Map<String,Object>> queryForList(String sql, Object... args) {
            if (sql.contains("GROUP BY shop_id,record_date,kind,currency")) ledgerScans++;
            if (sql.contains("GROUP BY r.shop_id,r.record_date,r.currency,o.currency,o.status")) costScans++;
            return super.queryForList(sql, args);
        }
    }

    static Object canonical(Object value) {
        if (value instanceof Map<?,?> map) {
            var sorted = new TreeMap<String,Object>();
            map.forEach((key, item) -> sorted.put(String.valueOf(key), canonical(item)));
            return sorted;
        }
        if (value instanceof List<?> list) return list.stream().map(FinanceBiPerformanceProbe::canonical).toList();
        if (value instanceof Number number) return new BigDecimal(number.toString()).stripTrailingZeros().toPlainString();
        return value;
    }

    static String amountHash(Map<String,Object> value, ObjectMapper json) throws Exception {
        var metrics = new TreeMap<String,Object>();
        for (String key : List.of("totals", "trend", "ranking", "fees", "statuses", "profit", "productCosts", "currencies"))
            metrics.put(key, canonical(value.get(key)));
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json.writeValueAsBytes(metrics)));
    }

    static Map<String,Object> measure(String operation, FinanceService service, Scope scope, Filter filter,
                                      boolean cny, String fxId, TimedJdbc db, ObjectMapper json) throws Exception {
        int ledger = db.ledgerScans, costs = db.costScans;
        long start = System.nanoTime();
        var response = service.dashboard(scope, filter, cny, fxId);
        long millis = (System.nanoTime() - start) / 1_000_000;
        return Map.of("operation", operation, "millis", millis,
                "responseBytes", json.writeValueAsBytes(response).length,
                "ledgerScans", db.ledgerScans - ledger, "costScans", db.costScans - costs,
                "financialHash", amountHash(response, json), "refreshing", map(response.get("cache")).getOrDefault("refreshing", false));
    }

    public static void main(String[] args) throws Exception {
        if (!"true".equals(System.getenv("FINANCE_PERF_READ_ONLY")))
            throw new IllegalStateException("Explicit FINANCE_PERF_READ_ONLY=true is required");
        var json = new ObjectMapper();
        var configuration = new RedisStandaloneConfiguration(System.getenv().getOrDefault("REDIS_HOST", "redis"),
                Integer.parseInt(System.getenv().getOrDefault("REDIS_PORT", "6379")));
        String password = System.getenv("REDIS_PASSWORD");
        if (password != null && !password.isEmpty()) configuration.setPassword(password);
        var factory = new LettuceConnectionFactory(configuration, LettuceClientConfiguration.builder()
                .commandTimeout(Duration.ofSeconds(3)).shutdownTimeout(Duration.ZERO).build());
        factory.afterPropertiesSet();factory.start();
        String prefix = "qa:finance-performance:" + UUID.randomUUID() + ":";
        var redis = new StringRedisTemplate(factory);
        redis.setKeySerializer(new RedisSerializer<String>() {
            @Override public byte[] serialize(String key) { return key == null ? null : (prefix + key).getBytes(StandardCharsets.UTF_8); }
            @Override public String deserialize(byte[] bytes) {
                if (bytes == null) return null;
                String key = new String(bytes, StandardCharsets.UTF_8);
                if (!key.startsWith(prefix)) throw new IllegalStateException("Unexpected cache namespace");
                return key.substring(prefix.length());
            }
        });
        redis.afterPropertiesSet();
        var offset = new AtomicLong();
        var background = new ArrayDeque<Runnable>();
        var cache = new FinanceReadCache(redis, json, () -> System.currentTimeMillis() + offset.get(), background::add, false);
        var output = new LinkedHashMap<String,Object>();
        try (var connection = DriverManager.getConnection(System.getenv("DB_URL"), System.getenv("DB_USERNAME"), System.getenv("DB_PASSWORD"))) {
            connection.setReadOnly(true);
            connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            var db = new TimedJdbc(connection);db.setQueryTimeout(90);
            db.execute("SET SESSION MAX_EXECUTION_TIME=90000");
            db.execute("SET SESSION TRANSACTION READ ONLY");
            var organization = db.queryForObject("SELECT organization_id FROM finance_connections WHERE status<>'REMOVED' GROUP BY organization_id ORDER BY COUNT(*) DESC LIMIT 1", String.class);
            var fxId = db.queryForObject("SELECT id FROM finance_fx_snapshots ORDER BY rate_time DESC LIMIT 1", String.class);
            if (fxId == null || fxId.isBlank()) throw new IllegalStateException("An existing FX snapshot is required");
            var repo = new FinanceRepository(db, json);
            var service = new FinanceService(repo, null, null, new FinanceFx(repo, json));
            var field = FinanceService.class.getDeclaredField("sharedSnapshots");field.setAccessible(true);field.set(service, cache);
            var month = args.length == 0 ? YearMonth.now(ZoneId.of("Asia/Shanghai")).minusMonths(1) : YearMonth.parse(args[0]);
            var scope = companyScope(organization);
            var filter = new Filter(month.atDay(1), month.atEndOfMonth(), "", "", "", "day");
            var measurements = new ArrayList<Map<String,Object>>();
            measurements.add(measure("cold-month", service, scope, filter, true, fxId, db, json));
            measurements.add(measure("repeat-month", service, scope, filter, true, fxId, db, json));
            if (!measurements.get(0).get("financialHash").equals(measurements.get(1).get("financialHash")))
                throw new IllegalStateException("Warm cache changed financial metrics");
            output.put("warmFinancialMetricsEqual", true);
            var cacheSizes = new ArrayList<Map<String,Object>>();
            for (String key : Objects.requireNonNull(redis.keys("heyi:finance:snapshot:*"))) {
                if (redis.type(key) != org.springframework.data.redis.connection.DataType.STRING || key.endsWith("revision")) continue;
                String value = redis.opsForValue().get(key);
                cacheSizes.add(Map.of("facts", key.endsWith(":facts"), "bytes", value.getBytes(StandardCharsets.UTF_8).length,
                        "compressed", value.startsWith("gz:")));
            }
            output.put("cacheEntries", cacheSizes);
            if (args.length > 1 && args[1].equals("switch")) {
                offset.addAndGet(20_000);cache.refresh(scope);
                var shop = db.queryForObject("SELECT id FROM finance_connections WHERE organization_id=? AND status<>'REMOVED' ORDER BY id LIMIT 1", String.class, organization);
                measurements.add(measure("shop-after-20s-and-worker-change", service, scope,
                        new Filter(filter.start(), filter.end(), shop, "", "", "day"), true, fxId, db, json));
            }
            output.put("measurements", measurements);output.put("deferredBackgroundTasks", background.size());
            output.put("databaseReadOnly", connection.isReadOnly());output.put("productionWrites", false);
        } finally {
            cache.stop();
            var keys = redis.keys("*");
            if (keys != null && !keys.isEmpty()) redis.delete(keys);
            factory.destroy();
        }
        System.out.println(json.writeValueAsString(output));
    }
}
