# enginehost RPG Maker plugin

This fork is the complete native Android plugin for the `rpgmaker` engine
family. The upstream-facing branch remains mkxp-z; `plugin-core` carries the
portable enginehost and Android packaging changeset. Version branches begin at
a selected mkxp-z revision and merge that changeset.

The Android scaffold is derived from the GPL-2.0-or-later
`thehatkid/mkxp-z-android` project at commit
`468fe8128a40cc7d2cba4ac7fbe21a82787de255`. It builds this branch's mkxp-z
source directly rather than carrying a second copy of the engine. Upstream
copyright, `COPYING`, and dependency licenses remain intact.

The exported `EngineHostRunActivity` accepts the resolved game path, engine
context, engine version, and JSON options. RGSS games run in place through
mkxp-z. MV/MZ deployed HTML games use the bundled WebView runner. No game is
copied and the plugin does not request all-files access.

`options` is an engine-owned configuration object. The native runtime overlays
it on mkxp-z configuration in memory after the host has applied the
authoritative game-folder configuration. A version branch may further restrict
or translate settings for its Ruby ABI and compatibility profiles.

Ruby is selection-critical rather than an ordinary option. This build's RGSS
capabilities advertise `runtimeComponents: { "ruby": "3.1.3" }`; a game that
needs the original VX Ace ABI requests
`runtimeRequirements: { "ruby": "1.9.2" }` and will resolve only to the
matching capability. The completed plugin bundles both internally namespaced
Ruby/mkxp native builds in one APK and routes by capability ID; it does not
require a separate installed plugin for each Ruby. Signed plugin APK releases
are the downloadable runtime bundles—native libraries are not loaded loose
from game storage.

Android APKs are produced in CI. The initial RGSS3/Ruby 3.1 line is useful for
broad compatibility but is not yet a faithful Ruby 1.9 runtime for every VX Ace
game; MGQ Paradox requires additional Win32API compatibility work.
