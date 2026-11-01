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

package nu.fgv.spexify.server.user;

import nu.fgv.spexify.server.util.security.SecurityUtil;
import org.jspecify.annotations.Nullable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Resolves the caller from the access token, and records people who log in so that their username can be shown on
 * <em>My apps</em> and admins can see who uses Spexify.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Service
public class CallerService {

    private static final String PREFIX = "ROLE_" + SecurityUtil.CLIENT_ID + "_";
    private static final String SPEXFLIX_USER = "ROLE_fgv-spexflix_USER";
    private static final Duration LAST_SEEN_RESOLUTION = Duration.ofMinutes(10);

    private final AppUserRepository repository;
    private final Clock clock;
    private final TransactionTemplate transactionTemplate;

    public CallerService(final AppUserRepository repository, final Clock clock, final PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.clock = clock;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.transactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public static Caller fromJwt(final Jwt jwt, final java.util.Collection<String> authorities) {
        final Set<Role> roles = EnumSet.noneOf(Role.class);

        authorities.stream()
                .filter(authority -> authority.startsWith(PREFIX))
                .map(authority -> authority.substring(PREFIX.length()))
                .forEach(role -> {
                    for (final Role candidate : Role.values()) {
                        if (candidate.name().equals(role)) {
                            roles.add(candidate);
                        }
                    }
                });

        // A service account's token may have no subject; it never acts as a user, so its client id stands in
        final String subject = firstText(jwt.getSubject(), "client:" + jwt.getClaimAsString("azp"));
        final String username = firstText(jwt.getClaimAsString("preferred_username"), jwt.getClaimAsString("email"), subject);

        return new Caller(java.util.Objects.requireNonNull(subject), username == null ? subject : username,
                firstText(jwt.getClaimAsString("name"), username), roles, authorities.contains(SPEXFLIX_USER));
    }

    private static @Nullable String firstText(final @Nullable String... candidates) {
        for (final String candidate : candidates) {
            if (StringUtils.hasText(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    /**
     * The caller, or an exception when there is no valid token.
     */
    public Caller current() {
        return currentIfAny().orElseThrow(() -> new AuthenticationCredentialsNotFoundException("No access token"));
    }

    public Optional<Caller> currentIfAny() {
        return SecurityUtil.currentJwt().map(jwt -> fromJwt(jwt, SecurityUtil.currentAuthentication()
                .map(authentication -> authentication.getAuthorities().stream().map(GrantedAuthority::getAuthority).toList())
                .orElse(java.util.List.of())));
    }

    /**
     * Records the caller as a user, or refreshes their username, name and when they were last seen.
     */
    public AppUser record(final Caller caller) {
        try {
            return Objects.requireNonNull(transactionTemplate.execute(_ -> recordInTransaction(caller)));
        } catch (final DataIntegrityViolationException e) {
            // Two first requests from the same user, e.g. two GraphQL fields, raced to insert them; now the row exists
            return Objects.requireNonNull(transactionTemplate.execute(_ -> recordInTransaction(caller)));
        }
    }

    private AppUser recordInTransaction(final Caller caller) {
        final Instant now = clock.instant();
        final AppUser user = repository.findBySubject(caller.subject()).orElseGet(() -> {
            final AppUser created = new AppUser();

            created.setSubject(caller.subject());
            created.setCreatedAt(now);
            created.setLastSeenAt(now);
            return created;
        });

        if (user.getId() == null
                || !caller.username().equals(user.getUsername())
                || !Objects.equals(caller.displayName(), user.getDisplayName())
                || user.getLastSeenAt().plus(LAST_SEEN_RESOLUTION).isBefore(now)) {
            user.setUsername(caller.username());
            user.setDisplayName(caller.displayName());
            user.setLastSeenAt(now);
            return repository.save(user);
        }
        return user;
    }

    /**
     * The record of the caller, created on first use.
     */
    public AppUser currentUser() {
        return record(current());
    }
}
