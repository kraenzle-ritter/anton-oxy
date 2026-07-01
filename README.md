# anton-oxy

An **Oxygen XML Editor** plugin that searches actors, places and keywords **live in
[Anton](https://kr.anton.ch)** and writes the matched id into an attribute of the
TEI element under the caret.

It issues **one `?search=` request per query** (`GET /api/actors`,
`GET /api/places`, `GET /api/keywords`) — it never downloads a whole register. Anton
returns the full id (including the project slug) ready to use, and the search
endpoints are public (no authentication).

Element→register mapping, the target attribute, the id value template and the base URL
are all **configurable**, so the plugin works for any Anton tenant and tagging scheme.

The default mapping:

| Element       | Register   | Attribute  | Example written value          |
| ------------- | ---------- | ---------- | ------------------------------ |
| `persName`    | `actors`   | `@ref`     | `ref="sulger-actors-123"`      |
| `orgName`     | `actors`   | `@ref`     | `ref="sulger-actors-123"`      |
| `placeName`   | `places`   | `@ref`     | `ref="sulger-places-45"`       |
| `objectName`  | `keywords` | `@ref`     | `ref="sulger-keywords-6"`      |
| `term`        | `keywords` | `@ref`     | `ref="sulger-keywords-8"`      |
| `unit`        | `keywords` | `@corresp` | `corresp="sulger-keywords-12"` |

(`persName` also covers organisations / Körperschaften. `unit` uses `@corresp` instead
of `@ref` — see the per-element attribute override below.)

## How it works

Two ways to start, then the same live search:

**A — caret in an existing element** (set/replace the reference):

1. Put the caret inside a mapped element — works in both **Text** and **Author** mode.
2. Trigger the action: toolbar button **“Anton @ref”**, menu **Anton →
   Anton-Referenz einfügen**, or **Ctrl+Shift+A**.
3. A search dialog opens (pre-filled with the element text). Results update live as you
   type; the register is preselected from the element but can be switched.
4. Pick a hit (Enter / double-click / “Einfügen”) → the plugin sets or replaces the
   attribute on that element. Other attributes are preserved.

**B — select bare text** (*Wrap & Tag*, the fast path):

1. Select an untagged name/place/term (no element needed yet) and trigger the action.
2. The dialog opens in **wrap mode**: pick which element to wrap the selection in
   (`persName`, `placeName`, …) — the register follows that choice.
3. Pick a hit → the plugin wraps the selection as
   `<persName ref="…">selected text</persName>` in one step.

**Serial tagging (“Einfügen & weiter”).** Either flow offers an **Einfügen & weiter**
button next to **Einfügen**. It inserts the reference, then jumps to and selects the
**next occurrence of the same text** and reopens the dialog — so tagging every mention
of a person in a document is a rhythm of *pick → Enter → pick → Enter*. The previously
chosen wrap element stays preselected. (Next-occurrence search works in **Text** mode.)

## Requirements

- **Oxygen XML Editor/Author 22 or newer.** Compiled to Java 8 bytecode against the
  stable WorkspaceAccess/Author API, so it runs on oXygen 22 (bundled Java 8) and on
  newer versions.
- To build: a JDK (≥ 8; tested with JDK 26 via `--release 8`) and a local oXygen
  installation (for `oxygen.jar`).

## Installation

### Quick install (recommended, any OS — no build needed)

In oXygen: **Help → Install new add-ons…**, paste this URL, follow the wizard, then
**restart oXygen**:

```
https://github.com/kraenzle-ritter/anton-oxy/releases/latest/download/updateSite.xml
```

No admin rights needed, it survives oXygen updates, and **Check for add-on updates**
will pick up future releases automatically. After restarting, set your Anton URL under
**Anton → Anton-Einstellungen…** (default `https://kr.anton.ch`).

### Even simpler on macOS

Double-click **`install-mac.command`** (from a clone of this repo). It downloads the
latest release and installs it into your local oXygen — no Terminal, no Java needed.
Restart oXygen afterwards.

---

The sections below are only needed to **build from source** or to install manually.
The recommended route on every OS is the **add-on** (no admin rights, survives oXygen
updates). Build the jar once, package the add-on, then install via the oXygen GUI.

### macOS / Linux

```bash
./build.sh          # compile (set OXYGEN_DIR if oXygen is elsewhere)
./make-addon.sh     # package add-on  ->  addon/updateSite.xml + dist/anton-oxy-1.1.0.zip
# ── or copy straight into the app: ──
./install.sh        # copies into "<oXygen>/plugins/anton-oxy"
```

### Windows (PowerShell)

```powershell
powershell -ExecutionPolicy Bypass -File .\build.ps1        # compile
powershell -ExecutionPolicy Bypass -File .\make-addon.ps1   # package add-on
# ── or copy straight into the app (needs an elevated PowerShell): ──
powershell -ExecutionPolicy Bypass -File .\install.ps1
```

The scripts auto-detect oXygen under `C:\Program Files\Oxygen XML Editor*`. If it is
elsewhere, set it first:

```powershell
$env:OXYGEN_DIR = 'C:\Program Files\Oxygen XML Editor 27'
```

A JDK must be on the `PATH` (so `javac` and `jar` are callable). Copying into
`C:\Program Files` needs an elevated (“Run as administrator”) PowerShell — the add-on
route avoids that.

### Install the add-on in oXygen (macOS / Windows / Linux)

**Help → Install new add-ons…**, enter the path to `addon/updateSite.xml`, follow the
wizard, then **restart oXygen**. (Host `updateSite.xml` + the `.zip` on a web server and
use that URL for auto-updates.)

After installation the “Anton” menu (Ctrl+Shift+A) and the “Anton @ref” toolbar button
appear.

### Build with Maven (optional, any OS)

```bash
mvn -Doxygen.dir="/path/to/Oxygen XML Editor" package
```

## Configuration

Open **Anton → Anton-Einstellungen…** (or “Einstellungen…” in the search dialog).
Settings are stored in oXygen’s options.

| Setting              | Meaning                                                                 | Default |
| -------------------- | ----------------------------------------------------------------------- | ------- |
| **Anton base URL**   | Anton instance/tenant URL.                                              | `https://kr.anton.ch` |
| **Hits per search**  | Page size for the search request.                                       | `30` |
| **Target attribute** | Default attribute that receives the id.                                 | `ref` |
| **ID value template**| Value written into the attribute. Placeholders: `{fullId}` `{slug}` `{register}` `{id}`. | `{fullId}` |
| **Element → register** | One `element=register` per line (`#` comments allowed). An optional `@attribute` suffix overrides the attribute for that element. | see below |
| **Accept self-signed certs** | Lenient TLS for local DDEV/mkcert hosts (per-connection only). Leave off for a URL with a valid certificate. | off |

Default mapping (editable):

```
persName=actors
orgName=actors
placeName=places
objectName=keywords
term=keywords
unit=keywords@corresp       # per-element attribute override: writes @corresp
```

Examples:

```
# write "#sulger-actors-123" into @key instead of @ref:
Target attribute:   key
ID value template:  #{fullId}
```

## Tests

```bash
./test/run.sh
```

Offline sanity checks (no network): JSON parsing, Text-mode attribute insert/replace
with attribute preservation and nesting, Wrap &amp; Tag (element wrapping + attribute
escaping), next-occurrence selection, register mapping, per-element attribute overrides
and id-value templates.

## Project structure

```
plugin.xml                                Oxygen plugin descriptor (WorkspaceAccess + toolbar)
build.sh  install.sh  make-addon.sh       build & packaging (macOS/Linux)
build.ps1 install.ps1 make-addon.ps1      build & packaging (Windows PowerShell)
pom.xml                                   optional Maven build
addon/updateSite.xml                      add-on descriptor (generated)
src/main/java/ch/kr/anton/oxy/
  AntonOxyPlugin.java                      plugin entry point
  AntonOxyPluginExtension.java             toolbar/menu + action
  RefTargets.java                          locate element + write attribute (Text & Author)
  SearchDialog.java                        live search dialog
  SettingsDialog.java                      settings (URL / attribute / template / mapping / TLS)
  AntonClient.java                         HTTP client for /api/{register}
  AntonEntity.java  Json.java  Config.java
test/ManualTest.java                       offline tests
```

## License

MIT — see [LICENSE](LICENSE).
