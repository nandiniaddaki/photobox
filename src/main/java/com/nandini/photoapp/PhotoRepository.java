package com.nandini.photoapp;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * All database work. Every method first asks the ShardRouter which table to use.
 */
@Repository
public class PhotoRepository {

    public record StoredImage(String contentType, byte[] data) {}

    private final JdbcTemplate jdbc;
    private final ShardRouter router;
    private final ActivityLog log;
    private final long slowQueryMs;

    public PhotoRepository(JdbcTemplate jdbc, ShardRouter router, ActivityLog log,
                           @Value("${app.db.slow-query-ms}") long slowQueryMs) {
        this.jdbc = jdbc;
        this.router = router;
        this.log = log;
        this.slowQueryMs = slowQueryMs;
    }

    /** Write goes to exactly ONE shard. */
    public void save(String id, String owner, String filename, String contentType, byte[] data) {
        int shard = router.shardFor(id);
        String table = router.tableForShard(shard);
        jdbc.update("INSERT INTO " + table
                        + " (id, owner, filename, content_type, size_bytes, created_at, data) VALUES (?, ?, ?, ?, ?, ?, ?)",
                id, owner, filename, contentType, data.length, System.currentTimeMillis(), data);
        log.add("SHARD", "New photo hash(id) % " + ShardRouter.SHARD_COUNT + " = " + shard + " → saved in table " + table);
    }

    /**
     * Feed = latest photos from ALL shards.
     * We don't know which shard has the newest photos, so we ask every shard and merge.
     * This is called "scatter-gather" and is the main cost of sharding.
     */
    public List<PhotoMeta> findLatest(int limit) {
        simulateHeavyQuery();
        List<PhotoMeta> all = new ArrayList<>();
        for (int shard = 0; shard < ShardRouter.SHARD_COUNT; shard++) {
            final int s = shard;
            List<PhotoMeta> fromShard = jdbc.query(
                    "SELECT id, owner, filename, size_bytes, created_at FROM " + router.tableForShard(s)
                            + " ORDER BY created_at DESC LIMIT ?",
                    (rs, rowNum) -> new PhotoMeta(
                            rs.getString("id"),
                            rs.getString("owner"),
                            rs.getString("filename"),
                            rs.getLong("size_bytes"),
                            rs.getLong("created_at"),
                            s),
                    limit);
            all.addAll(fromShard);
        }
        all.sort(Comparator.comparingLong(PhotoMeta::createdAtMillis).reversed());
        log.add("DB", "Scatter-gather: queried " + ShardRouter.SHARD_COUNT + " shards, merged " + all.size() + " photos");
        return all.size() > limit ? new ArrayList<>(all.subList(0, limit)) : all;
    }

    /** Read by id goes straight to the right shard - no need to ask the others. */
    public Optional<StoredImage> findImage(String id) {
        List<StoredImage> rows = jdbc.query(
                "SELECT content_type, data FROM " + router.tableFor(id) + " WHERE id = ?",
                (rs, rowNum) -> new StoredImage(rs.getString("content_type"), rs.getBytes("data")),
                id);
        return rows.stream().findFirst();
    }

    public boolean delete(String id) {
        int rows = jdbc.update("DELETE FROM " + router.tableFor(id) + " WHERE id = ?", id);
        return rows > 0;
    }

    public long countAll() {
        long total = 0;
        for (int shard = 0; shard < ShardRouter.SHARD_COUNT; shard++) {
            Long c = jdbc.queryForObject("SELECT COUNT(*) FROM " + router.tableForShard(shard), Long.class);
            total += (c == null ? 0 : c);
        }
        return total;
    }

    private void simulateHeavyQuery() {
        if (slowQueryMs <= 0) return;
        try {
            Thread.sleep(slowQueryMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
