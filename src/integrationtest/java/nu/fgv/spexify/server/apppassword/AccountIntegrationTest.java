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

package nu.fgv.spexify.server.apppassword;

import nu.fgv.spexify.server.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A listener's app passwords and API keys, and what admins see and do with them.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
class AccountIntegrationTest extends AbstractIntegrationTest {

    @Test
    void aListenerKeepsAppPasswordsAndApiKeys() {
        final String passwordId = graphQl(LISTENER).document("mutation { appPasswordCreate(name: \"Telefon\") { appPassword { id expired } } }")
                .execute()
                .path("appPasswordCreate.appPassword.expired").entity(Boolean.class).isEqualTo(false)
                .path("appPasswordCreate.appPassword.id").entity(String.class).get();
        final String keyId = graphQl(LISTENER).document("mutation { apiKeyCreate(name: \"Feishin\") { key apiKey { id } } }")
                .execute()
                .path("apiKeyCreate.key").hasValue()
                .path("apiKeyCreate.apiKey.id").entity(String.class).get();

        graphQl(LISTENER).document("{ appPasswords { name } apiKeys { name } }").execute()
                .path("appPasswords[0].name").entity(String.class).isEqualTo("Telefon")
                .path("apiKeys[0].name").entity(String.class).isEqualTo("Feishin");

        graphQl(LISTENER).document("mutation($id: ID!) { appPasswordRevoke(id: $id) }").variable("id", passwordId).execute().errors().verify();
        graphQl(LISTENER).document("mutation($id: ID!) { apiKeyRevoke(id: $id) }").variable("id", keyId).execute().errors().verify();
        graphQl(LISTENER).document("{ appPasswords { id } apiKeys { id } }").execute()
                .path("appPasswords").entityList(Object.class).hasSize(0)
                .path("apiKeys").entityList(Object.class).hasSize(0);
    }

    @Test
    void anAdminSeesTheUsersAndRevokesTheirCredentials() {
        graphQl(LISTENER).document("mutation { appPasswordCreate(name: \"Telefon\") { password } }").execute();

        final String userId = graphQl(ADMIN).document("{ users { id username appPasswordCount locked } }").execute()
                .path("users[?(@.username == '" + LISTENER + "')].appPasswordCount").entityList(Integer.class).containsExactly(1)
                .path("users[?(@.username == '" + LISTENER + "')].id").entityList(String.class).get().getFirst();

        graphQl(ADMIN).document("mutation($id: ID!) { userCredentialsRevoke(userId: $id) }").variable("id", userId).execute().errors().verify();
        graphQl(ADMIN).document("mutation($id: ID!) { userUnlock(userId: $id) }").variable("id", userId).execute().errors().verify();
        graphQl(LISTENER).document("{ appPasswords { id } }").execute().path("appPasswords").entityList(Object.class).hasSize(0);
        assertThat(jdbcClient.sql("SELECT COUNT(*) FROM audit_entry WHERE action = 'REVOKE'").query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void theMusicServerIsShownToEditorsAndRescannedByAdmins() {
        graphQl(EDITOR).document("{ libraryStatus { navidromeAvailable trackCount } }").execute()
                .path("libraryStatus.trackCount").entity(Integer.class).isEqualTo(0);
        expectError(graphQl(EDITOR).document("mutation { libraryRescan }").execute(), "FORBIDDEN");
        graphQl(ADMIN).document("mutation { libraryRescan }").execute().errors().verify();
        graphQl(ADMIN).document("{ libraryStatus { scanPending } }").execute().path("libraryStatus.scanPending").entity(Boolean.class).isEqualTo(true);
    }

    @Test
    void listenersSeeSettingsAndCannotChangeThem() {
        graphQl(LISTENER).document("{ settings { appPasswordExpiryMonths } }").execute()
                .path("settings.appPasswordExpiryMonths").entity(Integer.class).isEqualTo(12);
        expectError(graphQl(LISTENER).document("{ users { id } }").execute(), "FORBIDDEN");
    }
}
