package com.nandini.photoapp;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The "brain" of the app. This is where the system design concepts live:
 *   1. CACHE-ASIDE   - getFeed(): check Redis first, go to the DB only on a miss
 *   2. TTL           - cached feed expires automatically after N seconds
 *   3. INVALIDATION  - upload()/delete() remove the cached feed so nobody sees stale data
 *   4. RATE LIMITING - allowUpload(): max N uploads per user per minute, counted in Redis
 *   5. GRACEFUL FALLBACK - if Redis is down, the app still works (just slower)
 */
@Service
public class PhotoService {

    public static final String FEED_KEY = "feed:latest";
    private static final Set<String> ALLOWED_TYPES = Set.of("image/jpeg", "image/png", "image/gif", "image/webp");

    public record FeedResult(List<PhotoMeta> photos, boolean cacheHit, long millis, long ttlSeconds) {
        public long countInShard(int shard) {
            return photos.stream().filter(p -> p.shard() == shard).count();
        }
    }

    public static class UploadRejectedException extends RuntimeException {
        public UploadRejectedException(String message) {
            super(message);
        }
    }

    private final PhotoRepository repo;
    private final StringRedisTemplate redis;
    private final ObjectMapper json;
    private final ActivityLog log;
    private final Duration feedTtl;
    private final int maxUploads;
    private final Duration rateWindow;
    private final int maxPhotos;

    public PhotoService(PhotoRepository repo, StringRedisTemplate redis, ObjectMapper json, ActivityLog log,
                        @Value("${app.cache.feed-ttl-seconds}") long feedTtlSeconds,
                        @Value("${app.rate-limit.max-uploads}") int maxUploads,
                        @Value("${app.rate-limit.window-seconds}") long rateWindowSeconds,
                        @Value("${app.max-photos}") int maxPhotos) {
        this.repo = repo;
        this.redis = redis;
        this.json = json;
        this.log = log;
        this.feedTtl = Duration.ofSeconds(feedTtlSeconds);
        this.maxUploads = maxUploads;
        this.rateWindow = Duration.ofSeconds(rateWindowSeconds);
        this.maxPhotos = maxPhotos;
    }

    // ------------------------------------------------------------------
    // 1 + 2. CACHE-ASIDE with TTL
    // ------------------------------------------------------------------
    public FeedResult getFeed() {
        long start = System.nanoTime();

        // Step 1: look in the cache
        String cached = readCache();
        if (cached != null) {
            List<PhotoMeta> photos = fromJson(cached);
            if (photos != null) {
                long ms = elapsedMs(start);
                long ttl = ttlSeconds();
                log.add("CACHE HIT", "Feed served from Redis in " + ms + " ms (expires in " + ttl + "s)");
                return new FeedResult(photos, true, ms, ttl);
            }
        }

        // Step 2: cache miss → go to the database (slow)
        log.add("CACHE MISS", "Feed not in Redis → asking the database");
        List<PhotoMeta> photos = repo.findLatest(maxPhotos);

        // Step 3: save it in the cache with a TTL, so the next requests are fast
        writeCache(photos);
        long ms = elapsedMs(start);
        return new FeedResult(photos, false, ms, ttlSeconds());
    }

    // ------------------------------------------------------------------
    // 3. INVALIDATION on write
    // ------------------------------------------------------------------
    public String upload(String owner, MultipartFile file, String clientIp) {
        if (!allowUpload(clientIp)) {
            throw new UploadRejectedException("Too many uploads! Max " + maxUploads + " per "
                    + rateWindow.toSeconds() + " seconds. Konchem wait chesi try cheyyandi.");
        }
        if (file == null || file.isEmpty()) {
            throw new UploadRejectedException("Please choose a photo first.");
        }
        String contentType = file.getContentType();
        if (contentType == null || !ALLOWED_TYPES.contains(contentType)) {
            throw new UploadRejectedException("Only JPG, PNG, GIF or WEBP images are allowed.");
        }
        if (repo.countAll() >= maxPhotos) {
            throw new UploadRejectedException("Album is full (" + maxPhotos + " photos). Delete one of yours first.");
        }

        byte[] data;
        try {
            data = file.getBytes();
        } catch (IOException e) {
            throw new UploadRejectedException("Could not read the file.");
        }

        String id = UUID.randomUUID().toString();
        repo.save(id, cleanOwner(owner), cleanFilename(file.getOriginalFilename()), contentType, data);
        invalidateFeed("new photo uploaded");
        return id;
    }

    public boolean delete(String id) {
        boolean deleted = repo.delete(id);
        if (deleted) {
            invalidateFeed("photo deleted");
        }
        return deleted;
    }

    public Optional<PhotoRepository.StoredImage> findImage(String id) {
        return repo.findImage(id);
    }

    private void invalidateFeed(String reason) {
        try {
            redis.delete(FEED_KEY);
            log.add("INVALIDATE", "Deleted '" + FEED_KEY + "' from Redis because: " + reason);
        } catch (Exception e) {
            log.add("REDIS DOWN", "Could not invalidate cache: " + e.getClass().getSimpleName());
        }
    }

    // ------------------------------------------------------------------
    // 4. RATE LIMITING (fixed window counter in Redis)
    //    INCR rate:upload:<user>  → if it's the first hit, EXPIRE it after the window
    // ------------------------------------------------------------------
    private boolean allowUpload(String clientIp) {
        String key = "rate:upload:" + Integer.toHexString(clientIp.hashCode()); // don't store raw IPs
        try {
            Long count = redis.opsForValue().increment(key);
            if (count != null && count == 1) {
                redis.expire(key, rateWindow);
            }
            if (count != null && count > maxUploads) {
                log.add("RATE LIMIT", "Blocked an upload: this user did " + count + " uploads in "
                        + rateWindow.toSeconds() + "s (limit " + maxUploads + ")");
                return false;
            }
            log.add("RATE LIMIT", "Upload allowed (" + count + "/" + maxUploads + " in this window)");
            return true;
        } catch (Exception e) {
            // 5. Fail open: if Redis is down we let uploads through rather than breaking the app
            log.add("REDIS DOWN", "Rate limiter unavailable, allowing upload");
            return true;
        }
    }

    // ------------------------------------------------------------------
    // Redis helpers (all wrapped so a Redis outage never crashes the page)
    // ------------------------------------------------------------------
    private String readCache() {
        try {
            return redis.opsForValue().get(FEED_KEY);
        } catch (Exception e) {
            log.add("REDIS DOWN", "Cache read failed, falling back to DB: " + e.getClass().getSimpleName());
            return null;
        }
    }

    private void writeCache(List<PhotoMeta> photos) {
        try {
            redis.opsForValue().set(FEED_KEY, json.writeValueAsString(photos), feedTtl);
            log.add("CACHE SET", "Saved feed in Redis with TTL " + feedTtl.toSeconds() + "s");
        } catch (Exception e) {
            log.add("REDIS DOWN", "Cache write failed: " + e.getClass().getSimpleName());
        }
    }

    private long ttlSeconds() {
        try {
            Long ttl = redis.getExpire(FEED_KEY);
            return ttl == null ? -2 : ttl;
        } catch (Exception e) {
            return -2;
        }
    }

    private List<PhotoMeta> fromJson(String value) {
        try {
            return json.readValue(value, new TypeReference<List<PhotoMeta>>() {});
        } catch (Exception e) {
            return null;
        }
    }

    private static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    private static String cleanOwner(String owner) {
        if (owner == null || owner.isBlank()) return "anonymous";
        String trimmed = owner.trim();
        return trimmed.length() > 30 ? trimmed.substring(0, 30) : trimmed;
    }

    private static String cleanFilename(String name) {
        if (name == null || name.isBlank()) return "photo";
        return name.length() > 120 ? name.substring(0, 120) : name;
    }
}
