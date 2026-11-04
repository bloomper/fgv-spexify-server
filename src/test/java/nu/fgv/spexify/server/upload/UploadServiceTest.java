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

import nu.fgv.spexify.server.TestCallers;
import nu.fgv.spexify.server.TestClock;
import nu.fgv.spexify.server.config.SpexifyProperties;
import nu.fgv.spexify.server.media.MediaStorage;
import nu.fgv.spexify.server.util.error.BadRequestException;
import nu.fgv.spexify.server.util.error.ConflictException;
import nu.fgv.spexify.server.util.error.ResourceNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.util.unit.DataSize;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Resumable uploads: who may upload, offsets, sizes and clean-up.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@ExtendWith(MockitoExtension.class)
class UploadServiceTest {

    private final Map<String, UploadSession> sessions = new HashMap<>();
    private final TestClock clock = new TestClock(Instant.parse("2026-11-01T10:00:00Z"));
    @Mock
    private UploadSessionRepository repository;
    @Mock
    private MediaStorage storage;
    @TempDir
    private Path folder;
    private UploadService service;

    @BeforeEach
    void setUp() {
        final SpexifyProperties properties = new SpexifyProperties(null, null, null,
                new SpexifyProperties.Media(folder, DataSize.ofBytes(100), null, null, null), null, null, null, null, null);

        service = new UploadService(repository, storage, properties, clock);
        lenient().when(storage.uploadFile(anyString())).thenAnswer(invocation -> folder.resolve(invocation.<String>getArgument(0)));
        lenient().when(repository.save(any())).thenAnswer(invocation -> {
            final UploadSession session = invocation.getArgument(0);

            sessions.put(session.getId(), session);
            return session;
        });
        lenient().when(repository.findById(anyString())).thenAnswer(invocation -> Optional.ofNullable(sessions.get(invocation.<String>getArgument(0))));
    }

    @Test
    void onlyEditorsUploadAndNothingAboveTheLimit() {
        assertThatThrownBy(() -> service.create(TestCallers.listener(), 10, "a.flac")).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> service.create(TestCallers.editor(), 101, "a.flac")).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> service.create(TestCallers.editor(), 0, "a.flac")).isInstanceOf(BadRequestException.class);
        assertThat(service.maxSize()).isEqualTo(100);
    }

    @Test
    void chunksAreAppendedAtTheOffsetUntilTheUploadIsComplete() throws Exception {
        final UploadSession session = service.create(TestCallers.editor(), 6, "a".repeat(300));

        assertThat(session.getFileName()).hasSize(255);
        assertThat(service.append(TestCallers.editor(), session.getId(), 0, new ByteArrayInputStream(new byte[]{1, 2, 3}))).isEqualTo(3);
        assertThatThrownBy(() -> service.append(TestCallers.editor(), session.getId(), 0, new ByteArrayInputStream(new byte[]{9})))
                .isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> service.claim(session.getId(), TestCallers.editor())).isInstanceOf(BadRequestException.class);
        assertThat(service.append(TestCallers.editor(), session.getId(), 3, new ByteArrayInputStream(new byte[]{4, 5, 6}))).isEqualTo(6);

        assertThat(session.getCompletedAt()).isEqualTo(clock.instant());
        assertThat(Files.readAllBytes(service.claim(session.getId(), TestCallers.editor()))).containsExactly(1, 2, 3, 4, 5, 6);
    }

    @Test
    void whatABrokenOffChunkWroteIsDroppedWhenTheNextOneComes() throws Exception {
        final UploadSession session = service.create(TestCallers.editor(), 6, "a.flac");

        service.append(TestCallers.editor(), session.getId(), 0, new ByteArrayInputStream(new byte[]{1, 2, 3}));
        // A chunk that broke off after two bytes: the bytes are on disk, but the offset does not count them
        Files.write(folder.resolve(session.getId()), new byte[]{8, 8}, java.nio.file.StandardOpenOption.APPEND);
        assertThat(service.append(TestCallers.editor(), session.getId(), 3, new ByteArrayInputStream(new byte[]{4, 5, 6}))).isEqualTo(6);

        assertThat(Files.readAllBytes(service.claim(session.getId(), TestCallers.editor()))).containsExactly(1, 2, 3, 4, 5, 6);
    }

    @Test
    void aChunkBeyondTheDeclaredLengthIsRefused() throws Exception {
        final UploadSession session = service.create(TestCallers.editor(), 2, "a.flac");

        assertThatThrownBy(() -> service.append(TestCallers.editor(), session.getId(), 0, new ByteArrayInputStream(new byte[3])))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void anUploadIsOnlyItsOwners() throws Exception {
        final UploadSession session = service.create(TestCallers.editor(), 2, "a.flac");

        assertThatThrownBy(() -> service.get(TestCallers.admin(), session.getId())).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.terminate(TestCallers.admin(), session.getId())).isInstanceOf(ResourceNotFoundException.class);
        assertThat(service.get(TestCallers.editor(), session.getId())).isSameAs(session);
    }

    @Test
    void terminatingDeletesTheFile() throws Exception {
        final UploadSession session = service.create(TestCallers.editor(), 2, null);

        service.terminate(TestCallers.editor(), session.getId());

        assertThat(folder.resolve(session.getId())).doesNotExist();
        verify(repository).delete(session);
        assertThat(service.fileName("unknown")).isEmpty();
    }

    @Test
    void uploadsOlderThanADayAreCleanedUp() throws Exception {
        final UploadSession session = service.create(TestCallers.editor(), 2, "a.flac");

        clock.advance(Duration.ofHours(25));
        when(repository.findByCreatedAtBefore(clock.instant().minus(Duration.ofHours(24)))).thenReturn(List.of(session));
        service.cleanUp();

        verify(repository).deleteById(session.getId());
        assertThat(folder.resolve(session.getId())).doesNotExist();
    }
}
