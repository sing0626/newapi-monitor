#!/usr/bin/env python3
"""Check device IPA layout without signing, installing, or executing its code."""
import plistlib
import sys
import zipfile
from pathlib import PurePosixPath


def validate(path):
    with zipfile.ZipFile(path) as archive:
        names = archive.namelist()
        if len(names) != len(set(names)):
            raise ValueError("Duplicate ZIP entries")
        for name in names:
            p = PurePosixPath(name)
            if p.is_absolute() or ".." in p.parts:
                raise ValueError("Unsafe ZIP entry")
        plists = [n for n in names if n.startswith("Payload/")
                  and n.endswith(".app/Info.plist") and n.count("/") == 2]
        if len(plists) != 1:
            raise ValueError("Expected exactly one Payload/App.app/Info.plist")
        info = plistlib.loads(archive.read(plists[0]))
        if info.get("CFBundlePackageType") != "APPL":
            raise ValueError("Not an application bundle")
        if "iPhoneOS" not in info.get("CFBundleSupportedPlatforms", []):
            raise ValueError("Expected a device build, not a simulator build")
        executable = info.get("CFBundleExecutable", "")
        if not executable or "/" in executable or "\\" in executable:
            raise ValueError("Invalid executable name")
        binary = str(PurePosixPath(plists[0]).parent / executable)
        if binary not in names or archive.getinfo(binary).file_size == 0:
            raise ValueError("Missing app executable")
        magic = archive.open(binary).read(4)
        if magic not in (b"\xcf\xfa\xed\xfe", b"\xfe\xed\xfa\xcf", b"\xca\xfe\xba\xbe", b"\xca\xfe\xba\xbf"):
            raise ValueError("Executable is not Mach-O")
        print(f"IPA layout valid: {info['CFBundleIdentifier']} {info['CFBundleShortVersionString']}")
        print("Layout validation does not establish code-signature validity or installability.")


if __name__ == "__main__":
    if len(sys.argv) != 2:
        raise SystemExit("Usage: validate-ipa.py <unsigned.ipa>")
    validate(sys.argv[1])
