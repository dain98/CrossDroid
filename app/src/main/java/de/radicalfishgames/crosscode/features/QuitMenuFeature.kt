package de.radicalfishgames.crosscode.features

import de.radicalfishgames.crosscode.GameActivity
import de.radicalfishgames.crosscode.GameWrapper

// CrossCode only creates the title-screen "Exit" button on desktop:
//   ig.platform==DESKTOP && (b += this._createButton("close", b, 5, <nw.gui close>, "setOptions"))
// On Android it's omitted (and the desktop callback closes an nw.js window that doesn't exist here).
// This feature re-adds that button on the title screen and routes it to the CCAndroid bridge, which
// runs the same cloud-save-on-quit flow as the system-back quit dialog. CrossCode's menus are
// canvas-rendered, so we patch the engine class via ImpactJS's native .inject() (added once the
// class is defined; we poll because it loads asynchronously during boot).
class QuitMenuFeature(
    gameWrapper: GameWrapper,
    hostActivity: GameActivity
) : Feature(gameWrapper, hostActivity) {

    override fun onPostGamePageLoad() {
        runJS(PATCH_JS)
    }

    companion object {
        private val PATCH_JS = """
            (function () {
              function patch() {
                if (typeof sc === 'undefined' || typeof ig === 'undefined' || !sc.TitleScreenButtonGui) {
                  setTimeout(patch, 250); return;
                }
                if (sc.TitleScreenButtonGui.__ccQuitPatched) return;
                sc.TitleScreenButtonGui.__ccQuitPatched = true;
                sc.TitleScreenButtonGui.inject({
                  init: function () {
                    this.parent();
                    try {
                      if (!window.CCAndroid || ig.platform === ig.PLATFORM_TYPES.DESKTOP) return;
                      var existing = this.buttons.slice();
                      var h = this._createButton("close", 12, 5, function () {
                        ig.interact.removeEntry(this.buttonInteract);
                        window.CCAndroid.quit();
                        this.hide();
                      }.bind(this), "setOptions");
                      // Push the buttons the original init placed down so Exit sits on top (desktop layout).
                      for (var i = 0; i < existing.length; i++) existing[i].hook.pos.y += h;
                      console.log("[cc-quit] Exit button added to title menu");
                    } catch (e) { console.error("[cc-quit] inject failed", e); }
                  }
                });
                console.log("[cc-quit] sc.TitleScreenButtonGui patched");
              }
              patch();
            })();
        """.trimIndent()
    }
}
