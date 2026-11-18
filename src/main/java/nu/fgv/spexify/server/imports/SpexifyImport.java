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

package nu.fgv.spexify.server.imports;

import nu.fgv.spexify.server.catalog.AlbumKind;
import nu.fgv.spexify.server.catalog.AlbumKindService;
import nu.fgv.spexify.server.catalog.CatalogDtos;
import nu.fgv.spexify.server.catalog.CatalogService;
import nu.fgv.spexify.server.imports.ImportEngine.Batch;
import nu.fgv.spexify.server.imports.ImportEngine.Handler;
import nu.fgv.spexify.server.imports.ImportEngine.ItemResult;
import nu.fgv.spexify.server.imports.ImportRecords.ImportAlbum;
import nu.fgv.spexify.server.imports.ImportRecords.ImportTrack;
import nu.fgv.spexify.server.util.Uuids;
import nu.fgv.spexify.server.util.error.BadRequestException;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Spexify's import API (spec 5.6): albums and their tracks with audio files. They go through the same services as
 * editors' changes, unpublished in {@code unpublished/}; publishing writes the tags.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Service
public class SpexifyImport {

    public static final String ALBUM = "ALBUM";
    public static final String TRACK = "TRACK";

    private final ImportEngine engine;
    private final ImportFileStore files;
    private final CatalogService catalog;
    private final AlbumKindService kinds;

    public SpexifyImport(final ImportEngine engine, final ImportFileStore files, final CatalogService catalog, final AlbumKindService kinds) {
        this.engine = engine;
        this.files = files;
        this.catalog = catalog;
        this.kinds = kinds;
    }

    public List<ItemResult> run(final ImportRecords.Batch input, final boolean dryRun) {
        final int size = (input.albums() == null ? 0 : input.albums().size()) + (input.tracks() == null ? 0 : input.tracks().size());

        if (size > ImportEngine.MAX_RECORDS) {
            throw new BadRequestException("error.import.tooMany", ImportEngine.MAX_RECORDS);
        }

        final Batch batch = new Batch(dryRun, Boolean.TRUE.equals(input.createMissingKinds()));
        final List<ItemResult> results = new ArrayList<>();

        results.addAll(engine.run(albumHandler, input.albums(), batch));
        results.addAll(engine.run(trackHandler, input.tracks(), batch));
        return results;
    }

    public int forget(final String sourcePrefix) {
        return engine.forget(sourcePrefix, List.of(trackHandler, albumHandler));
    }

    private @Nullable UUID albumKind(final @Nullable String name, final Batch batch) {
        if (name == null || name.isBlank()) {
            return null;
        }
        return kinds.match(name).map(AlbumKind::getUuid).orElseGet(() -> {
            if (batch.createMissingKinds()) {
                batch.warn("Ny albumsort: " + name.strip());
                return kinds.createForImport(name.strip()).getUuid();
            }
            throw new BadRequestException("error.kind.unknown", name,
                    kinds.kinds().stream().map(AlbumKindService.AlbumKindDto::nameSv).collect(Collectors.joining(", ")));
        });
    }

    private final Handler<ImportAlbum> albumHandler = new Handler<>() {

        @Override
        public String kind() {
            return ALBUM;
        }

        @Override
        public String sourceKey(final ImportAlbum record) {
            return record.sourceKey();
        }

        @Override
        public UUID apply(final ImportAlbum record, final @Nullable UUID existing, final Batch batch) {
            final CatalogDtos.AlbumInput input = new CatalogDtos.AlbumInput(record.spexIds(), record.title(), record.year(),
                    albumKind(record.kind(), batch), record.description());
            final UUID id = existing == null ? catalog.albumCreate(input).id() : catalog.albumUpdate(existing, input).id();

            if (record.cover() != null) {
                final java.nio.file.Path cover = files.require(record.cover().sha256());

                if (!batch.dryRun()) {
                    catalog.albumImportCover(id, cover, Objects.requireNonNullElse(record.cover().name(), "cover.jpg"));
                }
            }
            return id;
        }

        @Override
        public @Nullable Long version(final UUID id) {
            return catalog.recordVersion(id);
        }

        @Override
        public boolean forget(final UUID id) {
            return catalog.forget(id);
        }
    };

    private final Handler<ImportTrack> trackHandler = new Handler<>() {

        @Override
        public String kind() {
            return TRACK;
        }

        @Override
        public String sourceKey(final ImportTrack record) {
            return record.sourceKey();
        }

        @Override
        public UUID apply(final ImportTrack record, final @Nullable UUID existing, final Batch batch) {
            final java.nio.file.Path file = files.require(record.file().sha256());
            final UUID album = engine.recordOf(ALBUM, record.albumSourceKey());
            final String name = Objects.requireNonNullElse(record.file().name(), record.title());
            final CatalogDtos.TrackInput input = new CatalogDtos.TrackInput(record.discNumber() == null ? 1 : record.discNumber(),
                    record.trackNumber() == null ? 1 : record.trackNumber(), record.title(), record.performers(), record.melody(),
                    record.lyricsAuthor(), record.arranger(), record.lyrics(), Boolean.TRUE.equals(record.lyricsSynced()));

            if (album == null) {
                if (batch.dryRun() && batch.planned(record.albumSourceKey())) {
                    // The album is made earlier in this dry run but not kept: check the file and stop there
                    catalog.trackImport(Uuids.v7(), null, file, name, input, record.numberId(), true);
                    return Uuids.v7();
                }
                throw new BadRequestException("error.field.invalid", "albumSourceKey");
            }

            final UUID id = catalog.trackImport(album, existing, file, name, input, record.numberId(), batch.dryRun());

            return id == null ? Uuids.v7() : id;
        }

        @Override
        public @Nullable Long version(final UUID id) {
            return catalog.recordVersion(id);
        }

        @Override
        public boolean forget(final UUID id) {
            return catalog.forget(id);
        }
    };
}
