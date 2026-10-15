# Publishing jmux

This public repository is an allowlisted source cut. Its Git history is separate
from the private development repository; only approved source snapshots are
exported. Maintain the export templates in the private repository and rerun
`scripts/oss-export.sh` there for subsequent cuts. This public repository only
contains the standalone `scripts/build.sh` build helper.

## Validation

Requires JDK 25 or newer and Maven 3.9 or newer.

```sh
./scripts/build.sh -Prelease -Dgpg.skip=true
java -jar target/jmux-0.1.0-cli.jar version
```

This builds the library, standalone CLI, sources and Javadoc JARs, and runs all
tests without signing or publishing. The build helper uses empty Maven settings
and an isolated Maven repository to confirm that public dependencies resolve
without credentials.

## Release

1. Export and validate a clean source cut before pushing it to this repository.
2. Set a non-SNAPSHOT version and matching SCM tag in `pom.xml`. Update the
   versioned README examples and CI CLI filename when bumping versions.
3. Wait for public CI to pass, then publish a non-prerelease GitHub Release
   tagged `v<version>` at that exact commit.
4. The release workflow validates the tag and ownership metadata, rebuilds and
   tests, then signs and deploys through the same Maven Central plugin used by
   `ffmpeg-ffm` and `panama-media`. It waits for Central to report publication.
5. Confirm the library and CLI resolve anonymously from Maven Central.

Both artifacts are published as `io.github.ghosthack:jmux:0.1.0`:
the ordinary Java library and the standalone JAR with classifier `cli`.
Original jmux code is MIT licensed; dependencies retain their own licenses and
notices in `THIRD-PARTY.md` and the bundled `META-INF/licenses` directory.

## Secrets

Set the repository secrets with the existing
`gpg-backup-audit/set-maven-central-secrets.sh` helper:
`MAVEN_CENTRAL_USERNAME`, `MAVEN_CENTRAL_PASSWORD`, `GPG_PRIVATE_KEY`, and
`GPG_PASSPHRASE`. Only the publishing job receives them. Public CI and normal
builds use no signing key or registry credentials.

Releases are immutable. Publish changes under a new version rather than
replacing an existing Central artifact.
