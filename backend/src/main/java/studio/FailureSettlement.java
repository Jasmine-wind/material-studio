package studio;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.concurrent.*;

/** Retries only FAILED writes, never production. All reservations and failures belong to this process. */
@Service
public class FailureSettlement {
    private static final Logger log = LoggerFactory.getLogger(FailureSettlement.class);
    private static final int CAPACITY = 128;
    private final StudioMapper mapper;
    private final Semaphore capacity = new Semaphore(CAPACITY);
    private final ConcurrentMap<String, Entry> entries = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        var thread = new Thread(r, "failure-settlement");
        thread.setDaemon(true);
        return thread;
    });
    private record Failure(String stage, String message) {}
    private static final class Entry {
        final String id;
        Failure failure;
        int attempts;
        long nextAttemptNanos;
        Entry(String id) { this.id = id; }
    }
    public FailureSettlement(StudioMapper mapper) { this.mapper = mapper; }

    @PostConstruct
    void start() { scheduler.scheduleWithFixedDelay(this::retry, 250, 250, TimeUnit.MILLISECONDS); }

    /** Reserve before INSERT, so even executor rejection during an outage has bounded, guaranteed space. */
    public void reserve(String id) {
        if (!capacity.tryAcquire()) {
            log.warn("Failure settlement capacity exhausted ({} reservations); refusing a new task before INSERT", CAPACITY);
            throw new ApiError(HttpStatus.SERVICE_UNAVAILABLE, "FAILURE_SETTLEMENT_FULL", "失败结算积压，暂不接受新任务，请稍后再提交");
        }
        if (entries.putIfAbsent(id, new Entry(id)) != null) {
            capacity.release();
            throw new IllegalStateException("Duplicate task reservation: " + id);
        }
    }
    public void complete(String id) {
        if (entries.remove(id) != null) capacity.release();
    }
    public void fail(String id, String stage, String message) {
        var entry = entries.get(id);
        if (entry == null) return; // Already settled; a stale caller cannot reopen it.
        synchronized (entry) {
            if (entry.failure == null) {
                String reason = message == null ? "制作异常，任务已停止" : message;
                entry.failure = new Failure(stage, reason.substring(0, Math.min(reason.length(), 500)));
            }
            attempt(entry);
        }
    }
    private void retry() {
        for (var entry : entries.values()) {
            synchronized (entry) { attempt(entry); }
        }
    }
    private void attempt(Entry entry) {
        if (entries.get(entry.id) != entry || entry.failure == null) return;
        if (entry.attempts > 0 && System.nanoTime() - entry.nextAttemptNanos < 0) return;
        entry.attempts++;
        try {
            // Existing conditional SQL protects both SUCCEEDED and FAILED. Zero rows also ends settlement.
            int changed = mapper.fail(entry.id, entry.failure.stage(), entry.failure.message());
            complete(entry.id);
            log.info("Failure settled: task={} failedStage={} attempts={} changed={}",
                    entry.id, entry.failure.stage(), entry.attempts, changed);
        } catch (RuntimeException e) {
            long delaySeconds = Math.min(30L, 1L << Math.min(entry.attempts - 1, 5));
            entry.nextAttemptNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(delaySeconds);
            log.warn("Failure write pending: task={} failedStage={} attempt={} retryInSeconds={} reservations={} cause={}",
                    entry.id, entry.failure.stage(), entry.attempts, delaySeconds, entries.size(), e.toString());
        }
    }
    @PreDestroy
    void stop() {
        scheduler.shutdownNow();
        if (!entries.isEmpty()) log.warn("Stopping with {} task reservations; in-memory settlement is not restart recovery", entries.size());
    }
}
