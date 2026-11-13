# Security policy

Please report security problems privately through GitHub's "Report a vulnerability" button on the Security tab of this repository instead of opening a public issue.

Areas worth a careful look:

- The one-time local-network phone-setup page (`system/PhoneSetup.kt`), which is served over plain HTTP on your LAN while the panel is open.
- The accessibility services, which are opt-in and only observe window changes and key events.
- Stored API keys and the Plex token (kept in app-private storage; backup is disabled).
- The in-app updater, which downloads APKs from this repository's GitHub Releases.

VITRE contains no analytics, ads or crash reporting.
