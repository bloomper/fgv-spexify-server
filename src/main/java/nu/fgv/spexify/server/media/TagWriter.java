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

import nu.fgv.spexify.server.catalog.Album;
import nu.fgv.spexify.server.catalog.Track;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Writes a track's metadata into its file's tags with ffmpeg, replacing whatever tags it had. Spexify's database is the
 * source of truth; Navidrome learns about the catalogue only from the files.
 *
 * <p>The album and track uuids are written as MusicBrainz album id and release track id, which Navidrome derives its
 * persistent ids from by default, so editing a title or renumbering a track keeps the id that stars and playlists refer
 * to. MP4 files cannot carry them through ffmpeg; for those Navidrome falls back to album, disc, number and title.</p>
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Component
public class TagWriter {

    public static final String GENRE = "Spex";

    private final Ffmpeg ffmpeg;

    public TagWriter(final Ffmpeg ffmpeg) {
        this.ffmpeg = ffmpeg;
    }

    /**
     * The tags Spexify writes for a track, by ffmpeg's generic keys where there is one.
     */
    public static Map<String, String> tags(final Track track) {
        final Album album = track.getAlbum();
        final Map<String, String> tags = new LinkedHashMap<>();
        final List<String> comment = new ArrayList<>();

        tags.put("title", track.getTitle());
        tags.put("album", album.getTitle());
        tags.put("album_artist", albumArtist(album));
        tags.put("artist", StringUtils.hasText(track.getPerformers()) ? track.getPerformers() : albumArtist(album));
        tags.put("track", Integer.toString(track.getTrackNumber()));
        tags.put("disc", Integer.toString(track.getDiscNumber()));
        if (album.getYear() != null) {
            tags.put("date", Integer.toString(album.getYear()));
        }
        tags.put("genre", GENRE);
        if (StringUtils.hasText(track.getLyricsAuthor())) {
            tags.put("lyricist", track.getLyricsAuthor());
        }
        if (StringUtils.hasText(track.getMelody())) {
            comment.add("Melodi: " + track.getMelody());
        }
        if (StringUtils.hasText(track.getArranger())) {
            comment.add("Arr: " + track.getArranger());
        }
        if (!comment.isEmpty()) {
            tags.put("comment", String.join("\n", comment));
        }
        return tags;
    }

    private static String albumArtist(final Album album) {
        final String category = album.getSpex().getCategoryName();

        return StringUtils.hasText(category) ? category : "Spex";
    }

    /**
     * A hash of everything written, so that a file is rewritten only when its tags would change.
     */
    public static String hash(final Track track, final @Nullable String coverChecksum) {
        final StringBuilder content = new StringBuilder();

        tags(track).forEach((key, value) -> content.append(key).append('=').append(value).append('\n'));
        content.append("uuid=").append(track.getUuid()).append('/').append(track.getAlbum().getUuid()).append('\n');
        content.append("lyrics=").append(track.isLyricsSynced()).append(':').append(track.getLyrics()).append('\n');
        content.append("cover=").append(coverChecksum);
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (final NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Rewrites the file's tags in place, through a temporary file beside it.
     */
    public void write(final Track track, final Path file) throws IOException {
        final AudioFormat format = AudioFormat.ofExtension(track.getFileExtension(), track.getFileFormat());
        final Path temporary = file.resolveSibling("." + track.getUuid() + ".tagging." + format.extension());
        final List<String> arguments = new ArrayList<>(List.of("-i", file.toString(), "-map", "0:a", "-c", "copy", "-map_metadata", "-1"));

        tags(track).forEach((key, value) -> {
            arguments.add("-metadata");
            arguments.add(key + "=" + value);
        });
        switch (format) {
            case MP3 -> {
                arguments.addAll(List.of("-id3v2_version", "3"));
                arguments.addAll(List.of("-metadata", "MusicBrainz Release Track Id=" + track.getUuid()));
                arguments.addAll(List.of("-metadata", "MusicBrainz Album Id=" + track.getAlbum().getUuid()));
            }
            case FLAC, VORBIS, OPUS -> {
                arguments.addAll(List.of("-metadata", "MUSICBRAINZ_RELEASETRACKID=" + track.getUuid()));
                arguments.addAll(List.of("-metadata", "MUSICBRAINZ_ALBUMID=" + track.getAlbum().getUuid()));
            }
            case AAC, ALAC -> arguments.addAll(List.of("-movflags", "+faststart"));
        }
        arguments.addAll(List.of("-f", format.muxer(), temporary.toString()));
        try {
            ffmpeg.ffmpeg(arguments);
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
