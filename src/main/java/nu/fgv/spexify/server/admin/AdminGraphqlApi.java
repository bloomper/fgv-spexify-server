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

package nu.fgv.spexify.server.admin;

import nu.fgv.spexify.server.audit.AuditEntry;
import nu.fgv.spexify.server.audit.AuditService;
import nu.fgv.spexify.server.catalog.Location;
import nu.fgv.spexify.server.catalog.TrackRepository;
import nu.fgv.spexify.server.navidrome.LibraryScanner;
import nu.fgv.spexify.server.settings.SettingsService;
import nu.fgv.spexify.server.settings.SettingsService.SettingsDto;
import nu.fgv.spexify.server.user.CallerService;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Page;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.MutationMapping;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * GraphQL for settings, Navidrome's state and the audit log.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Controller
public class AdminGraphqlApi {

    private final SettingsService settingsService;
    private final AuditService auditService;
    private final LibraryScanner scanner;
    private final TrackRepository trackRepository;
    private final CallerService callerService;

    public AdminGraphqlApi(final SettingsService settingsService, final AuditService auditService,
                           final LibraryScanner scanner, final TrackRepository trackRepository,
                           final CallerService callerService) {
        this.settingsService = settingsService;
        this.auditService = auditService;
        this.scanner = scanner;
        this.trackRepository = trackRepository;
        this.callerService = callerService;
    }

    @QueryMapping
    public SettingsDto settings() {
        return settingsService.read();
    }

    @MutationMapping
    public SettingsDto settingsUpdate(@Argument final SettingsDto input) {
        return settingsService.update(input);
    }

    @QueryMapping
    @Transactional(readOnly = true)
    public LibraryStatusDto libraryStatus() {
        callerService.current().requireEditor();

        final LibraryScanner.Status status = scanner.status();

        return new LibraryStatusDto(status.available(), status.scanning(), status.pending(), status.lastScanAt(),
                status.lastError(), trackRepository.count(), trackRepository.countByLocation(Location.MUSIC));
    }

    @MutationMapping
    public @Nullable Void libraryRescan() {
        callerService.current().requireAdmin();
        scanner.requestScan();
        return null;
    }

    @QueryMapping
    public AuditPageDto auditLog(@Argument final @Nullable Integer page, @Argument final @Nullable Integer size) {
        final int p = page == null ? 0 : page;
        final int s = size == null ? 50 : size;
        final Page<AuditEntry> entries = auditService.page(p, s);

        return new AuditPageDto(entries.getContent().stream()
                .map(entry -> new AuditLogEntryDto(entry.getId(), entry.getAt(), entry.getActorName(), entry.getAction(),
                        entry.getEntityType(), entry.getEntityId(), entry.getSummary()))
                .toList(), entries.getTotalElements(), p, s);
    }

    public record LibraryStatusDto(boolean navidromeAvailable, boolean scanning, boolean scanPending,
                                   @Nullable Instant lastScanAt, @Nullable String lastScanError, long trackCount,
                                   long listedTrackCount) {
    }

    public record AuditPageDto(List<AuditLogEntryDto> entries, long totalCount, int page, int size) {
    }

    public record AuditLogEntryDto(Long id, Instant at, @Nullable String actorName, String action, String entityType,
                                   @Nullable String entityId, String summary) {
    }
}
