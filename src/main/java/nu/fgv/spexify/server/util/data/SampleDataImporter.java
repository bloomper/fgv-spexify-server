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

package nu.fgv.spexify.server.util.data;

import jakarta.ws.rs.core.Response;
import lombok.extern.slf4j.Slf4j;
import nu.fgv.spexify.server.apppassword.AppPasswordService;
import nu.fgv.spexify.server.audit.AuditEntry;
import nu.fgv.spexify.server.audit.AuditEntryRepository;
import nu.fgv.spexify.server.catalog.Album;
import nu.fgv.spexify.server.catalog.AlbumKind;
import nu.fgv.spexify.server.catalog.AlbumKindRepository;
import nu.fgv.spexify.server.catalog.AlbumRepository;
import nu.fgv.spexify.server.catalog.Location;
import nu.fgv.spexify.server.catalog.Track;
import nu.fgv.spexify.server.config.KeycloakConfig;
import nu.fgv.spexify.server.config.SpexifyProperties;
import nu.fgv.spexify.server.media.AudioPreparer;
import nu.fgv.spexify.server.media.Ffmpeg;
import nu.fgv.spexify.server.media.LibraryPlacer;
import nu.fgv.spexify.server.media.MediaStorage;
import nu.fgv.spexify.server.media.PreparedAudio;
import nu.fgv.spexify.server.navidrome.LibraryScanner;
import nu.fgv.spexify.server.spex.SpexDto;
import nu.fgv.spexify.server.spex.SpexService;
import nu.fgv.spexify.server.spex.SpexregisterClient;
import nu.fgv.spexify.server.user.AppUser;
import nu.fgv.spexify.server.user.AppUserRepository;
import nu.fgv.spexify.server.util.security.SecurityUtil;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.resource.ClientResource;
import org.keycloak.admin.client.resource.RealmResource;
import org.keycloak.representations.idm.CredentialRepresentation;
import org.keycloak.representations.idm.RoleRepresentation;
import org.keycloak.representations.idm.UserRepresentation;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.FileSystemUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.UUID;

/**
 * Replaces everything with sample data at every start while {@code spexify.data.import-sample-data} is set: the
 * database, the media folder and the sample users in Keycloak. Built on Spexregister's sample spex, so Spexregister must
 * run with its sample data too. Ids are the same on every run, so Navidrome's stars keep pointing at the same tracks.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Slf4j
@Component
public class SampleDataImporter {

    // Spexregister's sample users, which its sample data import creates; they get roles here
    static final String ADMIN = "admin@fgv.nu";
    static final String EDITOR = "editor@fgv.nu";
    static final String USER = "user@fgv.nu";
    // The only user of the applications' own, shared by Spexify's and Spexflix's sample data: an account without access
    static final String NO_ACCESS = "no-access@fgv.nu";
    private static final int SPEXREGISTER_ATTEMPTS = 30;
    private static final Duration SPEXREGISTER_RETRY_INTERVAL = Duration.ofSeconds(10);
    private static final List<String> TABLES = List.of("audit_entry", "api_key", "app_password", "track", "album", "album_kind", "upload_session", "app_user");

    private final SpexifyProperties properties;
    private final Environment environment;
    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;
    private final SpexregisterClient spexregisterClient;
    private final SpexService spexService;
    private final Keycloak keycloak;
    private final MediaStorage storage;
    private final Ffmpeg ffmpeg;
    private final AudioPreparer audioPreparer;
    private final LibraryPlacer placer;
    private final LibraryScanner scanner;
    private final AlbumRepository albumRepository;
    private final AlbumKindRepository albumKindRepository;
    private final AppUserRepository userRepository;
    private final AppPasswordService appPasswordService;
    private final AuditEntryRepository auditRepository;
    private final Clock clock;

    @SuppressWarnings("java:S107")
    public SampleDataImporter(final SpexifyProperties properties, final Environment environment, final JdbcTemplate jdbcTemplate,
                              final TransactionTemplate transactionTemplate, final SpexregisterClient spexregisterClient,
                              final SpexService spexService, @Qualifier(KeycloakConfig.ADMIN) final Keycloak keycloak,
                              final MediaStorage storage, final Ffmpeg ffmpeg, final AudioPreparer audioPreparer,
                              final LibraryPlacer placer, final LibraryScanner scanner, final AlbumRepository albumRepository,
                              final AlbumKindRepository albumKindRepository,
                              final AppUserRepository userRepository, final AppPasswordService appPasswordService,
                              final AuditEntryRepository auditRepository, final Clock clock) {
        this.properties = properties;
        this.environment = environment;
        this.jdbcTemplate = jdbcTemplate;
        this.transactionTemplate = transactionTemplate;
        this.spexregisterClient = spexregisterClient;
        this.spexService = spexService;
        this.keycloak = keycloak;
        this.storage = storage;
        this.ffmpeg = ffmpeg;
        this.audioPreparer = audioPreparer;
        this.placer = placer;
        this.scanner = scanner;
        this.albumRepository = albumRepository;
        this.albumKindRepository = albumKindRepository;
        this.userRepository = userRepository;
        this.appPasswordService = appPasswordService;
        this.auditRepository = auditRepository;
        this.clock = clock;
    }

    private static UserRepresentation spexregisterUser(final RealmResource realm, final String email) {
        return realm.users().searchByEmail(email, true).stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("Spexregister's sample user " + email
                        + " is missing; start Spexregister with its sample data first"));
    }

    @EventListener(ApplicationReadyEvent.class)
    @Order(Ordered.LOWEST_PRECEDENCE)
    public void onReady() throws IOException {
        if (!properties.data().importSampleData()) {
            return;
        }
        if (Arrays.asList(environment.getActiveProfiles()).contains("production")) {
            throw new IllegalStateException("Refusing to import sample data in the production profile");
        }
        importSampleData();
    }

    public void importSampleData() throws IOException {
        final long started = System.nanoTime();
        final boolean large = "large".equalsIgnoreCase(properties.data().sampleDataSize());

        log.info("Importing {} sample data", large ? "large" : "small");
        // Truncating resets the ids, so sample ids are the same on every run and links between the applications hold
        jdbcTemplate.execute((java.sql.Connection connection) -> {
            try (java.sql.Statement statement = connection.createStatement()) {
                statement.execute("SET FOREIGN_KEY_CHECKS = 0");
                for (final String table : TABLES) {
                    statement.execute("TRUNCATE TABLE " + table);
                }
                statement.execute("SET FOREIGN_KEY_CHECKS = 1");
            }
            return null;
        });
        storage.wipe();

        final List<SpexDto> spex = spexWithRetry();
        final RealmResource realm = keycloak.realm(properties.keycloak().realm());
        final Map<String, AppUser> users = NotificationSuspender.whileSuspended(realm, () -> createUsers(realm));
        final Path clipsFolder = Files.createTempDirectory("spexify-sample-");

        try {
            final List<PreparedAudio> clips = prepareClips(clipsFolder, large ? 2 : 12);
            final int albumCount = large ? 300 : 20;
            final List<AlbumKind> kinds = Objects.requireNonNull(transactionTemplate.execute(_ -> createKinds()));

            for (int i = 0; i < albumCount; i++) {
                final int index = i;

                transactionTemplate.executeWithoutResult(_ -> createAlbum(index, spex, kinds, clips, large));
            }
            transactionTemplate.executeWithoutResult(_ -> createCredentials(users));
            transactionTemplate.executeWithoutResult(_ -> createAudit(users));
        } finally {
            FileSystemUtils.deleteRecursively(clipsFolder);
        }
        scanner.requestScan();
        log.info("Imported sample data in {} s", Duration.ofNanos(System.nanoTime() - started).toSeconds());
    }

    // ---------------------------------------------------------------------------------------------------- users

    private List<SpexDto> spexWithRetry() {
        for (int attempt = 1; ; attempt++) {
            try {
                final List<SpexDto> spex = spexregisterClient.allSpex();

                if (spex.isEmpty()) {
                    throw new IllegalStateException("Spexregister has no spex; import its sample data first");
                }
                return spex.stream().sorted(Comparator.comparing((SpexDto s) -> s.year() == null ? 0 : s.year())).toList();
            } catch (final SpexregisterClient.SpexregisterCallException e) {
                if (attempt == SPEXREGISTER_ATTEMPTS) {
                    throw new IllegalStateException("Spexregister is unavailable, so sample data cannot be imported", e);
                }
                log.info("Waiting for Spexregister ({}/{})", attempt, SPEXREGISTER_ATTEMPTS);
                try {
                    Thread.sleep(SPEXREGISTER_RETRY_INTERVAL);
                } catch (final InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Interrupted while waiting for Spexregister", interrupted);
                }
            }
        }
    }

    /**
     * Gives Spexregister's sample users {@code admin@fgv.nu}, {@code editor@fgv.nu} and {@code user@fgv.nu} the roles
     * of the same name on this client, and creates {@code no-access@fgv.nu}, without roles, when it is missing. Refuses
     * without Spexregister's sample users, which keeps the import away from real environments.
     */
    private Map<String, AppUser> createUsers(final RealmResource realm) {
        final ClientResource client = realm.clients().get(realm.clients().findByClientId(SecurityUtil.CLIENT_ID).getFirst().getId());
        final String clientUuid = client.toRepresentation().getId();
        final Map<String, AppUser> users = new LinkedHashMap<>();

        users.put(ADMIN, grant(realm, client, clientUuid, spexregisterUser(realm, ADMIN), "USER", "EDITOR", "ADMIN"));
        users.put(EDITOR, grant(realm, client, clientUuid, spexregisterUser(realm, EDITOR), "USER", "EDITOR"));
        users.put(USER, grant(realm, client, clientUuid, spexregisterUser(realm, USER), "USER"));
        users.put(NO_ACCESS, recordUser(noAccessUser(realm)));
        return users;
    }

    private AppUser grant(final RealmResource realm, final ClientResource client, final String clientUuid,
                          final UserRepresentation user, final String... roles) {
        final List<RoleRepresentation> representations = Arrays.stream(roles)
                .map(role -> client.roles().get(role).toRepresentation())
                .toList();

        realm.users().get(user.getId()).roles().clientLevel(clientUuid).add(representations);
        return recordUser(user);
    }

    private AppUser recordUser(final UserRepresentation representation) {
        final Instant now = clock.instant();
        final AppUser user = new AppUser();

        user.setSubject(representation.getId());
        user.setUsername(representation.getUsername());
        user.setDisplayName(representation.getFirstName());
        user.setCreatedAt(now);
        user.setLastSeenAt(now);
        return transactionTemplate.execute(_ -> userRepository.save(user));
    }

    private UserRepresentation noAccessUser(final RealmResource realm) {
        final List<UserRepresentation> existing = realm.users().searchByEmail(NO_ACCESS, true);

        if (!existing.isEmpty()) {
            return existing.getFirst();
        }

        final CredentialRepresentation credential = new CredentialRepresentation();
        final UserRepresentation user = new UserRepresentation();

        credential.setType(CredentialRepresentation.PASSWORD);
        credential.setValue(properties.data().sampleUserPassword());
        credential.setTemporary(false);
        user.setUsername(NO_ACCESS);
        user.setEmail(NO_ACCESS);
        user.setFirstName("No");
        user.setLastName("Access");
        user.setEnabled(true);
        user.setEmailVerified(true);
        user.setCredentials(List.of(credential));

        try (Response response = realm.users().create(user)) {
            if (response.getStatus() != Response.Status.CREATED.getStatusCode()) {
                throw new IllegalStateException("Could not create user " + NO_ACCESS + " in Keycloak: " + response.getStatus());
            }

            final String path = response.getLocation().getPath();

            return realm.users().get(path.substring(path.lastIndexOf('/') + 1)).toRepresentation();
        }
    }

    private void createCredentials(final Map<String, AppUser> users) {
        final AppUser user = userRepository.findById(users.get(USER).getId()).orElseThrow();
        final Instant now = clock.instant();

        appPasswordService.createWithValue(user, "Telefon, Symfonium", properties.data().sampleAppPassword(), now.minus(Duration.ofDays(30)))
                .setLastUsedAt(now.minus(Duration.ofDays(1)));
        appPasswordService.createWithValue(user, "Gammal telefon", "expired-" + properties.data().sampleAppPassword(), now.minus(Duration.ofDays(800)));
    }

    private void createAudit(final Map<String, AppUser> users) {
        final AppUser editor = users.get(EDITOR);
        final Instant now = clock.instant();
        int minutes = 0;

        for (final Album album : albumRepository.findAll().stream().limit(8).toList()) {
            final AuditEntry entry = new AuditEntry();

            entry.setAt(now.minus(Duration.ofMinutes(minutes += 37)));
            entry.setActorSubject(editor.getSubject());
            entry.setActorName(editor.getDisplayName());
            entry.setAction(album.isPublished() ? "PUBLISH" : "CREATE");
            entry.setEntityType("ALBUM");
            entry.setEntityId(album.getUuid().toString());
            entry.setSummary((album.isPublished() ? "Publicerade albumet " : "Skapade albumet ") + album.getTitle());
            auditRepository.save(entry);
        }
    }

    // ---------------------------------------------------------------------------------------------------- catalogue

    private List<PreparedAudio> prepareClips(final Path folder, final int seconds) throws IOException {
        final List<PreparedAudio> prepared = new ArrayList<>();

        for (final SampleAudio.Clip clip : SampleAudio.generate(ffmpeg, folder, seconds)) {
            prepared.add(audioPreparer.prepare(clip.file()));
        }
        return prepared;
    }

    /**
     * The kinds a new database starts with, as in the migration: show (the default), studio, compilation and other.
     */
    private List<AlbumKind> createKinds() {
        final String[][] names = {{"Föreställning", "Show"}, {"Studio", "Studio"}, {"Samling", "Compilation"}, {"Övrigt", "Other"}};
        final List<AlbumKind> kinds = new ArrayList<>();

        for (int i = 0; i < names.length; i++) {
            final AlbumKind kind = new AlbumKind();

            kind.setUuid(UUID.fromString("00000000-0000-7000-8000-00000000a00" + (i + 1)));
            kind.setNameSv(names[i][0]);
            kind.setNameEn(names[i][1]);
            kind.setPosition(i + 1);
            kind.setCreatedBy("system");
            kinds.add(albumKindRepository.save(kind));
        }
        return kinds;
    }

    private void createAlbum(final int index, final List<SpexDto> spex, final List<AlbumKind> kinds,
                             final List<PreparedAudio> clips, final boolean large) {
        final Random random = new Random(2028L + index);
        final Album album = new Album();
        final boolean compilation = index == 9;
        final SpexDto picked = spex.get(index % spex.size());

        album.setUuid(UUID.nameUUIDFromBytes(("spexify-sample-album-" + index).getBytes(StandardCharsets.UTF_8)));
        if (compilation) {
            album.setTitle(SampleCatalog.COMPILATION_TITLE);
            album.setYear(2023);
            album.setKind(kinds.get(2));
        } else {
            spexService.copy(picked, album.getSpex());
            album.setTitle(index >= spex.size() ? picked.title() + " – studioinspelning" : String.valueOf(picked.title()));
            album.setYear(picked.year());
            album.setKind(kinds.get(index >= spex.size() ? 1 : index % 7 == 6 ? 3 : 0));
            album.setDescription("Inspelat under föreställningarna " + picked.year() + ". Exempeldata.");
        }
        album.setPublished(index != 3 && index != 5);
        album.setPublishedAt(album.isPublished() ? clock.instant().minus(Duration.ofDays(index)) : null);
        album.setTrashedAt(index == 7 ? clock.instant().minus(Duration.ofDays(2)) : null);
        album.setCreatedBy("system");
        albumRepository.save(album);

        if (index % 3 != 2) {
            try {
                album.setCoverExtension("jpg");
                Files.write(storage.coverFile(album), SampleCover.draw(album.getTitle(), index));
            } catch (final IOException e) {
                album.setCoverExtension(null);
                log.warn("Could not draw the cover of {}", album.getTitle(), e);
            }
        }

        final int trackCount = large ? 10 + random.nextInt(6) : 6 + random.nextInt(7);

        for (int number = 1; number <= trackCount; number++) {
            final Track track = new Track();
            final PreparedAudio clip = clips.get((index + number) % clips.size());
            final boolean secondDisc = index == 1 && number > trackCount / 2;

            track.setUuid(UUID.nameUUIDFromBytes(("spexify-sample-track-" + index + "-" + number).getBytes(StandardCharsets.UTF_8)));
            track.setAlbum(album);
            track.setDiscNumber(secondDisc ? 2 : 1);
            track.setTrackNumber(secondDisc ? number - trackCount / 2 : number);
            track.setTitle(SampleCatalog.TITLES.get((index * 3 + number) % SampleCatalog.TITLES.size()));
            track.setPerformers(SampleCatalog.PERFORMERS.get(random.nextInt(SampleCatalog.PERFORMERS.size())));
            if (random.nextInt(3) > 0) {
                track.setMelody(SampleCatalog.MELODIES.get(random.nextInt(SampleCatalog.MELODIES.size())));
            }
            track.setLyricsAuthor(SampleCatalog.LYRICS_AUTHORS.get(random.nextInt(SampleCatalog.LYRICS_AUTHORS.size())));
            if (number % 4 == 1) {
                track.setLyrics(SampleCatalog.SYNCED_LYRICS);
                track.setLyricsSynced(true);
            } else if (number % 4 == 2) {
                track.setLyrics(SampleCatalog.PLAIN_LYRICS);
            }
            track.setPublished(!(index == 2 && number == 2));
            track.setTrashedAt(index == 4 && number == 3 ? clock.instant().minus(Duration.ofDays(1)) : null);
            track.setFileExtension(clip.format().extension());
            track.setFileFormat(clip.format().name());
            track.setFileSize(clip.size());
            track.setFileChecksum(clip.checksum());
            track.setDurationMs(clip.probe().durationMs());
            track.setBitrate(clip.probe().bitrateKbps());
            track.setLocation(Location.UNPUBLISHED);
            track.setCreatedBy("system");
            album.getTracks().add(track);
            try {
                final Path target = storage.trackFile(track, Location.UNPUBLISHED);

                Files.createDirectories(target.getParent());
                Files.copy(clip.file(), target);
            } catch (final IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        }
        albumRepository.save(album);
        placer.place(album);
        if (index == 6) {
            try {
                Files.deleteIfExists(storage.trackFile(album.getTracks().getLast()));
            } catch (final IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        }
    }
}
