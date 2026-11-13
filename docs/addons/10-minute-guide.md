# Add-on in 10 minutes

A VITRE add-on is a separate APK in its own process. It supplies data and answers events; VITRE draws everything, fetches the images
and builds every Intent. No code is ever loaded into VITRE, and a crash or hang in an add-on cannot stall Home.

## 1. Build and install the template

1. Copy `addon-template/` (in this repo it already builds as is; if you move it, see its README for the two `../` paths and the
   `addon-api/` module it needs, since nothing is published to Maven yet).
2. Change the tile label in `MyAddonService.tiles()` (`label = "My Counter"`), and `applicationId`, `namespace`, the package and the
   provider authority if this is a real add-on.
3. Build: `./gradlew -p addon-template :app:assembleDebug` (or `scripts/addon-template-check`, which also lints). Check the manifest:
   `scripts/addon-lint addon-template`.
4. Install (not run by any script here): `adb install -r addon-template/app/build/outputs/apk/debug/app-debug.apk`.
5. In VITRE: **Settings > Add-ons**. The add-on is listed but **off**: sideloaded add-ons start disabled. Switch it on.
6. **Control Center > Add a Control > Add-ons**: add your tile. Select on it runs `onTap`.
   Also try: Settings > Top Shelf Content > "My Add-on (add-on)"; the Home row at the bottom of Home; Settings > Add-ons > "My Add-on
   Settings"; Settings > Remote Buttons > a button > Add-on actions.

## 2. Manifest reference

On the one exported service with the action `dev.glasslauncher.ADDON` and `android:permission="dev.glasslauncher.permission.BIND_ADDON"`:

| meta-data `dev.glasslauncher.addon.` | Value |
|---|---|
| `api` | Integer API level you built for (now 1). |
| `min_glass_api` | Lowest VITRE API level you need. VITRE accepts `api >= (its level - 1)` and `min_glass_api <= its level`. |
| `title` | Name shown in Settings (printable text, 32 characters). |
| `icon` | A drawable resource. |
| `needs_root` | `true` if the add-on needs root on its own (drives a Root badge later). VITRE never roots anything for you. |
| `provider` | Authority of your ContentProvider (needed for `topshelf`, `homerow`, `settings`). |
| `points` | Comma list of `tiles`, `topshelf`, `homerow`, `settings`, `actions`. Missing = `tiles`. |

The provider must be yours, `android:exported="true"` and `android:permission="dev.glasslauncher.permission.BIND_ADDON"`, or VITRE
ignores its points. Do **not** declare the permission yourself (VITRE defines it; two definitions clash on install order) and you need
no `<queries>`. **One add-on service per package.**

## 3. The contract, per extension point

All messages are JSON strings (the classes are in `:addon-api`, package `dev.glasslauncher.addon`). Unknown fields are ignored, so
the API only ever grows. Any reply may carry `error`: it counts as a failure. A reply that does not parse is a failure too.

**Control Center tile** (service, `GlassAddonService`): `tiles()` returns `TileState(id, label, icon, on, value, enabled)`;
`onTap(id)` returns the new state. Round 1x1 or pill 2x1 tiles only. `icon` is one of VITRE's glyph keys:
`add home code play music movie weather speed power download tv info settings` (anything else shows a puzzle piece). At most 8 tiles,
ids `[A-Za-z0-9_.-]`, label 24, value 18 characters. VITRE asks when Control Center opens (at most every 30 s) and after a tap; the tap
reply carries the new state. There is **no long-press** (hold-Select is Control Center's edit mode).

**Top Shelf feed** (provider `getFeed`): `FeedReply(heading, items, refreshMinutes)`; `FeedItem(id, title, subtitle, description, image,
logo, link)`. At most **30 items**; title 60, subtitle 80, description 280 characters. The user picks it in Settings > Top Shelf Content.

**Home row** (provider `getHomeRow`): `HomeRowReply(title, items, refreshMinutes)`; `RowItem(id, title, subtitle, image, link, component)`.
Select opens `link` (https, or `market://details?id=<package>`), or `component` (an activity of **your own** package), or, with neither,
launches your app. Anything else drops the card. VITRE shows at most 3 add-on rows; at most 30 cards each.

**Settings page** (provider `getSettingsForm`, `onSettingsAction`): `FormReply(title, fields)`; `FormField(id, type, label, help, choices,
default)` with type `toggle` ("true"/"false"), `choice` (one of up to 8 `Choice(value, label)`), `text`, or `action` (a button). At most 12
fields; values 80 characters; a default that does not fit the type is dropped. VITRE stores the values and sends them with **every**
provider call in `ProviderRequest.values` (keyed by field id): that is how you read them. The form is read when the page opens. Values
for fields you stop declaring, and for an uninstalled add-on, are deleted.

**Dynamic forms.** A field can depend on another field's value, and VITRE works it out locally from the values it stores (nothing of
yours runs):
- `showIf = ShowIf(field, equals)`: the field is drawn only while that other field has that value (a toggle's value is "true"/"false").
  The other field must itself have no dependency (no `showIf`, no `optionsFrom`): nesting depth is 1, so no chains or cycles. A broken rule, or a rule whose target was dropped, drops the field (its stored value is kept as long as your reply still names it).
- `optionsFrom = "<field id>"` (on a `choice`): the options come from your provider's `fieldOptions(req)` (method `getFieldOptions`,
  `req.id` = the field, `req.values` = the current values) and are asked for again **only when that other field's value changes**. Static
  `choices` show until the answer arrives (they may be empty). When the field it depends on changes, VITRE forgets the dependent's stored
  value. Up to 8 options per reply, at most 20 option reads a minute per add-on; a default can only be checked against the static options. While the options load VITRE shows the static ones, "Unavailable" if the call fails (retried when the row is focused again), and "Not set" for a stored value that is not one of the options.
- The same caps apply (12 fields, 8 options, printable text). Hidden fields keep their stored values; values of fields you stop declaring are
  purged. You still read everything from `ProviderRequest.values`.

**Remote action** (service, `actions()` / `onAction(id)`): up to 12 `ActionInfo(id, label)`, listed in the button picker as
"<label> · via <your add-on>". `onAction` may return a short notice (60 characters) that VITRE shows as "<your add-on>: <notice>". If the add-on is unavailable, muted or fails, VITRE says "<your add-on> unavailable". A second press inside the one-second rate window waits and runs once more; further presses are dropped. The mapping is stored as
`addon:<package>/<service>/<id>`; if you disappear it shows as unavailable in the picker and a press says so. Choosing it is the user's consent.

**Images** (`image`, `logo`): `https` only, a public host name (no localhost, local-network names or IP addresses; DNS answers that point
inside the network are refused), JPEG, PNG or static WebP (no GIF, animated WebP or APNG), **at most 1280 px on the long side**, under
2 MB. You never send bitmaps: VITRE fetches them with its own strict client and decodes at the size shown.

**Text**: printable characters only. Control, bidi and zero-width characters are removed and lengths capped, so nothing can reorder or spoof
what is on screen. Your content is marked as add-on content ("· via <add-on>", a puzzle-piece mark).

## 4. Limits and budget

- **Bind on demand, unbind after the call.** No persistent process: keep state in storage, not in fields.
- **2 s per call**, including starting your process and acquiring the provider. One call in flight per add-on; one call per second per
  tile, method or action.
- **Mute after 3 failures in a row** (timeout, exception, error, junk). The add-on shows as paused in Settings until the user switches it
  off and on. A failed Home row stays up for up to 30 min, then clears; VITRE retries after 90 s.
- **Refresh floor: 15 minutes** (`refreshMinutes`, 0 = 30). Never continuously: focusing your Home row, or showing your Top Shelf, asks for a refresh, and the answer is no until the floor has passed (and while Home is hidden). The floor is reopened only by explicit user actions:
  changing one of your settings, switching you on, and Settings > Top Shelf Content > Refresh Now.
- VITRE keeps its own memory budget (about 120 MB PSS): add-on data is rows and strings, never bitmaps. Return small lists.
- API level: additive changes only within a level; VITRE supports its level and the one before. `ON_LONG_PRESS` was dropped; code 4 is
  never reused.

## 5. Security model, in plain words

- **Only VITRE can talk to you.** `BIND_ADDON` is a signature-level permission VITRE declares; your service and provider require it, so no
  other app can bind or query them. Your base classes check it again on every call.
- **You are off until the user turns you on**, per package **and signing certificate**. VITRE re-checks the package, the certificate and
  that the provider is still yours (and guarded) before every call, on the service path and the provider path alike. Re-sign with another key (or rotate it) and you are switched off; the user must confirm after seeing the old and new
  fingerprints. VITRE pins the current signing certificate only, on purpose.
- **VITRE renders, you supply data.** No code is loaded into VITRE, no custom drawing surface. VITRE builds every Intent itself from the
  links you give: https or market links, or your own package's activity. It never targets VITRE or another app and never grants URI
  permissions.
- **No privilege proxying.** VITRE may run as a privileged system app with root, but it never executes root commands or privileged calls on
  an add-on's behalf. An add-on that needs root gets its own grant from the user.
- **Everything you send is cleaned and bounded**, and VITRE fetches the images (see above), so a hostile add-on cannot probe the network,
  flood the screen or make VITRE decode huge or animated pictures.

`scripts/addon-lint <dir>` checks the manifest wiring, https-only art and the refresh floor in the source text of a project.
