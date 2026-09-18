#!/usr/bin/env python3
"""Fail when the Java and Kotlin protocol constants drift apart.

The wire values are intentionally frozen. This checker compares the authoritative
camera-side Java constants with the phone-side Kotlin mirror; it does not rewrite
either source file.
"""

from __future__ import annotations

import re
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
JAVA = ROOT / "app" / "app" / "src" / "main" / "java" / "com" / "bi2qfa" / "sonyconnect" / "PtpCodec.java"
KOTLIN = (
    Path.home()
    / "AndroidStudioProjects"
    / "SonyConnect"
    / "app"
    / "src"
    / "main"
    / "java"
    / "com"
    / "bi2qfa"
    / "sonyconnect"
    / "ptpip"
    / "PtpCodec.kt"
)

PREFIXES = ("T_", "OP_", "EV_", "RC_", "FAIL_", "KIND_", "DP_")
SCALARS = ("CHUNK", "MAX_PACKET", "PROTO_VERSION")
JAVA_RE = re.compile(r"public\s+static\s+final\s+int\s+(\w+)\s*=\s*([^;]+);")
KOTLIN_RE = re.compile(r"const\s+val\s+(\w+)\s*=\s*([^\n]+)")


def parse_java(path: Path) -> dict[str, str]:
    values: dict[str, str] = {}
    for name, expression in JAVA_RE.findall(path.read_text(encoding="utf-8")):
        if name in SCALARS or name.startswith(PREFIXES):
            values[name] = normalize(expression)
    return values


def parse_kotlin(path: Path) -> dict[str, str]:
    values: dict[str, str] = {}
    for name, expression in KOTLIN_RE.findall(path.read_text(encoding="utf-8")):
        if name in SCALARS or name.startswith(PREFIXES):
            values[name] = normalize(expression)
    return values


def normalize(expression: str) -> str:
    value = expression.strip()
    value = re.sub(r"//.*$", "", value).strip()
    value = value.replace("_", "")
    return re.sub(r"\s+", " ", value)


def main() -> int:
    if not JAVA.is_file() or not KOTLIN.is_file():
        print(f"missing codec file: {JAVA if not JAVA.is_file() else KOTLIN}", file=sys.stderr)
        return 2
    java = parse_java(JAVA)
    kotlin = parse_kotlin(KOTLIN)
    errors: list[str] = []

    for name in sorted(set(java) | set(kotlin)):
        if name not in java:
            errors.append(f"{name}: missing in Java")
        elif name not in kotlin:
            errors.append(f"{name}: missing in Kotlin")
        elif java[name] != kotlin[name]:
            errors.append(f"{name}: Java={java[name]} Kotlin={kotlin[name]}")

    if errors:
        print("protocol constants differ:", file=sys.stderr)
        for error in errors:
            print(f"  - {error}", file=sys.stderr)
        return 1
    print(f"protocol constants OK: {len(java)} entries")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
