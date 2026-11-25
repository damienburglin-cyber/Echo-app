# Echo app

The Android app around **Echo**, a personal English coach that lives as a claude.ai
artifact. Echo is the brain (Claude, data, daily programme); this app is the hands:
it shows Echo full screen and lets it open other apps (ELSA, Netflix, Prime Video)
and drive GlobalExam in a second, hidden browser where the owner signs in himself.

This repo is public on purpose: it holds only the app code and its releases, so the
installed app can find updates without a password. No personal data lives here; Echo
itself and its data stay private on claude.ai, and the project notes stay in the private
EchoV2 repo.

## Install and update

- First install: open the latest release (Releases, on the right), download the `.apk`,
  allow the installation.
- Updates: the app checks this repo's latest release at start and offers to install it.
  Android asks once to allow Echo to install updates.

## How it is built

`.github/workflows/release.yml` builds the APK on every push. When `versionName` in
`app/build.gradle` has no release yet, it publishes `v<versionName>` with the APK and the
matching section of `CHANGELOG.md`. To release: bump `versionCode` and `versionName`,
add a `CHANGELOG.md` section, push.

Signing: a fixed debug key, `echo-debug.keystore`, with the standard public debug
passwords. It is committed on purpose so every build has the same signature and a new
version installs over the old one without losing the sign-ins. It protects nothing secret.
Known limit: anyone could sign an APK with it, but the app only installs updates from this
repo's releases.

## Bridge

Only pages served as claude.ai artifacts (`https://*.frame.claudeusercontent.com`) see
`window.EchoAppMsg`. The page posts JSON `{id, cmd, ...}`; the app answers `{id, ok, ...}`.
Commands: `hello`, `openApp` (`elsa`, `netflix`, `prime`), `openUrl` (https), `copy`,
`web.open`, `web.text`, `web.tree`, `web.run` (since 0.3), `web.click`, `web.select`,
`web.type`, `web.show`, `web.hide`.
