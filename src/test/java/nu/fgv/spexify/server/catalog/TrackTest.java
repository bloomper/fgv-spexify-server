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

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class TrackTest {

    private static Track track(final boolean albumPublished, final boolean trackPublished) {
        final Album album = new Album();
        final Track track = new Track();

        album.setPublished(albumPublished);
        track.setAlbum(album);
        track.setPublished(trackPublished);
        return track;
    }

    @Test
    void belongsInTheMusicFolderOnlyWhenItAndItsAlbumArePublished() {
        assertThat(track(true, true).desiredLocation()).isEqualTo(Location.MUSIC);
        assertThat(track(true, false).desiredLocation()).isEqualTo(Location.UNPUBLISHED);
        assertThat(track(false, true).desiredLocation()).isEqualTo(Location.UNPUBLISHED);
    }

    @Test
    void belongsInTheTrashWhenItOrItsAlbumIsTrashed() {
        final Track trashed = track(true, true);
        final Track inTrashedAlbum = track(true, true);

        trashed.setTrashedAt(Instant.now());
        inTrashedAlbum.getAlbum().setTrashedAt(Instant.now());
        assertThat(trashed.desiredLocation()).isEqualTo(Location.TRASH);
        assertThat(inTrashedAlbum.desiredLocation()).isEqualTo(Location.TRASH);
        assertThat(trashed.isListed()).isFalse();
    }

    @Test
    void readsNumbersAndTitlesFromTagsAndFileNames() {
        assertThat(CatalogService.parseNumber("3/12", 1)).isEqualTo(3);
        assertThat(CatalogService.parseNumber("x", 7)).isEqualTo(7);
        assertThat(CatalogService.parseNumber(null, 7)).isEqualTo(7);
        assertThat(CatalogService.titleFromFileName("07 - Finalen.flac")).isEqualTo("Finalen");
        assertThat(CatalogService.titleFromFileName("Rövar_visan.mp3")).isEqualTo("Rövar visan");
    }
}
