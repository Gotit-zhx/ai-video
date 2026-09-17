package com.yingdao.aicommerce.product.application;

import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;
import java.util.*;

/** Immutable tenant-scoped derived assets. Cache identity includes every input and algorithm version. */
final class CommerceMockupDerivativeCache {
    record Entry(String key,String storageKey,String sha,Object report) {}
    private final JdbcTemplate db;
    private final CommerceImageArchive archive;
    private final ObjectMapper json;
    CommerceMockupDerivativeCache(JdbcTemplate db,CommerceImageArchive archive,ObjectMapper json) {
        this.db=db;this.archive=archive;this.json=json;
    }
    String key(String algorithm,Object inputs) {
        return CommerceImageArchive.sha(json.writeValueAsBytes(List.of(algorithm,inputs)));
    }
    Entry find(String org,String key) {
        var rows=db.queryForList("SELECT storage_key,content_sha256,report_json FROM commerce_mockup_derivatives WHERE organization_id=? AND cache_key=?",org,key);
        if(rows.isEmpty())return null;
        var row=rows.getFirst();String storage=(String)row.get("storage_key");
        archive.read(org,storage); // Read verifies tenant ownership and content hash on every reuse.
        if(!storage.endsWith("/"+row.get("content_sha256")))throw new IllegalStateException("派生素材索引与内容不一致，请检查归档");
        return new Entry(key,storage,(String)row.get("content_sha256"),json.readTree((String)row.get("report_json")));
    }
    Entry store(String org,String key,String kind,byte[] bytes,Object report) {
        var stored=archive.store(org,bytes);
        db.update("INSERT IGNORE INTO commerce_mockup_derivatives(organization_id,cache_key,kind,storage_key,content_sha256,report_json,created_at) VALUES(?,?,?,?,?,?,NOW(6))",
                org,key,kind,stored.storageKey(),stored.sha256(),json.writeValueAsString(report));
        // A racing worker reuses the first committed result; it never changes an existing cache entry.
        return find(org,key);
    }
}
