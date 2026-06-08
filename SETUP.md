# Game files

**You don't need to do any of this anymore.** On first launch, CrossDroid downloads CrossCode
straight from Steam (the copy you own on your account) and sets up the mod loader for you — just
sign in and tap **PLAY**. The one-time download is ~1.5 GB; after that, **PLAY** only syncs your
cloud save and launches.

This guide is kept as an **optional fallback** for anyone who'd rather copy the files over by hand —
for example to skip the download on a metered connection, or if you already have CrossCode set up on
the device. CrossDroid detects an existing `CrossCode` folder that already has a mod loader and
**skips the download automatically**.

---

## Manual install (optional)

### 1. Grant the storage permission

The game files live in your phone's storage, and the app needs permission to read them. Make sure
CrossDroid has the **Storage** permission enabled.

### 2. Find the game files

The location depends on how you installed CrossCode. If you used Steam, it's usually
`C:\Program Files (x86)\Steam\steamapps\common\CrossCode` on Windows, or
`~/.steam/steam/steamapps/common/CrossCode` on Linux. If you used a different store
([GOG](https://www.gog.com/), itch.io, etc.), search online for where those games are stored.

### 3. Install CCLoader

Follow the steps at https://wiki.c2dl.info/CCLoader#Installation

**Use CCLoader3 v3.3.1-alpha.** Newer versions (v3.3.2+) rely on a service worker / Node `require`
that doesn't work in the Android WebView and will leave you on a black screen.

### 4. Install the [cc-font-fix](https://github.com/krypciak/cc-font-fix) mod

Follow the guide on [installing mods](https://wiki.c2dl.info/Installing_mods). You can download
cc-font-fix directly from [its v1.0.0 release](https://github.com/krypciak/cc-font-fix/releases/tag/v1.0.0).

### 5. Optional: install your favorite mods

Be aware that some mods may not work on Android.

### 6. Copy the directory to your phone

Grab the folder called `CrossCode` and copy it to
`Android/data/de.radicalfishgames.crosscode/files`

**Verify that your files look like this:**
- `Android`
  - `data`
    - `de.radicalfishgames.crosscode`
      - `files`
        - `CrossCode` (the main game directory)
          - `assets`
          - `ccloader`
          - `...`

### That's it. You're done!
