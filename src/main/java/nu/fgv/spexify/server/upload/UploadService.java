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

package nu.fgv.spexify.server.upload;

import lombok.extern.slf4j.Slf4j;
import nu.fgv.spexify.server.config.SpexifyProperties;
import nu.fgv.spexify.server.media.MediaStorage;
import nu.fgv.spexify.server.user.Caller;
import nu.fgv.spexify.server.util.error.BadRequestException;
import nu.fgv.spexify.server.util.error.ConflictException;
import nu.fgv.spexify.server.util.error.ResourceNotFoundException;
import org.jspecify.annotations.Nullable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.UUID;

/**
 * Uploads: created, appended to chunk by chunk, then claimed by the operation that uses the file. Only the person who
 * started an upload can append to or use it.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Slf4j
@Service
public class UploadService {

    public static final long MAX_CHUNK = 60L * 1024 * 1024;
    private static final Duration KEEP = Duration.ofHours(24);

    private final UploadSessionRepository repository;
    private final MediaStorage storage;
    private final long maxSize;
    private final Clock clock;

    public UploadService(final UploadSessionRepository repository, final MediaStorage storage,
                         final SpexifyProperties properties, final Clock clock) {
        this.repository = repository;
        this.storage = storage;
        this.maxSize = properties.media().maxTrackSize().toBytes();
        this.clock = clock;
    }

    public long maxSize() {
        return maxSize;
    }

    @Transactional
    public UploadSession create(final Caller caller, final long length, final @Nullable String fileName) throws IOException {
        caller.requireEditor();
        if (length <= 0 || length > maxSize) {
            throw new BadRequestException("error.upload.tooLarge");
        }

        final UploadSession session = new UploadSession();

        session.setId(UUID.randomUUID().toString());
        session.setOwner(caller.subject());
        session.setLength(length);
        session.setOffset(0);
        session.setFileName(fileName == null ? null : fileName.length() > 255 ? fileName.substring(0, 255) : fileName);
        session.setCreatedAt(clock.instant());
        Files.createFile(storage.uploadFile(session.getId()));
        return repository.save(session);
    }

    @Transactional(readOnly = true)
    public UploadSession get(final Caller caller, final String id) {
        return owned(caller, id);
    }

    /**
     * Appends a chunk at the given offset, which must be where the upload stands.
     *
     * @return the new offset
     */
    @Transactional
    public long append(final Caller caller, final String id, final long offset, final InputStream body) throws IOException {
        final UploadSession session = owned(caller, id);

        if (offset != session.getOffset()) {
            throw new ConflictException("error.upload.offset");
        }

        final Path file = storage.uploadFile(id);
        final long remaining = session.getLength() - session.getOffset();
        long written = 0;

        try (OutputStream out = Files.newOutputStream(file, StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
            final byte[] buffer = new byte[64 * 1024];
            int read;

            while ((read = body.read(buffer)) != -1) {
                if (written + read > remaining || written + read > MAX_CHUNK) {
                    throw new BadRequestException("error.upload.tooLarge");
                }
                out.write(buffer, 0, read);
                written += read;
            }
        }
        session.setOffset(session.getOffset() + written);
        if (session.isComplete()) {
            session.setCompletedAt(clock.instant());
        }
        return session.getOffset();
    }

    @Transactional
    public void terminate(final Caller caller, final String id) throws IOException {
        final UploadSession session = owned(caller, id);

        Files.deleteIfExists(storage.uploadFile(session.getId()));
        repository.delete(session);
    }

    /**
     * The file of a finished upload, for an operation that will move it away.
     */
    @Transactional(readOnly = true)
    public Path claim(final String id, final Caller caller) {
        final UploadSession session = owned(caller, id);

        if (!session.isComplete()) {
            throw new BadRequestException("error.upload.incomplete");
        }
        return storage.uploadFile(id);
    }

    public String fileName(final String id) {
        return repository.findById(id).map(UploadSession::getFileName).filter(Objects::nonNull).orElse("");
    }

    /**
     * Forgets an upload whose file has been used.
     */
    @Transactional
    public void release(final String id) {
        repository.deleteById(id);
        try {
            Files.deleteIfExists(storage.uploadFile(id));
        } catch (final IOException e) {
            log.warn("Could not delete upload {}", id, e);
        }
    }

    @Scheduled(fixedDelay = 1, timeUnit = java.util.concurrent.TimeUnit.HOURS, initialDelay = 5)
    @Transactional
    public void cleanUp() {
        for (final UploadSession session : repository.findByCreatedAtBefore(clock.instant().minus(KEEP))) {
            release(session.getId());
        }
    }

    private UploadSession owned(final Caller caller, final String id) {
        return repository.findById(id)
                .filter(session -> session.getOwner().equals(caller.subject()))
                .orElseThrow(() -> new ResourceNotFoundException("error.upload.notFound", id));
    }
}
