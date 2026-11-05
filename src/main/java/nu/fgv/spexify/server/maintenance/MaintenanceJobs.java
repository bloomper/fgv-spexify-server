/*
 * Copyright 2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package nu.fgv.spexify.server.maintenance;

import lombok.extern.slf4j.Slf4j;
import nu.fgv.spexify.server.catalog.LoudnessScan;
import nu.fgv.spexify.server.listening.ListeningService;
import nu.fgv.spexify.server.navidrome.LibraryScanner;
import nu.fgv.spexify.server.spex.SpexService;
import nu.fgv.spexify.server.upload.UploadService;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Runs the maintenance jobs on their schedules, one run of each at a time, and remembers how the latest run of each
 * went, whether the schedule or ADMIN started it. The runs are kept in memory until the application restarts.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Slf4j
@Component
public class MaintenanceJobs {

    private final Map<MaintenanceJob, Runnable> work;
    private final Set<MaintenanceJob> running = ConcurrentHashMap.newKeySet();
    private final ConcurrentMap<MaintenanceJob, Run> lastRuns = new ConcurrentHashMap<>();
    private final AtomicBoolean syncedAfterStart = new AtomicBoolean();

    @Autowired
    public MaintenanceJobs(final SpexService spexService, final UploadService uploadService, final LoudnessScan loudnessScan,
                           final ListeningService listeningService) {
        this(Map.of(
                MaintenanceJob.SPEX_REFRESH, spexService::refresh,
                MaintenanceJob.UPLOAD_CLEANUP, uploadService::cleanUp,
                MaintenanceJob.LOUDNESS_SCAN, loudnessScan::run,
                MaintenanceJob.LISTENING_SYNC, listeningService::sync
        ));
    }

    MaintenanceJobs(final Map<MaintenanceJob, Runnable> work) {
        this.work = work;
    }

    @Scheduled(cron = "${spexify.jobs.spex-refresh.cron-expression}", zone = "${spexify.time-zone}")
    void spexRefresh() {
        run(MaintenanceJob.SPEX_REFRESH);
    }

    @Scheduled(cron = "${spexify.jobs.upload-cleanup.cron-expression}", zone = "${spexify.time-zone}")
    void uploadCleanup() {
        run(MaintenanceJob.UPLOAD_CLEANUP);
    }

    @Scheduled(cron = "${spexify.jobs.loudness-scan.cron-expression}", zone = "${spexify.time-zone}")
    void loudnessScan() {
        run(MaintenanceJob.LOUDNESS_SCAN);
    }

    @Scheduled(cron = "${spexify.jobs.listening-sync.cron-expression}", zone = "${spexify.time-zone}")
    void listeningSync() {
        run(MaintenanceJob.LISTENING_SYNC);
    }

    /**
     * Copies ratings, likes and plays once the library is first matched after a start. The gateway mirrors a change only
     * for a track whose Navidrome id is known, so what was done before the release, or with tracks not matched yet,
     * would otherwise wait for the night.
     */
    @EventListener
    void libraryMatched(final LibraryScanner.LibraryMatched event) {
        if (event.matched() > 0 && syncedAfterStart.compareAndSet(false, true)) {
            run(MaintenanceJob.LISTENING_SYNC);
        }
    }

    /**
     * Runs the job in the calling thread unless it is already running.
     *
     * @param job the job
     * @return whether it ran
     */
    public boolean run(final MaintenanceJob job) {
        if (!running.add(job)) {
            log.info("Not starting {}, it is already running", job);
            return false;
        }

        final Instant startedAt = Instant.now();
        boolean succeeded = false;

        lastRuns.put(job, new Run(startedAt, null, null));
        try {
            work.get(job).run();
            succeeded = true;
        } catch (final RuntimeException e) {
            log.error("{} failed", job, e);
        } finally {
            lastRuns.put(job, new Run(startedAt, Instant.now(), succeeded));
            running.remove(job);
        }
        return true;
    }

    /**
     * @param job the job
     * @return whether it is running
     */
    public boolean isRunning(final MaintenanceJob job) {
        return running.contains(job);
    }

    /**
     * @param job the job
     * @return its latest run since the application started
     */
    public Optional<Run> lastRun(final MaintenanceJob job) {
        return Optional.ofNullable(lastRuns.get(job));
    }

    /**
     * A run of a job.
     *
     * @param startedAt  when it started
     * @param finishedAt when it finished, or null while it runs
     * @param succeeded  whether it finished without an error, or null while it runs
     */
    public record Run(Instant startedAt, @Nullable Instant finishedAt, @Nullable Boolean succeeded) {
    }
}
