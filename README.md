# Unifiarr

One web UI for qBittorrent, Sonarr, Radarr, Jellyfin and Seerr. A small Node server serves the app and proxies allow-listed API calls, so logins and API keys never reach the browser.

## Run

```sh
node server.mjs          # http://127.0.0.1:4180
```

Set up services in the app's **Services** page. They're saved to `CONFIG` (default `.unifiarr.json`, mode 0600, git-ignored).

Env vars: `PORT`, `HOST`, `CONFIG`, `ALLOWED_HOSTS`, plus seed values such as `SONARR_URL`/`SONARR_KEY`, `QBIT_URL`/`QBIT_USER`/`QBIT_PASS`.

There is no Unifiarr login: keep `HOST` on loopback or put it behind an authenticating reverse proxy.

## Docker

```sh
docker compose up -d --build
```

Config persists in `./data`.

## Test

```sh
node --test
```

`android/` holds the Android wrapper app.

## Releases

Update `CHANGELOG.md` before pushing a `v*` tag. The release workflow builds the
APK and Play Store AAB, attaches both to a GitHub Release and uses `CHANGELOG.md`
as its description. Manual runs upload both as workflow artifacts.

Configure repository Actions secrets `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`,
`KEY_ALIAS` and `KEY_PASSWORD` with your release/upload keystore credentials.
For an existing Play Store app, use its registered upload key.

Upload `unifiarr.aab` to Google Play Console; the workflow does not publish to
Google Play automatically. Increase `versionCode` in
`android/app/build.gradle.kts` for each Play Store update.
