# VITRE add-on template

A minimal add-on that shows every extension point there is today: a Control Center tile, a remote-button action (both from the
service), and a Top Shelf feed, a Home row and a settings form (from the provider). The full walk-through is
[docs/addons/10-minute-guide.md](../docs/addons/10-minute-guide.md).

This is a standalone Gradle project. It is not in VITRE's `settings.gradle.kts` and nothing in `:app` depends on it. There is no
Maven artifact for the add-on API yet (nothing is published), so the template reaches it by path:

- **In this repo:** nothing to do. `settings.gradle.kts` includes `../addon-api` and reads versions from `../gradle/libs.versions.toml`.
  Build with `scripts/addon-template-check`, or `./gradlew -p addon-template :app:assembleDebug` from the repo root.
- **Copied out of the repo:** copy `addon-template/`, `addon-api/` and `gradle/libs.versions.toml` (plus a Gradle wrapper) side by side,
  then fix the two `../` paths in `settings.gradle.kts`. A composite build works too: put `includeBuild("path/to/glass/addon-api")` in
  the settings and depend on `dev.glasslauncher:addon-api` once it is published.

Change `applicationId`/`namespace` and the package `com.example.myaddon`, the provider authority (manifest and
`dev.glasslauncher.addon.provider`), the title, and the labels. Then `scripts/addon-lint addon-template`.
