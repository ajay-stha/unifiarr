# Changelog

## v1.0.0 - Public testing

Initial testing release. Android version `1.0.0` (version code `4`).

- Unified web interface for qBittorrent, Sonarr, Radarr, Jellyfin and Seerr.
- Server-side API proxy keeps service credentials out of the browser.
- Configure service connections through the Services page.
- Docker support with persistent configuration.
- Android app with package name `aj.unifiarr.app`.
- Download the Android APK from the release assets below.
- Signed Android App Bundle (`unifiarr.aab`) for Google Play internal testing.

### Installation

Download `unifiarr.apk` on an Android device running Android 8.0 or newer.
Allow installation from the browser or file manager when prompted, then install
the APK.

For Google Play internal testing, upload `unifiarr.aab` to the app's internal
testing track in Play Console, roll out the release and share the opt-in link
with your testers. Publishing this GitHub release does not publish to Google Play.

### Notes

The Android package name changed from `app.unifiarr` to `aj.unifiarr.app`.
Existing installations using the old package name will not be upgraded in place.

The server has no built-in login. Keep it on loopback or use an authenticating
reverse proxy.
