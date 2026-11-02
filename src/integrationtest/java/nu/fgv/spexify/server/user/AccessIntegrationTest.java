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

package nu.fgv.spexify.server.user;

import nu.fgv.spexify.server.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Who may use the API at all: a token from {@code fgv-spexify} with {@code USER}.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
class AccessIntegrationTest extends AbstractIntegrationTest {

    @Test
    void meListsTheCallersRoles() {
        graphQl(EDITOR)
                .document("{ me { username displayName roles spexflixUser } }")
                .execute()
                .path("me.username").entity(String.class).isEqualTo(EDITOR)
                .path("me.roles").entityList(String.class).contains("USER", "EDITOR")
                .path("me.spexflixUser").entity(Boolean.class).isEqualTo(false);
    }

    @Test
    void meSaysWhetherTheCallerAlsoUsesSpexflix() {
        graphQl(BOTH)
                .document("{ me { spexflixUser } }")
                .execute()
                .path("me.spexflixUser").entity(Boolean.class).isEqualTo(true);
    }

    @Test
    void withoutUserTheCatalogueIsForbidden() {
        expectError(graphQl(NO_ACCESS).document("{ albums { id } }").execute(), "FORBIDDEN");
    }

    @Test
    void withoutATokenTheCatalogueIsUnauthorized() {
        expectError(graphQl(null).document("{ albums { id } }").execute(), "UNAUTHORIZED");
    }

    @Test
    void listenersCannotCreateAlbums() {
        expectError(graphQl(LISTENER)
                .document("mutation { albumCreate(input: {spexIds: [\"00000000-0000-7000-8000-000000000001\"]}) { id } }")
                .execute(), "FORBIDDEN");
    }

    @Test
    void onlyAdminsChangeSettings() {
        final String update = "mutation { settingsUpdate(input: {appPasswordExpiryMonths: 12, lockoutAttempts: 5, lockoutMinutes: 15}) { lockoutAttempts } }";

        expectError(graphQl(EDITOR).document(update).execute(), "FORBIDDEN");
        graphQl(ADMIN)
                .document(update)
                .execute()
                .path("settingsUpdate.lockoutAttempts").entity(Integer.class).isEqualTo(5);
    }

    @Test
    void fieldsResolvedInParallelRecordAFirstTimeCallerOnce() {
        // Each field records the caller; in the first request they race to insert the same user
        graphQl(LISTENER).document("{ appPasswords { id } apiKeys { id } }").execute().errors().verify();
        assertThat(jdbcClient.sql("SELECT COUNT(*) FROM app_user").query(Long.class).single()).isEqualTo(1L);
    }
}
