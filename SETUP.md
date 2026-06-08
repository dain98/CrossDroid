# Installing and updating the game files

## 1. The game files are going to be placed in your phones storage.  

In order to execute them later, this app needs your permission to access this storage.  
Make sure now that the app has the "Storage" permission enabled.  

## 2. Now you have to find the game files.  

The location of them can vary depending on how you installed CrossCode.  
If you used Steam, the location should be  
`C:\Program Files (x86)\Steam\steamapps\common\CrossCode` on Windows, or  
`~/.steam/steam/steamapps/common/CrossCode` on Linux.  
If you used a different method ([GOG](https://www.gog.com/), itch.io, etc.), search on the internet where games for that are usually saved.

## 3. Install CCLoader  

Follow the steps at https://wiki.c2dl.info/CCLoader#Installation

**Use CCLoader3 v3.3.1-alpha.** Newer versions (v3.3.2+) rely on a service worker / Node `require`
that doesn't work in the Android WebView and will leave you on a black screen.

## 4. Install the [cc-font-fix](https://github.com/krypciak/cc-font-fix) mod.  

Follow the guide on how to install mods [here](https://wiki.c2dl.info/Installing_mods).  
You can download it directly from [here](https://github.com/krypciak/cc-font-fix/releases/tag/v1.0.0).  

## 5. OPTIONAL: Install your favorite mods

Be aware that some mods may not work on Android.

## 6. Copy the directory to your phone

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

### That's it. You are done!

