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

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import nu.fgv.spexify.server.settings.SettingsService;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Limits failed Subsonic logins per username and per IP address. After the configured number of failures within the
 * configured minutes, the username (or address) is locked for that long.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Component
public class LoginThrottle {

    private static final int ADDRESS_FACTOR = 5;

    private final SettingsService settingsService;
    private final Clock clock;
    private final Cache<String, Deque<Instant>> failures = Caffeine.newBuilder().expireAfterAccess(Duration.ofHours(24)).maximumSize(100_000).build();
    private final Cache<String, Instant> locks = Caffeine.newBuilder().expireAfterWrite(Duration.ofHours(24)).maximumSize(100_000).build();

    public LoginThrottle(final SettingsService settingsService, final Clock clock) {
        this.settingsService = settingsService;
        this.clock = clock;
    }

    public boolean isLocked(final String username, final String address) {
        return locked("u:" + username) || locked("a:" + address);
    }

    public boolean isUsernameLocked(final String username) {
        return locked("u:" + username);
    }

    public void failed(final String username, final String address) {
        final var settings = settingsService.current();

        record("u:" + username, settings.getLockoutAttempts(), settings.getLockoutMinutes());
        record("a:" + address, settings.getLockoutAttempts() * ADDRESS_FACTOR, settings.getLockoutMinutes());
    }

    public void succeeded(final String username) {
        failures.invalidate("u:" + username);
    }

    public void unlock(final String username) {
        failures.invalidate("u:" + username);
        locks.invalidate("u:" + username);
    }

    private boolean locked(final String key) {
        final Instant until = locks.getIfPresent(key);

        return until != null && until.isAfter(clock.instant());
    }

    private void record(final String key, final int attempts, final int minutes) {
        final Instant now = clock.instant();
        final Duration window = Duration.ofMinutes(minutes);
        final Deque<Instant> recent = failures.get(key, _ -> new ArrayDeque<>());

        synchronized (recent) {
            recent.addLast(now);
            while (!recent.isEmpty() && recent.peekFirst().isBefore(now.minus(window))) {
                recent.removeFirst();
            }
            if (recent.size() >= attempts) {
                locks.put(key, now.plus(window));
                recent.clear();
            }
        }
    }
}
