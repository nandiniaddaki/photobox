package com.nandini.photoapp;

/**
 * Photo details WITHOUT the image bytes.
 * This small object is what we put in the Redis cache (images themselves are too big).
 */
public record PhotoMeta(String id, String owner, String filename, long sizeBytes, long createdAtMillis, int shard) {

    public String sizeKb() {
        return (sizeBytes / 1024) + " KB";
    }

    public String ago() {
        long seconds = (System.currentTimeMillis() - createdAtMillis) / 1000;
        if (seconds < 60) return seconds + "s ago";
        if (seconds < 3600) return (seconds / 60) + "m ago";
        if (seconds < 86400) return (seconds / 3600) + "h ago";
        return (seconds / 86400) + "d ago";
    }
}
