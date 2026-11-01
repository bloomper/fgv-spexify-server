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

import nu.fgv.spexify.server.TestCallers;
import nu.fgv.spexify.server.TestClock;
import nu.fgv.spexify.server.audit.AuditService;
import nu.fgv.spexify.server.settings.SettingsService.SettingsDto;
import nu.fgv.spexify.server.user.CallerService;
import nu.fgv.spexify.server.util.error.BadRequestException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The settings admins keep, and their limits.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@ExtendWith(MockitoExtension.class)
class SettingsServiceTest {

    @Mock
    private SettingsRepository repository;
    @Mock
    private CallerService callerService;
    @Mock
    private AuditService auditService;
    private SettingsService service;

    @BeforeEach
    void setUp() {
        service = new SettingsService(repository, callerService, auditService, new TestClock(Instant.parse("2026-11-01T10:00:00Z")));
        lenient().when(repository.findById(Settings.ID)).thenReturn(Optional.empty());
    }

    @Test
    void withoutSavedSettingsTheDefaultsApply() {
        when(callerService.current()).thenReturn(TestCallers.listener());

        assertThat(service.read()).isEqualTo(new SettingsDto(null, null, 12, 10, 15));
    }

    @Test
    void anAdminSavesTrimmedTextsAndTheChangeIsAudited() {
        when(callerService.current()).thenReturn(TestCallers.admin());

        final SettingsDto saved = service.update(new SettingsDto("  Välkommen!  ", " ", 6, 5, 30));

        assertThat(saved).isEqualTo(new SettingsDto("Välkommen!", null, 6, 5, 30));
        verify(auditService).record("UPDATE", "SETTINGS", null, "Ändrade inställningarna");
    }

    @Test
    void valuesOutsideTheLimitsAreRefused() {
        when(callerService.current()).thenReturn(TestCallers.admin());

        assertThatThrownBy(() -> service.update(new SettingsDto(null, null, 0, 5, 30))).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> service.update(new SettingsDto(null, null, 121, 5, 30))).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> service.update(new SettingsDto(null, null, 6, 2, 30))).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> service.update(new SettingsDto(null, null, 6, 5, 1441))).isInstanceOf(BadRequestException.class);
    }

    @Test
    void onlyAdminsChangeTheSettings() {
        when(callerService.current()).thenReturn(TestCallers.editor());

        assertThatThrownBy(() -> service.update(new SettingsDto(null, null, 6, 5, 30))).isInstanceOf(AccessDeniedException.class);
    }
}
