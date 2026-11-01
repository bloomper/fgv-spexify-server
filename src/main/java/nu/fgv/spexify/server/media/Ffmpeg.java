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
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Runs {@code ffmpeg} and {@code ffprobe}, with arguments as a list so that nothing is interpreted by a shell.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Slf4j
@Component
public class Ffmpeg {

    /**
     * Input options for audio files people uploaded: local files and audio containers only, so that a file posing as a
     * playlist cannot make ffmpeg read other files or URLs. They go before the {@code -i} they apply to.
     */
    public static final List<String> AUDIO_INPUT = List.of("-protocol_whitelist", "file",
            "-format_whitelist", "flac,mp3,mov,ogg,wav,w64,aiff,caf,aac");
    private static final Duration TIMEOUT = Duration.ofMinutes(10);

    private static String run(final List<String> command) throws IOException {
        final Process process = new ProcessBuilder(command).redirectErrorStream(false).start();
        final CompletableFuture<String> stdout = read(process.getInputStream());
        final CompletableFuture<String> stderr = read(process.getErrorStream());

        try {
            if (!process.waitFor(TIMEOUT.toSeconds(), TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IOException(command.getFirst() + " timed out");
            }
            if (process.exitValue() != 0) {
                throw new IOException(command.getFirst() + " failed: " + stderr.join().strip());
            }
            return stdout.join();
        } catch (final InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new IOException(command.getFirst() + " was interrupted", e);
        }
    }

    private static CompletableFuture<String> read(final InputStream stream) {
        return CompletableFuture.supplyAsync(() -> {
            try (stream) {
                return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            } catch (final IOException e) {
                return "";
            }
        });
    }

    public String probe(final List<String> arguments) throws IOException {
        final List<String> command = new ArrayList<>();

        command.add("ffprobe");
        command.addAll(arguments);
        return run(command);
    }

    public void ffmpeg(final List<String> arguments) throws IOException {
        final List<String> command = new ArrayList<>(List.of("ffmpeg", "-nostdin", "-hide_banner", "-loglevel", "error", "-y"));

        command.addAll(arguments);
        run(command);
    }
}
