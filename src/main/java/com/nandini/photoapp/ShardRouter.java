package com.nandini.photoapp;

import org.springframework.stereotype.Component;

/**
 * SHARDING: decides which shard (table) a photo lives in.
 *
 *   shard = hash(photoId) % SHARD_COUNT
 *
 * Experiment idea: change SHARD_COUNT to 3 and think about what happens
 * to photos that were already saved with "% 2". (Most of them would be looked up
 * in the wrong shard!) That problem is why consistent hashing exists.
 */
@Component
public class ShardRouter {

    public static final int SHARD_COUNT = 2;

    public int shardFor(String photoId) {
        return Math.floorMod(photoId.hashCode(), SHARD_COUNT);
    }

    public String tableForShard(int shard) {
        return "photos_" + shard;
    }

    public String tableFor(String photoId) {
        return tableForShard(shardFor(photoId));
    }
}
