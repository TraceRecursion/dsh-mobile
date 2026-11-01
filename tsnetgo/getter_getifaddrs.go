// 变体 B：上游未合并的 PR tailscale/tailscale#19455
// （net/netmon/androidbind：用 cgo getifaddrs 提供 InterfaceGetter）。
// 不复制它的代码，而是用 go.mod 的 replace 指到该 PR 的分支来验证；
// 上游合并后删掉 replace 与构建标签即可。
//
// 启用方式：gomobile bind -tags tsnet_getter_getifaddrs

//go:build android && tsnet_getter_getifaddrs

package tsnetgo

import _ "tsnetgo/androidbind"
