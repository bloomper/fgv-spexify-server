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

package nu.fgv.spexify.server.catalog;

import lombok.extern.slf4j.Slf4j;
import nu.fgv.spexify.server.audit.AuditService;
import nu.fgv.spexify.server.catalog.CatalogDtos.AlbumDto;
import nu.fgv.spexify.server.catalog.CatalogDtos.AlbumFilter;
import nu.fgv.spexify.server.catalog.CatalogDtos.AlbumInput;
import nu.fgv.spexify.server.catalog.CatalogDtos.AlbumSpexDto;
import nu.fgv.spexify.server.catalog.CatalogDtos.CategoryDto;
import nu.fgv.spexify.server.catalog.CatalogDtos.SearchResultDto;
import nu.fgv.spexify.server.catalog.CatalogDtos.TrackDto;
import nu.fgv.spexify.server.catalog.CatalogDtos.TrackInput;
import nu.fgv.spexify.server.catalog.CatalogDtos.TrackRefDto;
import nu.fgv.spexify.server.catalog.CatalogDtos.TrashDto;
import nu.fgv.spexify.server.media.AudioPreparer;
import nu.fgv.spexify.server.media.LibraryPlacer;
import nu.fgv.spexify.server.media.MediaStorage;
import nu.fgv.spexify.server.media.PreparedAudio;
import nu.fgv.spexify.server.navidrome.LibraryScanner;
import nu.fgv.spexify.server.spex.SpexDto;
import nu.fgv.spexify.server.spex.SpexService;
import nu.fgv.spexify.server.upload.UploadService;
import nu.fgv.spexify.server.user.Caller;
import nu.fgv.spexify.server.user.CallerService;
import nu.fgv.spexify.server.util.error.BadRequestException;
import nu.fgv.spexify.server.util.error.ResourceNotFoundException;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The catalogue: reading albums and tracks as the caller may see them, and editing them. Every change is placed in the
 * media folder at once ({@link LibraryPlacer}) and recorded in the audit log.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Slf4j
@Service
public class CatalogService {

    private static final int MAX_SEARCH = 50;
    private static final int MAX_TITLE = 255;

    private final AlbumRepository albumRepository;
    private final TrackRepository trackRepository;
    private final CallerService callerService;
    private final SpexService spexService;
    private final LibraryPlacer placer;
    private final MediaStorage storage;
    private final AudioPreparer audioPreparer;
    private final UploadService uploadService;
    private final LibraryScanner scanner;
    private final AuditService auditService;
    private final Clock clock;

    public CatalogService(final AlbumRepository albumRepository, final TrackRepository trackRepository,
                          final CallerService callerService, final SpexService spexService, final LibraryPlacer placer,
                          final MediaStorage storage, final AudioPreparer audioPreparer, final UploadService uploadService,
                          final LibraryScanner scanner, final AuditService auditService, final Clock clock) {
        this.albumRepository = albumRepository;
        this.trackRepository = trackRepository;
        this.callerService = callerService;
        this.spexService = spexService;
        this.placer = placer;
        this.storage = storage;
        this.audioPreparer = audioPreparer;
        this.uploadService = uploadService;
        this.scanner = scanner;
        this.auditService = auditService;
        this.clock = clock;
    }

    // ---------------------------------------------------------------------------------------------------- reading

    @Transactional(readOnly = true)
    public List<AlbumDto> albums(final @Nullable AlbumFilter filter) {
        final Caller caller = callerService.current();

        caller.requireUser();

        final boolean unpublished = filter != null && Boolean.TRUE.equals(filter.includeUnpublished()) && caller.isEditor();
        final List<Album> albums = unpublished
                ? albumRepository.findByTrashedAtIsNullOrderByYearDescTitleAsc()
                : albumRepository.findByPublishedTrueAndTrashedAtIsNullOrderByYearDescTitleAsc();

        return albums.stream()
                .filter(album -> filter == null || filter.categoryId() == null || filter.categoryId().equals(album.getSpex().getCategoryId()))
                .filter(album -> filter == null || filter.year() == null || filter.year().equals(album.getYear()))
                .filter(album -> filter == null || filter.spexId() == null || filter.spexId().equals(album.getSpex().getId()))
                .map(album -> toDto(album, caller))
                .toList();
    }

    @Transactional(readOnly = true)
    public @Nullable AlbumDto album(final Long id) {
        final Caller caller = callerService.current();

        caller.requireUser();
        return albumRepository.findById(id)
                .filter(album -> album.isListed() || caller.isEditor())
                .map(album -> toDto(album, caller))
                .orElse(null);
    }

    @Transactional(readOnly = true)
    public List<AlbumDto> recent(final int limit) {
        final Caller caller = callerService.current();

        caller.requireUser();
        return albumRepository.findByPublishedTrueAndTrashedAtIsNullOrderByPublishedAtDesc(PageRequest.of(0, Math.clamp(limit, 1, 50)))
                .stream().map(album -> toDto(album, caller)).toList();
    }

    @Transactional(readOnly = true)
    public List<CategoryDto> categories() {
        callerService.current().requireUser();

        final Map<Long, String> names = new HashMap<>();
        final Map<Long, Integer> counts = new HashMap<>();

        for (final Album album : albumRepository.findByPublishedTrueAndTrashedAtIsNullOrderByYearDescTitleAsc()) {
            final Long categoryId = album.getSpex().getCategoryId();

            if (categoryId != null) {
                names.put(categoryId, Objects.requireNonNullElse(album.getSpex().getCategoryName(), "?"));
                counts.merge(categoryId, 1, Integer::sum);
            }
        }
        return names.entrySet().stream()
                .map(entry -> new CategoryDto(entry.getKey(), entry.getValue(), counts.get(entry.getKey())))
                .sorted(Comparator.comparing(CategoryDto::albumCount).reversed().thenComparing(CategoryDto::name))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<Integer> years() {
        callerService.current().requireUser();
        return albumRepository.findByPublishedTrueAndTrashedAtIsNullOrderByYearDescTitleAsc().stream()
                .map(Album::getYear)
                .filter(Objects::nonNull)
                .distinct()
                .sorted(Comparator.reverseOrder())
                .toList();
    }

    @Transactional(readOnly = true)
    public SearchResultDto search(final String query) {
        final Caller caller = callerService.current();

        caller.requireUser();

        final String q = query.strip().toLowerCase(Locale.ROOT);

        if (q.length() < 2) {
            return new SearchResultDto(List.of(), List.of());
        }

        final String pattern = "%" + q.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";

        return new SearchResultDto(
                albumRepository.search(pattern, caller.isEditor()).stream().limit(MAX_SEARCH).map(album -> toDto(album, caller)).toList(),
                trackRepository.search(pattern, caller.isEditor(), PageRequest.of(0, MAX_SEARCH)).stream().map(this::toDto).toList());
    }

    /**
     * The tracks of an album the caller may see: listed ones for listeners, every one not in the trash for editors.
     */
    @Transactional(readOnly = true)
    public List<TrackDto> tracks(final Long albumId) {
        final Caller caller = callerService.current();
        final Album album = visibleAlbum(albumId, caller);

        return album.getTracks().stream()
                .filter(track -> caller.isEditor() ? !track.isTrashed() : track.isListed())
                .map(this::toDto)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<TrackRefDto> spexTracks(final Long spexId) {
        final Caller caller = callerService.current();

        if (!caller.isSpexflixService() && !caller.isEditor()) {
            throw new AccessDeniedException("SPEXFLIX or EDITOR required");
        }
        return trackRepository.findListedBySpex(spexId).stream().map(CatalogService::toRef).toList();
    }

    @Transactional(readOnly = true)
    public List<TrackRefDto> tracksByIds(final Collection<Long> ids) {
        final Caller caller = callerService.current();

        if (!caller.isSpexflixService() && !caller.isUser()) {
            throw new AccessDeniedException("SPEXFLIX or USER required");
        }
        return trackRepository.findAllById(ids).stream().filter(Track::isListed).map(CatalogService::toRef).toList();
    }

    @Transactional(readOnly = true)
    public List<TrackDto> tracksByNavidromeIds(final List<String> ids) {
        callerService.current().requireUser();

        final Map<String, Track> byId = trackRepository.findByNavidromeIdIn(ids).stream()
                .filter(Track::isListed)
                .collect(Collectors.toMap(Track::getNavidromeId, track -> track, (a, _) -> a));

        return ids.stream().map(byId::get).filter(Objects::nonNull).map(this::toDto).toList();
    }

    @Transactional(readOnly = true)
    public TrashDto trash() {
        final Caller caller = callerService.current();

        caller.requireEditor();
        return new TrashDto(
                albumRepository.findByTrashedAtIsNotNullOrderByTrashedAtDesc().stream().map(album -> toDto(album, caller)).toList(),
                trackRepository.findByTrashedAtIsNotNullOrderByTrashedAtDesc().stream()
                        .filter(track -> !track.getAlbum().isTrashed())
                        .map(this::toDto).toList());
    }

    /**
     * A track the caller may play or download: listed for listeners, any for editors.
     */
    @Transactional(readOnly = true)
    public Track playableTrack(final Long id) {
        final Caller caller = callerService.current();

        caller.requireUser();

        final Track track = trackRepository.findById(id).orElseThrow(() -> new ResourceNotFoundException("error.track.notFound", id));

        if (!track.isListed() && !caller.isEditor()) {
            throw new ResourceNotFoundException("error.track.notFound", id);
        }
        track.getAlbum().getTitle();
        return track;
    }

    /**
     * An album the caller may see, with its tracks loaded.
     */
    @Transactional(readOnly = true)
    public Album visibleAlbumWithTracks(final Long id) {
        final Album album = visibleAlbum(id, callerService.current());

        album.getTracks().size();
        return album;
    }

    // ---------------------------------------------------------------------------------------------------- albums

    @Transactional
    public AlbumDto albumCreate(final AlbumInput input) {
        final Caller caller = callerService.current();

        caller.requireEditor();

        final Album album = new Album();

        apply(album, input);
        albumRepository.save(album);
        auditService.record("CREATE", "ALBUM", album.getId(), "Skapade albumet " + album.getTitle());
        return toDto(album, caller);
    }

    @Transactional
    public AlbumDto albumUpdate(final Long id, final AlbumInput input) {
        final Caller caller = callerService.current();

        caller.requireEditor();

        final Album album = editableAlbum(id);

        apply(album, input);
        place(album);
        auditService.record("UPDATE", "ALBUM", album.getId(), "Ändrade albumet " + album.getTitle());
        return toDto(album, caller);
    }

    private void apply(final Album album, final AlbumInput input) {
        SpexDto spex = null;

        if (input.spexId() != null) {
            spex = Objects.equals(input.spexId(), album.getSpex().getId()) && album.getSpex().getTitle() != null
                    ? null
                    : spexService.byId(input.spexId());
            if (spex != null) {
                spexService.copy(spex, album.getSpex());
            }
        } else {
            album.setSpex(new SpexRef());
        }

        final String title = StringUtils.hasText(input.title()) ? input.title().strip()
                : album.getSpex().getTitle() != null ? album.getSpex().getTitle() : null;

        if (title == null) {
            throw new BadRequestException("error.album.titleRequired");
        }
        album.setTitle(limit(title));
        album.setYear(input.year() != null ? input.year() : album.getSpex().getYear());
        album.setKind(input.kind());
        album.setDescription(StringUtils.hasText(input.description()) ? input.description().strip() : null);
    }

    @Transactional
    public AlbumDto albumPublish(final Long id, final boolean published) {
        final Caller caller = callerService.current();

        caller.requireEditor();

        final Album album = editableAlbum(id);

        album.setPublished(published);
        if (published && album.getPublishedAt() == null) {
            album.setPublishedAt(clock.instant());
        }
        place(album);
        auditService.record(published ? "PUBLISH" : "UNPUBLISH", "ALBUM", album.getId(),
                (published ? "Publicerade albumet " : "Avpublicerade albumet ") + album.getTitle());
        return toDto(album, caller);
    }

    @Transactional
    public AlbumDto albumTrash(final Long id, final boolean trashed) {
        final Caller caller = callerService.current();

        caller.requireEditor();

        final Album album = albumRepository.findById(id).orElseThrow(() -> new ResourceNotFoundException("error.album.notFound", id));

        album.setTrashedAt(trashed ? clock.instant() : null);
        place(album);
        auditService.record(trashed ? "TRASH" : "RESTORE", "ALBUM", album.getId(),
                (trashed ? "Lade albumet i papperskorgen: " : "Återställde albumet ") + album.getTitle());
        return toDto(album, caller);
    }

    @Transactional
    public void albumDelete(final Long id) {
        callerService.current().requireAdmin();

        final Album album = albumRepository.findById(id).orElseThrow(() -> new ResourceNotFoundException("error.album.notFound", id));

        if (!album.isTrashed()) {
            throw new BadRequestException("error.album.notTrashed");
        }
        deleteAlbum(album);
    }

    private void deleteAlbum(final Album album) {
        final boolean wasInMusic = album.getTracks().stream().anyMatch(track -> track.getLocation() == Location.MUSIC);

        try {
            storage.deleteAlbum(album);
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
        auditService.record("DELETE", "ALBUM", album.getId(), "Raderade albumet " + album.getTitle());
        albumRepository.delete(album);
        if (wasInMusic) {
            scanner.requestScan();
        }
    }

    /**
     * Sets the album's own cover from an uploaded image.
     */
    @Transactional
    public AlbumDto albumCover(final Long id, final byte[] content, final String extension) {
        final Caller caller = callerService.current();

        caller.requireEditor();

        final Album album = editableAlbum(id);

        try {
            if (album.getCoverExtension() != null) {
                java.nio.file.Files.deleteIfExists(storage.coverFile(album));
            }
            album.setCoverExtension(extension);
            java.nio.file.Files.write(storage.coverFile(album), content);
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
        place(album);
        auditService.record("UPDATE", "ALBUM", album.getId(), "Bytte omslag på albumet " + album.getTitle());
        return toDto(album, caller);
    }

    @Transactional
    public AlbumDto albumCoverRemove(final Long id) {
        final Caller caller = callerService.current();

        caller.requireEditor();

        final Album album = editableAlbum(id);

        if (album.getCoverExtension() != null) {
            try {
                java.nio.file.Files.deleteIfExists(storage.coverFile(album));
            } catch (final IOException e) {
                throw new UncheckedIOException(e);
            }
            album.setCoverExtension(null);
            place(album);
            auditService.record("UPDATE", "ALBUM", album.getId(), "Tog bort omslaget på albumet " + album.getTitle());
        }
        return toDto(album, caller);
    }

    @Transactional
    public AlbumDto tracksReorder(final Long albumId, final List<Long> trackIds) {
        final Caller caller = callerService.current();

        caller.requireEditor();

        final Album album = editableAlbum(albumId);
        final Map<Long, Track> tracks = album.getTracks().stream().collect(Collectors.toMap(Track::getId, track -> track));
        final Map<Integer, Integer> numbers = new LinkedHashMap<>();

        if (!tracks.keySet().containsAll(trackIds) || Set.copyOf(trackIds).size() != trackIds.size()) {
            throw new BadRequestException("error.album.reorder");
        }
        for (final Long trackId : trackIds) {
            final Track track = tracks.get(trackId);

            track.setTrackNumber(numbers.merge(track.getDiscNumber(), 1, Integer::sum));
        }
        album.getTracks().sort(Comparator.comparingInt(Track::getDiscNumber).thenComparingInt(Track::getTrackNumber));
        place(album);
        auditService.record("UPDATE", "ALBUM", album.getId(), "Ändrade ordningen på albumet " + album.getTitle());
        return toDto(album, caller);
    }

    // ---------------------------------------------------------------------------------------------------- tracks

    @Transactional
    public TrackDto trackCreate(final Long albumId, final String uploadId) {
        final Caller caller = callerService.current();

        caller.requireEditor();

        final Album album = editableAlbum(albumId);
        final PreparedAudio audio = prepare(uploadId, caller);
        final Track track = new Track();
        final int nextNumber = album.getTracks().stream().filter(t -> !t.isTrashed()).mapToInt(Track::getTrackNumber).max().orElse(0) + 1;
        final String fileName = uploadService.fileName(uploadId);

        track.setAlbum(album);
        track.setTitle(limit(Objects.requireNonNullElse(audio.probe().tag("title"), titleFromFileName(fileName))));
        track.setTrackNumber(parseNumber(audio.probe().tag("track", "tracknumber"), nextNumber));
        track.setDiscNumber(parseNumber(audio.probe().tag("disc", "discnumber"), 1));
        track.setPerformers(audio.probe().tag("artist"));
        track.setLyrics(audio.probe().tag("lyrics", "unsyncedlyrics"));
        track.setLyricsAuthor(audio.probe().tag("lyricist"));
        // A track added to a published album waits for its own publishing
        track.setPublished(!album.isPublished());
        setAudio(track, audio);
        track.setLocation(Location.UNPUBLISHED);
        trackRepository.save(track);
        try {
            storage.store(audio.file(), track);
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
        uploadService.release(uploadId);
        album.getTracks().add(track);
        place(album);
        auditService.record("CREATE", "TRACK", track.getId(), "Laddade upp " + track.getTitle() + " till " + album.getTitle());
        return toDto(track);
    }

    @Transactional
    public TrackDto trackReplaceAudio(final Long id, final String uploadId) {
        final Caller caller = callerService.current();

        caller.requireEditor();

        final Track track = track(id);
        final PreparedAudio audio = prepare(uploadId, caller);
        final boolean wasInMusic = track.getLocation() == Location.MUSIC;

        try {
            storage.delete(track);
            setAudio(track, audio);
            storage.store(audio.file(), track);
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
        uploadService.release(uploadId);
        track.setTagsHash(null);
        place(track.getAlbum());
        if (wasInMusic) {
            scanner.requestScan();
        }
        auditService.record("UPDATE", "TRACK", track.getId(), "Bytte ljudfil för " + track.getTitle());
        return toDto(track);
    }

    @Transactional
    public TrackDto trackUpdate(final Long id, final TrackInput input) {
        callerService.current().requireEditor();

        final Track track = track(id);

        if (!StringUtils.hasText(input.title()) || input.trackNumber() < 1 || input.discNumber() < 1) {
            throw new BadRequestException("error.track.invalid");
        }
        track.setTitle(limit(input.title().strip()));
        track.setDiscNumber(input.discNumber());
        track.setTrackNumber(input.trackNumber());
        track.setPerformers(blankToNull(input.performers()));
        track.setMelody(blankToNull(input.melody()));
        track.setLyricsAuthor(blankToNull(input.lyricsAuthor()));
        track.setArranger(blankToNull(input.arranger()));
        track.setLyrics(StringUtils.hasText(input.lyrics()) ? input.lyrics() : null);
        track.setLyricsSynced(StringUtils.hasText(input.lyrics()) && input.lyricsSynced());
        place(track.getAlbum());
        auditService.record("UPDATE", "TRACK", track.getId(), "Ändrade " + track.getTitle());
        return toDto(track);
    }

    @Transactional
    public TrackDto trackPublish(final Long id, final boolean published) {
        callerService.current().requireEditor();

        final Track track = track(id);

        track.setPublished(published);
        place(track.getAlbum());
        auditService.record(published ? "PUBLISH" : "UNPUBLISH", "TRACK", track.getId(),
                (published ? "Publicerade " : "Avpublicerade ") + track.getTitle());
        return toDto(track);
    }

    @Transactional
    public TrackDto trackTrash(final Long id, final boolean trashed) {
        callerService.current().requireEditor();

        final Track track = trackRepository.findById(id).orElseThrow(() -> new ResourceNotFoundException("error.track.notFound", id));

        track.setTrashedAt(trashed ? clock.instant() : null);
        place(track.getAlbum());
        auditService.record(trashed ? "TRASH" : "RESTORE", "TRACK", track.getId(),
                (trashed ? "Lade i papperskorgen: " : "Återställde ") + track.getTitle());
        return toDto(track);
    }

    @Transactional
    public void trackDelete(final Long id) {
        callerService.current().requireAdmin();

        final Track track = trackRepository.findById(id).orElseThrow(() -> new ResourceNotFoundException("error.track.notFound", id));

        if (!track.isTrashed()) {
            throw new BadRequestException("error.track.notTrashed");
        }
        deleteTrack(track);
    }

    private void deleteTrack(final Track track) {
        try {
            storage.delete(track);
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
        auditService.record("DELETE", "TRACK", track.getId(), "Raderade " + track.getTitle());
        track.getAlbum().getTracks().remove(track);
        trackRepository.delete(track);
    }

    @Transactional
    public void trashEmpty() {
        callerService.current().requireAdmin();
        for (final Track track : trackRepository.findByTrashedAtIsNotNullOrderByTrashedAtDesc()) {
            if (!track.getAlbum().isTrashed()) {
                deleteTrack(track);
            }
        }
        for (final Album album : albumRepository.findByTrashedAtIsNotNullOrderByTrashedAtDesc()) {
            deleteAlbum(album);
        }
    }

    // ---------------------------------------------------------------------------------------------------- helpers

    private PreparedAudio prepare(final String uploadId, final Caller caller) {
        try {
            return audioPreparer.prepare(uploadService.claim(uploadId, caller));
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void setAudio(final Track track, final PreparedAudio audio) {
        track.setFileExtension(audio.format().extension());
        track.setFileFormat(audio.format().name());
        track.setFileSize(audio.size());
        track.setFileChecksum(audio.checksum());
        track.setDurationMs(audio.probe().durationMs());
        track.setBitrate(audio.probe().bitrateKbps());
    }

    private void place(final Album album) {
        if (placer.place(album)) {
            scanner.requestScan();
        }
    }

    private Album editableAlbum(final Long id) {
        final Album album = albumRepository.findById(id).orElseThrow(() -> new ResourceNotFoundException("error.album.notFound", id));

        if (album.isTrashed()) {
            throw new BadRequestException("error.album.trashed");
        }
        return album;
    }

    private Album visibleAlbum(final Long id, final Caller caller) {
        caller.requireUser();
        return albumRepository.findById(id)
                .filter(album -> album.isListed() || caller.isEditor())
                .orElseThrow(() -> new ResourceNotFoundException("error.album.notFound", id));
    }

    private Track track(final Long id) {
        final Track track = trackRepository.findById(id).orElseThrow(() -> new ResourceNotFoundException("error.track.notFound", id));

        if (track.isTrashed() || track.getAlbum().isTrashed()) {
            throw new BadRequestException("error.track.trashed");
        }
        return track;
    }

    static int parseNumber(final @Nullable String value, final int fallback) {
        if (value == null) {
            return fallback;
        }

        final String number = value.contains("/") ? value.substring(0, value.indexOf('/')) : value;

        try {
            final int parsed = Integer.parseInt(number.strip());

            return parsed > 0 ? parsed : fallback;
        } catch (final NumberFormatException e) {
            return fallback;
        }
    }

    static String titleFromFileName(final String fileName) {
        final String base = fileName.contains(".") ? fileName.substring(0, fileName.lastIndexOf('.')) : fileName;
        final String withoutNumber = base.replaceFirst("^\\d{1,3}\\s*[-._ ]\\s*", "").replace('_', ' ').strip();

        return withoutNumber.isEmpty() ? base : withoutNumber;
    }

    private static @Nullable String blankToNull(final @Nullable String value) {
        return StringUtils.hasText(value) ? value.strip() : null;
    }

    private static String limit(final String value) {
        return value.length() > MAX_TITLE ? value.substring(0, MAX_TITLE) : value;
    }

    private AlbumDto toDto(final Album album, final Caller caller) {
        final List<Track> tracks = album.getTracks().stream()
                .filter(track -> caller.isEditor() ? !track.isTrashed() : track.isListed())
                .toList();
        final SpexRef spex = album.getSpex();

        return new AlbumDto(album.getId(), album.getUuid(), album.getTitle(), album.getYear(), album.getKind(),
                album.getDescription(),
                spex.getId() == null ? null : new AlbumSpexDto(spex.getId(), spex.getTitle(), spex.getYear(), spex.getRevival(), spex.getCategoryId(), spex.getCategoryName()),
                album.getCoverExtension() != null, album.isPublished(), album.getPublishedAt(), album.getTrashedAt(),
                album.getNavidromeId(), tracks.size(), tracks.stream().mapToLong(Track::getDurationMs).sum(),
                album.getCreatedAt(), album.getLastModifiedAt());
    }

    private TrackDto toDto(final Track track) {
        return new TrackDto(track.getId(), track.getUuid(), track.getAlbum().getId(), track.getAlbum().getTitle(),
                track.getDiscNumber(), track.getTrackNumber(), track.getTitle(), track.getPerformers(), track.getMelody(),
                track.getLyricsAuthor(), track.getArranger(), track.getLyrics(), track.isLyricsSynced(),
                track.getFileFormat(), track.getFileSize(), track.getDurationMs(), track.getBitrate(), track.isPublished(),
                track.isListed(), track.getTrashedAt(), track.getNavidromeId(), !storage.exists(track));
    }

    private static TrackRefDto toRef(final Track track) {
        return new TrackRefDto(track.getId(), track.getTitle(), track.getDiscNumber(), track.getTrackNumber(),
                track.getAlbum().getId(), track.getAlbum().getTitle(), track.getAlbum().getSpex().getId());
    }

}
