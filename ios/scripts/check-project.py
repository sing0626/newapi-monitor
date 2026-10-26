#!/usr/bin/env python3
"""Portable resource checks; these do not replace Xcode compilation or XCTest."""
import json
import plistlib
import re
import struct
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
APP = ROOT / "NewAPIMonitor"


def check():
    count = 0
    for path in APP.rglob("*.json"):
        data = json.loads(path.read_text(encoding="utf-8"))
        if path.name == "Contents.json":
            for image in data.get("images", []):
                if "filename" in image:
                    assert (path.parent / image["filename"]).is_file(), f"Missing asset: {path}"
        count += 1
    info = plistlib.loads((APP / "Resources/Info.plist").read_bytes())
    assert not info.get("NSAppTransportSecurity", {}).get("NSAllowsArbitraryLoads")
    assert not info.get("UIBackgroundModes"), "Background monitoring is not implemented in v0.1"
    entitlements = plistlib.loads((APP / "Resources/NewAPIMonitor.entitlements").read_bytes())
    assert not entitlements, "First release must not require paid account entitlements"
    icon_path = APP / "Resources/Assets.xcassets/AppIcon.appiconset"
    icons = json.loads((icon_path / "Contents.json").read_text())["images"]
    assert icons and all(i.get("filename") for i in icons), "Missing app icon filename"
    for icon in icons:
        png = (icon_path / icon["filename"]).read_bytes()
        assert png[:8] == b"\x89PNG\r\n\x1a\n", "App icon must be PNG"
        width, height, depth, color = struct.unpack(">IIBB", png[16:26])
        assert (width, height) == (1024, 1024)
        assert color == 2 and depth == 8, "App icon must be opaque 8-bit RGB"
    sources = list(APP.rglob("*.swift"))
    assert len(sources) >= 10
    assert sum(bool(re.search(r"@main\s", p.read_text(encoding="utf-8"))) for p in sources) == 1
    assert list((ROOT / "NewAPIMonitorTests").glob("*Tests.swift")), "Missing unit tests"
    project = (ROOT / "project.yml").read_text(encoding="utf-8")
    assert 'SWIFT_VERSION: "5.0"' in project
    assert 'MARKETING_VERSION: "0.1.0"' in project
    print(f"PASS: {count} JSON resources, plists, entitlements, icon, app entry, {len(sources)} Swift source paths.")
    print("NOT VERIFIED: Swift types, linking, XCTest, simulator UI, device signing or installation.")


if __name__ == "__main__":
    check()
