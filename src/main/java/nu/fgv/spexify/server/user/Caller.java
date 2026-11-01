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

import org.jspecify.annotations.Nullable;
import org.springframework.security.access.AccessDeniedException;

import java.util.Set;

/**
 * The caller of the current request: their Keycloak subject, username, display name and roles on {@code fgv-spexify},
 * and whether they also have access to Spexflix.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
public record Caller(String subject, String username, @Nullable String displayName, Set<Role> roles,
                     boolean spexflixUser) {

    public Caller {
        roles = Set.copyOf(roles);
    }

    public boolean isUser() {
        return roles.contains(Role.USER);
    }

    /**
     * Editors see unpublished albums and change the catalogue; admins can do everything editors can.
     */
    public boolean isEditor() {
        return isUser() && (roles.contains(Role.EDITOR) || roles.contains(Role.ADMIN));
    }

    public boolean isAdmin() {
        return isUser() && roles.contains(Role.ADMIN);
    }

    public boolean isSpexflixService() {
        return roles.contains(Role.SPEXFLIX);
    }

    public void requireUser() {
        if (!isUser()) {
            throw new AccessDeniedException("USER required");
        }
    }

    public void requireEditor() {
        if (!isEditor()) {
            throw new AccessDeniedException("EDITOR required");
        }
    }

    public void requireAdmin() {
        if (!isAdmin()) {
            throw new AccessDeniedException("ADMIN required");
        }
    }
}
