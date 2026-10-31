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

package nu.fgv.spexify.server.settings;

import nu.fgv.spexify.server.audit.AuditService;
import nu.fgv.spexify.server.user.Caller;
import nu.fgv.spexify.server.user.CallerService;
import nu.fgv.spexify.server.util.error.BadRequestException;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Clock;

/**
 * Reads and changes the settings.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Service
public class SettingsService {

    private final SettingsRepository repository;
    private final CallerService callerService;
    private final AuditService auditService;
    private final Clock clock;

    public SettingsService(final SettingsRepository repository, final CallerService callerService,
                           final AuditService auditService, final Clock clock) {
        this.repository = repository;
        this.callerService = callerService;
        this.auditService = auditService;
        this.clock = clock;
    }

    /**
     * The settings, without checking the caller, for the gateway and other services.
     */
    @Transactional(readOnly = true)
    public Settings current() {
        return repository.findById(Settings.ID).orElseGet(Settings::new);
    }

    @Transactional(readOnly = true)
    public SettingsDto read() {
        callerService.current().requireUser();
        return toDto(current());
    }

    @Transactional
    public SettingsDto update(final SettingsDto input) {
        final Caller caller = callerService.current();

        caller.requireAdmin();
        if (input.appPasswordExpiryMonths() < 1 || input.appPasswordExpiryMonths() > 120
                || input.lockoutAttempts() < 3 || input.lockoutAttempts() > 1000
                || input.lockoutMinutes() < 1 || input.lockoutMinutes() > 24 * 60) {
            throw new BadRequestException("error.settings.invalid");
        }

        final Settings settings = repository.findById(Settings.ID).orElseGet(Settings::new);

        settings.setWelcomeSv(text(input.welcomeSv()));
        settings.setWelcomeEn(text(input.welcomeEn()));
        settings.setAppPasswordExpiryMonths(input.appPasswordExpiryMonths());
        settings.setLockoutAttempts(input.lockoutAttempts());
        settings.setLockoutMinutes(input.lockoutMinutes());
        settings.setLastModifiedBy(caller.subject());
        settings.setLastModifiedAt(clock.instant());
        repository.save(settings);
        auditService.record("UPDATE", "SETTINGS", null, "Ändrade inställningarna");
        return toDto(settings);
    }

    private static @Nullable String text(final @Nullable String value) {
        return StringUtils.hasText(value) ? value.strip() : null;
    }

    private static SettingsDto toDto(final Settings settings) {
        return new SettingsDto(settings.getWelcomeSv(), settings.getWelcomeEn(), settings.getAppPasswordExpiryMonths(),
                settings.getLockoutAttempts(), settings.getLockoutMinutes());
    }

    /**
     * The settings as the GraphQL API shows and takes them.
     */
    public record SettingsDto(@Nullable String welcomeSv, @Nullable String welcomeEn, int appPasswordExpiryMonths,
                              int lockoutAttempts, int lockoutMinutes) {
    }
}
