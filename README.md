# Spexify Server

## Overview

Spexify Server is the backend of Spexify, FGV's music service for the songs the spex have recorded over the years.
It is a Spring Boot application with a GraphQL API, plain HTTP endpoints for audio, downloads, covers and resumable
(tus) uploads, and a Subsonic API for apps, which it serves as a gateway to [Navidrome][1]. It is backed by MySQL, a
media folder and Keycloak ([fgv-keycloak][2]) for authentication, and reads spex from Spexregister. [Spexify Web][3] is
its frontend and [fgv-spexify-deploy][4] runs it on a server.

The product spec is in [fgv-spexify-spec][5].

## Learning more

To learn more about Spexify Server, please consult the reference documentation, which is served at `/docs`. When
running locally, the reference documentation is available [here][11]. Its source is in `src/main/asciidoc`, and
`getting-started.adoc` describes how to run the whole stack locally with `docker compose`.

## Building from source

You will need Java 25 or later to build Spexify Server, ffmpeg for the media tests, and Docker for the integration
tests. It is built using [Gradle][6]:

```
./gradlew test              # unit tests
./gradlew integrationTest   # integration tests, against MySQL, Keycloak and Navidrome in Testcontainers
./gradlew build             # both, plus the reference documentation
```

## Published images

CI builds the image with Jib and publishes `bloomper/fgv-spexify-server` for `linux/amd64` and `linux/arm64`.
Since the API needs ffmpeg, Jib builds on `bloomper/fgv-spexify-server-base:25` (`eclipse-temurin:25-jre` plus ffmpeg, from
`base/Dockerfile`), which CI rebuilds and publishes first:

| Trigger              | Tags                              |
|----------------------|-----------------------------------|
| push to `develop`    | `develop`, `sha-<commit>`         |
| push of tag `vX.Y.Z` | `X.Y.Z`, `latest`, `sha-<commit>` |

To release, set `version` in `build.gradle` to `X.Y.Z`, merge to `main`, then `git tag vX.Y.Z && git push origin vX.Y.Z`.
The build fails if the tag doesn't match the version or if `X.Y.Z` is already on Docker Hub.

## Contributing

Contributors to this project agree to uphold its [code of conduct][7].

You can contribute to Spexify Server by:

- Opening a [pull request][8]. Please see the [contributor guidelines][9] for details

## License

Spexify Server is open source software released under the [Apache 2.0 license][10].

[1]: https://www.navidrome.org/
[2]: https://github.com/bloomper/fgv-keycloak
[3]: https://github.com/bloomper/fgv-spexify-web
[4]: https://github.com/bloomper/fgv-spexify-deploy
[5]: https://github.com/bloomper/fgv-spexify-spec
[6]: https://gradle.org
[7]: CODE_OF_CONDUCT.md
[8]: https://help.github.com/articles/using-pull-requests/
[9]: CONTRIBUTING.md
[10]: https://www.apache.org/licenses/LICENSE-2.0.html
[11]: https://api.spexify.fgv.local.nu:8443/docs/index.html
