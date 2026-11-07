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

import nu.fgv.spexify.server.TestCallers;
import nu.fgv.spexify.server.audit.AuditService;
import nu.fgv.spexify.server.catalog.CatalogDtos.AlbumDto;
import nu.fgv.spexify.server.catalog.CatalogDtos.AlbumFilter;
import nu.fgv.spexify.server.catalog.CatalogDtos.AlbumInput;
import nu.fgv.spexify.server.catalog.CatalogDtos.TrackDto;
import nu.fgv.spexify.server.catalog.CatalogDtos.TrackInput;
import nu.fgv.spexify.server.media.AudioFormat;
import nu.fgv.spexify.server.media.AudioPreparer;
import nu.fgv.spexify.server.media.AudioProbe;
import nu.fgv.spexify.server.media.LibraryPlacer;
import nu.fgv.spexify.server.media.Loudness;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static nu.fgv.spexify.server.util.TestUuids.uuid;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Who sees which albums and tracks, and the rules for editing them.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@ExtendWith(MockitoExtension.class)
class CatalogServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-01T10:00:00Z");
    private static final SpexDto CLEOPATRA = new SpexDto(uuid(7), "Cleopatra", 2024, false, null, uuid(1), "Chalmersspexet");
    private static final AlbumKind SHOW = kind(1, "Föreställning", "Show");
    private static final AlbumKind STUDIO = kind(2, "Studio", "Studio");
    private final List<Album> albums = new ArrayList<>();
    @Mock
    private AlbumRepository albumRepository;
    @Mock
    private AlbumKindService kindService;
    @Mock
    private TrackRepository trackRepository;
    @Mock
    private CallerService callerService;
    @Mock
    private SpexService spexService;
    @Mock
    private LibraryPlacer placer;
    @Mock
    private MediaStorage storage;
    @Mock
    private AudioPreparer audioPreparer;
    @Mock
    private UploadService uploadService;
    @Mock
    private LibraryScanner scanner;
    @Mock
    private AuditService auditService;
    @TempDir
    private Path folder;
    private CatalogService service;
    private Album published;
    private Album unpublished;

    static AlbumKind kind(final long id, final String nameSv, final String nameEn) {
        final AlbumKind kind = new AlbumKind();

        kind.setId(id);
        kind.setUuid(uuid(100 + id));
        kind.setNameSv(nameSv);
        kind.setNameEn(nameEn);
        kind.setPosition((int) id);
        return kind;
    }

    private static Album album(final long id, final String title, final boolean isPublished) {
        final Album album = new Album();

        album.setId(id);
        album.setUuid(uuid(id));
        album.setTitle(title);
        album.setYear(2024);
        album.setKind(SHOW);
        album.setPublished(isPublished);
        return album;
    }

    private static SpexRef spex(final Album album, final UUID id, final UUID parentId, final UUID categoryId, final String categoryName) {
        final SpexRef ref = new SpexRef();

        ref.setId(id);
        ref.setParentId(parentId);
        ref.setCategoryId(categoryId);
        ref.setCategoryName(categoryName);
        album.getSpex().add(ref);
        return ref;
    }

    private static Track track(final long id, final Album album, final int number, final String title) {
        final Track track = new Track();

        track.setId(id);
        track.setUuid(uuid(id));
        track.setAlbum(album);
        track.setTrackNumber(number);
        track.setTitle(title);
        track.setFileExtension("flac");
        track.setFileFormat("FLAC");
        album.getTracks().add(track);
        return track;
    }

    private void as(final Caller caller) {
        when(callerService.current()).thenReturn(caller);
    }

    @BeforeEach
    void setUp() {
        service = new CatalogService(albumRepository, kindService, trackRepository, callerService, spexService, placer, storage, audioPreparer,
                uploadService, scanner, auditService, Clock.fixed(NOW, ZoneOffset.UTC));
        lenient().when(kindService.kindOrDefault(null)).thenReturn(SHOW);
        lenient().when(kindService.kindOrDefault(STUDIO.getUuid())).thenReturn(STUDIO);
        published = album(1L, "Cleopatra", true);
        unpublished = album(2L, "Opublicerat", false);
        track(11L, published, 1, "Ouvertyr");
        track(12L, published, 2, "Duetten").setPublished(false);
        albums.addAll(List.of(published, unpublished));
        lenient().when(albumRepository.findByUuid(any(UUID.class))).thenAnswer(invocation ->
                albums.stream().filter(album -> album.getUuid().equals(invocation.getArgument(0))).findFirst());
        lenient().when(trackRepository.findByUuid(any(UUID.class))).thenAnswer(invocation -> albums.stream()
                .flatMap(album -> album.getTracks().stream())
                .filter(track -> track.getUuid().equals(invocation.getArgument(0))).findFirst());
        lenient().when(storage.exists(any(Track.class))).thenReturn(true);
        lenient().doAnswer(invocation -> {
            final SpexDto spex = invocation.getArgument(0);
            final SpexRef ref = invocation.getArgument(1);

            ref.setId(spex.id());
            ref.setParentId(spex.parentId());
            ref.setTitle(spex.title());
            ref.setYear(spex.year());
            ref.setCategoryId(spex.categoryId());
            ref.setCategoryName(spex.categoryName());
            return null;
        }).when(spexService).copy(any(), any());
    }

    // ---------------------------------------------------------------------------------------------------- reading

    @Test
    void listenersSeeOnlyPublishedAlbumsAndListedTracks() {
        as(TestCallers.listener());
        when(albumRepository.findByPublishedTrueAndTrashedAtIsNullOrderByYearDescTitleAsc()).thenReturn(List.of(published));

        final List<AlbumDto> result = service.albums(new AlbumFilter(null, null, null, true));

        assertThat(result).extracting(AlbumDto::title).containsExactly("Cleopatra");
        assertThat(result.getFirst().trackCount()).isEqualTo(1);
        assertThat(service.album(uuid(2))).isNull();
        when(albumRepository.findByUuidIn(List.of(uuid(1), uuid(2)))).thenReturn(albums);
        final Map<UUID, List<TrackDto>> tracks = service.tracksOf(List.of(uuid(1), uuid(2)));
        assertThat(tracks.get(uuid(1))).extracting(TrackDto::title).containsExactly("Ouvertyr");
        assertThat(tracks.get(uuid(2))).isEmpty();
    }

    @Test
    void editorsSeeUnpublishedAlbumsWhenTheyAskAndEveryTrackNotInTheTrash() {
        as(TestCallers.editor());
        when(albumRepository.findByTrashedAtIsNullOrderByYearDescTitleAsc()).thenReturn(albums);

        assertThat(service.albums(new AlbumFilter(null, null, null, true))).extracting(AlbumDto::title).containsExactly("Cleopatra", "Opublicerat");
        assertThat(service.album(uuid(2))).isNotNull();
        when(albumRepository.findByUuidIn(List.of(uuid(1)))).thenReturn(List.of(published));
        assertThat(service.tracksOf(List.of(uuid(1))).get(uuid(1))).extracting(TrackDto::title).containsExactly("Ouvertyr", "Duetten");
    }

    @Test
    void albumsAreFilteredByCategoryYearAndSpex() {
        as(TestCallers.listener());
        spex(published, uuid(7), null, uuid(1), null);
        when(albumRepository.findByPublishedTrueAndTrashedAtIsNullOrderByYearDescTitleAsc()).thenReturn(List.of(published));

        assertThat(service.albums(new AlbumFilter(uuid(1), 2024, uuid(7), null))).hasSize(1);
        assertThat(service.albums(new AlbumFilter(uuid(2), null, null, null))).isEmpty();
        assertThat(service.albums(new AlbumFilter(null, 1999, null, null))).isEmpty();
        assertThat(service.albums(new AlbumFilter(null, null, uuid(8), null))).isEmpty();
    }

    @Test
    void albumsOfAnOriginalIncludeThoseOfItsRevivalsAndCompilations() {
        as(TestCallers.listener());

        final Album revival = album(3L, "Cleopatra 2028", true);
        final Album compilation = album(4L, "Det bästa", true);
        final Album other = album(5L, "Nobel", true);

        spex(published, uuid(7), null, uuid(1), "Chalmersspexet");
        spex(revival, uuid(70), uuid(7), uuid(1), "Chalmersspexet");
        spex(compilation, uuid(8), null, uuid(2), "Bobspexet");
        spex(compilation, uuid(70), uuid(7), uuid(1), "Chalmersspexet");
        spex(other, uuid(8), null, uuid(2), "Bobspexet");
        when(albumRepository.findByPublishedTrueAndTrashedAtIsNullOrderByYearDescTitleAsc()).thenReturn(List.of(published, revival, compilation, other));

        assertThat(service.albums(new AlbumFilter(null, null, uuid(7), null))).extracting(AlbumDto::title)
                .containsExactly("Cleopatra", "Cleopatra 2028", "Det bästa");
        assertThat(service.albums(new AlbumFilter(null, null, uuid(70), null))).extracting(AlbumDto::title)
                .containsExactly("Cleopatra 2028", "Det bästa");
        assertThat(service.albums(new AlbumFilter(uuid(2), null, null, null))).extracting(AlbumDto::title)
                .as("by the main spex's category").containsExactly("Det bästa", "Nobel");
        albums.add(compilation);
        assertThat(service.album(uuid(4)).spex()).extracting(CatalogDtos.AlbumSpexDto::familyId).containsExactly(uuid(8), uuid(7));
    }

    @Test
    void withoutUserNothingIsRead() {
        as(TestCallers.noAccess());

        assertThatThrownBy(() -> service.albums(null)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> service.search("cleo")).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> service.playableTrack(uuid(11))).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void searchNeedsTwoCharactersAndEscapesWildcards() {
        as(TestCallers.listener());

        assertThat(service.search(" c ").albums()).isEmpty();
        verify(albumRepository, never()).search(any(), eq(false));

        service.search("50%_\\");
        verify(albumRepository).search("%50\\%\\_\\\\%", false);
    }

    @Test
    void categoriesAreCountedAndSortedByCountThenName() {
        as(TestCallers.listener());

        final Album other = album(3L, "Nobel", true);
        final Album third = album(4L, "Fritiof", true);

        spex(published, uuid(7), null, uuid(1), "Chalmersspexet");
        spex(other, uuid(8), null, uuid(1), "Chalmersspexet");
        spex(third, uuid(9), null, uuid(2), "Bobspexet");
        when(albumRepository.findByPublishedTrueAndTrashedAtIsNullOrderByYearDescTitleAsc()).thenReturn(List.of(published, other, third, unpublished));

        assertThat(service.categories()).extracting(category -> category.name() + ":" + category.albumCount())
                .containsExactly("Chalmersspexet:2", "Bobspexet:1");
    }

    @Test
    void yearsAreDistinctNewestFirst() {
        as(TestCallers.listener());

        final Album older = album(3L, "Nobel", true);

        older.setYear(1975);
        unpublished.setYear(null);
        when(albumRepository.findByPublishedTrueAndTrashedAtIsNullOrderByYearDescTitleAsc()).thenReturn(List.of(older, published, unpublished, published));

        assertThat(service.years()).containsExactly(2024, 1975);
    }

    @Test
    void anUnlistedTrackIsNotFoundForListenersButPlayableForEditors() {
        as(TestCallers.listener());
        assertThatThrownBy(() -> service.playableTrack(uuid(12))).isInstanceOf(ResourceNotFoundException.class);

        as(TestCallers.editor());
        assertThat(service.playableTrack(uuid(12)).getTitle()).isEqualTo("Duetten");
    }

    @Test
    void spexTracksAreForSpexflixAndEditorsOnly() {
        as(TestCallers.listener());
        assertThatThrownBy(() -> service.spexTracks(List.of(uuid(7)))).isInstanceOf(AccessDeniedException.class);

        as(TestCallers.spexflixService());
        when(trackRepository.findListedBySpex(Set.of(uuid(7), uuid(8)))).thenReturn(List.of(published.getTracks().getFirst()));
        assertThat(service.spexTracks(List.of(uuid(7), uuid(8), uuid(7)))).extracting(CatalogDtos.TrackRefDto::title).containsExactly("Ouvertyr");
        assertThat(service.spexTracks(List.of())).isEmpty();
    }

    @Test
    void tracksByIdsLeaveOutUnlistedTracks() {
        as(TestCallers.spexflixService());
        when(trackRepository.findByUuidIn(List.of(uuid(11), uuid(12)))).thenReturn(published.getTracks());

        assertThat(service.tracksByIds(List.of(uuid(11), uuid(12)))).extracting(CatalogDtos.TrackRefDto::id).containsExactly(uuid(11));
    }

    @Test
    void tracksByNavidromeIdsKeepTheAskedOrderAndSkipUnknownOnes() {
        as(TestCallers.listener());

        final Track first = published.getTracks().getFirst();
        final Track second = track(13L, published, 3, "Finalen");

        first.setNavidromeId("nd-1");
        second.setNavidromeId("nd-3");
        when(trackRepository.findByNavidromeIdIn(List.of("nd-3", "nd-x", "nd-1"))).thenReturn(List.of(first, second));

        assertThat(service.tracksByNavidromeIds(List.of("nd-3", "nd-x", "nd-1"))).extracting(TrackDto::title).containsExactly("Finalen", "Ouvertyr");
    }

    @Test
    void theTrashListsTracksOfAlbumsThatAreNotTrashedThemselves() {
        as(TestCallers.editor());

        final Album trashed = album(3L, "I papperskorgen", false);
        final Track inTrashedAlbum = track(31L, trashed, 1, "Med albumet");
        final Track alone = published.getTracks().getFirst();

        trashed.setTrashedAt(NOW);
        inTrashedAlbum.setTrashedAt(NOW);
        alone.setTrashedAt(NOW);
        when(albumRepository.findByTrashedAtIsNotNullOrderByTrashedAtDesc()).thenReturn(List.of(trashed));
        when(trackRepository.findByTrashedAtIsNotNullOrderByTrashedAtDesc()).thenReturn(List.of(inTrashedAlbum, alone));

        assertThat(service.trash().albums()).extracting(AlbumDto::title).containsExactly("I papperskorgen");
        assertThat(service.trash().tracks()).extracting(TrackDto::title).containsExactly("Ouvertyr");
    }

    // ---------------------------------------------------------------------------------------------------- albums

    @Test
    void aNewAlbumTakesTitleAndYearFromItsSpexAndStartsUnpublished() {
        as(TestCallers.editor());
        when(spexService.byId(uuid(7))).thenReturn(CLEOPATRA);

        final AlbumDto created = service.albumCreate(new AlbumInput(List.of(uuid(7)), " ", null, null, "  Inspelat live.  "));

        assertThat(created.title()).isEqualTo("Cleopatra");
        assertThat(created.year()).isEqualTo(2024);
        assertThat(created.published()).isFalse();
        assertThat(created.description()).isEqualTo("Inspelat live.");
        assertThat(created.kind().nameEn()).isEqualTo("Show");
        assertThat(created.spex()).extracting(CatalogDtos.AlbumSpexDto::id).containsExactly(uuid(7));
        verify(auditService).record(eq("CREATE"), eq("ALBUM"), any(), eq("Skapade albumet Cleopatra"));
    }

    @Test
    void aCompilationWithoutSpexNeedsATitle() {
        as(TestCallers.editor());

        assertThatThrownBy(() -> service.albumCreate(new AlbumInput(List.of(), null, 2023, null, null)))
                .isInstanceOf(BadRequestException.class);

        final AlbumDto created = service.albumCreate(new AlbumInput(null, " Jubileum ", 2023, null, null));

        assertThat(created.title()).isEqualTo("Jubileum");
        assertThat(created.spex()).isEmpty();
    }

    @Test
    void titlesAreCutAt255Characters() {
        as(TestCallers.editor());

        assertThat(service.albumCreate(new AlbumInput(null, "x".repeat(300), null, null, null)).title()).hasSize(255);
    }

    @Test
    void anUpdateKeepsTheSpexWithoutAskingSpexregisterAgain() {
        as(TestCallers.editor());
        spex(published, uuid(7), null, null, null).setTitle("Cleopatra");

        service.albumUpdate(uuid(1), new AlbumInput(List.of(uuid(7)), "Cleopatra – studio", 2025, STUDIO.getUuid(), null));

        verify(spexService, never()).byId(any());
        assertThat(published.getTitle()).isEqualTo("Cleopatra – studio");
        assertThat(published.getYear()).isEqualTo(2025);
        assertThat(published.getKind()).isSameAs(STUDIO);
        verify(placer).place(published);
    }

    @Test
    void listenersCannotEditAndTrashedAlbumsCannotBeEdited() {
        as(TestCallers.listener());
        assertThatThrownBy(() -> service.albumPublish(uuid(1), false)).isInstanceOf(AccessDeniedException.class);

        as(TestCallers.editor());
        published.setTrashedAt(NOW);
        assertThatThrownBy(() -> service.albumPublish(uuid(1), false)).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> service.albumPublish(uuid(99), true)).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void publishingSetsTheDateOnceAndRequestsAScanWhenFilesMove() {
        as(TestCallers.editor());
        when(placer.place(unpublished)).thenReturn(true);

        service.albumPublish(uuid(2), true);
        assertThat(unpublished.getPublishedAt()).isEqualTo(NOW);
        verify(scanner).requestScan();

        unpublished.setPublishedAt(Instant.parse("2020-01-01T00:00:00Z"));
        service.albumPublish(uuid(2), false);
        service.albumPublish(uuid(2), true);
        assertThat(unpublished.getPublishedAt()).isEqualTo(Instant.parse("2020-01-01T00:00:00Z"));
    }

    @Test
    void trashingAndRestoringAnAlbumIsAudited() {
        as(TestCallers.editor());

        service.albumTrash(uuid(1), true);
        assertThat(published.getTrashedAt()).isEqualTo(NOW);
        service.albumTrash(uuid(1), false);
        assertThat(published.getTrashedAt()).isNull();
        verify(auditService).record("TRASH", "ALBUM", uuid(1), "Lade albumet i papperskorgen: Cleopatra");
        verify(auditService).record("RESTORE", "ALBUM", uuid(1), "Återställde albumet Cleopatra");
    }

    @Test
    void onlyAdminsDeleteAndOnlyFromTheTrash() throws Exception {
        as(TestCallers.editor());
        assertThatThrownBy(() -> service.albumDelete(uuid(1))).isInstanceOf(AccessDeniedException.class);

        as(TestCallers.admin());
        assertThatThrownBy(() -> service.albumDelete(uuid(1))).isInstanceOf(BadRequestException.class);

        published.setTrashedAt(NOW);
        published.getTracks().getFirst().setLocation(Location.MUSIC);
        service.albumDelete(uuid(1));
        verify(storage).deleteAlbumAfterCommit(published);
        verify(albumRepository).delete(published);
        verify(scanner).requestScan();
    }

    @Test
    void emptyingTheTrashDeletesLooseTracksAndTrashedAlbums() throws Exception {
        as(TestCallers.admin());

        final Track loose = published.getTracks().getFirst();

        loose.setTrashedAt(NOW);
        unpublished.setTrashedAt(NOW);
        when(trackRepository.findByTrashedAtIsNotNullOrderByTrashedAtDesc()).thenReturn(List.of(loose));
        when(albumRepository.findByTrashedAtIsNotNullOrderByTrashedAtDesc()).thenReturn(List.of(unpublished));

        service.trashEmpty();

        verify(storage).deleteAfterCommit(loose);
        verify(trackRepository).delete(loose);
        assertThat(published.getTracks()).extracting(Track::getTitle).containsExactly("Duetten");
        verify(albumRepository).delete(unpublished);
    }

    @Test
    void aCoverIsWrittenAndRemoved() throws Exception {
        as(TestCallers.editor());

        // MediaStorage keeps the earlier cover until the commit (MediaStorageTest)
        assertThat(service.albumCover(uuid(1), new byte[]{1, 2, 3}, "png").hasCover()).isTrue();
        verify(storage).replaceCover(published, null, new byte[]{1, 2, 3});

        assertThat(service.albumCoverRemove(uuid(1)).hasCover()).isFalse();
        verify(storage).replaceCover(published, "png", null);
    }

    @Test
    void reorderingNumbersEachDiscFromOne() {
        as(TestCallers.editor());

        final Track second = published.getTracks().get(1);
        final Track onDiscTwo = track(13L, published, 1, "Andra skivan");

        onDiscTwo.setDiscNumber(2);
        service.tracksReorder(uuid(1), List.of(uuid(13), uuid(12), uuid(11)));

        assertThat(second.getTrackNumber()).isEqualTo(1);
        assertThat(published.getTracks().getFirst().getTitle()).isEqualTo("Duetten");
        assertThat(onDiscTwo.getTrackNumber()).isEqualTo(1);
        assertThatThrownBy(() -> service.tracksReorder(uuid(1), List.of(uuid(11), uuid(11)))).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> service.tracksReorder(uuid(1), List.of(uuid(99)))).isInstanceOf(BadRequestException.class);
    }

    // ---------------------------------------------------------------------------------------------------- tracks

    private void upload(final String fileName, final Map<String, String> tags) throws Exception {
        final Path file = folder.resolve("upload");

        when(uploadService.claim("u-1", TestCallers.editor())).thenReturn(file);
        lenient().when(uploadService.fileName("u-1")).thenReturn(fileName);
        when(audioPreparer.prepare(file)).thenReturn(new PreparedAudio(file, AudioFormat.FLAC, 2048, "sha",
                new AudioProbe.Result("flac", "flac", 61_000, 900, tags), new Loudness(-20.0, -1.0)));
    }

    @Test
    void aNewTrackTakesItsFieldsFromTheTagsAndWaitsForPublishingInAPublishedAlbum() throws Exception {
        as(TestCallers.editor());
        upload("07 Något.flac", Map.of("title", "Finalen", "track", "7/12", "disc", "2", "artist", "Ensemblen", "lyricist", "Kalle"));

        final TrackDto created = service.trackCreate(uuid(1), "u-1");

        assertThat(created.title()).isEqualTo("Finalen");
        assertThat(created.trackNumber()).isEqualTo(7);
        assertThat(created.discNumber()).isEqualTo(2);
        assertThat(created.performers()).isEqualTo("Ensemblen");
        assertThat(created.lyricsAuthor()).isEqualTo("Kalle");
        assertThat(created.published()).isFalse();
        assertThat(created.durationMs()).isEqualTo(61_000);
        verify(uploadService).release("u-1");
    }

    @Test
    void withoutTagsATrackIsNamedByItsFileAndNumberedLast() throws Exception {
        as(TestCallers.editor());
        upload("03 - Hovets_klagan.mp3", Map.of());

        final TrackDto created = service.trackCreate(uuid(2), "u-1");

        assertThat(created.title()).isEqualTo("Hovets klagan");
        assertThat(created.trackNumber()).isEqualTo(1);
        assertThat(created.published()).isTrue();
    }

    @Test
    void trackNumbersAndFileNamesAreParsedLeniently() {
        assertThat(CatalogService.parseNumber("3/10", 9)).isEqualTo(3);
        assertThat(CatalogService.parseNumber(" 4 ", 9)).isEqualTo(4);
        assertThat(CatalogService.parseNumber("0", 9)).isEqualTo(9);
        assertThat(CatalogService.parseNumber("A1", 9)).isEqualTo(9);
        assertThat(CatalogService.parseNumber(null, 9)).isEqualTo(9);
        assertThat(CatalogService.titleFromFileName("01 Ouvertyr.flac")).isEqualTo("Ouvertyr");
        assertThat(CatalogService.titleFromFileName("12.Finalen.ogg")).isEqualTo("Finalen");
        assertThat(CatalogService.titleFromFileName("2024.flac")).isEqualTo("2024");
        assertThat(CatalogService.titleFromFileName("utan-punkt")).isEqualTo("utan-punkt");
    }

    @Test
    void replacingAudioInTheMusicFolderRequestsAScan() throws Exception {
        as(TestCallers.editor());
        upload("ny.flac", Map.of());

        final Track track = published.getTracks().getFirst();

        track.setLocation(Location.MUSIC);
        track.setTagsHash("old");
        when(storage.trackFile(track)).thenReturn(folder.resolve("old.flac"));
        when(storage.uploadFile("u-1")).thenReturn(folder.resolve("upload"));
        service.trackReplaceAudio(uuid(11), "u-1");

        // The old file is kept aside until the transaction completes
        verify(storage).replace(folder.resolve("upload"), track, folder.resolve("old.flac"), folder.resolve("upload"));
        verify(storage, never()).delete(track);
        assertThat(track.getTagsHash()).isNull();
        assertThat(track.getFileSize()).isEqualTo(2048);
        verify(scanner).requestScan();
    }

    @Test
    void anUpdateValidatesAndClearsBlankFields() {
        as(TestCallers.editor());

        assertThatThrownBy(() -> service.trackUpdate(uuid(11), new TrackInput(1, 0, "Titel", null, null, null, null, null, false)))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> service.trackUpdate(uuid(11), new TrackInput(1, 1, " ", null, null, null, null, null, false)))
                .isInstanceOf(BadRequestException.class);

        final TrackDto updated = service.trackUpdate(uuid(11), new TrackInput(1, 2, " Uvertyr ", " ", "Dancing Queen", null, null, "  ", true));

        assertThat(updated.title()).isEqualTo("Uvertyr");
        assertThat(updated.performers()).isNull();
        assertThat(updated.melody()).isEqualTo("Dancing Queen");
        assertThat(updated.lyrics()).isNull();
        assertThat(updated.lyricsSynced()).isFalse();
    }

    @Test
    void tracksInTheTrashCannotBeEditedButCanBeRestored() {
        as(TestCallers.editor());
        published.getTracks().getFirst().setTrashedAt(NOW);

        assertThatThrownBy(() -> service.trackPublish(uuid(11), true)).isInstanceOf(BadRequestException.class);
        assertThat(service.trackTrash(uuid(11), false).trashedAt()).isNull();
        assertThat(service.trackPublish(uuid(11), false).published()).isFalse();
    }

    @Test
    void onlyAdminsDeleteTracksAndOnlyFromTheTrash() throws Exception {
        as(TestCallers.admin());
        assertThatThrownBy(() -> service.trackDelete(uuid(11))).isInstanceOf(BadRequestException.class);

        published.getTracks().getFirst().setTrashedAt(NOW);
        service.trackDelete(uuid(11));
        verify(storage).deleteAfterCommit(any(Track.class));
        assertThat(published.getTracks()).extracting(Track::getUuid).containsExactly(uuid(12));
    }
}
