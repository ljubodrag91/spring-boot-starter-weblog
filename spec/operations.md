# Operations

## Build & test

```bash
mvn clean test      # compile + run the unit test suite (no external dependencies needed)
mvn clean install   # install to the local repo — for iterating against a consuming app locally
                     # before a real release
```

## GitHub Packages

Artifact coordinates: `com.eventhorizon:spring-boot-starter-weblog`. Published to
`https://maven.pkg.github.com/ljubodrag91/spring-boot-starter-weblog`.

GitHub Packages requires authentication even for reads, so both consuming and deploying
need a `<server>` entry with `<id>github</id>` — matching `distributionManagement`'s id in
`pom.xml` and the `<repository>` id a consumer declares — holding a token with
`read:packages` (consume) or `write:packages` (publish). Unlike a Nexus group repo it
cannot be reached through a mirror, so consumers declare the repository explicitly.
See `README.md` → "Releasing a new version" for the exact XML.

Normal releases go through `.github/workflows/publish.yml`, triggered by pushing a `v*`
tag; it authenticates with the workflow's own `GITHUB_TOKEN`, so no local credentials are
needed.

## Versioning

Published versions are immutable — GitHub Packages rejects redeploying an existing version.
Every deploy needs a version bump in `pom.xml` first. There is no SNAPSHOT channel configured
in `distributionManagement` — every deploy is a real release.

```bash
mvn clean deploy
```

After a release, update the dependency version in every consuming app's `pom.xml` — nothing
here does that automatically.
