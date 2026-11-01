# Getting network interfaces to tsnet inside an Android app process — what people actually do

Research date: 2026-10-31 (all GitHub states checked on this date). Verified empirically where noted (go1.27.3 darwin/arm64, Homebrew Go; `gh` API against live GitHub).

## 0. Two premise corrections up front (these change the work)

**a) `//go:build linux` IS satisfied by `GOOS=android`.** Your assumption that `feature/androidbin` is "linux-only, so not linked for a gomobile app" is wrong about the tag. `go help buildconstraint` in go1.27.3: *"Using GOOS=android matches build tags and files as for GOOS=linux in addition to android tags and files."* In the toolchain source: `src/go/build/build.go:1959` `if ctxt.GOOS == "android" && name == "linux" { return true }` (comment at :1939 `// linux (if GOOS = android)`). I confirmed with a probe module: for `GOOS=android`, a file tagged `//go:build linux` and one tagged `//go:build unix` are both compiled.

**b) The real gate is a different file.** `feature/androidbin/register.go` is what actually installs the hook, and it is gated:

```go
//go:build (linux && !android) || (android && !cgo)
...
// The hook is not registered in GOOS=android cgo builds (the Android
// app), which register a Java-backed interface getter of their own.
func init() {
    if !feature.Register("androidbin") { return }
    netmon.HookInterfacesFallback.Set(fallbackInterfaces)
}
```

https://github.com/tailscale/tailscale/blob/main/feature/androidbin/register.go

Verified with `go list` against the released module `tailscale.com v1.104.0`:

| build | files compiled in `feature/androidbin` |
|---|---|
| `GOOS=linux CGO_ENABLED=1` | androidbin.go, cacerts.go, **register.go** → hook installs |
| `GOOS=android CGO_ENABLED=1` (**gomobile**) | androidbin.go only — **register.go and cacerts.go ignored** |
| `GOOS=android CGO_ENABLED=0` | androidbin.go, **register.go** → hook installs |

And nothing imports the package for you: `feature/condregister/maybe_androidbin.go` (`//go:build linux && !ts_omit_androidbin`) is linked into tailscaled, not tsnet (`grep androidbin tsnet/*.go` in v1.104.0 → nothing). The package doc says so explicitly: *"It is not linked into tsnet by default; tsnet apps and other programs opt in with a blank import of this package."*

So: Tailscale's own shipped Android fallback deliberately excludes the `android && cgo` case because it assumes cgo+android == the official app with Java glue. Third-party gomobile apps fall into that hole. That is precisely the gap tailscale/tailscale#19455 tries to close.

Also verified: `GOOS=android CGO_ENABLED=0 go build` **works** on go1.27.3 (produces a static arm64/android binary) — so "pure-Go GOOS=android" is a live build option if you can give up cgo.

---

## 1. The official Tailscale Android client

**Mechanism:** Java/Kotlin enumerates interfaces via `java.net.NetworkInterface.getNetworkInterfaces()`, serialises to JSON, the Go side parses it into `[]netmon.Interface` and registers it as the getter. No Java-side netlink, no JNI hand-written bridge — plain `golang.org/x/mobile/bind` interface method call.

Code (tailscale-android `main`, checked 2026-10-31):

- `libtailscale/tailscale.go:45` — `netmon.RegisterInterfaceGetter(a.getInterfaces)` (inside `start()`, before the backend runs).
- `libtailscale/interfaces.go` — the `AppContext` gomobile interface; declares `GetInterfacesAsJson() (string, error)`.
- `libtailscale/net.go:45` — `func (a *App) getInterfaces() ([]netmon.Interface, error)` → calls `a.appCtx.GetInterfacesAsJson()`, then `ifaceparse.ParseInterfacesJSONAsNetmon`.
- `libtailscale/ifaceparse/ifaceparse.go:44` — `ParseInterfacesJSONAsNetmon` (JSON → `netmon.Interface` with `AltAddrs`, flag mapping, `ParseStats` counters; zoned IPv6 kept as `*net.IPAddr`).
- Kotlin producer: `android/src/main/java/com/tailscale/ipn/App.kt:385` `override fun getInterfacesAsJson(): String` — `Collections.list(NetworkInterface.getNetworkInterfaces())`, `nif.interfaceAddresses` with `hostAddress` + `networkPrefixLength`, kotlinx.serialization, "Avoid pretty printing to keep payload small". Note `broadcast` is set from `supportsMulticast()` (copy/paste oddity in their code).
- Build: `Makefile` runs `gomobile bind -target android -androidapi 26 -tags $(./build-tags.sh) ... ./libtailscale` → `libtailscale.aar` / `libgojni.so`.

**Freshness** (this is the part most people miss): on `GOOS=android` netmon's OS monitor is the polling monitor — `net/netmon/netmon_polling.go` (`//go:build ... || android`) → `polling.go:60-65`: `d := 10 * time.Second`, but `if runtime.GOOS == "android" { d = 10 * time.Minute }` with the comment *"We'll have Android notify the link monitor to wake up earlier, so this can go very slowly there, to save battery"* (tailscale#1427). The app therefore also pushes state in from Java: `android/.../NetworkChangeCallback.kt` (ConnectivityManager) → `libtailscale/callbacks.go:43 netmon.UpdateLastKnownDefaultGateway(...)`, `libtailscale/net.go:275 netmon.UpdateLastKnownDefaultRouteInterface(ifname)` then `net.go:277 b.netMon.InjectEvent()`. netmon has Android-specific sinks for those in `net/netmon/interfaces_android.go` (`UpdateLastKnownDefaultGateway`, `UpdateLastKnownDefaultRouteInterface`, `defaultRoute()`).

**History / status (all released, in the shipped app):**
- The hook itself: tailscale/tailscale **PR #2990** "net/interfaces: add RegisterInterfaceGetter for Android" (bradfitz), opened 2021-10-04, closed 2021-10-06; updates tailscale#2293. `RegisterInterfaceGetter` shows as "added in v1.66.0" on pkg.go.dev (that's when the current signature/package path stabilised).
- **tailscale/tailscale-android#21** "cmd/tailscale: implement getInterfaces + SDK 30" (DentonGentry): opened 2021-10-04, **merged 2021-10-08T23:19:31Z**, merge commit `f0dcec6c2757eb2e61e1a4bb358fd9c502854b69`, 5 files (`App.java` +60, `cmd/tailscale/main.go` +83, build.gradle, go.mod/sum), "Fixes tailscale#2293". Its body is the origin of the quote referenced from #9836 ("glomming code on the side" is **DentonGentry in tailscale#9836, 2023-10-17**: *"SDK30 where apps cannot use the bind() function on NETLINK_ROUTE sockets. For the Android app we fixed it by glomming code on the side: <PR 21>. golang/go#40569 would fix it for all Go code building for Android, but has been open for a while."* The first version passed a **custom text format** across JNI because passing lists through JNI is painful; it became JSON only later.
- App rewritten for gomobile 2024-03-19 (`98a72c2` kari-ts "android: new backend", `5e7e36e` Percy Wegmann "android: switch to using gomobile"); `android_legacy` removed 2024-04-17 (#335).
- The shared JSON parser `libtailscale/ifaceparse` + JSON on both sides: commit `94f2829` 2026-01-07 kari-ts, "android: fix local endpoint parsing and add regression ifaceparse_test (#728)".

**License:** BSD-3-Clause (tailscale-android `LICENSE`; file headers `SPDX-License-Identifier: BSD-3-Clause`).

**Work for you:** this is the *only* approach that gives the complete real interface list. In a third-party app it costs ~60 lines Kotlin + ~80 lines Go parsing (see §5 for two people who did exactly this).

**Caveats:** the getter must be cheap and non-blocking (netmon calls it on the 10-minute poll and on `InjectEvent`); you need a ConnectivityManager callback to be correct on network switch; the official app also feeds gateway/default-route separately (netmon's `defaultRoute()` on Android reads a value pushed from Java, and falls back to `/proc/net/route`, which is often unreadable in app sandboxes).

**Related and still open:** tailscale/tailscale#7757 "FR: libtailscale: add function to RegisterInterfaceGetter for Android SDK30 support" (dellgreen, 2023-04-01) — **open**, i.e. there is no supported way for a non-Tailscale app to reuse the app's Java glue through the public libtailscale binding.

---

## 2. tailscale/tailscale PR #19455 — `net/netmon/androidbind`

- **State: OPEN, not merged, no maintainer engagement.** Created **2026-04-19T23:45:07Z** by **GlassOnTin**; still open on 2026-10-31, `updated_at` unchanged from creation, `mergeable: true`, 1 commit, 3 files, +311/-0. Reviews: **0**. Review comments: **0**. PR-issue comments: **0**. Timeline activity is only cross-references (#17311 on 2026-04-27 and 2026-10-11 by lozbrown). https://github.com/tailscale/tailscale/pull/19455
- **What it adds:** `net/netmon/androidbind/androidbind.go` (54 lines, doc-only package + `init()` opt-in side-effect import), `net/netmon/androidbind/androidbind_android.go` (194 lines, `//go:build android`, **cgo**: `getifaddrs(3)` via `<ifaddrs.h>`, `init(){ netmon.RegisterInterfaceGetter(androidInterfaces) }`, tries `net.Interfaces()` first and falls through on EACCES, addresses from `getifaddrsAddrs(name)` into `AltAddrs`), plus a 63-line Android smoke test asserting loopback is found.
- **Not in any release, not on any upstream branch:** `https://raw.githubusercontent.com/tailscale/tailscale/main/net/netmon/androidbind/androidbind.go` → **404**; `net/netmon/androidbind/` exists only on the fork branch **`GlassOnTin/tailscale:android-netmon-getifaddrs`** (3 files, verified via contents API). So today it is a personal fork/branch.
- The author's own claim in the PR body: on bionic (API 24+) `getifaddrs(3)` is implemented over `ioctl(SIOCGIFCONF)` on an AF_INET UDP socket — the same path `java.net.NetworkInterface.getNetworkInterfaces()` uses — which `untrusted_app` SELinux permits; "Verified end-to-end against a live tailnet from a Pixel 8 Pro (Android 16, untrusted_app) with this package imported into a gomobile-bound tsnet embedding."
- Production instance of the same code, already shipped by its author: **GlassOnTin/Haven** (Android SSH/SFTP/VNC client, **AGPL-3.0**), `rclone-android/go/tsbridge/tsbridge.go` — `init(){ netmon.RegisterInterfaceGetter(androidSafeInterfaces) }` with `getifaddrsInterfaces()` / `getifaddrsAddrs()` and flag translation. https://github.com/GlassOnTin/Haven/blob/main/rclone-android/go/tsbridge/tsbridge.go (Haven's own comment on #17311, 2026-04-19, says the code is "under Haven's GPLv3; glad to relicense the relevant ~120 lines under BSD-3" — the repo's license field is AGPL-3.0.)

**bradfitz's "tsnet apps should not need any of that stuff" rework:** the phrase exists only as a comment on #17311 — bradfitz, **2025-10-07T04:44:18Z**: *"This is actually something that I want to fix up soon. tsnet apps should not need any of that stuff."* I could **not find any issue or PR number for that rework.** The nearest tracked sibling is tailscale/tailscale#17313 (bradfitz, 2025-09-27, still **open**): *"tsnet, wgengine/router: tsnet applications shouldn't depend/link system router implementations"* — about linked router implementations, not interface enumeration. Searching `repo:tailscale/tailscale tsnet netmon` in commits returns exactly one hit: the androidbin commit. As of 2026-10-31 tsnet still *requires* netmon: `tsnet/tsnet.go:879 s.netMon, err = netmon.New(...)` (v1.104.0), with no option to skip it.

**What *did* land instead (and it matters more for you than #19455):**
- Commit **`60d9c54b6b35` "feature/androidbin: make netmon and TLS work in raw binaries on Android"** (bradfitz, **2026-09-08**) → new `netmon.HookInterfacesFallback` + `feature/androidbin` + `feature/androiddns` + `cacerts.go`. Also `2ee809d10dfa` "feature: add TS_DISABLE_FEATURE" (2026-09-14).
- **First release containing it: `v1.104.0`** (present at tags v1.104.0/.1/.2, v1.104.4, v1.105.0-pre; **absent** from v1.103.0-pre, v1.102.*, v1.100, v1.98–99). Verified by raw-file probe per tag and by `go get tailscale.com@v1.104.0` + grepping `net/netmon/state.go:829-835`.
- Trigger: tailscale/tailscale#21129 "net/netmon, net/dns: no working path for a standalone binary on Android (Termux)" (user **shaunlee**, 2026-09-05, **closed as completed 2026-09-08** by bradfitz's commit) — filed while getting tailcat running on a phone (tailscale/tailcat#92).
- Hook semantics (`net/netmon/state.go:850-868`): `RegisterInterfaceGetter` wins outright; otherwise `net.Interfaces()` is tried, and `HookInterfacesFallback` is consulted **only if that returned an error**; if the fallback errors, the original error is returned. So a synthetic fallback is last-resort and inert on desktop Linux (androidbin also runtime-detects Android via `/dev/__properties__`).

---

## 3. anet

**Naming note:** the maintained library is **`github.com/wlynxg/anet`** (wenglw/"wlynxg"), BSD-3-Clause, created 2023-06-30, 45 stars, 28 forks, latest tag **v0.0.5** (last code commit 2024-10-04 `839bc3a9` = merged PR #8 "Fix build with CGO_ENABLED=0 GOOS=android"; last commit overall 2025-01-09, README). `github.com/functionland/anet` is a **stale private copy** (created 2023-07-05, last push 2023-07-08, 0 stars, 0 forks, same BSD-3, description "fix go bug #40569") referenced by exactly one repo: **functionland/fula-build-aar** (`go.mod`). A third: **hossinasaadi/anet** (no license file, 1 star, created 2025-04-10, "fix route ip+net: netlinkrib: permission denied in gomobile android 11 +").

**Mechanism (not a build tag, not a monkey patch — a drop-in API you must call):**
- `interface.go` `//go:build !android` → forwards to `net.Interfaces()` etc.
- `interface_android.go` (implicit android constraint, no tag) → its own `NetlinkRIB` (`netlink_android.go`) that **does not `bind()` the netlink socket** and only uses `RTM_GETADDR`, then gets name/MTU/flags via `ioctl(SIOCGIFCONF/SIOCGIFHWADDR/...)` on an index; reimplements `interfaceTable`, `linkFlags`, and the stdlib IPv6 zone cache (`zoneCache.update`, reached with `//go:linkname net.zoneCache`).
- It branches on API level: `android_api_level_cgo.go` (`//go:build android && cgo`) uses `android_get_device_api_level()`; `android_api_level.go` (`!(android && cgo)`) returns a default, and `SetAndroidVersion(uint)` lets the caller set it explicitly. Below API 30 it just calls stdlib.
- Consequence for Go 1.23+: the `//go:linkname` on `net.zoneCache` requires **`-ldflags=-checklinkname=0`**, otherwise `link: github.com/wlynxg/anet: invalid reference to net.zoneCache` (README "How to build with Go 1.23.0 or later"; the same error is what breaks the Termux kubo build today — ipfs/kubo#9513 comment 2026-05-04).
- **Maintenance status:** quiet. Open issue **#11** "invalid reference to net.zoneCache" (updated 2025-10-25) and open PR **#9** "Update interface_android.go" (2025-10-01). Per kubo triage: "PR wlynxg/anet#9 drops the linkname but reintroduces an IPv6 multicast regression (wlynxg/anet#11)". Known functional gaps from the README: `Interfaces()` "can only return interfaces with IP addresses" and "cannot return hardware MAC addresses".

**How it is wired into Go programs (real patterns found):**
- Build-tagged shim in your own code: **syncthing/syncthing** `lib/netutil/interfaces_android.go` (MPL-2.0, added 2025) — `func Interfaces() ([]net.Interface, error) { return anet.Interfaces() }` + `InterfaceAddrsByInterface`; direct dep (`go.mod:44 github.com/wlynxg/anet v0.0.5`).
- Direct calls + explicit API level: **Psiphon-Labs/psiphon-tunnel-core** `psiphon/utils.go` — calls `anet.SetAndroidVersion(...)` from `Build.VERSION.SDK_INT` passed in by the mobile client, with the comment: *"anet.Interfaces fails to enumerate all interfaces, including wlan, on older Android versions"*, so they pin the stock path below API 30 and avoid `android_get_device_api_level` ("may not always be available"). Also `MobileLibrary/Android/make.bash`.
- Other direct users found by GitHub code search: yggdrasil-network/yggdrasil-go (`src/multicast/multicast.go`), yggdrasil-network/yggstack, status-im/status-go (`internal/httpserver/ips.go`), skycoin/skywire (`pkg/netutil/anet_android.go`), mudler/edgevpn (`pkg/discovery/mdns_android.go`), kercre123/wire-pod, anywherelan/awl, ooni/probe-cli (`internal/cmd/buildtool/gomobile.go`), viamrobotics/rdk (`android.make`), confeden/Nova-Android, USBridge-Technologies/USBridge-Remote (vendored copy under `client/internal/anet/`), functionland/fula-build-aar.
- **Transitive** (`// indirect`): **pion/transport/v2** is the real integrator in the WebRTC world — `go mod graph` shows `github.com/pion/transport/v2@v2.2.10 -> github.com/wlynxg/anet@v0.0.3`; hence pion/ice and pion/turn (`go.mod:17`, `:20` indirect) and, through `pion/webrtc`, **ipfs/kubo (`go.mod:238`, v0.0.5) and go-libp2p (`go.mod:98`), netbird, mysterium, harmony, venus, anacrolix/torrent (v0.0.3)**.
- **The IPFS/libp2p Android fix is NOT merged:** libp2p/go-libp2p#2986 "BasicHost: `permission denied` appears on Android11+" (wlynxg, 2024-09-29) **open**; multiformats/go-multiaddr#255 (wlynxg, 2024-09-29) **open, not merged**; ipfs/kubo#9513 (NatoBoram, 2022-12-18) **still open**; maintainer triage **2026-05-04 (lidel)**: *"Small progress since 2022, but I'm afraid the entire networking side is still broken"* — a go-libp2p change made the failure non-fatal (`addrs_manager.go:776` swallows it and falls back to loopback), so the daemon boots but advertises only `127.0.0.1/::1`, mDNS/NAT/source-IP selection are broken → "as a libp2p peer the Android node is effectively isolated." Their recommended guidance for devs is: don't run it, or build with `-ldflags=-checklinkname=0` and accept loopback-only.
- pion/turn#358 (2023-10-11, Lanius-collaris): closed same day as completed — by pion/transport's anet-based fix (see the "EDIT: SOLVED BY pion/webrtc#2640" cross-reference chain), i.e. WebRTC-on-gomobile works today through anet, not through a stdlib fix.
- Berty (production p2p messenger) took a different route: **berty/weshnet-expo#20, merged 2024-10-18** (D4ryl00): *"Android 11 and later restrict some permissions… The workaround is to get local interfaces in Java native code, and then give them to the replaced `go-multiaddr` package in `go.mod`"* + a `WifiManager.MulticastLock` for mDNS + a pinned Go downgrade to dodge golang/go#68285.

**For tsnet specifically:** anet cannot help by itself, because netmon calls `net.Interfaces()` and there is no way to redirect stdlib. It becomes useful only when *you* bridge it into the hook. A working recipe with full code exists in **tailscale/tailscale#18245** (user **ge9**, 2025-12-18, still open): `netmon.RegisterInterfaceGetter(func() ... anet.Interfaces() ... anet.InterfaceAddrsByInterface(&ifs[i]) ...)` — run under **Termux**, "must be run with `-ldflags "-checklinkname=0"` in recent Go", Tailscale 1.90.2, Android 16.

**Effort:** trivial to add as a dep for your own code (~10 lines + linker flag), but for tsnet you still write the bridge above.

**Caveats:** no `HardwareAddr`; interfaces without addresses are omitted; IPv6 multicast regression history (#6, #11); `-checklinkname=0` is required on Go ≥1.23 and it is *repo-wide* (any module in the graph importing anet forces it, cf. kubo); API level must be supplied when not using cgo.

---

## 4. Go upstream status (confirming, not assuming)

- **golang/go#40569** "x/mobile: Calling net.InterfaceAddrs() fails on Android SDK 30" — opened 2020-08-04, **still OPEN**, milestone **Unreleased**, labels `mobile` + `NeedsInvestigation`, last touched 2025-09-22 (no owner, no assignee). https://github.com/golang/go/issues/40569
- **golang/go#61089 / Gerrit CL 507415** "x/mobile: fix net.Interface() and net.InterfaceAddrs() in Android11 version" (wlynxg) — GitHub PR opened 2023-06-30, **still OPEN**, `merged: false`, **`mergeable: false` (needs rebase)**, last updated 2026-03-02 by a commenter; the real review is at https://go.dev/cl/507415 → Gerrit state **NEW**, last activity **2024-03-04**, no Code-Review votes recorded. The author himself wrote (2024-07-19): *"For this submission, I feel it will be difficult to merge it into the official version because it is related to the Android platform. I personally recommend using a third-party library… github.com/wlynxg/anet."* ianlancetaylor (2026-02-28) points people to the Gerrit CL rather than the GitHub PR. Recent comment thread: master255 "In Go 1.26, the interface_linux.go file has changed slightly. I've been using your fix for 3 years now."
- **golang/go#68082** "x/mobile: Calling net.Listen fails on Android 11+" — opened 2024-06-20, **OPEN**, Unreleased. This is the second bug anet says it works around (hence the linkname/zone-cache machinery).
- **Confirmed for your exact toolchain (go1.27.3), it is still broken:** `GOOS=android go list -f '{{.GoFiles}}' net` → the net package compiles **`interface_linux.go`** (there is no `interface_android.go`; the file name's implicit `linux` constraint matches android). That file still does `tab, err := syscall.NetlinkRIB(syscall.RTM_GETLINK, syscall.AF_UNSPEC)` (line 17) and `RTM_GETADDR` (line 124). Grep for "android" across `$GOROOT/src/net/` shows only resolver/cgo plumbing (`conf.go` handling, `cgo_*` tags) — **no interface/netlink special case in Go 1.24 → 1.27**. Nothing in the Go release notes changed this; your failure is expected.

---

## 5. Other real things in the wild (this is the interesting section)

**5a. `expo-tailcat` — current, published, third-party, solves it exactly like the official app.** Part of `slopus/happy` (Happy, RN/Expo). npm package **`expo-tailcat` v0.1.0**, **MIT**, shipped prebuilt artifacts (`android/libs/ExpoTailcat.aar`, iOS xcframework); go.mod pins `github.com/tailscale/tailcat v0.6.1-0.20260909154426` + `tailscale.com v1.103.0-pre.0.20260904030409` (i.e. before androidbin existed).
- Go: `packages/expo-tailcat/go/mobile/interfaces.go` — `init(){ if runtime.GOOS=="android" { netmon.RegisterInterfaceGetter(→ snapshot) } }`, plus exported `SetInterfaces(rawJSON)` that maps name/index/mtu/up/loopback/multicast + CIDR addresses into `netmon.Interface{...AltAddrs}`.
- Kotlin: `packages/expo-tailcat/android/src/main/java/expo/modules/tailcat/ExpoTailcatModule.kt:89 refreshInterfaces()` — `NetworkInterface.getNetworkInterfaces()` → JSON → `Mobile.setInterfaces(json)`; a `ConnectivityManager.NetworkCallback` (`onAvailable/onLost/onLinkPropertiesChanged`) re-pushes it, and because *"Tailcat does not expose its internal netmon.InjectEvent"*, they close and reopen all tunnels on change (`manager.closeAll()` + `onTunnelsClosed` event) instead of waiting for "Android's ten-minute Go polling interval".
- Build: `scripts/build-native.sh` → `gomobile bind -target=android/arm64,android/amd64 -androidapi=26 -tags="osusergo,ts_omit_portmapper,ts_omit_captiveportal,ts_omit_logtail" -ldflags='-s -w -extldflags=-Wl,-z,max-page-size=16384'`.
- So: it *is* gomobile, it *is* a normal app process, it works. Note they use Tailcat (data plane without control plane), not tsnet — but the interface mechanism is identical and directly copyable.

**5b. `package:tailscale` for Dart/Flutter (danReynolds/tailscale_dart) — the closest analogue to "embedding tsnet in an app", and it does not use Java at all.** MIT, pub.dev **v0.9.0 published 2026-08-15** (0.7.x 2026-07-21), "embeds upstream Go tsnet"; Android listed as **"Core supported"** with real-device receipts 2026-08-12/13; `tls.bind` unsupported on mobile (because upstream's localapi cert route is excluded on android — see §7). Not gomobile: Dart **native-assets/`code_assets`** build hook compiles the Go package to a `c-shared` dylib with the NDK toolchain (`hook/build.dart:_configureAndroid`, `CC=aarch64-linux-android<api>-clang`, api 24, adds build tags **`ts_omit_listenrawdisco`** and **`ts_omit_portmapper`** "to avoid socket permission issues / denied netlink or /proc route reads"). Pinned `tailscale.com v1.102.2`.
- `go/netmon_snapshot.go` — `ConfigureHostNetworkSnapshot(rawJSON)`: `if runtime.GOOS != "android" return nil`; parse snapshot; **if empty, use `androidFallbackInterfaces()`** = one synthetic iface `android0`, MTU 1500, address from a 200 ms UDP `DialTimeout("udp","8.8.8.8:53")` local address (i.e. their own re-implementation of the androidbin trick); then `sync.Once{ netmon.RegisterInterfaceGetter(snapshot getter) }`; then `netmon.UpdateLastKnownDefaultRouteInterface(chosen)`. Getters return defensive clones.
- Producer side is **Dart**, not Kotlin: `lib/src/worker/worker.dart:36 _loadHostNetworkSnapshot()` uses `dart:io NetworkInterface.list(includeLinkLocal: true, includeLoopback: true)` (Dart's implementation uses getifaddrs, which is permitted on Android), and the snapshot is passed as a string on every `up()`/`StartRuntimeWithToken(...)` FFI call. Comment: *"dart:io doesn't expose MTU. Go will substitute a sane default."*

**5c. sing-box (SagerNet) — production Android VPN app that feeds interfaces into Tailscale.** It maintains a Tailscale fork (`github.com/sagernet/tailscale`) and in `protocol/tailscale/system_binding.go` (default branch `testing`, build tag `with_tailscale`) it calls `netmon.RegisterInterfaceGetter` mapped from **sing-box's own platform interface provider** (`adapter.PlatformInterface.UsePlatformNetworkInterfaces()` → `network.InterfaceFinder().Interfaces()`), and overrides `defaultRoute` from `InterfaceMonitor().DefaultInterface()`. So in sing-box the platform interface list comes from the app/platform layer (sing-box's Android SDK supplies interfaces/DNS from Kotlin to the Go core) rather than from Go syscalls. Same repo also has `protocol/tailscale/tailcat_*.go` (Tailcat integration).

**5d. Tailcat / Submarine (the Oct-2026 report you mentioned).**
- Report: **lozbrown on tailscale/tailscale#17311, 2026-10-11T07:15:13Z**: same `netlinkrib: permission denied` in an Android app embedding Tailcat built as a gomobile `c-shared` library; the app is **Submarine** (github.com/lozbrown/Submarine, **MIT**, a Rust SSH/SFTP client with an Android build using libtailcat). His diagnosis: *"The dependency version we tested excludes feature/androidbin registration for android && cgo, while gomobile produces a cgo-enabled Android library"* and *"A local build-tag-only workaround enabling that fallback for this cgo consumer allowed Tailcat to start. Submarine then established an SSH connection over Tailcat on a physical Android phone."*
- **The fix exists as a personal fork, not upstream:** `lozbrown/tailscale` branch **`androidbin/register-cgo-fallback`**, commit **`7745abf5d1` (2026-10-11)** "feature/androidbin: enable fallback in Android cgo builds", diff = one build-tag line: `- //go:build (linux && !android) || (android && !cgo)` → `+ //go:build linux || android`, and the comment rewritten to "Android apps may register a Java-backed interface getter. This fallback is consulted only if no getter is registered and net.Interfaces fails." (BSD-3-Clause.) No PR was opened against upstream for it; nothing was merged into tailscale main as of 2026-10-31.
- Tailcat upstream itself is a *separate* Android story: **tailscale/tailcat#118** "all: bump tailscale.com, run on Android, fix e2e test deadlock" (bradfitz, **merged 2026-09-16T04:40:04Z**) added `android_linux.go` blank-importing `tailscale.com/feature/androidbin` + `feature/androiddns` ("The static linux binaries are commonly run as raw executables on Android, under Termux, adb shell, or a rooted shell…"), plus build tag plumbing. Tailcat is BSD-3-Clause. Related open issues: tailcat#126 (closed, `androiddns: bogus answer length`, 2026-09-19→09-26), #136 (open, running tailcat on a Firestick via adb shell), #94 "Add mobile app?" (open, 2026-09-04). tailcat#117 (the DNS one referenced in the #17311 comment) is closed — it was the missing-resolv.conf problem, not interfaces.
- **Is "avoid `net.Interfaces` entirely" a real strategy?** For Tailscale/tsnet, no: there's no way to run tsnet without netmon (`tsnet.go:879`), and even the official app supplies interfaces rather than avoiding the call. What people do instead is (i) supply the minimum Tailscale needs (one synthetic iface: androidbin, tailscale_dart's fallback), or (ii) use **Tailcat** instead of tsnet (no control plane, no logtail/DNS/cert machinery) and still register a getter (expo-tailcat), or (iii) drop Tailscale and use a userspace-only stack (wgengine/netstack or wg-go) — I found **no public project** doing wgengine/netstack-in-an-Android-app without net.Interfaces; the closest published analogue is **tailscale-rs** (lozbrown also forks it; "Rust implementation of Tailscale (preview, experimental)", BSD-3, pushed 2026-10-04) which sidesteps the Go problem entirely, and **Martian** which I could not locate/verify (§8).

**5e. Non-Tailscale Go network stacks on Android** (what they do about it): syncthing (build-tag shim → anet, §3); Psiphon (direct anet + explicit API level, §3); pion/ice/turn/webrtc (anet via pion/transport/v2, works today); OONI probe, yggdrasil, skywire, edgevpn, status-go, viam rdk, screego, anywherelan, Nova-Android, USBridge-Remote (anet calls or `-checklinkname=0` build plumbing); holochain/tx5#87 and litecoin-foundation/nexus#132 and smolcars/blixt-wallet#192 are unresolved open reports of the same error. I found **no** Go-based Android VPN SDK that got this fixed by the platform; every one of them either calls anet, gets data from the platform layer, or runs as a Termux/adb binary.

---

## 6. GOOS=linux instead of android, Termux, "raw binary" paths

- **`gomobile bind` cannot target linux.** `gomobile bind -h` (x/mobile current): `-target android|ios|iossimulator|macos|maccatalyst`; android is the default and there is no linux target. So "gomobile with GOOS=linux" is not a thing; you'd build the shared object yourself (`go build -buildmode=c-shared`) or ship a separate executable.
- **What people actually do instead: ship a static GOOS=linux binary and run it on the device.** That is the explicit target of `feature/androidbin` (doc: "makes Tailscale binaries work when run as raw (non-GUI app) executables on Android, such as under Termux, adb, or a rooted shell… Like androiddns, the package builds on GOOS=linux as well as GOOS=android, because static linux binaries are commonly run under Android kernels"). Concretely:
  - tailscale/tailscale#21129 (Termux) → closed by the androidbin commit, v1.104.0.
  - tailcat: `android_linux.go` + `#118`; user reports of `tailcat` running on a Firestick/Android via `adb shell` (tailcat#136, #126) — i.e. it works, with caveats (the `androiddns` path had a "bogus answer length" bug fixed in Sept 2026).
  - ge9's tsnet-on-Android/Termux recipe in #18245 (GOOS=android native Termux Go + anet bridge + `-checklinkname=0`), and tailscale in Termux in general (also note: Termux/Android Go binaries additionally need `/dev/__properties__`-detected CA-certificate handling, `feature/androidbin/cacerts.go`, and no `/etc/resolv.conf` → `feature/androiddns`).
  - NetBird: **not** merged upstream — `github.com/wlynxg/anet v0.0.5 // indirect` only. The real-world NetBird-on-Android fix is a personal build: blog post "Workaround for NetBird Android `netlinkrib: permission denied`" (blog.lzc256.com) → patch NetBird's sources to call anet, `go mod edit -replace github.com/wlynxg/anet=github.com/lzc256/anet@latest`, then `GOOS=linux GOARCH=arm64 CGO_ENABLED=0 go build -ldflags "-s -w -checklinkname=0" ... ./client` + an `android-anet.patch`. Confirms that "GOOS=linux, CGO_ENABLED=0, run on Android" is a used pattern.
- **The "patch the Go stdlib and rebuild" family** (still used, works, high maintenance):
  - OP of #17311 (ntlv): *"I can work around the error by building go from source with https://go-review.googlesource.com/c/go/+/507415 applied."*
  - golang/go#40569 comment 2024-01-24 (invented-pro): built Go from source with PR 61089 applied, built the .aar, "no netlinkrib: permission denied anymore" (notes: Go-from-src build fails on Apple silicon; needs a Linux build host).
  - **functionland/fula-build-aar** (Functionland/Cryptee side, default branch `master`, last push 2026-06-01, an AAR wrapper for go-fula/IPFS): directory `golang-issue-no-40569-workaround/` with `interface_linux.patch` + `netlink_linux.patch` + `patch.sh`/`unpatch.sh` — it copies `$GOROOT/src/net/interface_linux.go` and `$GOROOT/syscall/netlink_linux.go`, `git apply`s the patches, `go install -a net syscall`, then `gomobile build`. The readme says the patches are *"Adapted from https://github.com/yan12125/go/blob/…/src/net/interface_linux.go"* (yan12125's Go fork, the pre-CL-507415 community patch).
- **Does relaxing the tag actually work for a real app?** Yes, per lozbrown's Submarine result (§5d) and the fact that `fallbackInterfaces`/`buildSynthetic` compile fine under `android && cgo` (only `cacerts.go` is excluded there).

---

## 7. Adjacent Android/tsnet landmines you should budget for (found while tracing these threads)

Fixing interfaces is necessary but not sufficient; the same people hit these:
1. **No `/etc/resolv.conf`** → every DNS lookup fails (`feature/androiddns`, also v1.104.0; tailscale#21129 §1, tailcat#117/#126).
2. **No CA roots** for a GOOS=linux binary (`feature/androidbin/cacerts.go`, tag `linux && !android`) → all TLS fails.
3. **LocalAPI `cert/` route is excluded on android** → ACME/Funnel TLS 404s: tailscale#18245 (ge9, 2025-12-18, **open**) and #21719 (badibam, 2026-10-09, **open**): *"Tested in an Android app embedding tsnet (arm64, NDK, cgo) on v1.104.1: certificate issuance then takes about 40 s, and Funnel requests succeed over Wi-Fi and 4G"* after removing `android` from `ipn/localapi/cert.go`/`disabled_stubs.go` build tags; fix "PR to follow" (not filed as of 2026-10-31). Note this reporter got interfaces working on v1.104.1 in an app — how, is not stated anywhere (unconfirmed).
4. **Raw disco sockets / portmapper** hit SELinux denials: build tags `ts_omit_listenrawdisco`, `ts_omit_portmapper` (tailscale_dart), `ts_omit_portmapper,ts_omit_captiveportal,ts_omit_logtail` (expo-tailcat's `native-tags.txt`).
5. **16 KB pages / NDK**: `-extldflags=-Wl,-z,max-page-size=16384`, `-androidapi=26` (official Makefile, expo-tailcat).
6. **10-minute interface polling** unless you call `Monitor.InjectEvent()` (§1).

---

## 8. Ranked recommendation for "we embed tsnet via gomobile in a normal Android app"

Ranked by what real people actually do today, plus risk:

1. **Feed the real interface list from Kotlin/Java into `netmon.RegisterInterfaceGetter`.** This is the approach the official app uses, and the two independent third-party projects that got tsnet/Tailcat working inside a normal app process (expo-tailcat; and tailscale_dart, which uses Dart's `NetworkInterface.list` instead of Java) both use the same shape. Cost: ~150 lines total (Kotlin JSON producer + Go parser; you can literally copy `ifaceparse`'s 140 lines since it's a standalone package with no other deps — BSD-3). Correct for multi-homing (Wi-Fi + cellular), IPv6 (prefix lengths and zones), multicast flags. Add a `ConnectivityManager.NetworkCallback` and call `netmon.UpdateLastKnownDefaultRouteInterface` + `Monitor.InjectEvent()`; without that you inherit the 10-minute poll. This is also the only approach that keeps working if Tailscale changes its fallbacks.
2. **cgo `getifaddrs(3)` in your own Go package** (Haven's `tsbridge.go`, PR #19455's `androidbind_android.go`): ~120 lines of cgo, no Java/Kotlin code at all, full interface list including IPv6 (no `HardwareAddr`). This is the best choice if you don't want to touch the Android side. It is *not* upstream (PR open, unreviewed), so you own it — but it's proven by an AGPL app shipping today and by the PR author's Pixel 8 Pro end-to-end test. Note your app must already be cgo (gomobile is).
3. **Synthetic single interface** — copy `feature/androidbin`'s `buildSynthetic()` (≈40 lines) and set `netmon.HookInterfacesFallback.Set(...)` yourself, since `register.go` won't compile for `android && cgo` (§0b). Works with `tailscale.com ≥ v1.104.0`. Cheapest (one file), self-disabling on non-Android, and it's the same code Tailscale ships — but it gives you one made-up iface with only the primary outbound IPv4+IPv6 /32 + /128, MTU 1500, no name/index truth, no multi-homed selection, no subnet routes, and no link-change signal (netmon polls it every 10 min). Fine for an outbound-only client; wrong for a node that advertises routes or listens on specific interfaces. (lozbrown's Submarine workaround is exactly this, via a 1-line tag change in a fork.)
4. **Bridge `wlynxg/anet` into `RegisterInterfaceGetter`** (the #18245 recipe): small, and anet is already in a huge number of graphs (pion/transport). Downsides today: unmaintained-ish, forces `-checklinkname=0` repo-wide on Go ≥1.23, no MAC addresses, drops address-less interfaces, IPv6-multicast regression in its own PR #9.
5. **Don't use tsnet:** use Tailcat for point-to-point data-plane needs (expo-tailcat is the template, `feature/androidbin`+`androiddns` already wired for the binary case), or use `tailscale-rs` to sidestep Go, or require the official Tailscale app / VpnService. Note there is *no* supported public embed path for the official Android client's Java glue: #7757 (asking for it) is still open.
6. **Out-of-process (Termux / adb shell / static GOOS=linux binary):** real and supported for tailcat/tailscaled/kubo/netbird, but it is not "inside your app process": no gomobile, no `c-shared`, and you need `feature/androidbin`+`feature/androiddns`+CA handling. `gomobile bind` has no linux target.
7. **Patching the Go stdlib / building Go from source** (CL 507415, yan12125's fork, fula's `patch.sh`): works, but it's the highest-maintenance option, the CL has been NEW-and-unreviewed since 2024-03, and the author of the CL himself recommends the library instead.

---

## 9. What I could not confirm

- **Any upstream engagement with #19455** — zero reviews, zero comments, no maintainer response; and no milestone/label. Its `merge_commit_sha` is an auto-merge *test* object, not a merge (`merged: false`).
- **A tracked issue/PR for bradfitz's "tsnet apps should not need any of that stuff" rework.** Only #17313 (router linking) is tracked; searching issues and commits for the rework found nothing else as of 2026-10-31.
- **Whether `net/netmon/androidbind` exists on any upstream branch** — it does not (404 on main; present only in `GlassOnTin/tailscale@android-netmon-getifaddrs`).
- **How user `badibam` (tailscale#21719) got interfaces working on v1.104.1 inside an app process** — the issue says "Android app embedding tsnet (arm64, NDK, cgo) on v1.104.1" but no comments and no linked repo; not determinable.
- **Whether Submarine/Tailcat upstream ever took lozbrown's one-line tag change** — no PR exists against tailscale/tailscale or tailscale/tailcat; only his fork branch. Tailcat's own issue tracker does not mention the app-process case as fixed.
- **Whether Termux patches Go for this** (termux/termux-packages `golang`, `tailscale`, `netbird` ports) — not verified. There is an `android-kxxt/external_tailscale` AOSP-shaped repo I did not examine.
- **Whether getifaddrs on bionic returns interface index/flags for address-less interfaces, and MTU** — Haven's code reads `ifa_index`/`ifa_flags` but I did not verify whether bionic emits AF_PACKET entries at all; treat "full list" for option 2 as *slightly weaker* than for option 1 (anet and the official app both note missing interfaces/MAC).
- **Whether `expo-tailcat` 0.1.0 has actually been published to npm** (package.json says 0.1.0 with `publishConfig.access: public`; I confirmed the source and CI workflow in `slopus/happy`, not the npm registry artifact), and its exact release date.
- **tailscale-qt**: no project by that name found. `MirageNetwork/MirageClient` (a Tailscale fork with its own `net/interfaces/interfaces.go`) exists but I did not verify its Android mechanism or that it is maintained.
- **Martian** (the userspace in-app networking stack I associated with Mysterium/XMTP): I could not locate the repo under `xmtp/martian` or `mysteriumnetwork/martian` in the time available, so I cannot say whether it calls `net.Interfaces()` on Android.
- **No code was executed on an Android device/emulator for this report.** Everything about runtime behaviour is from source, released modules, and first-party reports (including two reports of end-to-end success on a Pixel 8 Pro / Android 16 untrusted_app and one on a physical Android phone with Submarine→libtailcat→SSH).
