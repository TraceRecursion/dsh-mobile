# Embedding Tailscale inside a third-party mobile app — feasibility assessment

Research date: **2026-10-29** (all URLs checked that day unless noted).
Target apps: Kotlin Multiplatform + Compose Android (minSdk **API 24**), native SwiftUI iOS (deployment target **iOS 16.1**). Use case: app **dials out** a WebSocket (`ws://` / `wss://`) to a tailnet `100.x.y.z` host. No separate Tailscale app, no system-wide VPN.

---

## TL;DR VERDICT

| Platform | Verdict | Why |
|---|---|---|
| **Android, API 24** | **Feasible — with caveats (DIY, no official SDK)** | There is **no official Tailscale Android/Kotlin SDK**. The mechanism itself is proven and well-supported: `tailscale.com/tsnet` (userspace, no `VpnService`, no TUN, no consent dialog, no root) compiled into your app with `gomobile bind`, exactly the way Tailscale builds its own Android client. Caveats: you own the binding code; upstream's own app is `minSdk 26` + `-androidapi 26` so **API 24–25 is untested upstream**; background liveness requires your own foreground service; **OkHttp/Ktor cannot authenticate to the tsnet SOCKS5 proxy**, so the WebSocket plumbing is custom work. |
| **iOS, deployment target 16.1** | **Not feasible as shipped / feasible only by forking** | The official Swift layer (**TailscaleKit**, in `tailscale/libtailscale`) is built with `IPHONEOS_DEPLOYMENT_TARGET = 18.1` and its inbound-listen API is annotated `@available(iOS 18.0, …)`. Its URLSession bridge needs `URLSessionConfiguration.proxyConfigurations` = **iOS 17+**. I verified the **C library itself links into an iOS 16.1-target binary without complaint** — so a fork that lowers the target and drops the URLSession bridge is technically viable, but that is an unsupported, self-maintained build. If you can move the iOS floor to **18.1** (or 17.0 with the upstream patch that is already merged), this becomes a straightforward yes. |
| Mechanism (both) | userspace `tsnet` **inside your process** | gVisor netstack + wireguard-go in-process; optional loopback **SOCKS5 proxy on 127.0.0.1** + LocalAPI. **No NEPacketTunnelProvider, no NEAppProxyProvider, no `VpnService`, no TUN, no VPN toggle, no VPN icon, no system VPN configuration.** |

---

# iOS

## 1. Is there an official Tailscale SDK for iOS/Swift? Name, distribution, version, stability

**Yes — "TailscaleKit", and it is official, but it is not a distributable package.**

* It lives inside **`github.com/tailscale/libtailscale`** ("Tailscale C library", created 2023-02-21, **BSD-3-Clause**, 347 stars, last push **2026-10-14**) at **`swift/`** → Swift package/framework named **`TailscaleKit`** (`swift/README.md` title: "# TailscaleKit").
  https://github.com/tailscale/libtailscale · https://github.com/tailscale/libtailscale/tree/main/swift
* **Distribution: build-it-yourself, not SPM, not a published XCFramework.** The repo has **0 tags and 0 GitHub releases** (checked 2026-10-29), so there is **no version number** — you pin a commit. You build with `cd swift && make ios` / `make ios-sim` / `make ios-fat` (produces an **unsigned** `TailscaleKit.xcframework` in `swift/build/Build/Products/Release-iphonefat/`). README: *"The frameworks are not signed and must be signed when they are embedded."*
* **SPM support is an open, unmerged PR**: tailscale/libtailscale#55 *"Add SwiftPM and initial linux support for swift"* (opened 2026-07-11, still open). https://github.com/tailscale/libtailscale/pull/55
* There is **no Tailscale documentation page for TailscaleKit** — the docs site (validated Jul 24 2026) documents `tsnet` for Go only: https://tailscale.com/docs/features/tsnet. TailscaleKit's only docs are the repo README + the `TailscaleKitHello` example.
* Provenance: issue tailscale/tailscale#13937 *"FR: first-class Swift support"* — **closed as completed**, and per #20992 "TailscaleKit is the result"; #15410 *"libtailscale on iDevices"* also closed as completed.
* Underlying version: `go.mod` pins **`tailscale.com v1.104.1`** with `go 1.27.1` (main branch, 2026-10-29). libtailscale PR/issue #64 *"bump tailscale to v1.104.1"* merged 2026-10-09.
* Build requirement: **Xcode 16.1 or newer** (README; CI runs `macos-15` + Go 1.24 in `.github/workflows/swift.yml`, though go.mod now needs 1.27).

**Community distribution option (unofficial, active):** `AkinoKaede/libtailscale-spm` — "Swift package wrapper and Apple XCFramework builds for official Tailscale"; ships a **prebuilt `CTailscale.xcframework`** (macOS arm64/x86_64, iOS arm64, iOS-sim arm64/x86_64), adds Keychain-backed node state, and runs a *weekly upstream tracking* workflow that re-pins upstream and re-releases. Created 2026-10-02, pushed 2026-10-19, BSD-3, 1 star. It states its platform support as **"macOS 15+ and iOS 18+"**. https://github.com/AkinoKaede/libtailscale-spm
**Active commercial fork:** `GalaxNet-Ltd/libtailscale` (17 stars, pushed 2026-10-28), behind an iPhone app ("NovaScale") that ships built-in Tailscale. Its deployment target is also **18.1** (I checked its `project.pbxproj`).

## 2. Minimum iOS version — the blocker for iOS 16.1

**Currently: iOS 18.1 (project setting) / iOS 18.0 (code annotations). iOS 16.1 is not supported out of the box.**

Evidence (primary):
* `swift/TailscaleKit.xcodeproj/project.pbxproj`: `IPHONEOS_DEPLOYMENT_TARGET = 18.1` in the iOS target; `MACOSX_DEPLOYMENT_TARGET = 15.0` / `15.6`. I fetched the file at **all 7 commits that ever touched it** (2025-01-02 `2948fe11` → 2026-02-06 `1012969d`) and it has been **18.1 the whole time**.
* `swift/TailscaleKit/Listener.swift:14` and `IncomingConnection.swift:15` are annotated `@available(iOS 18.0, macOS 15.0, tvOS 18.0, watchOS 11.0, *)`.
* Issue tailscale/tailscale#21036 (2026-08-30, closed) contains **measured** floors by a third-party adopter building at each target with Xcode 26.1.1:
  * as-is → **iOS 18.0 / macOS 15.0**;
  * with the two listener actors gated (the fix, merged as libtailscale `8564835` via PR #60) → **iOS 17.0 / macOS 14.0**;
  * *"at iOS 13.0 only `ProxyConfiguration` (`URLSession+Tailscale.swift`) fails"*; *"at iOS 12.0 Swift concurrency itself fails"*;
  * *"The Go layer is not the constraint: `swift/script/clangwrap-ios.sh` already builds it `-mios-version-min=12.0`."*
  * They also warn the published floors (18.1) are higher than the code's real floor, and that a mismatch **links fine and then dies at launch (dyld)** — *"We shipped exactly that to App Review before catching it."*
  https://github.com/tailscale/tailscale/issues/21036 · https://github.com/tailscale/libtailscale/pull/60
* The remaining iOS-17 dependency is real API: `URLSessionConfiguration.proxyConfigurations` + `ProxyConfiguration(socksv5Proxy:)` + `applyCredential(username:password:)` are iOS 17+ (used in `swift/TailscaleKit/URLSession+Tailscale.swift`, no `@available` guard there).

**My own measurement (this machine: Go 1.27.3, Xcode 26.x / iOS SDK 27.1, arm64):**
* `make c-archive-ios` on libtailscale main → `libtailscale_ios.a` = **26,207,728 bytes**.
* A trivial C program calling `tailscale_new()`/`tailscale_set_hostname()`, linked with `-mios-version-min=16.1`, **linked successfully** (only extra requirement: `-framework CoreFoundation -framework Security`). So the **Go/C layer is genuinely fine at iOS 16.1**.

→ **Practical reading:** iOS 16.1 is achievable only by building a fork: lower `IPHONEOS_DEPLOYMENT_TARGET`, keep the `@available(iOS 18)` gate on the listener types, and **exclude or reimplement `URLSession+Tailscale.swift`** (i.e. do your own SOCKS5 handshake against the loopback proxy). Nobody upstream supports or CI-tests that configuration.

## 3. Mechanism — no VPN toggle, no NetworkExtension at all

* `tsnet` README (auto-generated, in the tailscale repo, main @ 2026-10-29): *"Package tsnet embeds a Tailscale node directly into a Go program … **without running a separate tailscaled daemon or requiring any system-level configuration** … it runs a fully self-contained Tailscale node inside your process using a **userspace TCP/IP stack (gVisor)**. This means: No root privileges required. No system daemons to install or manage. **Multiple independent Tailscale nodes can run within a single binary.** The node's Tailscale identity and state are stored in a directory you control."* https://github.com/tailscale/tailscale/blob/main/tsnet/README.md
* Tailscale docs, tsnet page: *"`tsnet` uses a [userspace] TCP/IP networking stack."* https://tailscale.com/docs/features/tsnet (last validated Jul 24 2026); userspace mode concept page: https://tailscale.com/docs/concepts/userspace-networking (Nov 12 2025)
* #20992's reproducer is explicitly *"a userspace tsnet app (no `NEPacketTunnelProvider`) running on a physical iPhone"* — i.e. no NetworkExtension involved.
* Instead of a TUN, the SDK vends **a SOCKS5 proxy on `127.0.0.1:<random port>`** with mandatory credentials, plus the LocalAPI over the same listener (`tailscale_loopback` in `tailscale.h`; implementation `tsnet.Server.Loopback()` → `socks5.Server{Dialer: s.dialer.UserDial, Username: "tsnet", Password: <32 hex>}`, `net.Listen("tcp","127.0.0.1:0")`). Because the SOCKS server dials through tsnet's userspace dialer, **tailnet/MagicDNS names given as SOCKS domain addresses are resolved by the embedded node**.
* Contrast (what you're avoiding): the **Tailscale iOS app** *"will install a NetworkExtension VPN configuration"* on first launch — https://tailscale.com/docs/integrations/mdm/ios. And Apple-style **per-app VPN on iOS is MDM-only**: tailscale/tailscale#18408 *"FR: Support per-app VPN on iOS"* (2026-01-16), Tailscale member `barnstar` 2026-01-19: requires a fully managed device, managed apps, and a per-app VPN profile from an MDM, and *"Apple's Network Extension framework … will not let multiple tunnels run simultaneously."* https://github.com/tailscale/tailscale/issues/18408

## 4. API surface for a dial-out-only WebSocket app (+ cost of listening)

C API (`tailscale.h`, complete as of main @ 2026-10-14): `tailscale_new`, `tailscale_start`, `tailscale_up`, `tailscale_close`, `tailscale_set_dir|_hostname|_authkey|_control_url|_ephemeral|_logfd`, **`tailscale_dial(sd, "tcp"|"udp", addr, &conn)`**, `tailscale_listen`, `tailscale_accept`, `tailscale_getremoteaddr`, `tailscale_getips`, **`tailscale_loopback(sd, addr_out, addrlen, proxy_cred_out, local_api_cred_out)`**, `tailscale_status_json`, `tailscale_enable_funnel_to_localhost_plaintext_http1`, `tailscale_errmsg`. Connections are *"a pipe(2) on which you can use read(2), write(2), and close(2)"* — real fds, so epoll/poll/select work.

Swift API (`swift/TailscaleKit/`):
* `TailscaleNode` (actor): `Configuration(hostName:path:authKey:controlURL:ephemeral:)`, `up()`, `down()`, `close()`, `addrs() -> (ip4,ip6)`, `statusJSON() -> Data` (ipnstate.Status JSON via the in-memory LocalAPI), `loopback() -> LoopbackConfig(address, proxyCredential, localAPIKey)`. *"You may instantiate several 'nodes' in a single application. Each will get a unique IP address on the Tailnet."*
* **URLSession integration (the one you want):** `URLSessionConfiguration.proxyVia(_ node:)` and `URLSessionConfiguration.tailscaleSession(_ node:)` install the SOCKS5 proxy into a session config → any `URLSession` request (and, by extension, `URLSessionWebSocketTask`) then routes over the tailnet. README snippet shows exactly this; *"You can cache this. It will not change once the node is up."*
* `OutgoingConnection` (actor, NWConnection-flavoured): `init(tailscale:proto:address:logger:)`, `connect()`, `send(_:)`, `close()`, `state: ConnectionState(.idle/.connected/.closed/.failed)`. Docs: *"For HTTP(s), consider using URLSession.tailscaleSession"*.
* `Listener` / `IncomingConnection` for **inbound**: `Listener(tailscale:proto:address:)`, `accept(timeout: TimeInterval = 60)`, `IncomingConnection.receive(maximumLength:timeout:)` / `receiveMessage(timeout:)`, state as `AsyncSequence`.
* A partially complete **LocalAPI client** (`LocalAPI/LocalAPIClient.swift`) over the loopback HTTP server — README: *"functional (though somewhat incomplete)"*.

Cost / sharp edges of listening (inbound) and of the dial path (all dated, primary):
* `Listener.accept` is **poll(2)-blocking with a ms-precision timeout capped at `Int32.max` ms**; `IncomingConnection` uses an internal 2048-byte chunked `SocketReader`.
* **`IncomingConnection` can receive but cannot send** — answering a peer requires dropping to the C API. FR **tailscale/tailscale#22039** (2026-10-28, open) also asks for `tailscale_whois`, public `logout()`, and `UserID` in peer status.
* Open bug **tailscale/tailscale#22025** (2026-10-28): *"OutgoingConnection.send uses a pointer outside withUnsafeBytes"* (undefined behaviour / potential corruption) — libtailscale#70 open.
* Open bug **tailscale/tailscale#21934** (2026-10-25): *"accepted connections are not cleaned up when their fd number is reused"* — libtailscale#69 open. Relevant if you listen.
* Open bug **tailscale/tailscale#21220** (2026-09-11): *"watch-ipn-bus long poll is killed every 60s on an idle tailnet"* — matters if you use the ipn bus for interactive auth.
* **Open bug tailscale/tailscale#21005** (2026-08-27): `TailscaleNode.down()` calls `tailscale_up()` — it *starts* a stopped node instead of stopping it (still present in current source; I read it).
* **Open bug tailscale/tailscale#21167** (2026-09-09): `tailscale_close` returns success while `tailscale_up` remains blocked (libtailscale#63 open).
* **Open bug tailscale/tailscale#20997** (2026-08-26): `LocalAPIClient` unusable during bring-up because `up()` holds the node actor.
* `#21878` (2026-10-21): `watchIPNBus` with `.netmap` fails silently.
* Not verified by me: that `URLSessionConfiguration.proxyConfigurations` applies to `URLSessionWebSocketTask` specifically (session-level config suggests yes, but I found no primary statement). Treat as **to-be-tested** in a spike.

## 5. Backgrounding / termination of the embedded node

* iOS: *"By default, backgrounded apps are suspended. They don't get CPU time."* — Apple, WWDC25 session 227 "Finish tasks in the background" (https://developer.apple.com/videos/play/wwdc2025/227). An in-process Go runtime is frozen with the app; there is no network-extension-style separate process in this design.
* TailscaleKit's own code confirms the consequence: `tailscale_status_json` docs — *"it keeps working when the OS **reclaims the loopback TCP listener from a suspended process (observed on iOS)**, where the cached loopback address goes permanently stale"* (added via libtailscale#54, 2026-06-08). i.e. Tailscale is designing around a **suspended** embedded node.
* ⇒ **The embedded node does not survive backgrounding beyond the short background-execution window, and certainly not termination.** A WebSocket (`URLSessionWebSocketTask` or a raw `OutgoingConnection`) **drops when the app suspends**; you must reconnect on foreground, and re-run `up()`/re-create the node after relaunch (state on disk in `Configuration.path` means the node keeps its identity, subject to key expiry).
* I found **no** Tailscale statement that an embedded node can be kept alive in the iOS background, and no background-mode recipe in TailscaleKit. (iOS has no equivalent of an Android foreground service; CallKit/PushKit/VoIP wakeups are Apple mechanisms, not Tailscale-documented here.)

## 6. Binary size, memory, documented limits

**Binary size — measured by me** (Go 1.27.3, `tailscale.com v1.104.1`, libtailscale main, arm64 iPhoneOS, Release):
* `libtailscale_ios.a` static archive: **26,207,728 B (~25 MB)** (built with `-ldflags -w`).
* Trivial consumer binary linked against it, stripped: **19,643,280 B (~18.7 MB)** total file, **`__TEXT` = 16,302,080 B (~15.5 MB)**, of which `__text` ≈ 8.06 MB; baseline hello-world stripped = 49,432 B. `gzip -9` of the linked binary ≈ 7.5 MB vs 781 B baseline.
  So budget **~15–19 MB of `__TEXT`/binary growth per embedded engine** (the same Go engine either way; TailscaleKit does not add meaningfully to that). This is my measurement, not a Tailscale-published number. Note the archive size is not the app growth — link-time dead-stripping removes a lot.
* **Memory/RSS cost: not documented anywhere I could find.** Unconfirmed.
* **Multiple embedded nodes:** supported. tsnet README has a section *"Running multiple nodes in one process"* (unique `Dir` + `Hostname` each); TailscaleKit says the same. No stated cap on nodes per tailnet.
* **Plan/accounting limits instead of technical limits** (https://tailscale.com/pricing, checked 2026-10-29): Personal is free for **up to 6 users**, **"User Devices … Unlimited"**, **50 tagged resources included** (+$1/mo each), **1,000 ephemeral resource-minutes per month**. So: an embedded node that enrolls **as a user device** is free/unlimited; if you hand out **tagged** auth keys it counts against the 50; if you use **ephemeral** auth keys it burns ephemeral minutes. Plan rework date: **2026-04-08** (https://tailscale.com/blog/pricing-v4).
* **DNS/MagicDNS:** tsnet resolves tailnet names for you through its own userspace dialer (that is what the loopback SOCKS5 uses); `ListenTLS` hard-fails without MagicDNS: *"tsnet: you must enable MagicDNS in the DNS page of the admin panel to proceed."* (`tsnet/tsnet.go:1468`).
* **HTTPS certs in the SDK:** `tsnet` supports `CertDomains()`/`ListenTLS()` and `tailscale_enable_funnel_to_localhost_plaintext_http1` exists in the C API (Funnel → local plain HTTP, mints Let's Encrypt certs). **But** per the Dart embedder: *"`tls.bind` … Upstream's certificate endpoint is **disabled on iOS/Android**, so mobile termination is explicitly unsupported; the package does not add a parallel certificate path"* (https://pub.dev/packages/tailscale, 0.9.0, published ~2026-08). I did **not** independently confirm the upstream mobile gating in the Go source → **partially unconfirmed**, but treat server-side TLS termination on mobile as unsupported. For a *client* dialling `wss://` to a host that got its cert from its own Tailscale client, this does not affect you.
* Docs pages for reference: tsnet API reference https://tailscale.com/docs/reference/tsnet-server-api · HTTPS certs https://tailscale.com/docs/how-to/set-up-https-certificates · auth keys https://tailscale.com/docs/features/access-control/auth-keys · ephemeral nodes https://tailscale.com/docs/features/ephemeral-nodes

## 7. Authentication options for the embedded node

* **TailscaleKit/libtailscale exposes exactly two config knobs**: `authKey` and `ephemeral` (`Configuration`, and `tailscale_set_authkey` / `tailscale_set_ephemeral` / `_hostname` / `_dir` / `_control_url`). README: *"Nodes need to be authorized in order to function. Set an auth key via the `config.authKey` parameter, or **watch the ipn bus** (see the example) for the **`browseToURL`** field for interactive web-based auth."* → i.e. **in-app browser sign-in is possible** by presenting that URL, exactly like the Tailscale app's login flow. `LocalAPIClient` also has `login`/`logout`/`resetAuth`-shaped surface (partially incomplete; `logout()` is `internal` per #22039).
* **OAuth client credentials are NOT exposed** by libtailscale/TailscaleKit, even though upstream `tsnet` supports them: `tsnet.Server` fields include `ClientID`, `ClientSecret`, `IDToken`, `Audience`, `AdvertiseTags`, `Store`, `UserLogf` (source: `tsnet/tsnet.go:234-335`), and tsnet README documents the auth precedence: **AuthKey → `TS_AUTHKEY` → `TS_AUTH_KEY` → OAuth client secret (`ClientSecret`, which *mints* an auth key) → workload identity federation (`ClientID` + `IDToken`/`Audience`, opt-in feature import) → interactive login URL printed to `UserLogf`**. The C shim only implements the auth-key and ephemeral subset (I verified `tailscale.h` and the exported setters).
* **Tagged/ephemeral node naming**: you choose `hostName`; tagging/ACL placement is done via the auth key's tags (or `AdvertiseTags`, not exposed in the C API). Device approval: tsnet docs note that with device approval enabled an admin must approve the node, or you pre-approve with an auth key.
* **Does the end user need a Tailscale account?** **No, if you ship a pre-provisioned auth key** — the app registers its own node in *your* tailnet and the user never signs into Tailscale. **Yes, if you want the user's own tailnet** (interactive `browseToURL` login). Practical caveat: an **auth key shipped inside a distributed app binary is a public secret** — anyone can extract it and mint nodes; use a pre-approved, tagged, reusable-but-scoped key, or ephemeral keys, and control access by ACL/tag rather than by secrecy.
* Self-hosted control servers (Headscale) are supported via `controlURL` (the Dart package documents Headscale behaviour, incl. that embedded tsnet still uploads diagnostic logs to Tailscale by default unless you disable it).

## Apple App Store / policy notes

* **No Apple-policy objection to an in-process userspace node is documented by Tailscale** — and none is expected, because no `NEVPNManager`/VPN configuration is created and the personal-VPN entitlement is not needed. **That is my inference, not a Tailscale statement** (unconfirmed).
* What **is** documented is packaging pain at upload time — issue **tailscale/tailscale#20992** (2026-08-26, still open): *"libtailscale: `TailscaleKit.xcframework` is rejected at App Store upload (**ITMS-91053**) and has no macOS slice"*:
  * `ITMS-91053: Missing API declaration` — **neither iOS slice contains `PrivacyInfo.xcprivacy`**; Apple requires the manifest present in every embedded framework even when it collects nothing. *"Nothing surfaces at build time. The first signal is the rejection email."*
  * `make ios-fat` produces **no macOS slice**; hand-assembling one reintroduces **Error 90238** (privacy manifest must live in `Versions/A/Resources/` for versioned macOS bundles; stray `Info.plist` at bundle root) and **symlinks copied into iOS slices are rejected outright**.
  * Also references **#15802** (no vendor team identifier left in the signature). Fix proposed in **libtailscale PR #57** *"swift: make the built xcframework distributable to the App Store"* — **open** as of 2026-10-29.
  https://github.com/tailscale/tailscale/issues/20992 · https://github.com/tailscale/libtailscale/pull/57
* Frameworks come out **unsigned** and must be re-signed by you (README).
* The example app's `Info_iOS.plist` adds an **`NSAppTransportSecurity` exception for `ts.net`** (insecure HTTP allowed) — i.e. if you dial plain `ws://`/`http://` you'll be dealing with ATS yourself.

---

# Android

## 8. Is there an official Tailscale SDK for Android/Kotlin as of late 2026?

**No.** As of 2026-10-29:

* `tailscale/libtailscale` contains bindings for **C (the core), Swift, Python, Ruby** — **no `android/`, no `kotlin/`, no Java/JNI directory** (I enumerated the full tree via the GitHub API).
* **Maven Central has nothing**: `search.maven.org?q=tailscale` → 2 unrelated hits (`com.pulumi:tailscale`, `org.virtuslab:besom-tailscale_3`); `q=tsnet` → 0 hits.
* Tailscale's **TailscaleUp 2026 (Aug 24, 2026)** SDK push covered **Rust, Python, C, C++ and Elixir** — not Kotlin/Android/Swift. Tailscale's own framing (blog, 2026-08-31): *"watch [tailscale-rs](…). It brings this embedded Tailscale model to Rust, and opens up bindings in Python, Elixir, and C… Right now it's experimental (pre-alpha)"* — https://tailscale.com/blog/easier-building-with-tailscale
* **`tailscale-rs` is not a mobile option**: platform support is *"Linux (x86_64/ARM64), macOS (ARM64), Windows (x86_64)"* and **Android and iOS are listed under "Unsupported"** (v0.6.1; repo created 2026-04-05; BSD-3). https://github.com/tailscale/tailscale-rs · https://tailscale.com/blog/tailscale-rs-rust-tsnet-library-preview · docs.rs still says *"please do not build production software using these libraries or rely on it for data privacy until we have a chance to batten down some hatches and complete a third-party audit."* https://docs.rs/tailscale/latest

**The practical path — and it is the same path Tailscale itself uses:**

Upstream's Android client is **`github.com/tailscale/tailscale-android`** (BSD-3-Clause + a `PATENTS` file; pushed 2026-10-28). Its Makefile builds the Go engine with:

```
gomobile bind -target android -androidapi 26 -tags "…" \
  -ldflags "-linkmode=external -extldflags=-Wl,-z,max-page-size=16384 …" \
  -o android/libs/libtailscale_unstripped.aar ./libtailscale
```
producing an AAR whose payload is `jni/arm64-v8a/libgojni.so`. **Caveat: that `libtailscale` Go package is the VPN-app engine** (`ipnlocal` + `VpnService`/TUN, `vpnfacade.go`, `keystore.go`) — reusing it *does* mean `VpnService`. For an embedded-node SDK you write your **own** small Go package that imports `tailscale.com/tsnet` and gomobile-bind it.

Real existing implementations of that recipe (community; maintenance state as of 2026-10-29):

| Project | What it is | State |
|---|---|---|
| **`Matt-wzy/ssh-with-tailscale`** — "Android SSH client with Tailscale built in — connect to 100.x devices **without installing the Tailscale app or using a VPN**" | `go/tailscale/tailscale.go` = **gomobile binding of `tailscale.com/tsnet`**, userspace netstack, **SOCKS5 at `127.0.0.1:1055`**; Kotlin app uses JSch `ProxySOCKS5`; `TailscaleService` = **`specialUse` foreground service** to keep the node alive after backgrounding; also inbound via `tsnet.Listen`. Build requires **Go 1.26+ (`gomobile@latest` requires go ≥ 1.26) + NDK**. | created 2026-10-02, pushed 2026-10-11, 8 stars, **GPL-3.0** |
| **`rnk-io/kdec-bridge`** | "runs a Tailscale node inside the app, in userspace and without a VPN interface … **keeps the phone's VPN slot free**"; foreground service with embedded node; *"Low battery use. Event-driven, with no polling, wakelocks or alarms."* | pushed 2026-09-27, 0 stars |
| **`danReynolds/tailscale_dart`** → pub.dev **`tailscale` 0.9.0** (Dart/Flutter, compiles upstream tsnet via a native build hook) | *"Userspace tsnet, **no root**. Persistent nodes require **Android 12 / API 31+**; older versions can run explicitly **ephemeral** nodes."* (their own Keybay/Keystore-custody design, not an upstream constraint) | 0.9.0 published ~2026-08; MIT; pre-1.0; 1 like / 363 downloads |
| **`thetbw/libtailscale_java`** | "libtailscale java wrapper" | created+pushed 2025-11-06, 0 stars, Apache-2.0 — one-off, effectively unmaintained |
| **`gay-pizza/libtailscale`** "libtailscale for Kotlin" | Kotlin wrapper | stale (2023); **"Supports ARM64/AMD64 macOS/Linux"** only — not Android |

So: **no official Android SDK; the supported upstream primitive is `tsnet` + `gomobile bind`; and there are 2–3 working precedents you can read but none of them is a maintained library you can depend on.**

## 9. Does it avoid `VpnService` / TUN and the consent dialog?

Yes — same mechanism as iOS. tsnet is in-process userspace (gVisor netstack + wireguard-go), documented *"No root privileges required… without … any system-level configuration"*. No `VpnService`, so **no "…wants to set up a VPN connection that monitors your network traffic" dialog**, **no `BIND_VPN_SERVICE` permission**, no occupation of the single system VPN slot, and the app's other sockets are unaffected. Corroborated by two independent Android projects that exist precisely to avoid that: ssh-with-tailscale (*"no separate Tailscale app, no root, and **no VpnService permission**"*) and kdec-bridge (*"keeps the VPN slot free… runs alongside any VPN app"*).
⚠️ Only true if you use **`tsnet`** (your own gomobile package). If you bind Tailscale's `ipnlocal`/`tailscale-android/libtailscale` instead, you get `VpnService` back (`vpnfacade.go`, `VpnService.protect()`, `updateTUN`).

## 10. Can an OkHttp/Ktor WebSocket be routed through the embedded node?

There is **no official Kotlin `SocketFactory`/dialer API**. What the SDK gives you, verbatim from source (`tsnet/tsnet.go` `Server.Loopback()`):

```go
s5s := &socks5.Server{Logf: s5l, Dialer: s.dialer.UserDial,
                       Username: "tsnet", Password: s.proxyCred}   // 32-hex, random port on 127.0.0.1
```
i.e. **an authenticated SOCKS5 proxy on loopback** (plus a LocalAPI HTTP endpoint on the same listener). Your options on Android:

1. **SOCKS5 through OkHttp** — `OkHttpClient.Builder().proxy(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", port)))`. **Problem: tsnet's SOCKS5 requires username/password, and OkHttp/JDK SOCKS support does not do SOCKS5 auth.** Third-party evidence: Stack Overflow *"SOCKS5 authentication with OkHTTP fails"* (2023) → `java.net.SocketException: SOCKS : authentication failed`. https://stackoverflow.com/questions/76772846/socks5-authentication-with-okhttp-fails — **treat OkHttp-on-Android behaviour as unverified and test it first**; it is the make-or-break detail for this platform.
2. **A WS client that speaks authenticated SOCKS5** — e.g. JSch's `ProxySOCKS5` does (that's how ssh-with-tailscale tunnels SSH). Not a fit for a Kotlin WebSocket client.
3. **In-app loopback TCP forwarder** (my recommendation, and the pattern the C API is designed for): the Go/gomobile side exposes `tsnet.Dial("tcp","100.x.y.z:PORT")` (or you use `tailscale_dial`'s fd) → Kotlin accepts a `ServerSocket("127.0.0.1", 0)` and pumps bytes. Then point the app at `ws://127.0.0.1:PORT/` (or keep TLS end-to-end by using `wss://` through a pure byte pipe and overriding hostname verification / using OkHttp's `.dns()` to map a MagicDNS name to loopback). Bytes-only ⇒ `wss://` stays end-to-end encrypted to the real server.
4. **Do all WebSocket traffic on the Go side** (gomobile object refs give you `net.Conn` read/write) and hand Kotlin a stream — works but you lose OkHttp/Ktor's WS implementation.
5. Ktor: its engines accept an HTTP/SOCKS proxy (`ProxyBuilder(ProxyType.SOCKS)`), but I could not find primary documentation that Ktor's SOCKS path performs SOCKS5 *authentication* → **unconfirmed**; same forwarder fallback applies.

Note the upside of the SOCKS5/forwarder approach either way: since the SOCKS server dials with `s.dialer.UserDial`, **tailnet hostnames (MagicDNS) resolve inside the embedded node**, so you can use `wss://host.<tailnet>.ts.net` with a real Tailscale-issued certificate instead of a bare `100.x` IP — better than today's `ws://100.x.y.z`.

## 11. Minimum Android API level; backgrounding

* **Upstream reference point:** Tailscale's own Android client has `minSdk = 26` (`android/build.gradle.kts:25`), builds the Go AAR with **`-androidapi 26`**, `ndkVersion = "23.1.7779620"`, and links with `-Wl,-z,max-page-size=16384` for **16 KB page** support (their comment: *"specific to NDK 23. Your mileage may vary with other NDK versions"*). https://github.com/tailscale/tailscale-android/blob/main/Makefile · https://github.com/tailscale/tailscale-android/blob/main/android/build.gradle.kts
* **For your API 24 floor:** I found **no documented floor for `tsnet`-via-gomobile below API 26**, and no upstream statement that API 24–25 is tested. gomobile/Go's Android port is 64-bit-and-ARM capable on old APIs and `-androidapi` is a knob you set, so **API 24 is very likely fine, but unverified** — budget a spike on an API 24 device/emulator. (The `API 31+` figure you may see floating around is the **Dart package's own Keystore-custody requirement** for persisting node state, not a tsnet/Tailscale limit; on older Android they explicitly allow *ephemeral* nodes.)
* **Backgrounding works here, unlike iOS:** keep the process (and therefore the in-process node) alive with a **foreground service**. Both community apps do exactly that — ssh-with-tailscale: *"while the node runs, a `specialUse` foreground service keeps the SSH session and exported ports alive after backgrounding"*; kdec-bridge: *"The bridge is a foreground service with an embedded Tailscale node"*, *"Recovers on its own. Restarts after a reboot or an app update, and records when Android stops the service."* So: **Android lets you keep the WebSocket up while backgrounded** (with the usual `specialUse`/`remoteMessaging` FGS-type declaration, notification, and Android 14+ FGS-type requirements; doze/network-change handling is yours). Tailscale itself publishes no guidance for embedded-node backgrounding.

---

# General

## 12. Licensing for an MIT app shipping these pieces

* **`tailscale/tailscale` (the Go source incl. tsnet): BSD-3-Clause** — https://github.com/tailscale/tailscale/blob/main/LICENSE
* **`tailscale/libtailscale` (C library + TailscaleKit): BSD-3-Clause** (repo LICENSE; GitHub license field `bsd-3-clause`; README: *"BSD 3-Clause for this repository"*).
* **`tailscale/tailscale-android`: BSD-3-Clause + `PATENTS`** — a Google-style *"Additional IP Rights Grant"* with a defensive-termination clause (patent licences terminate if *you* sue alleging infringement). Worth listing in notices. https://github.com/tailscale/tailscale-android/blob/main/PATENTS
* **`tailscale/tailscale-rs`: BSD-3-Clause.**
* **Transitive third-party notices: Tailscale publishes them, per platform, generated with `go-licenses` and updated ~weekly** — pin the file at the release tag you actually build:
  * `licenses/apple.md` and `licenses/android.md` (and `licenses/tailscale.md`, `licenses/README.md`): https://github.com/tailscale/tailscale/tree/main/licenses
  * For a **tsnet/libtailscale embedding**, the set is **BSD-3-Clause / BSD-2-Clause / MIT / Apache-2.0** only — notably `gvisor.dev/gvisor/pkg` (**Apache-2.0**, that's the netstack), `github.com/tailscale/wireguard-go` (**MIT**), `golang.org/x/*` (BSD-3), `golang.org/x/mobile` (BSD-3, only for the gomobile path), `github.com/fxamacker/cbor/v2` (MIT), etc.
  * **No GPL or AGPL component appears in either generated list.** The extra items on the *Apple GUI* list that do not apply to an embedded build: Inter typeface (OFL-1.1), Sparkle (MIT), wireguard-apple (MIT), configd (APS), WebDAV-Swift, CachedAsyncImage.
  * Community wrappers you might pick up: MIT (`tailscale_dart`), Apache-2.0 (`libtailscale_java`), MIT (`gay-pizza`), **GPL-3.0** (`ssh-with-tailscale` — copyleft if you copy code from it), BSD-3 (`libtailscale-spm`, `GalaxNet-Ltd/libtailscale` forks).

## 13. Documented production caveats (and what Tailscale itself says)

* **Positioning:** userspace networking is *"primarily for serverless environments. We recommend using it with ephemeral nodes and auth keys."* — https://tailscale.com/docs/concepts/userspace-networking (validated Nov 12 2025). tsnet is production-used inside Tailscale (powers golink and support tooling) — https://tailscale.com/docs/features/tsnet.
* **`tailscale-rs` (only if you go that route):** *"There are no compatibility guarantees at the moment… Direct connections via NAT traversal is a work in progress. Communication may fall back to DERP relaying in more cases, with a corresponding hit to latency and throughput."* Plus docs.rs: *"unstable and insecure… do not build production software … until … a third-party audit."*
* **The Go/tailscale.com engine** does **not** carry an equivalent NAT-traversal caveat; but I found no published benchmark or battery/CPU figure for in-app userspace WireGuard. **Battery/CPU cost: unconfirmed** (only the anecdotal kdec-bridge claim of *"low battery use, event-driven, no polling/wakelocks/alarms"*).
* **Open libtailscale/TailscaleKit bugs that matter in production** (all open, dates above): fd-reuse leak in accepted connections (#21934), UB in `OutgoingConnection.send` (#22025 / libtailscale#70), `down()` actually calls up (#21005), `close` vs blocked `up` (#21167/#63), watchIPNBus 60 s kill (#21220/#62), App Store privacy manifest (#20992/#57), LocalAPI unusable during bring-up (#20997), `tailscale_set_logfd` closing the caller's fd (libtailscale#68), macOS slice not universal (libtailscale#59). **No tags/releases = no supported stable version.**
* **MTU:** no knob in tsnet/libtailscale; Tailscale manages MTU automatically (`TS_DEBUG_MTU` is a `tailscaled` debug flag; long-standing user reports of MTU trouble, e.g. tailscale/tailscale#8219). **Mobile/embedded MTU behaviour: unverified.**
* **Multicast/broadcast:** not part of the embedded path; the C API exposes no multicast control. (Tailscale's multicast support is a separate, opt-in client/tailnet feature — I did not verify current status for this report.) **Not applicable to a single outbound WebSocket.**
* **Not suitable for:** anything needing *system-wide* connectivity (a Tailscale client, not the SDK), inbound-serving on iOS background, or arbitrary IP protocols — the embedded node exposes **TCP/UDP** sockets (and, per third-party OpenWrt docs on userspace mode generally, *"Supports TCP, UDP, and ICMP, but not all IP protocols. Performance is typically lower than kernel TUN mode"* — **third-party, not Tailscale primary**).
* **Node lifecycle hygiene you must design for:** node identity lives in `Configuration.path` / `Server.Dir`; keys expire unless you use pre-authenticated/ephemeral keys (https://tailscale.com/docs/features/access-control/key-expiry); `logout()` semantics are not exposed in TailscaleKit (#22039); the Dart package documents an important default: **embedded tsnet uploads diagnostic logs to Tailscale by default** (logtail) — relevant for privacy/App-Kit declarations.
* **Tailscale's release-stage policy** (useful vocabulary): https://tailscale.com/docs/reference/tailscale-release-stages. There is **no release stage stated for TailscaleKit** — it is undocumented, unversioned software.

## 14. macOS / "Cordis"

* **I could not find any Tailscale product, or any Tailscale-team project, called "Cordis".** Searches of the web and of GitHub (`cordis tailscale`, `cordis` + network) return only unrelated things: EU CORDIS research-funding data analysis repos, and **DeepSeek-Harness ecosystem plugins that happen to be named Cordis** (e.g. `enginefuture/cordis-mobile-bridge` — *"Tauri 2 + Rust companion bridge for secure Android social-app control over Tailscale"*, `luohaiJack/dsh-phone` — *"Cordis 桥接插件 + 原生 Android 客户端"*). **Treat the name as unconfirmed/misremembered**, and if you tell me what the macOS binary/app is actually called I'll chase the mechanism directly.
* What *does* exist for "per-app Tailscale on macOS", with mechanisms:
  1. **TailscaleKit on macOS** — same in-process userspace node as iOS; `make macos` builds `TailscaleKit.framework`. **Floor: macOS 15** (`MACOS_TARGET ?= 15.0` in the root Makefile, with the comment *"This should match the minimum target in the xCode project. The wrapper lib currently requires features available in MacOS 15.0 (Sequoia)"*); #21036 measured macOS 15.0 as-is → **14.0** with the listener gating. Note also **libtailscale#59 (open)**: no universal (arm64+x86_64) macOS slice.
  2. **Per-app routing through a userspace `tailscaled`** — documented pattern: `tailscaled --tun=userspace-networking --socks5-server=localhost:1055` and then point **one** app at it (`ALL_PROXY=socks5://localhost:1055/ someapp`). That is "per-app Tailscale" on macOS without a system VPN interface. https://tailscale.com/docs/concepts/userspace-networking
  3. **macOS variants** (what Tailscale's own client does — Network Extension vs System Extension vs open-source `tailscaled` on kernel `utun`): https://tailscale.com/docs/concepts/macos-variants (validated Jan 5 2026).
  4. Community/experimental per-app routing on macOS: `UmutKDev/ayris` — *"Native macOS per-app routing alongside Tailscale exit nodes. Experimental SwiftUI / Liquid Glass source beta"* (2026-10-13, 0 stars); `anydoor7/tslink` — per-app ts.net addresses on your computer. Both are early/unproven.
  5. If the pattern you use on macOS is "each *app* has its own tailnet identity", the concept is exactly what Tailscale markets for tsnet: *"`tsnet` gives an identity to each **application**, separate from hosts"* — https://tailscale.com/blog/easier-building-with-tailscale (2026-08-31).

---

# Facts I could NOT confirm

1. **iOS 16.1 support** by any official configuration — TailscaleKit has never shipped a deployment target below 18.1; no doc/issue claims iOS 16 works. (My fork-and-lower recipe is inference from measured facts in #21036 + my own successful link test at `-mios-version-min=16.1`.)
2. Whether **`URLSessionConfiguration.proxyConfigurations` applies to `URLSessionWebSocketTask`** — no primary statement; must be spiked.
3. **Real device behaviour** of TailscaleKit (I could only build/link, not run an iPhone). Whether `Listener`/`accept` is usable at all on iOS in practice is untested by me; the `tailscale_listen` path is designed to hand fds back to C, which is compatible with iOS, but I saw no explicit confirmation.
4. **Memory (RSS) cost** of an embedded tsnet node on iOS/Android — no published figure.
5. **Battery/CPU cost** of userspace WireGuard in a mobile app — no primary numbers.
6. **Android API floor** for a tsnet+gomobile AAR at **API 24–25** — upstream is `minSdk 26` / `-androidapi 26`; no documented support statement either way. Also **16 KB-page/NDK** interactions flagged as "mileage may vary" by Tailscale themselves.
7. Whether **OkHttp on Android's libcore** can do any SOCKS5 authentication at all (JDK-side failure is documented by third parties; Android's stack differs). Likewise **Ktor CIO/OkHttp SOCKS5 auth**.
8. The upstream claim that **Tailscale's ACME/cert endpoint is disabled on iOS/Android builds** (asserted by `pub.dev/packages/tailscale`; I did not find the gating in the Go source).
9. **Whether Apple's App Review has any policy issue with an in-app userspace node** — I found only packaging (ITMS-91053) issues, no policy statement from Tailscale either way.
10. **Any official Tailscale docs page or stability/release-stage statement for TailscaleKit or libtailscale** — none exists; the repo publishes **no tags or releases**.
11. Whether **`tailscale-rs`'s C bindings will ever cover Android/iOS** ("Coming Soon" list contains DNS, HTTP integrations, peer lookups, port mapping — mobile platforms are under *Unsupported*).
12. **"Cordis"** — no such Tailscale macOS product found.
13. Current **multicast** support status for embedded nodes (not exposed in the C API; Tailscale's multicast feature status not re-verified here).

---

# Recommended next steps

**iOS:** raise the deployment target to **18.1** if at all possible — that converts this from a fork to a supported-ish path (still unversioned!). Otherwise plan a **maintained fork** of `libtailscale/swift` with a lowered target and the URLSession bridge replaced by a ~150-line SOCKS5 client. Spike first: (a) `URLSessionWebSocketTask` over `proxyVia(_:)` on a real device; (b) foreground→background→foreground reconnect logic; (c) `PrivacyInfo.xcprivacy` + signing for upload (PR #57 workaround).
**Android:** write your own `internal/tsnetgo` Go package (`tsnet.Server` + `Start/Up/Dial/Listen/Status`) and `gomobile bind -target=android/arm64 -androidapi 24` into an AAR; put the node behind a `specialUse` foreground service; **verify OkHttp SOCKS5 auth first** and fall back to an in-app loopback forwarder (option 3 above). Keep the auth key server-provisioned/tagged, and treat it as a public client credential.
**Both:** use MagicDNS/`ts.net` names instead of raw `100.x` IPs so `wss://` gets a real certificate; pin libtailscale to a commit and track libtailscale#57/#60/#69/#70 before shipping.
