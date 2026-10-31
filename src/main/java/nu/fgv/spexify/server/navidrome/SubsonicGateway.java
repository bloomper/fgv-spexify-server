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

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import nu.fgv.spexify.server.apppassword.AppPasswordService;
import nu.fgv.spexify.server.apppassword.LoginThrottle;
import nu.fgv.spexify.server.user.AccessChecker;
import nu.fgv.spexify.server.user.AppUser;
import nu.fgv.spexify.server.user.Caller;
import nu.fgv.spexify.server.user.CallerService;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpHeaders;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The only way into Navidrome. Authenticates a Subsonic request with an app password ({@code u} with {@code p} or
 * {@code t} and {@code s}), an OpenSubsonic API key ({@code apiKey}) or, from Spexify's web, a Keycloak token; checks
 * that the user still has access; refuses operations that would change the library or users; then passes the request
 * to Navidrome as that user ({@code Remote-User}) and streams the answer back.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Slf4j
@RestController
public class SubsonicGateway {

    /**
     * Operations that change the library, users, podcasts, radio stations, shares or the jukebox. The catalogue is
     * changed only in Spexify's web.
     */
    static final Set<String> REFUSED = Set.of(
            "startscan", "createuser", "updateuser", "deleteuser", "changepassword", "getusers",
            "createpodcastchannel", "deletepodcastchannel", "deletepodcastepisode", "downloadpodcastepisode", "refreshpodcasts",
            "createinternetradiostation", "updateinternetradiostation", "deleteinternetradiostation",
            "jukeboxcontrol", "createshare", "updateshare", "deleteshare");
    private static final Set<String> AUTH_PARAMETERS = Set.of("u", "p", "t", "s", "apikey");
    private static final List<String> REQUEST_HEADERS = List.of(HttpHeaders.RANGE, HttpHeaders.IF_NONE_MATCH,
            HttpHeaders.IF_MODIFIED_SINCE, HttpHeaders.ACCEPT, HttpHeaders.USER_AGENT);
    private static final List<String> RESPONSE_HEADERS = List.of(HttpHeaders.CONTENT_TYPE, HttpHeaders.CONTENT_LENGTH,
            HttpHeaders.CONTENT_RANGE, HttpHeaders.ACCEPT_RANGES, HttpHeaders.CONTENT_DISPOSITION, HttpHeaders.CACHE_CONTROL,
            HttpHeaders.ETAG, HttpHeaders.LAST_MODIFIED, HttpHeaders.EXPIRES);
    private static final Duration STREAM_TIMEOUT = Duration.ofHours(2);

    private final NavidromeClient navidrome;
    private final AppPasswordService appPasswordService;
    private final AccessChecker accessChecker;
    private final LoginThrottle throttle;
    private final CallerService callerService;
    private final JwtDecoder jwtDecoder;
    private final JwtAuthenticationConverter jwtAuthenticationConverter;

    public SubsonicGateway(final NavidromeClient navidrome, final AppPasswordService appPasswordService,
                           final AccessChecker accessChecker, final LoginThrottle throttle,
                           final CallerService callerService, final JwtDecoder jwtDecoder,
                           final JwtAuthenticationConverter jwtAuthenticationConverter) {
        this.navidrome = navidrome;
        this.appPasswordService = appPasswordService;
        this.accessChecker = accessChecker;
        this.throttle = throttle;
        this.callerService = callerService;
        this.jwtDecoder = jwtDecoder;
        this.jwtAuthenticationConverter = jwtAuthenticationConverter;
    }

    @RequestMapping(path = "/rest/**", method = {RequestMethod.GET, RequestMethod.POST, RequestMethod.HEAD})
    public void handle(final HttpServletRequest request, final HttpServletResponse response) throws IOException {
        final Map<String, String[]> parameters = request.getParameterMap();
        final String format = first(parameters, "f") == null ? "xml" : first(parameters, "f");
        final String endpoint = endpoint(request.getRequestURI());

        if (endpoint.isEmpty()) {
            SubsonicResponses.error(response, format, SubsonicResponses.NOT_FOUND, "Unknown endpoint");
            return;
        }

        final Authenticated user;

        try {
            user = authenticate(request, parameters, format, response);
        } catch (final AccessChecker.AccessCheckUnavailableException e) {
            SubsonicResponses.error(response, format, SubsonicResponses.GENERIC, "Access cannot be checked right now; try again later");
            return;
        }
        if (user == null) {
            return;
        }
        if (REFUSED.contains(endpoint.toLowerCase(Locale.ROOT))) {
            SubsonicResponses.error(response, format, SubsonicResponses.NOT_AUTHORIZED, "Not available in Spexify");
            return;
        }
        forward(request, response, endpoint, parameters, user.username());
    }

    private @Nullable Authenticated authenticate(final HttpServletRequest request, final Map<String, String[]> parameters,
                                                 final String format, final HttpServletResponse response) throws IOException {
        final String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
        final String username = first(parameters, "u");
        final String password = first(parameters, "p");
        final String token = first(parameters, "t");
        final String salt = first(parameters, "s");
        final String apiKey = first(parameters, "apiKey");
        final String address = request.getRemoteAddr();

        if (authorization != null && authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return bearer(authorization.substring(7).strip(), format, response);
        }
        if (apiKey != null) {
            if (username != null || password != null || token != null) {
                SubsonicResponses.error(response, format, SubsonicResponses.CONFLICTING_AUTHENTICATION, "Give either an API key or a username");
                return null;
            }
            if (throttle.isLocked("", address)) {
                SubsonicResponses.error(response, format, SubsonicResponses.INVALID_API_KEY, "Too many failed attempts; try again later");
                return null;
            }

            final AppUser user = appPasswordService.verifyApiKey(apiKey).orElse(null);

            if (user == null) {
                throttle.failed("", address);
                SubsonicResponses.error(response, format, SubsonicResponses.INVALID_API_KEY, "Invalid API key");
                return null;
            }
            return checked(user, format, response);
        }
        if (username == null || (password == null && (token == null || salt == null))) {
            SubsonicResponses.error(response, format, SubsonicResponses.MISSING_PARAMETER, "Required parameter is missing");
            return null;
        }
        if (throttle.isLocked(username, address)) {
            SubsonicResponses.error(response, format, SubsonicResponses.WRONG_CREDENTIALS, "Too many failed attempts; try again later");
            return null;
        }

        final AppUser user = appPasswordService.verifyPassword(username, decodePassword(password), token, salt).orElse(null);

        if (user == null) {
            throttle.failed(username, address);
            SubsonicResponses.error(response, format, SubsonicResponses.WRONG_CREDENTIALS, "Wrong username or password");
            return null;
        }
        throttle.succeeded(username);
        return checked(user, format, response);
    }

    private @Nullable Authenticated checked(final AppUser user, final String format, final HttpServletResponse response) throws IOException {
        if (!accessChecker.hasAccess(user.getSubject())) {
            SubsonicResponses.error(response, format, SubsonicResponses.NOT_AUTHORIZED, "You no longer have access to Spexify");
            return null;
        }
        return new Authenticated(user.getUsername());
    }

    private @Nullable Authenticated bearer(final String token, final String format, final HttpServletResponse response) throws IOException {
        try {
            final Jwt jwt = jwtDecoder.decode(token);
            final var authentication = jwtAuthenticationConverter.convert(jwt);
            final Caller caller = CallerService.fromJwt(jwt, authentication.getAuthorities().stream()
                    .map(org.springframework.security.core.GrantedAuthority::getAuthority).toList());

            if (!caller.isUser()) {
                SubsonicResponses.error(response, format, SubsonicResponses.NOT_AUTHORIZED, "You have no access to Spexify");
                return null;
            }
            callerService.record(caller);
            return new Authenticated(caller.username());
        } catch (final JwtException e) {
            SubsonicResponses.error(response, format, SubsonicResponses.WRONG_CREDENTIALS, "Invalid token");
            return null;
        }
    }

    private void forward(final HttpServletRequest request, final HttpServletResponse response, final String endpoint,
                         final Map<String, String[]> parameters, final String username) throws IOException {
        final Map<String, List<String>> forwarded = new LinkedHashMap<>();

        parameters.forEach((key, values) -> {
            if (!AUTH_PARAMETERS.contains(key.toLowerCase(Locale.ROOT))) {
                forwarded.put(key, List.of(values));
            }
        });
        forwarded.put("u", List.of(username));

        try {
            navidrome.ensureUser(username);
        } catch (final IOException e) {
            log.warn("Navidrome is unavailable: {}", e.getMessage());
            SubsonicResponses.error(response, first(parameters, "f") == null ? "xml" : first(parameters, "f"),
                    SubsonicResponses.GENERIC, "The music server is unavailable right now");
            return;
        }

        final boolean post = "POST".equalsIgnoreCase(request.getMethod());
        final HttpRequest.Builder builder = HttpRequest.newBuilder(navidrome.uri(endpoint, post ? Map.of() : forwarded))
                .timeout(STREAM_TIMEOUT)
                .header(NavidromeClient.USER_HEADER, username);

        for (final String header : REQUEST_HEADERS) {
            final String value = request.getHeader(header);

            if (value != null) {
                builder.header(header, value);
            }
        }
        if (post) {
            builder.header(HttpHeaders.CONTENT_TYPE, "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(NavidromeClient.encode(forwarded)));
        } else if ("HEAD".equalsIgnoreCase(request.getMethod())) {
            builder.method("HEAD", HttpRequest.BodyPublishers.noBody());
        } else {
            builder.GET();
        }

        final HttpResponse<InputStream> upstream;

        try {
            upstream = navidrome.httpClient().send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted", e);
        } catch (final IOException e) {
            log.warn("Navidrome is unavailable: {}", e.getMessage());
            SubsonicResponses.error(response, first(parameters, "f") == null ? "xml" : first(parameters, "f"),
                    SubsonicResponses.GENERIC, "The music server is unavailable right now");
            return;
        }

        response.setStatus(upstream.statusCode());
        for (final String header : RESPONSE_HEADERS) {
            upstream.headers().firstValue(header).ifPresent(value -> response.setHeader(header, value));
        }
        try (InputStream body = upstream.body(); OutputStream out = response.getOutputStream()) {
            body.transferTo(out);
        } catch (final IOException e) {
            // The client went away while streaming, which apps do when skipping
            log.debug("Stream of {} ended early: {}", endpoint, e.getMessage());
        }
    }

    /**
     * The endpoint name from a path such as {@code /rest/getAlbum.view}.
     */
    static String endpoint(final String uri) {
        final int rest = uri.indexOf("/rest/");

        if (rest < 0) {
            return "";
        }

        final String name = uri.substring(rest + "/rest/".length());
        final String endpoint = name.endsWith(".view") ? name.substring(0, name.length() - ".view".length()) : name;

        return endpoint.matches("[A-Za-z0-9]+") ? endpoint : "";
    }

    /**
     * A Subsonic password, given in clear or hex-encoded with an {@code enc:} prefix.
     */
    static @Nullable String decodePassword(final @Nullable String password) {
        if (password != null && password.startsWith("enc:")) {
            try {
                return new String(HexFormat.of().parseHex(password.substring(4)), java.nio.charset.StandardCharsets.UTF_8);
            } catch (final IllegalArgumentException e) {
                return password;
            }
        }
        return password;
    }

    private static @Nullable String first(final Map<String, String[]> parameters, final String name) {
        final String[] values = parameters.get(name);

        return values == null || values.length == 0 ? null : values[0];
    }

    private record Authenticated(String username) {
    }
}
