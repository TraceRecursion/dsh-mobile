// 变体 A：合成单接口 fallback。
//
// 逐字取自 tailscale.com v1.104.1 的 feature/androidbin（androidbin.go 的
// fallbackInterfaces / buildSynthetic / outboundIP / onAndroid），只是上游那份
// register.go 的构建门是 `(linux && !android) || (android && !cgo)`——
// gomobile 的 android+cgo 恰好在门外，因为它假定 Android 侧自带 Java getter。
// 第三方嵌入者没有 Java 桥，所以这里自己注册一次。
//
// 启用方式：gomobile bind -tags tsnet_getter_fallback
// 已知局限：只报告一个合成接口，magicsock 看不到多网卡/双网络的实际拓扑。
//
// Copyright (c) Tailscale Inc & contributors
// SPDX-License-Identifier: BSD-3-Clause

//go:build android && tsnet_getter_fallback

package tsnetgo

import (
	"errors"
	"net"
	"net/netip"
	"os"
	"runtime"
	"time"

	"tailscale.com/net/netmon"
)

func init() {
	netmon.HookInterfacesFallback.Set(fallbackInterfaces)
}

func fallbackInterfaces() ([]netmon.Interface, error) {
	if !runningOnAndroid() {
		return nil, errors.New("tsnetgo: not running on Android")
	}
	return buildSynthetic()
}

func buildSynthetic() ([]netmon.Interface, error) {
	var addrs []net.Addr
	// 这两个 dial 不会发出任何包：拨 UDP 套接字只是让内核选一条路由和源地址。
	if ip, ok := outboundIP("udp4", "8.8.8.8:53"); ok {
		addrs = append(addrs, &net.IPNet{IP: ip.AsSlice(), Mask: net.CIDRMask(32, 32)})
	}
	if ip, ok := outboundIP("udp6", "[2001:4860:4860::8888]:53"); ok {
		addrs = append(addrs, &net.IPNet{IP: ip.AsSlice(), Mask: net.CIDRMask(128, 128)})
	}
	if len(addrs) == 0 {
		return nil, errors.New("tsnetgo: no outbound routes found")
	}
	return []netmon.Interface{{
		Interface: &net.Interface{
			MTU:   1500,
			Name:  "android",
			Flags: net.FlagUp | net.FlagRunning,
		},
		AltAddrs: addrs,
	}}, nil
}

func outboundIP(network, addr string) (netip.Addr, bool) {
	d := net.Dialer{Timeout: 2 * time.Second}
	c, err := d.Dial(network, addr)
	if err != nil {
		return netip.Addr{}, false
	}
	defer c.Close()
	ua, ok := c.LocalAddr().(*net.UDPAddr)
	if !ok {
		return netip.Addr{}, false
	}
	ip, ok := netip.AddrFromSlice(ua.IP)
	if !ok {
		return netip.Addr{}, false
	}
	ip = ip.Unmap()
	if ip.IsLoopback() || ip.IsUnspecified() {
		return netip.Addr{}, false
	}
	return ip, true
}

func runningOnAndroid() bool {
	if runtime.GOOS == "android" {
		return true
	}
	_, err := os.Stat("/dev/__properties__")
	return err == nil
}
