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

package nu.fgv.spexify.server.apppassword;

import nu.fgv.spexify.server.TestCallers;
import nu.fgv.spexify.server.TestClock;
import nu.fgv.spexify.server.audit.AuditService;
import nu.fgv.spexify.server.config.SpexifyProperties;
import nu.fgv.spexify.server.settings.Settings;
import nu.fgv.spexify.server.settings.SettingsService;
import nu.fgv.spexify.server.user.AccessChecker;
import nu.fgv.spexify.server.user.AppUser;
import nu.fgv.spexify.server.user.AppUserRepository;
import nu.fgv.spexify.server.user.CallerService;
import nu.fgv.spexify.server.util.error.BadRequestException;
import nu.fgv.spexify.server.util.error.ResourceNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * App passwords and API keys: creating, revoking, expiry and how the gateway checks them.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@ExtendWith(MockitoExtension.class)
class AppPasswordServiceTest {

    private final TestClock clock = new TestClock(Instant.parse("2026-11-01T10:00:00Z"));
    private final List<AppPassword> passwords = new ArrayList<>();
    private final List<ApiKey> apiKeys = new ArrayList<>();
    private final PasswordCrypto crypto = new PasswordCrypto(new SpexifyProperties(null, null, null, null, null,
            new SpexifyProperties.Crypto(Base64.getEncoder().encodeToString(new byte[32])), null, null, null));
    @Mock
    private AppPasswordRepository passwordRepository;
    @Mock
    private ApiKeyRepository apiKeyRepository;
    @Mock
    private AppUserRepository userRepository;
    @Mock
    private CallerService callerService;
    @Mock
    private SettingsService settingsService;
    @Mock
    private LoginThrottle throttle;
    @Mock
    private AccessChecker accessChecker;
    @Mock
    private AuditService auditService;
    private AppPasswordService service;
    private AppUser lisa;

    @BeforeEach
    void setUp() {
        service = new AppPasswordService(passwordRepository, apiKeyRepository, userRepository, callerService, crypto, settingsService,
                throttle, accessChecker, auditService, clock);
        lisa = new AppUser();
        lisa.setId(1L);
        lisa.setSubject("s-listener");
        lisa.setUsername("lisa@spexify.test");
        lenient().when(callerService.current()).thenReturn(TestCallers.listener());
        lenient().when(callerService.record(any())).thenReturn(lisa);
        lenient().when(settingsService.current()).thenReturn(new Settings());
        lenient().when(passwordRepository.save(any())).thenAnswer(invocation -> {
            final AppPassword password = invocation.getArgument(0);

            password.setId((long) passwords.size() + 1);
            passwords.add(password);
            return password;
        });
        lenient().when(passwordRepository.findByUserIdAndRevokedAtIsNullOrderByCreatedAtDesc(anyLong()))
                .thenAnswer(_ -> passwords.stream().filter(password -> password.getRevokedAt() == null).toList());
        lenient().when(passwordRepository.findById(anyLong())).thenAnswer(invocation ->
                passwords.stream().filter(password -> password.getId().equals(invocation.getArgument(0))).findFirst());
        lenient().when(apiKeyRepository.save(any())).thenAnswer(invocation -> {
            final ApiKey apiKey = invocation.getArgument(0);

            apiKey.setId((long) apiKeys.size() + 1);
            apiKeys.add(apiKey);
            return apiKey;
        });
        lenient().when(userRepository.findByUsername("lisa@spexify.test")).thenReturn(Optional.of(lisa));
    }

    @Test
    void aPasswordIsShownOnceWithTheUsernameAndStoredEncrypted() {
        final var created = service.create("  Telefon, Symfonium  ");

        assertThat(created.username()).isEqualTo("lisa@spexify.test");
        assertThat(created.appPassword().name()).isEqualTo("Telefon, Symfonium");
        assertThat(created.appPassword().expiresAt()).isEqualTo(Instant.parse("2027-11-01T10:00:00Z"));
        assertThat(new String(passwords.getFirst().getCiphertext(), java.nio.charset.StandardCharsets.ISO_8859_1)).doesNotContain(created.password());
        assertThat(crypto.decrypt(passwords.getFirst().getCiphertext())).isEqualTo(created.password());
        verify(accessChecker).forget("s-listener");
    }

    @Test
    void aPasswordNeedsANameAndAUserHasAtMost25() {
        assertThatThrownBy(() -> service.create(" ")).isInstanceOf(BadRequestException.class);

        when(passwordRepository.countByUserIdAndRevokedAtIsNull(1L)).thenReturn(25L);
        assertThatThrownBy(() -> service.create("Telefon")).isInstanceOf(BadRequestException.class);
    }

    @Test
    void withoutUserNoPasswordIsCreated() {
        when(callerService.current()).thenReturn(TestCallers.noAccess());

        assertThatThrownBy(() -> service.create("Telefon")).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void thePasswordChecksWithPasswordOrWithTokenAndSalt() {
        final String plain = service.create("Telefon").password();

        assertThat(service.verifyPassword("lisa@spexify.test", plain, null, null)).contains(lisa);
        assertThat(service.verifyPassword("lisa@spexify.test", null, PasswordCrypto.md5Hex(plain + "salt").toUpperCase(), "salt")).contains(lisa);
        assertThat(service.verifyPassword("lisa@spexify.test", "fel", null, null)).isEmpty();
        assertThat(service.verifyPassword("lisa@spexify.test", null, "token", null)).isEmpty();
        assertThat(service.verifyPassword("okänd@spexify.test", plain, null, null)).isEmpty();
        assertThat(passwords.getFirst().getLastUsedAt()).isEqualTo(clock.instant());
    }

    @Test
    void aPasswordUnusedForTheSetMonthsExpires() {
        final String plain = service.create("Telefon").password();

        clock.advance(Duration.ofDays(366));
        assertThat(service.verifyPassword("lisa@spexify.test", plain, null, null)).isEmpty();
        assertThat(service.list().getFirst().expired()).isTrue();
    }

    @Test
    void usingAPasswordPostponesItsExpiry() {
        final String plain = service.create("Telefon").password();

        clock.advance(Duration.ofDays(300));
        assertThat(service.verifyPassword("lisa@spexify.test", plain, null, null)).isPresent();
        clock.advance(Duration.ofDays(300));
        assertThat(service.verifyPassword("lisa@spexify.test", plain, null, null)).isPresent();
    }

    @Test
    void aRevokedPasswordNoLongerWorksAndOnlyTheOwnerRevokes() {
        final var created = service.create("Telefon");
        final AppUser other = new AppUser();

        other.setId(2L);
        when(callerService.record(any())).thenReturn(other);
        assertThatThrownBy(() -> service.revoke(created.appPassword().id())).isInstanceOf(ResourceNotFoundException.class);

        when(callerService.record(any())).thenReturn(lisa);
        service.revoke(created.appPassword().id());
        assertThat(service.verifyPassword("lisa@spexify.test", created.password(), null, null)).isEmpty();
        assertThatThrownBy(() -> service.revoke(created.appPassword().id())).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void anApiKeyIsStoredOnlyAsAHashAndFoundByIt() {
        final var created = service.apiKeyCreate("Feishin");
        final ApiKey stored = apiKeys.getFirst();

        assertThat(stored.getKeyHash()).isEqualTo(PasswordCrypto.sha256Hex(created.key())).isNotEqualTo(created.key());
        when(apiKeyRepository.findByKeyHashAndRevokedAtIsNull(stored.getKeyHash())).thenReturn(Optional.of(stored));
        assertThat(service.verifyApiKey(created.key())).contains(lisa);
        assertThat(stored.getLastUsedAt()).isEqualTo(clock.instant());
    }

    @Test
    void adminsRevokeEveryCredentialOfAUserAndLiftLockOuts() {
        service.create("Telefon");
        when(callerService.current()).thenReturn(TestCallers.admin());
        when(userRepository.findById(1L)).thenReturn(Optional.of(lisa));

        service.revokeAll(1L);
        service.unlock(1L);

        assertThat(passwords).allSatisfy(password -> assertThat(password.getRevokedAt()).isNotNull());
        verify(throttle).unlock("lisa@spexify.test");
        verify(auditService).record("REVOKE", "USER", 1L, "Återkallade alla applösenord och nycklar för lisa@spexify.test");
    }

    @Test
    void onlyAdminsSeeTheUsers() {
        assertThatThrownBy(() -> service.users()).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> service.revokeAll(1L)).isInstanceOf(AccessDeniedException.class);
    }
}
