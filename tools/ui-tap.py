#!/usr/bin/env python3
"""按文字定位并点击（uiautomator）。属性顺序无关，bounds 单独解析。

用法：
  tools/ui-tap.py <serial> find "设置"            # 打印命中节点的 bounds/文本
  tools/ui-tap.py <serial> tap "设置"             # 点击第一个命中节点的中心
  tools/ui-tap.py <serial> tap "隧道" --nth 2     # 第 n 个命中
  tools/ui-tap.py <serial> type "登录密钥" "文本"  # 点中字段后输入文本
为什么自己写：现成驱动假设 text 出现在 bounds 之前，真机上（HyperOS）不成立，
会静默找不到控件——本项目里踩过。
"""
import re
import subprocess
import sys

def adb(serial, *args, binary=False):
    return subprocess.run(["adb", "-s", serial, *args], capture_output=True,
                          text=not binary, encoding=None if binary else "utf-8",
                          errors="replace")

def nodes(serial):
    adb(serial, "shell", "uiautomator", "dump", "/sdcard/uitap.xml")
    xml = adb(serial, "exec-out", "cat", "/sdcard/uitap.xml").stdout
    out = []
    for tag in re.findall(r"<node[^>]*>", xml):
        def attr(name):
            m = re.search(r'%s="([^"]*)"' % name, tag)
            return m.group(1) if m else ""
        bounds = attr("bounds")
        box = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", bounds)
        if not box:
            continue
        x1, y1, x2, y2 = map(int, box.groups())
        out.append({
            "text": attr("text"), "desc": attr("content-desc"),
            "resource": attr("resource-id"), "clickable": attr("clickable"),
            "cx": (x1 + x2) // 2, "cy": (y1 + y2) // 2, "bounds": bounds,
        })
    return out

def find(serial, needle):
    return [n for n in nodes(serial) if needle in (n["text"] or n["desc"] or n["resource"])]

def main():
    if len(sys.argv) < 4:
        print(__doc__)
        return 2
    serial, action, needle = sys.argv[1:4]
    nth = 0
    if "--nth" in sys.argv:
        nth = int(sys.argv[sys.argv.index("--nth") + 1])
    hits = find(serial, needle)
    if action == "find":
        for i, n in enumerate(hits):
            print(f"[{i}] {n['bounds']} clickable={n['clickable']} "
                  f"text={n['text']!r} desc={n['desc']!r} id={n['resource']!r}")
        if not hits:
            print("(没有命中)")
        return 0 if hits else 1
    if not hits:
        print(f"(找不到 {needle!r})")
        return 1
    target = hits[min(nth, len(hits) - 1)]
    adb(serial, "shell", "input", "tap", str(target["cx"]), str(target["cy"]))
    print(f"点击 {needle!r} 命中 {target['bounds']} (共 {len(hits)} 处)")
    if action == "type" and len(sys.argv) > 4:
        text = sys.argv[4]
        adb(serial, "shell", "input", "text", text)
        print(f"已在该控件输入 {len(text)} 个字符")
    return 0

if __name__ == "__main__":
    sys.exit(main())
