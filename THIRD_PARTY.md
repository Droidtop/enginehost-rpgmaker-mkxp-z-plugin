# Third-party source and runtime notice

This plugin is built from the mkxp-z engine and the Android scaffold identified
in `ENGINEHOST.md`. Their existing copyright and GPL-2.0-or-later notices remain
in this repository.

The APK contains two separately named Ruby shared libraries so a launch can
select the ABI required by a game without installing another plugin:

- Ruby 3.1.3 is the mkxp-z Android-compatible fork at
  `mkxp-z/ruby` commit `a2d396ea42e1ec778bc516489afe7fde6f0cef5d`.
- Ruby 1.9.2-p320 is the official `ruby/ruby` source at commit
  `ea0b32f984ada7baaaa195a7a94803f49bd9b4a8` (tag `v1_9_2_320`).

`android/app/jni/get_deps.sh` fetches those exact source revisions and the
repository contains all enginehost build changes needed to reproduce the
libraries. Ruby is copyright Yukihiro Matsumoto and contributors and is
available under GPL version 2 or the Ruby license. This plugin is distributed
under GPL-2.0-or-later; see `COPYING`.
