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

package nu.fgv.spexify.server.navidrome;

import nu.fgv.spexify.server.apppassword.AppPasswordService;
import nu.fgv.spexify.server.apppassword.LoginThrottle;
import nu.fgv.spexify.server.user.AccessChecker;
import nu.fgv.spexify.server.user.AppUser;
import nu.fgv.spexify.server.user.CallerService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * How the gateway authenticates Subsonic apps before anything reaches Navidrome. Navidrome is "down" here, so a request
 * that gets through answers that the music server is unavailable.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@ExtendWith(MockitoExtension.class)
class SubsonicGatewayAuthenticationTest {

    private static final String UNAVAILABLE = "The music server is unavailable right now";
    @Mock
    private NavidromeClient navidrome;
    @Mock
    private AppPasswordService appPasswordService;
    @Mock
    private AccessChecker accessChecker;
    @Mock
    private LoginThrottle throttle;
    @Mock
    private CallerService callerService;
    @Mock
    private JwtDecoder jwtDecoder;
    @Mock
    private JwtAuthenticationConverter jwtAuthenticationConverter;
    private SubsonicGateway gateway;
    private AppUser lisa;

    private static MockHttpServletRequest request(final String endpoint, final Map<String, String> parameters) {
        final MockHttpServletRequest request = new MockHttpServletRequest("GET", "/rest/" + endpoint + ".view");

        request.setRemoteAddr("10.0.0.1");
        request.addParameter("f", "json");
        parameters.forEach(request::addParameter);
        return request;
    }

    @BeforeEach
    void setUp() throws IOException {
        gateway = new SubsonicGateway(navidrome, appPasswordService, accessChecker, throttle, callerService, jwtDecoder, jwtAuthenticationConverter, java.util.List.of());
        lisa = new AppUser();
        lisa.setSubject("s-lisa");
        lisa.setUsername("lisa");
        lenient().doThrow(new IOException("down")).when(navidrome).ensureUser(anyString());
        lenient().when(accessChecker.hasAccess("s-lisa")).thenReturn(true);
    }

    private String call(final MockHttpServletRequest request) throws IOException {
        final MockHttpServletResponse response = new MockHttpServletResponse();

        gateway.handle(request, response);
        return response.getContentAsString();
    }

    @Test
    void anAppPasswordInClearOrHexGetsThrough() throws IOException {
        when(appPasswordService.verifyPassword("lisa", "hemligt", null, null)).thenReturn(Optional.of(lisa));

        assertThat(call(request("ping", Map.of("u", "lisa", "p", "enc:68656d6c696774")))).contains(UNAVAILABLE);
        verify(throttle).succeeded("lisa");
        verify(navidrome).ensureUser("lisa");
    }

    @Test
    void nobodyActsAsNavidromesSystemUser() throws IOException {
        final AppUser system = new AppUser();

        system.setSubject("s-system");
        system.setUsername("Spexify-System");
        when(navidrome.systemUser()).thenReturn("spexify-system");
        when(accessChecker.hasAccess("s-system")).thenReturn(true);
        when(appPasswordService.verifyPassword("Spexify-System", "hemligt", null, null)).thenReturn(Optional.of(system));

        assertThat(call(request("getPlaylists", Map.of("u", "Spexify-System", "p", "hemligt")))).contains("\"code\":50");
        verify(navidrome, never()).ensureUser(anyString());
    }

    @Test
    void aWrongPasswordCountsTowardsTheLock() throws IOException {
        when(appPasswordService.verifyPassword("lisa", "fel", null, null)).thenReturn(Optional.empty());

        assertThat(call(request("ping", Map.of("u", "lisa", "p", "fel")))).contains("\"code\":40");
        verify(throttle).failed("lisa", "10.0.0.1");
    }

    @Test
    void aLockedUsernameIsRefusedWithoutCheckingThePassword() throws IOException {
        when(throttle.isLocked("lisa", "10.0.0.1")).thenReturn(true);

        assertThat(call(request("ping", Map.of("u", "lisa", "t", "abc", "s", "salt")))).contains("Too many failed attempts");
        verify(appPasswordService, never()).verifyPassword(any(), any(), any(), any());
    }

    @Test
    void missingCredentialsAreAMissingParameter() throws IOException {
        assertThat(call(request("ping", Map.of("u", "lisa", "t", "abc")))).contains("\"code\":10");
    }

    @Test
    void anApiKeyMayNotBeCombinedWithAUsername() throws IOException {
        assertThat(call(request("ping", Map.of("apiKey", "k", "u", "lisa")))).contains("\"code\":43");
    }

    @Test
    void aValidApiKeyGetsThroughAndAnInvalidOneCounts() throws IOException {
        when(appPasswordService.verifyApiKey("good")).thenReturn(Optional.of(lisa));
        when(appPasswordService.verifyApiKey("bad")).thenReturn(Optional.empty());

        assertThat(call(request("ping", Map.of("apiKey", "good")))).contains(UNAVAILABLE);
        assertThat(call(request("ping", Map.of("apiKey", "bad")))).contains("\"code\":44");
        // Only the address counts, so that bad keys from one place cannot lock out everyone's keys
        verify(throttle).failedAddress("10.0.0.1");
        verify(throttle, never()).failed(any(), any());
    }

    @Test
    void someoneWhoLostAccessInKeycloakIsRefused() throws IOException {
        when(appPasswordService.verifyPassword(eq("lisa"), eq("hemligt"), isNull(), isNull())).thenReturn(Optional.of(lisa));
        when(accessChecker.hasAccess("s-lisa")).thenReturn(false);

        assertThat(call(request("ping", Map.of("u", "lisa", "p", "hemligt")))).contains("no longer have access");
    }

    @Test
    void whenKeycloakCannotBeAskedTheAppIsToldToTryLater() throws IOException {
        when(appPasswordService.verifyPassword(eq("lisa"), eq("hemligt"), isNull(), isNull())).thenReturn(Optional.of(lisa));
        when(accessChecker.hasAccess("s-lisa")).thenThrow(new AccessChecker.AccessCheckUnavailableException(new RuntimeException("down")));

        assertThat(call(request("ping", Map.of("u", "lisa", "p", "hemligt")))).contains("try again later");
    }

    @Test
    void operationsThatChangeTheLibraryAreRefusedAfterLogin() throws IOException {
        when(appPasswordService.verifyPassword(eq("lisa"), eq("hemligt"), isNull(), isNull())).thenReturn(Optional.of(lisa));

        assertThat(call(request("startScan", Map.of("u", "lisa", "p", "hemligt")))).contains("\"code\":50");
        verify(navidrome, never()).ensureUser(anyString());
    }

    @Test
    void aKeycloakTokenWithUserGetsThrough() throws IOException {
        final Jwt jwt = Jwt.withTokenValue("token").header("alg", "none").subject("s-lisa").claim("preferred_username", "lisa").build();
        final MockHttpServletRequest request = request("ping", Map.of());

        request.addHeader("Authorization", "Bearer token");
        when(jwtDecoder.decode("token")).thenReturn(jwt);
        when(jwtAuthenticationConverter.convert(jwt)).thenReturn(new JwtAuthenticationToken(jwt, List.of(new SimpleGrantedAuthority("ROLE_fgv-spexify_USER"))));

        assertThat(call(request)).contains(UNAVAILABLE);
        verify(callerService).record(any());
    }

    @Test
    void aBadTokenOrOneWithoutUserIsRefused() throws IOException {
        final MockHttpServletRequest bad = request("ping", Map.of());
        final MockHttpServletRequest noUser = request("ping", Map.of());
        final Jwt jwt = Jwt.withTokenValue("other").header("alg", "none").subject("s-x").build();

        bad.addHeader("Authorization", "Bearer broken");
        noUser.addHeader("Authorization", "bearer other");
        when(jwtDecoder.decode("broken")).thenThrow(new BadJwtException("broken"));
        when(jwtDecoder.decode("other")).thenReturn(jwt);
        when(jwtAuthenticationConverter.convert(jwt)).thenReturn(new JwtAuthenticationToken(jwt, List.of()));

        assertThat(call(bad)).contains("Invalid token");
        assertThat(call(noUser)).contains("You have no access to Spexify");
    }

    @Test
    void anUnknownEndpointIsNotFoundAndXmlIsTheDefault() throws IOException {
        final MockHttpServletRequest request = new MockHttpServletRequest("GET", "/rest/../admin");
        final MockHttpServletResponse response = new MockHttpServletResponse();

        gateway.handle(request, response);

        assertThat(response.getContentAsString()).contains("<subsonic-response").contains("code=\"70\"");
    }
}
