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

import nu.fgv.spexify.server.util.error.BadRequestException;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/**
 * Checks an uploaded file with ffprobe, refuses what is not accepted audio and converts WAV to FLAC.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Component
public class AudioPreparer {

    private final AudioProbe probe;
    private final Ffmpeg ffmpeg;

    public AudioPreparer(final AudioProbe probe, final Ffmpeg ffmpeg) {
        this.probe = probe;
        this.ffmpeg = ffmpeg;
    }

    public PreparedAudio prepare(final Path upload) throws IOException {
        AudioProbe.Result result = probe.probe(upload).orElseThrow(() -> new BadRequestException("error.upload.notAudio"));
        Path file = upload;

        if (AudioFormat.isPcm(result.codec())) {
            final Path flac = upload.resolveSibling(upload.getFileName() + ".flac");

            ffmpeg.ffmpeg(List.of("-i", upload.toString(), "-map", "0:a:0", "-map_metadata", "0", "-c:a", "flac", "-f", "flac", flac.toString()));
            Files.delete(upload);
            file = flac;
            result = probe.probe(flac).orElseThrow(() -> new BadRequestException("error.upload.notAudio"));
        }

        final AudioFormat format = AudioFormat.ofCodec(result.codec());

        if (format == null) {
            throw new BadRequestException("error.upload.unsupportedFormat", result.codec());
        }
        return new PreparedAudio(file, format, Files.size(file), checksum(file), result);
    }

    public static String checksum(final Path file) throws IOException {
        try {
            final MessageDigest digest = MessageDigest.getInstance("SHA-256");

            try (InputStream in = new DigestInputStream(Files.newInputStream(file), digest)) {
                in.transferTo(java.io.OutputStream.nullOutputStream());
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (final NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
