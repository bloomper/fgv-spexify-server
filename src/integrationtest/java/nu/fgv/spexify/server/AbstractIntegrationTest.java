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

package nu.fgv.spexify.server;

import com.github.tomakehurst.wiremock.WireMockServer;
import dasniko.testcontainers.keycloak.KeycloakContainer;
import jakarta.ws.rs.core.Response;
import nu.fgv.spexify.server.media.Ffmpeg;
import nu.fgv.spexify.server.media.MediaStorage;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.resource.ClientResource;
import org.keycloak.admin.client.resource.RealmResource;
import org.keycloak.representations.idm.ClientRepresentation;
import org.keycloak.representations.idm.CredentialRepresentation;
import org.keycloak.representations.idm.RoleRepresentation;
import org.keycloak.representations.idm.UserRepresentation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.json.JacksonJsonParser;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.cache.CacheManager;
import org.springframework.graphql.test.tester.GraphQlTester;
import org.springframework.graphql.test.tester.HttpGraphQlTester;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.BindMode;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.mysql.MySQLContainer;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * MySQL, Keycloak (with the committed copy of {@code fgv-keycloak}'s realm plus fictitious users) and Navidrome in
 * Testcontainers, and WireMock standing in for Spexregister's and Spexflix's GraphQL APIs. Every test starts from empty
 * tables and an empty media folder.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("integrationtest")
public abstract class AbstractIntegrationTest {

    protected static final String DOMAIN = "@spexify.test";
    protected static final String LISTENER = "lyssnare" + DOMAIN;
    protected static final String EDITOR = "redaktor" + DOMAIN;
    protected static final String ADMIN = "admin" + DOMAIN;
    protected static final String NO_ACCESS = "utan" + DOMAIN;
    protected static final String BOTH = "bada" + DOMAIN;
    // Loses USER in a test, so it is no one else's
    protected static final String REVOKED = "aterkallad" + DOMAIN;
    protected static final String PASSWORD = "s3cr3t-s3cr3t";
    protected static final String CLIENT_ID = "fgv-spexify";
    protected static final String SPEXFLIX_SERVICE_CLIENT_ID = "fgv-spexflix-service";
    protected static final String SPEX_ID = "00000000-0000-7000-8000-000000000001";
    protected static final WireMockServer SPEXREGISTER = new WireMockServer(options().dynamicPort());
    protected static final WireMockServer SPEXFLIX = new WireMockServer(options().dynamicPort());
    protected static final Path MEDIA = Path.of("build/integrationtest-data/media").toAbsolutePath();
    private static final String REALM = "fgv";
    private static final JacksonJsonParser JSON = new JacksonJsonParser();
    private static final Map<String, String> SECRETS = clientSecrets();
    private static final Map<String, String> TOKENS = new ConcurrentHashMap<>();
    @ServiceConnection
    private static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4")
            .withTmpFs(Map.of("/var/lib/mysql", "rw"))
            .withCommand("mysqld", "--innodb-flush-log-at-trx-commit=0", "--innodb-doublewrite=0", "--skip-log-bin", "--performance-schema=OFF");
    private static final KeycloakContainer KEYCLOAK = new KeycloakContainer("quay.io/keycloak/keycloak:26.8").withRealmImportFile("/realm/fgv.json");
    // Trusts Remote-User from anyone: the test JVM reaches it through Docker's gateway
    private static final GenericContainer<?> NAVIDROME = new GenericContainer<>("deluan/navidrome:0.64.2")
            .withExposedPorts(4533)
            .withEnv(Map.of(
                    "ND_MUSICFOLDER", "/music",
                    "ND_DATAFOLDER", "/data",
                    "ND_EXTAUTH_TRUSTEDSOURCES", "0.0.0.0/0",
                    "ND_SUBSONIC_DEFAULTREPORTREALPATH", "true",
                    "ND_ENABLEINSIGHTSCOLLECTOR", "false",
                    "ND_ENABLEEXTERNALSERVICES", "false",
                    "ND_SCANNER_SCHEDULE", "0"))
            .withFileSystemBind(musicFolder(), "/music", BindMode.READ_ONLY)
            .waitingFor(Wait.forHttp("/app/").forStatusCode(200));

    static {
        Startables.deepStart(MYSQL, KEYCLOAK, NAVIDROME).join();
        SPEXREGISTER.start();
        SPEXFLIX.start();
        prepareKeycloak();
    }

    @LocalServerPort
    protected int localPort;
    @Autowired
    protected JdbcClient jdbcClient;
    @Autowired
    protected MediaStorage storage;
    @Autowired
    protected Ffmpeg ffmpeg;
    @Autowired
    private CacheManager cacheManager;

    @DynamicPropertySource
    static void properties(final DynamicPropertyRegistry registry) {
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> KEYCLOAK.getAuthServerUrl() + "/realms/" + REALM);
        registry.add("spring.security.oauth2.client.registration.fgv-spexify-service.client-secret", () -> SECRETS.get("fgv-spexify-service"));
        registry.add("spexify.keycloak.url", KEYCLOAK::getAuthServerUrl);
        registry.add("spexify.keycloak.service-client-secret", () -> SECRETS.get("fgv-spexify-service"));
        registry.add("spexify.keycloak.admin-client-secret", () -> SECRETS.get("fgv-admin"));
        registry.add("spexify.navidrome.url", () -> "http://%s:%d".formatted(NAVIDROME.getHost(), NAVIDROME.getMappedPort(4533)));
        registry.add("spexify.media.root", MEDIA::toString);
        registry.add("spexify.spexregister.graphql-url", () -> SPEXREGISTER.baseUrl() + "/api/graphql");
        registry.add("spexify.spexflix.graphql-url", () -> SPEXFLIX.baseUrl() + "/api/graphql");
    }

    private static String musicFolder() {
        try {
            return Files.createDirectories(MEDIA.resolve("music")).toString();
        } catch (final IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * The client secrets in the committed realm copy, which are not real secrets.
     */
    private static Map<String, String> clientSecrets() {
        try (InputStream stream = AbstractIntegrationTest.class.getResourceAsStream("/realm/fgv.json")) {
            final Map<String, Object> realm = JSON.parseMap(new String(Objects.requireNonNull(stream).readAllBytes(), StandardCharsets.UTF_8));
            final Map<String, String> secrets = new ConcurrentHashMap<>();

            for (final Object client : (List<?>) realm.get("clients")) {
                if (client instanceof Map<?, ?> map && map.get("secret") instanceof String secret) {
                    secrets.put(String.valueOf(map.get("clientId")), secret);
                }
            }
            return secrets;
        } catch (final IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void prepareKeycloak() {
        try (Keycloak admin = KEYCLOAK.getKeycloakAdminClient()) {
            final RealmResource realm = admin.realm(REALM);
            final ClientRepresentation web = realm.clients().findByClientId(CLIENT_ID).getFirst();

            web.setDirectAccessGrantsEnabled(true);
            realm.clients().get(web.getId()).update(web);

            createUser(realm, LISTENER, "Lisa", List.of("USER"));
            createUser(realm, EDITOR, "Erik", List.of("USER", "EDITOR"));
            createUser(realm, ADMIN, "Anna", List.of("USER", "ADMIN"));
            createUser(realm, NO_ACCESS, "Nils", List.of());
            createUser(realm, REVOKED, "Rut", List.of("USER"));
            grantClientRoles(realm, createUser(realm, BOTH, "Bodil", List.of("USER")), "fgv-spexflix", List.of("USER"));
        }
    }

    private static String createUser(final RealmResource realm, final String email, final String firstName, final List<String> roles) {
        final CredentialRepresentation credential = new CredentialRepresentation();
        final UserRepresentation user = new UserRepresentation();

        credential.setType(CredentialRepresentation.PASSWORD);
        credential.setValue(PASSWORD);
        credential.setTemporary(false);
        user.setUsername(email);
        user.setEmail(email);
        user.setFirstName(firstName);
        user.setLastName("Test");
        user.setEnabled(true);
        user.setEmailVerified(true);
        user.setCredentials(List.of(credential));

        try (Response response = realm.users().create(user)) {
            final String path = response.getLocation().getPath();
            final String userId = path.substring(path.lastIndexOf('/') + 1);

            grantClientRoles(realm, userId, CLIENT_ID, roles);
            return userId;
        }
    }

    private static void grantClientRoles(final RealmResource realm, final String userId, final String clientId, final List<String> roles) {
        if (roles.isEmpty()) {
            return;
        }

        final ClientResource client = realm.clients().get(realm.clients().findByClientId(clientId).getFirst().getId());
        final List<RoleRepresentation> representations = roles.stream().map(role -> client.roles().get(role).toRepresentation()).toList();

        realm.users().get(userId).roles().clientLevel(client.toRepresentation().getId()).add(representations);
    }

    /**
     * A user as Spexregister's sample data creates them: with roles on Spexregister's client only.
     */
    protected static void createSpexregisterUser(final String email) {
        try (Keycloak admin = KEYCLOAK.getKeycloakAdminClient()) {
            final RealmResource realm = admin.realm(REALM);

            grantClientRoles(realm, createUser(realm, email, "Spexregister", List.of()), "spexregister", List.of("USER"));
        }
    }

    /**
     * The client roles a user has on {@code fgv-spexify}, or empty when the user does not exist.
     */
    protected static List<String> spexifyRoles(final String email) {
        try (Keycloak admin = KEYCLOAK.getKeycloakAdminClient()) {
            final RealmResource realm = admin.realm(REALM);
            final List<UserRepresentation> users = realm.users().searchByEmail(email, true);

            if (users.isEmpty()) {
                return List.of();
            }

            final String clientUuid = realm.clients().findByClientId(CLIENT_ID).getFirst().getId();

            return realm.users().get(users.getFirst().getId()).roles().clientLevel(clientUuid).listAll().stream()
                    .map(RoleRepresentation::getName).sorted().toList();
        }
    }

    /**
     * Removes a client role from a user, as when access is taken away in Keycloak.
     */
    protected static void revokeClientRole(final String email, final String role) {
        try (Keycloak admin = KEYCLOAK.getKeycloakAdminClient()) {
            final RealmResource realm = admin.realm(REALM);
            final String userId = realm.users().searchByEmail(email, true).getFirst().getId();
            final ClientResource client = realm.clients().get(realm.clients().findByClientId(CLIENT_ID).getFirst().getId());

            realm.users().get(userId).roles().clientLevel(client.toRepresentation().getId())
                    .remove(List.of(client.roles().get(role).toRepresentation()));
        }
        TOKENS.clear();
    }

    /**
     * A user's access token from {@code fgv-spexify}, by password grant.
     */
    protected static String token(final String user) {
        return TOKENS.computeIfAbsent(CLIENT_ID + ":" + user, _ -> requestToken(Map.of(
                "grant_type", "password", "client_id", CLIENT_ID, "client_secret", SECRETS.get(CLIENT_ID),
                "username", user, "password", PASSWORD, "scope", "openid")));
    }

    /**
     * Spexflix's service account's access token, by client credentials.
     */
    protected static String spexflixServiceToken() {
        return TOKENS.computeIfAbsent(SPEXFLIX_SERVICE_CLIENT_ID, _ -> requestToken(Map.of(
                "grant_type", "client_credentials", "client_id", SPEXFLIX_SERVICE_CLIENT_ID,
                "client_secret", SECRETS.get(SPEXFLIX_SERVICE_CLIENT_ID))));
    }

    private static String requestToken(final Map<String, String> parameters) {
        final MultiValueMap<String, String> form = new LinkedMultiValueMap<>();

        parameters.forEach(form::add);

        final String response = RestClient.create()
                .post()
                .uri(KEYCLOAK.getAuthServerUrl() + "/realms/" + REALM + "/protocol/openid-connect/token")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve()
                .body(String.class);

        return "Bearer " + JSON.parseMap(response).get("access_token");
    }

    /**
     * Asserts that the response has an error with the given {@code extensions.classification}, e.g. {@code FORBIDDEN}.
     */
    protected static void expectError(final GraphQlTester.Response response, final String code) {
        response.errors().satisfy(errors -> assertThat(errors)
                .as("errors")
                .anySatisfy(error -> assertThat(String.valueOf(error.getErrorType())).isEqualTo(code)));
    }

    /**
     * Spexregister answering with spex {@value #SPEX_ID}, Cleopatra 2024.
     */
    protected static void spexregisterUp() {
        SPEXREGISTER.resetAll();
        SPEXREGISTER.stubFor(post(urlEqualTo("/api/graphql"))
                .withRequestBody(containing("SpexById"))
                .willReturn(aResponse().withHeader("Content-Type", "application/json").withBody("""
                        {"data": {"spex": {"id": "00000000-0000-7000-8000-000000000001", "year": "2024", "title": "Cleopatra", "revival": false, "parent": null,
                                           "category": {"id": "00000000-0000-7000-8000-000000000001", "name": "Chalmersspexet"}}}}""")));
    }

    protected static void spexregisterDown() {
        SPEXREGISTER.resetAll();
        SPEXREGISTER.stubFor(post(urlEqualTo("/api/graphql")).willReturn(aResponse().withStatus(503)));
    }

    /**
     * Spexflix answering that no chapter is linked to any track.
     */
    protected static void spexflixUp() {
        SPEXFLIX.resetAll();
        SPEXFLIX.stubFor(post(urlEqualTo("/api/graphql"))
                .withRequestBody(containing("ChapterLinks"))
                .willReturn(aResponse().withHeader("Content-Type", "application/json").withBody("{\"data\": {\"chapterLinks\": []}}")));
    }

    @BeforeEach
    void resetState() throws IOException {
        jdbcClient.sql("SET FOREIGN_KEY_CHECKS = 0").update();
        for (final String table : List.of("audit_entry", "api_key", "app_password", "track", "album_spex", "album", "upload_session", "app_user")) {
            jdbcClient.sql("TRUNCATE TABLE " + table).update();
        }
        jdbcClient.sql("SET FOREIGN_KEY_CHECKS = 1").update();
        // Back to the album kinds of the migration, which a test may have changed
        jdbcClient.sql("DELETE FROM album_kind").update();
        jdbcClient.sql("""
                INSERT INTO album_kind (uuid, name_sv, name_en, position, created_by, created_at)
                VALUES ('00000000-0000-7000-8000-00000000a001', 'Föreställning', 'Show', 1, 'system', NOW(6)),
                       ('00000000-0000-7000-8000-00000000a002', 'Studio', 'Studio', 2, 'system', NOW(6)),
                       ('00000000-0000-7000-8000-00000000a003', 'Samling', 'Compilation', 3, 'system', NOW(6)),
                       ('00000000-0000-7000-8000-00000000a004', 'Övrigt', 'Other', 4, 'system', NOW(6))""").update();
        // Back to the defaults, which an admin test may have changed
        jdbcClient.sql("DELETE FROM settings").update();
        storage.wipe();
        cacheManager.getCacheNames().forEach(name -> Objects.requireNonNull(cacheManager.getCache(name)).clear());
        spexregisterUp();
        spexflixUp();
    }

    protected HttpGraphQlTester graphQl(final @Nullable String user) {
        return graphQlWithToken(user == null ? null : token(user));
    }

    protected HttpGraphQlTester graphQlWithToken(final @Nullable String token) {
        return HttpGraphQlTester.create(WebTestClient.bindToServer()
                .baseUrl("http://localhost:%d/api/graphql".formatted(localPort))
                .responseTimeout(Duration.ofSeconds(60))
                .defaultHeaders(headers -> {
                    if (token != null) {
                        headers.set(HttpHeaders.AUTHORIZATION, token);
                    }
                    headers.set(HttpHeaders.ACCEPT_LANGUAGE, "en");
                })
                .build());
    }

    protected RestClient http() {
        return RestClient.builder().baseUrl("http://localhost:" + localPort).build();
    }

    /**
     * A FLAC file of a tone, generated by ffmpeg.
     */
    protected Path flac(final Path folder, final int seconds) throws IOException {
        final Path file = folder.resolve("tone-" + seconds + ".flac");

        ffmpeg.ffmpeg(List.of("-f", "lavfi", "-i", "sine=frequency=440:sample_rate=44100:duration=" + seconds,
                "-metadata", "title=Ouvertyr", "-c:a", "flac", file.toString()));
        return file;
    }

    /**
     * Uploads a file with tus, in one chunk, as the given user, and returns the upload's id.
     */
    protected String upload(final String user, final Path file) throws IOException {
        final byte[] content = Files.readAllBytes(file);
        final String name = java.util.Base64.getEncoder().encodeToString(file.getFileName().toString().getBytes(StandardCharsets.UTF_8));
        final String location = Objects.requireNonNull(http().post()
                .uri("/api/uploads")
                .header(HttpHeaders.AUTHORIZATION, token(user))
                .header("Tus-Resumable", "1.0.0")
                .header("Upload-Length", String.valueOf(content.length))
                .header("Upload-Metadata", "filename " + name)
                .retrieve()
                .toBodilessEntity()
                .getHeaders()
                .getLocation()).toString();

        http().patch()
                .uri(location)
                .header(HttpHeaders.AUTHORIZATION, token(user))
                .header("Tus-Resumable", "1.0.0")
                .header("Upload-Offset", "0")
                .contentType(MediaType.parseMediaType("application/offset+octet-stream"))
                .body(content)
                .retrieve()
                .toBodilessEntity();
        return location.substring(location.lastIndexOf('/') + 1);
    }
}
