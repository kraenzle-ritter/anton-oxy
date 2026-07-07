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

The next-occurrence search matches **whole words only**: tagging `Schaan` skips over
`Schaaner` and lands on the next standalone `Schaan`. When an occurrence *is* a whole-word
match but you don’t want to tag it, **Überspringen** moves on to the next occurrence
without writing anything — so a false positive never forces you to abort serial tagging.

**Tag further occurrences in one batch.** After a plain **Einfügen** of an actor or place
reference (Text mode), the plugin scans the rest of the document for other mentions of the
same entity and offers them in a checklist — each shown with its surrounding context, the
name to be wrapped in **bold**. Tick the ones you want and they are all wrapped in the same
element with the same reference as **a single undo step**. The scan
- derives search terms from the tagged text *and* the name variants Anton returned
  (`name`, `alternative_names`, `variants`, `abbreviations`), including the bare surname of a
  “Nachname, Vorname” entry;
- accepts a German **genitive ending** (`Barths`, `Marx'`) and leaves the ending outside the
  element, as TEI convention wants;
- **skips text already inside a mapped element**, so nothing gets double-tagged.

This is the batch counterpart to serial *Einfügen & weiter*: it only fires on a plain insert,
never when you chose to step through occurrences yourself. Switch it off, or widen the preview
context, under **Anton-Einstellungen…** (see below).

**Tagging dates.** A separate **Datum** action (toolbar button, menu **Anton → Datum
taggen**, or **Ctrl+Shift+D**) wraps a selected date in `<date when="…">`. It best-effort
normalises the selection to an ISO 8601 value — `3. Juli 2026`, `03.07.2026` and
`Juli 2026` become `2026-07-03` / `2026-07` — which you confirm or correct, and you can
switch the attribute to a range endpoint (`from`/`to`/`notBefore`/`notAfter`). No Anton
lookup is involved. Works in **Text** and **Author** mode.

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
./make-addon.sh     # package add-on  ->  addon/updateSite.xml + dist/anton-oxy-1.3.0.zip
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
| **Vorschau-Kontext (Zeichen/Seite)** | Characters shown left and right of the base name in the “further occurrences” preview (words at the edge are never cut). | `60` |
| **Nach weiteren Vorkommen fragen** | After tagging an actor/place, offer to also tag its further occurrences in the document (Text mode). | on |
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
escaping), next-occurrence selection, register mapping, per-element attribute overrides,
id-value templates, further-occurrence term derivation + genitive-aware scanning, and
batch covering-span wrapping.

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
  RefTargets.java                          locate element + write attribute (Text & Author), batch wrap
  SearchDialog.java                        live search dialog
  SettingsDialog.java                      settings (URL / attribute / template / mapping / scan / TLS)
  Occurrences.java                         find further occurrences (terms + genitive-aware scan)
  OccurrenceDialog.java                    checklist to pick which further occurrences to tag
  AntonClient.java                         HTTP client for /api/{register}
  AntonEntity.java  Json.java  Config.java  DateDialog.java
test/ManualTest.java                       offline tests
```

## License

MIT — see [LICENSE](LICENSE).
