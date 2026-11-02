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

import nu.fgv.spexify.server.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

/**
 * Admins configure the album kinds, editors pick one for an album, and a kind in use stays.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
class AlbumKindIntegrationTest extends AbstractIntegrationTest {

    private static final String SHOW = "00000000-0000-7000-8000-00000000a001";
    private static final String STUDIO = "00000000-0000-7000-8000-00000000a002";

    @Test
    void theMigrationStartsWithFourKindsAndShowIsTheDefault() {
        graphQl(LISTENER).document("{ albumKinds { id nameSv nameEn isDefault } }").execute()
                .path("albumKinds[*].nameEn").entityList(String.class).containsExactly("Show", "Studio", "Compilation", "Other")
                .path("albumKinds[0].isDefault").entity(Boolean.class).isEqualTo(true)
                .path("albumKinds[1].isDefault").entity(Boolean.class).isEqualTo(false);

        graphQl(EDITOR)
                .document("mutation { albumCreate(input: {spexIds: [\"00000000-0000-7000-8000-000000000001\"]}) { kind { id } } }")
                .execute()
                .path("albumCreate.kind.id").entity(String.class).isEqualTo(SHOW);
    }

    @Test
    void anAdminAddsAKindThatAnEditorThenUses() {
        expectError(graphQl(EDITOR).document("mutation { albumKindCreate(input: {nameSv: \"Trailer\", nameEn: \"Trailer\"}) { id } }")
                .execute(), "FORBIDDEN");

        final String jubilee = graphQl(ADMIN)
                .document("mutation { albumKindCreate(input: {nameSv: \"Jubileum\", nameEn: \"Jubilee\"}) { id position } }")
                .execute()
                .path("albumKindCreate.position").entity(Integer.class).isEqualTo(5)
                .path("albumKindCreate.id").entity(String.class).get();
        final String albumId = graphQl(EDITOR)
                .document("mutation($kind: ID) { albumCreate(input: {title: \"Jubileet\", kindId: $kind}) { id kind { nameSv } } }")
                .variable("kind", jubilee)
                .execute()
                .path("albumCreate.kind.nameSv").entity(String.class).isEqualTo("Jubileum")
                .path("albumCreate.id").entity(String.class).get();

        // Without a kind, an update keeps the album's
        graphQl(EDITOR)
                .document("mutation($id: ID!) { albumUpdate(id: $id, input: {title: \"Jubileet 2\"}) { kind { id } } }")
                .variable("id", albumId)
                .execute()
                .path("albumUpdate.kind.id").entity(String.class).isEqualTo(jubilee);

        graphQl(ADMIN).document("{ albumKinds { nameEn albumCount } }").execute()
                .path("albumKinds[4].albumCount").entity(Integer.class).isEqualTo(1);
        expectError(graphQl(ADMIN).document("mutation($id: ID!) { albumKindDelete(id: $id) }").variable("id", jubilee).execute(), "CONFLICT");
    }

    @Test
    void reorderingMakesAnotherKindTheDefault() {
        final List<String> ids = graphQl(LISTENER).document("{ albumKinds { id } }").execute()
                .path("albumKinds[*].id").entityList(String.class).get();
        final List<String> reordered = new java.util.ArrayList<>(ids);

        reordered.remove(STUDIO);
        reordered.addFirst(STUDIO);
        graphQl(ADMIN).document("mutation($ids: [ID!]!) { albumKindsReorder(ids: $ids) { id } }")
                .variables(Map.of("ids", reordered))
                .execute()
                .path("albumKindsReorder[0].id").entity(String.class).isEqualTo(STUDIO);
        graphQl(LISTENER).document("{ albumKinds { id isDefault } }").execute()
                .path("albumKinds[0].id").entity(String.class).isEqualTo(STUDIO)
                .path("albumKinds[0].isDefault").entity(Boolean.class).isEqualTo(true);
    }

    @Test
    void anUnusedKindIsDeleted() {
        graphQl(ADMIN).document("mutation($id: ID!) { albumKindDelete(id: $id) }").variable("id", STUDIO).execute().errors().verify();
        graphQl(LISTENER).document("{ albumKinds { id } }").execute()
                .path("albumKinds[*].id").entityList(String.class).doesNotContain(STUDIO);
    }
}
