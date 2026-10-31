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

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.extern.slf4j.Slf4j;
import nu.fgv.spexify.server.config.SpexifyProperties;
import nu.fgv.spexify.server.util.security.SecurityUtil;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.resource.RealmResource;
import org.keycloak.representations.idm.RoleRepresentation;
import org.keycloak.representations.idm.UserRepresentation;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Whether someone still has {@code USER} on {@code fgv-spexify} in Keycloak, for requests that carry no token: Subsonic
 * apps with app passwords or API keys. Answers are cached for {@code spexify.security.access-cache} (10 minutes), so
 * removing access or disabling the account stops the user's apps within that time. When Keycloak cannot answer, the
 * request is refused.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Slf4j
@Component
public class AccessChecker {

    private final Keycloak keycloak;
    private final String realm;
    private final Cache<String, Boolean> answers;
    private final AtomicReference<String> clientUuid = new AtomicReference<>();

    public AccessChecker(final Keycloak keycloak, final SpexifyProperties properties) {
        this.keycloak = keycloak;
        this.realm = properties.keycloak().realm();
        this.answers = Caffeine.newBuilder()
                .expireAfterWrite(properties.security().accessCache())
                .maximumSize(10_000)
                .build();
    }

    /**
     * Whether the user with this Keycloak subject is enabled and has {@code USER}.
     *
     * @throws AccessCheckUnavailableException when Keycloak could not answer
     */
    public boolean hasAccess(final String subject) {
        final Boolean cached = answers.getIfPresent(subject);

        if (cached != null) {
            return cached;
        }

        final boolean answer = ask(subject);

        answers.put(subject, answer);
        return answer;
    }

    public void forget(final String subject) {
        answers.invalidate(subject);
    }

    private boolean ask(final String subject) {
        try {
            final RealmResource realmResource = keycloak.realm(realm);
            final UserRepresentation user;

            try {
                user = realmResource.users().get(subject).toRepresentation();
            } catch (final jakarta.ws.rs.NotFoundException e) {
                return false;
            }
            if (user == null || !Boolean.TRUE.equals(user.isEnabled())) {
                return false;
            }

            final List<RoleRepresentation> roles = realmResource.users().get(subject).roles().clientLevel(clientUuid(realmResource)).listEffective();

            return roles.stream().anyMatch(role -> "USER".equals(role.getName()));
        } catch (final RuntimeException e) {
            log.warn("Could not check the access of {} in Keycloak: {}", subject, e.getMessage());
            throw new AccessCheckUnavailableException(e);
        }
    }

    private String clientUuid(final RealmResource realmResource) {
        String uuid = clientUuid.get();

        if (uuid == null) {
            uuid = realmResource.clients().findByClientId(SecurityUtil.CLIENT_ID).getFirst().getId();
            clientUuid.set(uuid);
        }
        return uuid;
    }

    /**
     * Keycloak could not be asked.
     */
    public static class AccessCheckUnavailableException extends RuntimeException {

        public AccessCheckUnavailableException(final Throwable cause) {
            super(cause);
        }
    }
}
