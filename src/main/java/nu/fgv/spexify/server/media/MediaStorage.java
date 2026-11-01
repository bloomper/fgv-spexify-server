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

package nu.fgv.spexify.server.media;

import lombok.extern.slf4j.Slf4j;
import nu.fgv.spexify.server.catalog.Album;
import nu.fgv.spexify.server.catalog.Location;
import nu.fgv.spexify.server.catalog.Track;
import nu.fgv.spexify.server.config.SpexifyProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.FileSystemUtils;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * The media folder and its subfolders. Audio files are named by id ({@code <location>/<albumUuid>/<trackUuid>.<ext>}),
 * never by title, so edits never move them.
 *
 * <pre>
 * music/        published tracks and their covers, Navidrome's music folder (read-only there)
 * unpublished/  unpublished tracks
 * trash/        trashed tracks
 * covers/       every album's cover, by album uuid
 * uploads/      unfinished uploads
 * </pre>
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Slf4j
@Component
public class MediaStorage {

    public static final String COVER = "cover";
    private static final List<String> SIDECARS = List.of("lrc", "txt");

    private final Path root;

    public MediaStorage(final SpexifyProperties properties) {
        this.root = properties.media().root().toAbsolutePath().normalize();
        for (final String folder : List.of(Location.MUSIC.folder(), Location.UNPUBLISHED.folder(), Location.TRASH.folder(), "covers", "uploads")) {
            try {
                Files.createDirectories(root.resolve(folder));
            } catch (final IOException e) {
                throw new UncheckedIOException("Could not create " + root.resolve(folder), e);
            }
        }
    }

    public static String extensionOf(final String fileName) {
        final int dot = fileName.lastIndexOf('.');

        return dot < 0 ? "" : fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    public Path root() {
        return root;
    }

    public Path folder(final Location location) {
        return root.resolve(location.folder());
    }

    public Path albumFolder(final Album album, final Location location) {
        return folder(location).resolve(album.getUuid().toString());
    }

    public Path trackFile(final Track track) {
        return trackFile(track, track.getLocation());
    }

    public Path trackFile(final Track track, final Location location) {
        return albumFolder(track.getAlbum(), location).resolve(track.fileName());
    }

    public Path lyricsFile(final Track track, final Location location, final boolean synced) {
        return albumFolder(track.getAlbum(), location).resolve(track.getUuid() + (synced ? ".lrc" : ".txt"));
    }

    public Path coverFile(final Album album) {
        return root.resolve("covers").resolve(album.getUuid() + "." + album.getCoverExtension());
    }

    public Path uploadFile(final String uploadId) {
        return root.resolve("uploads").resolve(uploadId);
    }

    public boolean exists(final Track track) {
        return Files.isRegularFile(trackFile(track));
    }

    /**
     * Moves a track's file and its lyrics from one location to another.
     */
    public void move(final Track track, final Location from, final Location to) throws IOException {
        final Path source = trackFile(track, from);
        final Path target = trackFile(track, to);

        Files.createDirectories(target.getParent());
        Files.move(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        for (final String sidecar : SIDECARS) {
            Files.deleteIfExists(source.resolveSibling(track.getUuid() + "." + sidecar));
        }
    }

    /**
     * Puts a new file in place for a track, replacing any earlier one.
     */
    public void store(final Path file, final Track track) throws IOException {
        final Path target = trackFile(track);

        Files.createDirectories(target.getParent());
        Files.move(file, target, StandardCopyOption.REPLACE_EXISTING);
    }

    public void delete(final Track track) throws IOException {
        for (final Location location : Location.values()) {
            final Path file = trackFile(track, location);

            Files.deleteIfExists(file);
            for (final String sidecar : SIDECARS) {
                Files.deleteIfExists(file.resolveSibling(track.getUuid() + "." + sidecar));
            }
        }
    }

    public void deleteAlbum(final Album album) throws IOException {
        for (final Location location : Location.values()) {
            FileSystemUtils.deleteRecursively(albumFolder(album, location));
        }
        if (album.getCoverExtension() != null) {
            Files.deleteIfExists(coverFile(album));
        }
    }

    /**
     * Copies the album's cover into a folder that holds its tracks, or removes it from a folder that holds none.
     */
    public void syncCover(final Album album, final Location location) throws IOException {
        final Path folder = albumFolder(album, location);

        if (!Files.isDirectory(folder)) {
            return;
        }

        final boolean hasTracks;

        try (Stream<Path> files = Files.list(folder)) {
            hasTracks = files.anyMatch(file -> !file.getFileName().toString().startsWith(COVER + ".")
                    && !file.getFileName().toString().endsWith(".lrc") && !file.getFileName().toString().endsWith(".txt"));
        }
        try (DirectoryStream<Path> covers = Files.newDirectoryStream(folder, COVER + ".*")) {
            for (final Path cover : covers) {
                Files.delete(cover);
            }
        }
        if (!hasTracks) {
            try (Stream<Path> rest = Files.list(folder)) {
                if (rest.findAny().isEmpty()) {
                    Files.delete(folder);
                }
            }
            return;
        }
        if (album.getCoverExtension() != null && Files.isRegularFile(coverFile(album))) {
            Files.copy(coverFile(album), folder.resolve(COVER + "." + album.getCoverExtension()), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * Empties the media folders, for the sample data import. The folders themselves stay, because Navidrome's container
     * mounts {@code music/} and would otherwise keep pointing at the deleted one.
     */
    public void wipe() throws IOException {
        for (final String folder : List.of(Location.MUSIC.folder(), Location.UNPUBLISHED.folder(), Location.TRASH.folder(), "covers", "uploads")) {
            final Path path = root.resolve(folder);

            Files.createDirectories(path);
            try (Stream<Path> children = Files.list(path)) {
                for (final Path child : children.toList()) {
                    FileSystemUtils.deleteRecursively(child);
                }
            }
        }
    }
}
