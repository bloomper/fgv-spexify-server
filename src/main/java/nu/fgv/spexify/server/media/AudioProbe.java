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

import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Reads what an audio file is with ffprobe: its codec, container, duration, bitrate and tags.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Component
public class AudioProbe {

    private final Ffmpeg ffmpeg;
    private final JsonMapper jsonMapper;

    public AudioProbe(final Ffmpeg ffmpeg, final JsonMapper jsonMapper) {
        this.ffmpeg = ffmpeg;
        this.jsonMapper = jsonMapper;
    }

    /**
     * The file's audio, or empty when ffprobe cannot read it or it has no audio stream.
     */
    public Optional<Result> probe(final Path file) {
        final String json;

        try {
            final List<String> arguments = new ArrayList<>(Ffmpeg.AUDIO_INPUT);

            arguments.addAll(List.of("-v", "error", "-print_format", "json", "-show_format", "-show_streams", file.toString()));
            json = ffmpeg.probe(arguments);
        } catch (final IOException e) {
            return Optional.empty();
        }
        return parse(jsonMapper.readTree(json));
    }

    static Optional<Result> parse(final JsonNode root) {
        JsonNode audio = null;

        for (final JsonNode stream : root.path("streams")) {
            if ("audio".equals(stream.path("codec_type").asString(""))) {
                audio = stream;
                break;
            }
        }
        if (audio == null) {
            return Optional.empty();
        }

        final JsonNode format = root.path("format");
        final Map<String, String> tags = new HashMap<>();

        format.path("tags").properties().forEach(entry -> tags.put(entry.getKey().toLowerCase(Locale.ROOT), entry.getValue().asString("")));
        audio.path("tags").properties().forEach(entry -> tags.putIfAbsent(entry.getKey().toLowerCase(Locale.ROOT), entry.getValue().asString("")));

        final double seconds = parseDouble(format.path("duration").asString(audio.path("duration").asString("0")));
        final String bitrate = format.path("bit_rate").asString(audio.path("bit_rate").asString(""));

        return Optional.of(new Result(
                audio.path("codec_name").asString(""),
                format.path("format_name").asString(""),
                Math.round(seconds * 1000),
                bitrate.isEmpty() ? null : (int) Math.round(parseDouble(bitrate) / 1000),
                Map.copyOf(tags)));
    }

    private static double parseDouble(final String value) {
        try {
            return Double.parseDouble(value);
        } catch (final NumberFormatException e) {
            return 0;
        }
    }

    /**
     * What ffprobe found.
     *
     * @param codec       e.g. {@code flac}, {@code mp3}, {@code aac}, {@code pcm_s16le}
     * @param container   ffprobe's format name, e.g. {@code mov,mp4,m4a,3gp,3g2,mj2}
     * @param durationMs  length in milliseconds
     * @param bitrateKbps overall bitrate, when known
     * @param tags        tags, keys in lower case
     */
    public record Result(String codec, String container, long durationMs, @Nullable Integer bitrateKbps,
                         Map<String, String> tags) {

        public @Nullable String tag(final String... keys) {
            for (final String key : keys) {
                final String value = tags.get(key);

                if (value != null && !value.isBlank()) {
                    return value.strip();
                }
            }
            return null;
        }
    }
}
