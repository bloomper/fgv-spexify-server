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

import jakarta.ws.rs.NotFoundException;
import nu.fgv.spexify.server.config.SpexifyProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.representations.idm.ClientRepresentation;
import org.keycloak.representations.idm.RoleRepresentation;
import org.keycloak.representations.idm.UserRepresentation;
import org.mockito.Answers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Whether an app password's owner still has access in Keycloak.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@ExtendWith(MockitoExtension.class)
class AccessCheckerTest {

    @Mock(answer = Answers.RETURNS_DEEP_STUBS)
    private Keycloak keycloak;
    private AccessChecker checker;

    private static RoleRepresentation role(final String name) {
        final RoleRepresentation role = new RoleRepresentation();

        role.setName(name);
        return role;
    }

    private void user(final boolean enabled, final String... roles) {
        final UserRepresentation user = new UserRepresentation();

        user.setEnabled(enabled);
        when(keycloak.realm("fgv").users().get("s-1").toRepresentation()).thenReturn(user);
        lenient().when(keycloak.realm("fgv").users().get("s-1").roles().clientLevel("uuid").listEffective())
                .thenReturn(java.util.Arrays.stream(roles).map(AccessCheckerTest::role).toList());
    }

    @BeforeEach
    void setUp() {
        final ClientRepresentation client = new ClientRepresentation();

        client.setId("uuid");
        checker = new AccessChecker(keycloak, new SpexifyProperties(null,
                new SpexifyProperties.Keycloak(null, "fgv", null, null, null, null),
                new SpexifyProperties.Security(List.of(), Duration.ofMinutes(10)), null, null, null, null, null, null));
        lenient().when(keycloak.realm("fgv").clients().findByClientId("fgv-spexify")).thenReturn(List.of(client));
    }

    @Test
    void anEnabledUserWithUserHasAccessAndTheAnswerIsCached() {
        user(true, "USER", "EDITOR");

        assertThat(checker.hasAccess("s-1")).isTrue();
        assertThat(checker.hasAccess("s-1")).isTrue();
        verify(keycloak.realm("fgv").users().get("s-1"), times(1)).toRepresentation();
    }

    @Test
    void aDisabledUserOrOneWithoutUserHasNoAccess() {
        user(false, "USER");
        assertThat(checker.hasAccess("s-1")).isFalse();

        checker.forget("s-1");
        user(true, "EDITOR");
        assertThat(checker.hasAccess("s-1")).isFalse();
    }

    @Test
    void aDeletedUserHasNoAccess() {
        when(keycloak.realm("fgv").users().get("s-1").toRepresentation()).thenThrow(new NotFoundException());

        assertThat(checker.hasAccess("s-1")).isFalse();
    }

    @Test
    void anUnreachableKeycloakIsReported() {
        when(keycloak.realm("fgv").users().get("s-1").toRepresentation()).thenThrow(new IllegalStateException("Connection refused"));

        assertThatThrownBy(() -> checker.hasAccess("s-1")).isInstanceOf(AccessChecker.AccessCheckUnavailableException.class);
    }
}
