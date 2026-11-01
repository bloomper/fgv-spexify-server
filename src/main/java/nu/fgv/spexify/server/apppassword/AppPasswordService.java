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

import lombok.extern.slf4j.Slf4j;
import nu.fgv.spexify.server.audit.AuditService;
import nu.fgv.spexify.server.settings.SettingsService;
import nu.fgv.spexify.server.user.AccessChecker;
import nu.fgv.spexify.server.user.AppUser;
import nu.fgv.spexify.server.user.AppUserRepository;
import nu.fgv.spexify.server.user.Caller;
import nu.fgv.spexify.server.user.CallerService;
import nu.fgv.spexify.server.util.error.BadRequestException;
import nu.fgv.spexify.server.util.error.ResourceNotFoundException;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * App passwords and API keys: creating, listing and revoking one's own, revoking anyone's as admin, and checking them
 * for the Subsonic gateway.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Slf4j
@Service
public class AppPasswordService {

    private static final int MAX_NAME = 100;
    private static final int MAX_PER_USER = 25;
    private static final Duration LAST_USED_RESOLUTION = Duration.ofMinutes(10);

    private final AppPasswordRepository passwordRepository;
    private final ApiKeyRepository apiKeyRepository;
    private final AppUserRepository userRepository;
    private final CallerService callerService;
    private final PasswordCrypto crypto;
    private final SettingsService settingsService;
    private final LoginThrottle throttle;
    private final AccessChecker accessChecker;
    private final AuditService auditService;
    private final Clock clock;

    public AppPasswordService(final AppPasswordRepository passwordRepository, final ApiKeyRepository apiKeyRepository,
                              final AppUserRepository userRepository, final CallerService callerService,
                              final PasswordCrypto crypto, final SettingsService settingsService,
                              final LoginThrottle throttle, final AccessChecker accessChecker,
                              final AuditService auditService, final Clock clock) {
        this.passwordRepository = passwordRepository;
        this.apiKeyRepository = apiKeyRepository;
        this.userRepository = userRepository;
        this.callerService = callerService;
        this.crypto = crypto;
        this.settingsService = settingsService;
        this.throttle = throttle;
        this.accessChecker = accessChecker;
        this.auditService = auditService;
        this.clock = clock;
    }

    // ---------------------------------------------------------------------------------------------- own credentials

    private static String name(final String name) {
        if (!StringUtils.hasText(name)) {
            throw new BadRequestException("error.appPassword.nameRequired");
        }

        final String stripped = name.strip();

        return stripped.length() > MAX_NAME ? stripped.substring(0, MAX_NAME) : stripped;
    }

    private static ApiKeyDto toDto(final ApiKey apiKey) {
        return new ApiKeyDto(apiKey.getUuid(), apiKey.getName(), apiKey.getCreatedAt(), apiKey.getLastUsedAt());
    }

    @Transactional
    public List<AppPasswordDto> list() {
        final AppUser user = currentUser();
        final int months = settingsService.current().getAppPasswordExpiryMonths();

        return passwordRepository.findByUserIdAndRevokedAtIsNullOrderByCreatedAtDesc(user.getId()).stream()
                .map(password -> toDto(password, months))
                .toList();
    }

    @Transactional
    public AppPasswordCreatedDto create(final String name) {
        final AppUser user = currentUser();

        if (passwordRepository.countByUserIdAndRevokedAtIsNull(user.getId()) >= MAX_PER_USER) {
            throw new BadRequestException("error.appPassword.tooMany");
        }

        final String plain = crypto.newPassword();
        final AppPassword password = new AppPassword();

        password.setUser(user);
        password.setName(name(name));
        password.setCiphertext(crypto.encrypt(plain));
        password.setCreatedAt(clock.instant());
        passwordRepository.save(password);
        accessChecker.forget(user.getSubject());
        return new AppPasswordCreatedDto(toDto(password, settingsService.current().getAppPasswordExpiryMonths()), plain, user.getUsername());
    }

    /**
     * For the sample data import: an app password with a known value.
     */
    @Transactional
    public AppPassword createWithValue(final AppUser user, final String name, final String plain, final Instant createdAt) {
        final AppPassword password = new AppPassword();

        password.setUser(user);
        password.setName(name);
        password.setCiphertext(crypto.encrypt(plain));
        password.setCreatedAt(createdAt);
        return passwordRepository.save(password);
    }

    @Transactional
    public void revoke(final UUID id) {
        final AppUser user = currentUser();
        final AppPassword password = passwordRepository.findByUuid(id)
                .filter(candidate -> candidate.getUser().getId().equals(user.getId()) && candidate.getRevokedAt() == null)
                .orElseThrow(() -> new ResourceNotFoundException("error.appPassword.notFound", id));

        password.setRevokedAt(clock.instant());
    }

    @Transactional
    public List<ApiKeyDto> apiKeys() {
        final AppUser user = currentUser();

        return apiKeyRepository.findByUserIdAndRevokedAtIsNullOrderByCreatedAtDesc(user.getId()).stream()
                .map(AppPasswordService::toDto)
                .toList();
    }

    @Transactional
    public ApiKeyCreatedDto apiKeyCreate(final String name) {
        final AppUser user = currentUser();

        if (apiKeyRepository.countByUserIdAndRevokedAtIsNull(user.getId()) >= MAX_PER_USER) {
            throw new BadRequestException("error.appPassword.tooMany");
        }

        final String plain = crypto.newApiKey();
        final ApiKey apiKey = new ApiKey();

        apiKey.setUser(user);
        apiKey.setName(name(name));
        apiKey.setKeyHash(PasswordCrypto.sha256Hex(plain));
        apiKey.setCreatedAt(clock.instant());
        apiKeyRepository.save(apiKey);
        return new ApiKeyCreatedDto(toDto(apiKey), plain);
    }

    // ---------------------------------------------------------------------------------------------------- admin

    @Transactional
    public void apiKeyRevoke(final UUID id) {
        final AppUser user = currentUser();
        final ApiKey apiKey = apiKeyRepository.findByUuid(id)
                .filter(candidate -> candidate.getUser().getId().equals(user.getId()) && candidate.getRevokedAt() == null)
                .orElseThrow(() -> new ResourceNotFoundException("error.apiKey.notFound", id));

        apiKey.setRevokedAt(clock.instant());
    }

    private AppUser currentUser() {
        final Caller caller = callerService.current();

        caller.requireUser();
        return callerService.record(caller);
    }

    @Transactional(readOnly = true)
    public List<UserSummaryDto> users() {
        callerService.current().requireAdmin();
        return userRepository.findAllByOrderByLastSeenAtDesc().stream()
                .map(user -> new UserSummaryDto(user.getUuid(), user.getUsername(), user.getDisplayName(), user.getLastSeenAt(),
                        passwordRepository.countByUserIdAndRevokedAtIsNull(user.getId()),
                        apiKeyRepository.countByUserIdAndRevokedAtIsNull(user.getId()),
                        throttle.isUsernameLocked(user.getUsername())))
                .toList();
    }

    // ---------------------------------------------------------------------------------------------------- gateway

    @Transactional
    public void revokeAll(final UUID userId) {
        callerService.current().requireAdmin();

        final AppUser user = userRepository.findByUuid(userId).orElseThrow(() -> new ResourceNotFoundException("error.user.notFound", userId));
        final Instant now = clock.instant();

        passwordRepository.findByUserIdAndRevokedAtIsNullOrderByCreatedAtDesc(user.getId()).forEach(password -> password.setRevokedAt(now));
        apiKeyRepository.findByUserIdAndRevokedAtIsNullOrderByCreatedAtDesc(user.getId()).forEach(apiKey -> apiKey.setRevokedAt(now));
        auditService.record("REVOKE", "USER", userId, "Återkallade alla applösenord och nycklar för " + user.getUsername());
    }

    @Transactional(readOnly = true)
    public void unlock(final UUID userId) {
        callerService.current().requireAdmin();
        throttle.unlock(userRepository.findByUuid(userId).orElseThrow(() -> new ResourceNotFoundException("error.user.notFound", userId)).getUsername());
    }

    // ---------------------------------------------------------------------------------------------------- dtos

    /**
     * Checks a username with a password ({@code p}) or token and salt ({@code t}, {@code s}) against the user's app
     * passwords that are neither revoked nor expired.
     *
     * @return the user, or empty when nothing matches
     */
    @Transactional
    public Optional<AppUser> verifyPassword(final String username, final @Nullable String password,
                                            final @Nullable String token, final @Nullable String salt) {
        final Optional<AppUser> user = userRepository.findByUsername(username);

        if (user.isEmpty()) {
            return Optional.empty();
        }

        final int months = settingsService.current().getAppPasswordExpiryMonths();
        final Instant now = clock.instant();

        for (final AppPassword candidate : passwordRepository.findByUserIdAndRevokedAtIsNullOrderByCreatedAtDesc(user.get().getId())) {
            if (candidate.expiresAt(months).isBefore(now)) {
                continue;
            }

            final String plain = crypto.decrypt(candidate.getCiphertext());
            final boolean matches = password != null
                    ? PasswordCrypto.constantTimeEquals(plain, password)
                    : token != null && salt != null && PasswordCrypto.constantTimeEquals(PasswordCrypto.md5Hex(plain + salt), token.toLowerCase(java.util.Locale.ROOT));

            if (matches) {
                if (candidate.getLastUsedAt() == null || candidate.getLastUsedAt().plus(LAST_USED_RESOLUTION).isBefore(now)) {
                    candidate.setLastUsedAt(now);
                }
                return user;
            }
        }
        return Optional.empty();
    }

    @Transactional
    public Optional<AppUser> verifyApiKey(final String key) {
        final Instant now = clock.instant();

        return apiKeyRepository.findByKeyHashAndRevokedAtIsNull(PasswordCrypto.sha256Hex(key)).map(apiKey -> {
            if (apiKey.getLastUsedAt() == null || apiKey.getLastUsedAt().plus(LAST_USED_RESOLUTION).isBefore(now)) {
                apiKey.setLastUsedAt(now);
            }

            final AppUser user = apiKey.getUser();

            user.getUsername();
            return user;
        });
    }

    private AppPasswordDto toDto(final AppPassword password, final int months) {
        final Instant expiresAt = password.expiresAt(months);

        return new AppPasswordDto(password.getUuid(), password.getName(), password.getCreatedAt(), password.getLastUsedAt(),
                expiresAt, expiresAt.isBefore(clock.instant()));
    }

    public record AppPasswordDto(UUID id, String name, Instant createdAt, @Nullable Instant lastUsedAt,
                                 Instant expiresAt, boolean expired) {
    }

    public record AppPasswordCreatedDto(AppPasswordDto appPassword, String password, String username) {
    }

    public record ApiKeyDto(UUID id, String name, Instant createdAt, @Nullable Instant lastUsedAt) {
    }

    public record ApiKeyCreatedDto(ApiKeyDto apiKey, String key) {
    }

    public record UserSummaryDto(UUID id, String username, @Nullable String displayName, Instant lastSeenAt,
                                 long appPasswordCount, long apiKeyCount, boolean locked) {
    }
}
