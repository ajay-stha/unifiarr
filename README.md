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
