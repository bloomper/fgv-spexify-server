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

package nu.fgv.spexify.server.mcp;

import nu.fgv.spexify.server.util.error.BadRequestException;
import nu.fgv.spexify.server.util.error.ResourceNotFoundException;
import nu.fgv.spexify.server.util.error.SpexipediaUnavailableException;
import org.jspecify.annotations.Nullable;
import org.springframework.context.MessageSource;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Shared by the MCP tools, trimmed from Nyuppsättning's: refusals as readable messages, ids and durations from and for
 * tool arguments, and the result record. The tools only read, so nothing is audited.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Component
public class McpToolSupport {

    private final MessageSource messageSource;

    public McpToolSupport(final MessageSource messageSource) {
        this.messageSource = messageSource;
    }

    /**
     * An id from a tool argument, or NOT_FOUND with the given message when it is not one.
     */
    static UUID uuid(final String value, final String notFoundCode) {
        try {
            return UUID.fromString(value.strip());
        } catch (final IllegalArgumentException e) {
            throw new ResourceNotFoundException(notFoundCode, value.strip());
        }
    }

    /**
     * An optional id from a tool argument, or null when blank.
     */
    static @Nullable UUID optionalUuid(final @Nullable String value, final String notFoundCode) {
        return value == null || value.isBlank() ? null : uuid(value, notFoundCode);
    }

    /**
     * A duration as people read it, e.g. {@code 3:07} or {@code 1:02:45}.
     */
    static String duration(final double milliseconds) {
        final long seconds = Math.round(milliseconds / 1000);
        final long hours = seconds / 3600;

        return hours > 0
                ? "%d:%02d:%02d".formatted(hours, seconds % 3600 / 60, seconds % 60)
                : "%d:%02d".formatted(seconds / 60, seconds % 60);
    }

    /**
     * Runs a tool. A refusal becomes a tool error with the API's error code and the same English message the web
     * shows, e.g. "NOT_FOUND: The album does not exist.".
     */
    <T> T call(final Supplier<T> body) {
        try {
            return body.get();
        } catch (final AuthenticationException e) {
            throw refusal("UNAUTHORIZED", "error.unauthorized", null, e);
        } catch (final AccessDeniedException e) {
            throw refusal("FORBIDDEN", "error.forbidden", null, e);
        } catch (final BadRequestException e) {
            throw refusal("BAD_REQUEST", e);
        } catch (final ResourceNotFoundException e) {
            throw refusal("NOT_FOUND", e);
        } catch (final SpexipediaUnavailableException e) {
            throw refusal("SPEXIPEDIA_UNAVAILABLE", e);
        }
    }

    private McpToolRefusal refusal(final String code, final ResponseStatusException e) {
        return refusal(code, e.getDetailMessageCode(), e.getDetailMessageArguments(), e);
    }

    private McpToolRefusal refusal(final String code, final @Nullable String messageCode, final Object @Nullable [] arguments, final Exception cause) {
        final String key = messageCode == null ? "error.forbidden" : messageCode;

        return new McpToolRefusal("%s: %s".formatted(code, messageSource.getMessage(key, arguments, key, Locale.ENGLISH)), cause);
    }

    static final class McpToolRefusal extends RuntimeException {

        McpToolRefusal(final String message, final Throwable cause) {
            super(message, cause);
        }
    }

    public record ListResult<T>(List<T> items) {
    }
}
