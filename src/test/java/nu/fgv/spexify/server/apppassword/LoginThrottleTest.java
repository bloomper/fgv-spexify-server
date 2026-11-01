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

import nu.fgv.spexify.server.TestClock;
import nu.fgv.spexify.server.settings.Settings;
import nu.fgv.spexify.server.settings.SettingsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Locking usernames and addresses after failed Subsonic logins.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@ExtendWith(MockitoExtension.class)
class LoginThrottleTest {

    private final TestClock clock = new TestClock(Instant.parse("2026-11-01T10:00:00Z"));
    @Mock
    private SettingsService settingsService;
    private LoginThrottle throttle;

    @BeforeEach
    void setUp() {
        final Settings settings = new Settings();

        settings.setLockoutAttempts(3);
        settings.setLockoutMinutes(15);
        when(settingsService.current()).thenReturn(settings);
        throttle = new LoginThrottle(settingsService, clock);
    }

    private void fail(final String username, final String address, final int times) {
        for (int i = 0; i < times; i++) {
            throttle.failed(username, address);
        }
    }

    @Test
    void aUsernameIsLockedAfterTheSetNumberOfFailuresForTheSetMinutes() {
        fail("lisa", "10.0.0.1", 2);
        assertThat(throttle.isLocked("lisa", "10.0.0.1")).isFalse();

        fail("lisa", "10.0.0.1", 1);
        assertThat(throttle.isUsernameLocked("lisa")).isTrue();
        assertThat(throttle.isLocked("lisa", "10.0.0.2")).isTrue();

        clock.advance(Duration.ofMinutes(16));
        assertThat(throttle.isUsernameLocked("lisa")).isFalse();
    }

    @Test
    void badApiKeysLockOnlyTheirAddress() {
        for (int i = 0; i < 15; i++) {
            throttle.failedAddress("10.0.0.9");
        }

        assertThat(throttle.isAddressLocked("10.0.0.9")).isTrue();
        assertThat(throttle.isAddressLocked("10.0.0.10")).isFalse();
        assertThat(throttle.isLocked("lisa", "10.0.0.10")).isFalse();
    }

    @Test
    void failuresOutsideTheWindowAreForgotten() {
        fail("lisa", "10.0.0.1", 2);
        clock.advance(Duration.ofMinutes(16));
        fail("lisa", "10.0.0.1", 1);

        assertThat(throttle.isUsernameLocked("lisa")).isFalse();
    }

    @Test
    void anAddressIsLockedAfterFiveTimesAsManyFailures() {
        for (int i = 0; i < 14; i++) {
            throttle.failed("user" + i, "10.0.0.9");
        }
        assertThat(throttle.isLocked("ny", "10.0.0.9")).isFalse();

        throttle.failed("user14", "10.0.0.9");
        assertThat(throttle.isLocked("ny", "10.0.0.9")).isTrue();
    }

    @Test
    void successAndUnlockingClearTheCount() {
        fail("lisa", "10.0.0.1", 2);
        throttle.succeeded("lisa");
        fail("lisa", "10.0.0.2", 2);
        assertThat(throttle.isUsernameLocked("lisa")).isFalse();

        fail("lisa", "10.0.0.3", 1);
        assertThat(throttle.isUsernameLocked("lisa")).isTrue();
        throttle.unlock("lisa");
        assertThat(throttle.isUsernameLocked("lisa")).isFalse();
    }
}
