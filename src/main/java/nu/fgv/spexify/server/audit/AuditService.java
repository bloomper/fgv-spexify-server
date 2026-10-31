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

package nu.fgv.spexify.server.audit;

import nu.fgv.spexify.server.user.Caller;
import nu.fgv.spexify.server.user.CallerService;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;

/**
 * Writes and reads the audit log. Editors see their own changes, admins everyone's.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Service
public class AuditService {

    private static final int MAX_SUMMARY = 1000;
    private static final int MAX_PAGE = 200;

    private final AuditEntryRepository repository;
    private final CallerService callerService;
    private final Clock clock;

    public AuditService(final AuditEntryRepository repository, final CallerService callerService, final Clock clock) {
        this.repository = repository;
        this.callerService = callerService;
        this.clock = clock;
    }

    @Transactional
    public void record(final String action, final String entityType, final @Nullable Long entityId, final String summary) {
        final AuditEntry entry = new AuditEntry();
        final Caller caller = callerService.currentIfAny().orElse(null);

        entry.setAt(clock.instant());
        entry.setActorSubject(caller == null ? "system" : caller.subject());
        entry.setActorName(caller == null ? "Spexify" : caller.displayName());
        entry.setAction(action);
        entry.setEntityType(entityType);
        entry.setEntityId(entityId);
        entry.setSummary(summary.length() > MAX_SUMMARY ? summary.substring(0, MAX_SUMMARY) : summary);
        repository.save(entry);
    }

    @Transactional(readOnly = true)
    public Page<AuditEntry> page(final int page, final int size) {
        final Caller caller = callerService.current();
        final PageRequest request = PageRequest.of(Math.max(page, 0), Math.clamp(size, 1, MAX_PAGE));

        caller.requireEditor();
        return caller.isAdmin()
                ? repository.findAllByOrderByAtDesc(request)
                : repository.findByActorSubjectOrderByAtDesc(caller.subject(), request);
    }
}
