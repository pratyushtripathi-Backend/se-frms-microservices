package com.se.frms.fraudengine.geocode;

import com.se.frms.fraudengine.cache.GeocodeCache;
import com.se.frms.fraudengine.cache.GeocodeCache.PreloadResult;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Fills the location cache (memory + Redis) ahead of time for a list of coordinates, so
 * transactions at those places never wait for Geoapify (100-300 ms per new location).
 *
 * Runs in the background, one job at a time, at most frms.geocode.preload.per-second
 * Geoapify lookups per second (coordinates already cached cost nothing). Stops by itself
 * after 20 failures in a row (e.g. Geoapify's daily limit reached - 3,000 a day on the
 * free plan). It stores exactly what a transaction's own lookup would store, so fraud
 * results do not change.
 */
@Service
@Slf4j
public class GeocodePreloadService {

    public record Coordinate(BigDecimal latitude, BigDecimal longitude) {
    }

    public record Status(boolean running, int total, int processed, int alreadyCached, int resolved,
                         int noLocation, int failed, String stoppedReason, Instant startedAt, Instant finishedAt) {
    }

    private static final int MAX_FAILURES_IN_A_ROW = 20;

    private final GeocodeCache geocodeCache;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicInteger processed = new AtomicInteger();
    private final AtomicInteger alreadyCached = new AtomicInteger();
    private final AtomicInteger resolved = new AtomicInteger();
    private final AtomicInteger noLocation = new AtomicInteger();
    private final AtomicInteger failed = new AtomicInteger();
    private volatile int total;
    private volatile String stoppedReason;
    private volatile Instant startedAt;
    private volatile Instant finishedAt;

    @Value("${frms.geocode.preload.per-second:5}")
    private double perSecond;

    public GeocodePreloadService(GeocodeCache geocodeCache) {
        this.geocodeCache = geocodeCache;
    }

    /** Starts a job; false if one is already running. */
    public boolean start(List<Coordinate> coordinates) {
        if (!running.compareAndSet(false, true)) {
            return false;
        }
        total = coordinates.size();
        processed.set(0);
        alreadyCached.set(0);
        resolved.set(0);
        noLocation.set(0);
        failed.set(0);
        stoppedReason = null;
        startedAt = Instant.now();
        finishedAt = null;
        Thread worker = new Thread(() -> run(List.copyOf(coordinates)), "geocode-preload");
        worker.setDaemon(true);
        worker.start();
        return true;
    }

    public Status status() {
        return new Status(running.get(), total, processed.get(), alreadyCached.get(), resolved.get(),
                noLocation.get(), failed.get(), stoppedReason, startedAt, finishedAt);
    }

    private void run(List<Coordinate> coordinates) {
        long pauseMs = perSecond > 0 ? (long) Math.ceil(1000 / perSecond) : 0;
        int failuresInARow = 0;
        try {
            for (Coordinate c : coordinates) {
                PreloadResult result = geocodeCache.preload(c.latitude(), c.longitude());
                processed.incrementAndGet();
                switch (result) {
                    case ALREADY_CACHED -> alreadyCached.incrementAndGet();
                    case RESOLVED -> resolved.incrementAndGet();
                    case NO_LOCATION -> noLocation.incrementAndGet();
                    case FAILED -> failed.incrementAndGet();
                }
                failuresInARow = result == PreloadResult.FAILED ? failuresInARow + 1 : 0;
                if (failuresInARow >= MAX_FAILURES_IN_A_ROW) {
                    stoppedReason = MAX_FAILURES_IN_A_ROW + " lookups failed in a row (Geoapify down or daily limit reached)";
                    log.warn("Geocode preload stopped: {}", stoppedReason);
                    return;
                }
                if (result != PreloadResult.ALREADY_CACHED && pauseMs > 0) {
                    Thread.sleep(pauseMs);
                }
            }
            log.info("Geocode preload finished total={}, alreadyCached={}, resolved={}, noLocation={}, failed={}",
                    total, alreadyCached.get(), resolved.get(), noLocation.get(), failed.get());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            stoppedReason = "interrupted";
        } catch (RuntimeException ex) {
            stoppedReason = ex.getMessage();
            log.error("Geocode preload stopped", ex);
        } finally {
            finishedAt = Instant.now();
            running.set(false);
        }
    }
}
