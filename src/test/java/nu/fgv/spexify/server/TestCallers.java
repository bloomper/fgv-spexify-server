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

package nu.fgv.spexify.server;

import nu.fgv.spexify.server.user.Caller;
import nu.fgv.spexify.server.user.Role;

import java.util.Set;

/**
 * Callers for unit tests, one per role.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
public final class TestCallers {

    private TestCallers() {
    }

    public static Caller listener() {
        return new Caller("s-listener", "lisa@spexify.test", "Lisa", Set.of(Role.USER), false);
    }

    public static Caller editor() {
        return new Caller("s-editor", "erik@spexify.test", "Erik", Set.of(Role.USER, Role.EDITOR), false);
    }

    public static Caller admin() {
        return new Caller("s-admin", "anna@spexify.test", "Anna", Set.of(Role.USER, Role.ADMIN), false);
    }

    public static Caller noAccess() {
        return new Caller("s-none", "nils@spexify.test", "Nils", Set.of(), false);
    }

    public static Caller spexflixService() {
        return new Caller("client:fgv-spexflix-service", "service-account-fgv-spexflix-service", null, Set.of(Role.SPEXFLIX), false);
    }
}
