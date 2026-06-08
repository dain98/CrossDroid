# CrossDroid

Play **CrossCode** on Android — sign in with Steam, pull your save from the cloud, play with a
controller, and sync your progress back to **Steam Cloud** when you quit. Built for handhelds
(developed on an AYN Thor) and phones.

> **Unofficial fan project.** Not affiliated with or endorsed by Radical Fish Games or Valve.
> It ships **no game files** — you bring your own copy of CrossCode that you own on Steam.

## What it does

- **Sign in with Steam** — username/password *or* QR code (scan with the Steam Mobile app).
- **Steam Cloud saves** — **PLAY** pulls your latest cloud save before launch; quitting uploads your
  progress back, so you can hop between PC and handheld.
- **Controller-first** — physical gamepads work in-game, and the launcher is D-pad navigable.
- **In-game Exit** — restores the "Exit" option CrossCode hides on non-desktop, wired to the
  cloud-save-on-quit flow.
- Shows your Steam avatar and persona name.

## Requirements

- An Android device (developed/tested on an AYN Thor, Android 13).
- A Steam account that **owns CrossCode**.
- Your own CrossCode game files + CCLoader + the cc-font-fix mod, copied to the device —
  see **[SETUP.md](SETUP.md)**.

## Install

1. Download the APK from the [Releases page](../../releases/latest) and sideload it
   (allow "install from unknown sources").
2. Copy your game files to the device per **[SETUP.md](SETUP.md)**.
3. Open CrossDroid, sign in with Steam, then tap **PLAY**.

> The APK is signed with a local debug key — fine for sideloading, but you can't install it over a
> differently-signed build without uninstalling first (uninstalling wipes the on-device game files;
> your progress is safe in Steam Cloud).

## How saves work

CrossCode persists its save to the WebView's `localStorage`, byte-for-byte identical to the desktop
`cc.save` that Steam Cloud syncs. CrossDroid pulls that file from Steam Cloud into the app before the
game boots, and uploads it back when you quit — last-write-wins, and it skips the upload when nothing
changed.

## Credits

CrossDroid stands on the shoulders of these projects:

- **[CrossAndroid](https://gitlab.com/Namnodorel/crossandroid)** by Namnodorel — the WebView
  runtime/launcher this is forked from. CrossAndroid does not declare a license; this fork is shared
  as a **non-commercial fan project** with full credit. If the original author objects, please open
  an issue and it will be addressed.
- **[CCLoader](https://github.com/CCDirectLink/CCLoader3)** — the CrossCode mod loader. CrossDroid is
  verified against **CCLoader3 v3.3.1-alpha** (newer versions use a service worker / Node `require`
  that breaks in the Android WebView).
- **[cc-font-fix](https://github.com/krypciak/cc-font-fix)** by krypciak.
- **[JavaSteam](https://github.com/Longi94/JavaSteam)** — the Steam client library powering login and
  cloud sync.
- **CrossCode** © [Radical Fish Games](https://www.radicalfishgames.com/) — go buy it, it's great.
  CrossDroid bundles a little CrossCode UI art (the logo and a title background) on its launcher
  screen; all game assets remain © Radical Fish Games.
- Original CrossAndroid pixelart by Ichiki Hayaite (TheSparkstarScope).

## Disclaimer

A hobby project for playing a game you own on hardware you own. Not affiliated with Radical Fish
Games, Valve/Steam, or the CrossAndroid project. Use at your own risk.
