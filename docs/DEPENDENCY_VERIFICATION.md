# Dependency verification

`gradle/verification-metadata.xml` pins the SHA-256 of every artifact the build resolves. If a
downloaded jar, aar, pom or module file does not match, the build fails instead of running the
substituted bytes — which for a build system means running them with your credentials, your source
tree and your signing key in reach.

This matters more here than in most projects because `NewPipeExtractor` resolves from **JitPack**,
which builds artifacts from a git tag on demand. A tag can be moved. Without a pinned checksum,
"the same version" is not the same bytes.

## What is verified

- **Checksums:** SHA-256, for artifacts and for their metadata (`verify-metadata` is on).
- **Signatures:** off. PGP verification would mean curating a trusted-key list and dealing with
  the many artifacts that are unsigned or signed with expired keys. Checksums pin exactly what was
  reviewed, which is the property that was actually missing.

## When it fails

The failure names the artifact and points at
`build/reports/dependency-verification/…/dependency-verification-report.html`. In CI that report is
uploaded with the other build reports.

Two causes, and they need opposite responses:

1. **You changed a dependency** (bumped a version in `gradle/libs.versions.toml`, added a library,
   or upgraded AGP/Kotlin, which pulls new tooling artifacts). Expected. Regenerate — see below.
2. **You changed nothing.** Then the artifact you are being served is not the artifact that was
   pinned. Do not regenerate. Find out why first.

## Regenerating after a deliberate change

```bash
./gradlew --write-verification-metadata sha256 \
    detekt assembleDebug testDebugUnitTest test lintDebug help
```

The task list matters: the file records what the build *resolves*, so a task graph that misses a
configuration produces a file that fails the first time someone runs that task. The list above is
the one CI runs. Add `assembleRelease bundleRelease -PallowDebugSigningForRelease=true` when a
change can affect the release path (R8, packaging, a `releaseImplementation` dependency).

Then **read the diff before committing it.** A regeneration is only as trustworthy as the network
it ran on; the point of the file is that a human looked at what changed. Entries for artifacts you
did not expect to change are the signal worth stopping on.

## Platform-specific artifacts

Some artifacts carry an OS classifier, so a generated file only covers the machine that generated
it. `com.android.tools.build:aapt2` is the one this build hits: CI runs on Linux, so the Linux jar's
checksum has to be present even though nobody here generated it on Linux.

Those entries are marked with an `origin` saying they were added by hand. To refresh one after an
AGP bump:

```bash
V=<aapt2 version, e.g. 9.3.2-15703166>
for P in linux osx windows; do
  curl -sSLO "https://dl.google.com/dl/android/maven2/com/android/tools/build/aapt2/$V/aapt2-$V-$P.jar"
  sha256sum "aapt2-$V-$P.jar"
done
```

If a build fails on some *other* artifact only on one operating system, it is the same problem and
the same fix.

## Related

- `gradle/wrapper/gradle-wrapper.properties` pins `distributionSha256Sum` for the Gradle
  distribution itself. Update it in the same commit as `distributionUrl`; the published value is at
  `https://services.gradle.org/distributions/gradle-<version>-bin.zip.sha256`.
- `settings.gradle.kts` scopes the JitPack repository to `com.github.TeamNewPipe` with a content
  filter, so JitPack cannot serve anything else even if it wanted to.
