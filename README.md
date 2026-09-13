# Curmudgeon Browser

A small, fast Android browser that does what you tell it. About 1 MB.

Built for people who liked Naked Browser: a tab strip where titles wrap onto several lines and a tap on the
current tab closes it, a pull-up menu, a bookmarks sidebar that swipes in from the left, lots of gestures,
and a settings screen that goes deep when you want it to (flip **Advanced** at the top right).

## What it does
- **Tabs:** multi-line titles, tap / long-press / swipe to close, reopen closed tabs (long press +),
  remembered across restarts, background tabs paused beyond a memory limit.
- **Menus:** tabs and address at the top or bottom; pull-up menu with page top/end, fullscreen, desktop site,
  find, share, save page, night mode, pause history, per-site JavaScript / images / cookies / location.
- **Gestures:** edge swipes for menus and sidebar, two-finger swipes, double tap and drag, swipes along the
  bottom edge, bottom-corner swipes and taps, volume keys, link long press. Every one is configurable.
- **Privacy:** no ads, no accounts, no analytics, no crash reporting. Optional host-list ad and tracker
  blocking, Do Not Track and Global Privacy Control headers, third-party cookies off by default,
  clear history / cache / cookies on exit with per-site cookie keeping.
- **Address bar:** long press edits normally (cursor, select, paste where you want) with "Paste and go" alongside.

## What it connects to
Only the sites you open, plus: the block list address when you turn blocking on or update it, and Google's
suggestion service if you turn search suggestions on. Pages are rendered by Android System WebView.

## Build
See [BUILDING.md](BUILDING.md).

## Licence
GNU General Public License v3.0 only. See [LICENSE](LICENSE). Material icons are Apache-2.0.
If you build on this, keep the attribution. Part of the Curmudgeon family: https://curmudgeon.works
