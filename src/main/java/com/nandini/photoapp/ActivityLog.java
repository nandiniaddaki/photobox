package com.nandini.photoapp;

import org.springframework.stereotype.Component;

import java.time.LocalTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * A tiny in-memory "server log" shown on the web page,
 * so you can watch cache hits, misses, invalidations, shard routing and rate limiting live.
 */
@Component
public class ActivityLog {

    public record Event(String time, String type, String message) {}

    private static final int MAX_EVENTS = 25;
    private final Deque<Event> events = new ArrayDeque<>();

    public synchronized void add(String type, String message) {
        String time = LocalTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.SECONDS).toString();
        events.addFirst(new Event(time, type, message));
        while (events.size() > MAX_EVENTS) {
            events.removeLast();
        }
        System.out.println("[" + type + "] " + message); // also visible in Render logs
    }

    public synchronized List<Event> recent() {
        return new ArrayList<>(events);
    }
}
