#!/usr/bin/env python3
"""Build a locally signed Kotlin APK with the installed Android SDK, JDK and kotlinc."""

import argparse
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
from tempfile import TemporaryDirectory
import xml.etree.ElementTree as ET
from zipfile import ZipFile


PROJECT = Path(__file__).resolve().parent
ROOT = PROJECT.parent
MANIFEST = PROJECT / "AndroidManifest.xml"
SOURCE_PACKAGE = "com.nueex.psmtp"
DEFAULT_FIREBASE_DATABASE = ""


def firebase_update_url(config, override):
    base = override
    if config is not None:
        settings = json.loads(config.read_text(encoding="utf-8-sig"))
        app_id = ET.parse(MANIFEST).getroot().attrib["package"]
        clients = settings.get("client", [])
        if not any(client.get("client_info", {}).get("android_client_info", {}).get("package_name") == app_id
                   for client in clients):
            raise ValueError(f"{config} has no Android client for {app_id}")
        if base is None:
            base = settings["project_info"].get("firebase_url")
            if not base:
                raise ValueError("Create Realtime Database and download google-services.json again, or pass --firebase-database-url")
    if base is None:
        base = DEFAULT_FIREBASE_DATABASE
    base = base.strip().rstrip("/")
    if not base:
        return ""
    if not re.fullmatch(r"https://[A-Za-z0-9-]+(?:\.[A-Za-z0-9-]+)*\.(?:firebaseio\.com|firebasedatabase\.app)", base):
        raise ValueError("Use the HTTPS Realtime Database base URL from Firebase Console")
    return base + "/updates/android/stable.json"


def run(*command):
    subprocess.run([str(part) for part in command], check=True)


def pairing_source_roots():
    if (PROJECT / "native/lan_spake.c").is_file() and (PROJECT / "third_party/bearssl/LICENSE.txt").is_file():
        return PROJECT / "native/include", PROJECT / "native", PROJECT / "third_party/bearssl"
    return ROOT / "include", ROOT / "src", ROOT / "third_party/bearssl"


def pairing_libraries(sdk, work):
    ndks = sorted((sdk / "ndk").glob("28.*"))
    if not ndks:
        raise SystemExit("Install Android NDK r28 to build the wireless pairing library")
    toolchains = ndks[-1] / "toolchains" / "llvm" / "prebuilt"
    host = next(path for path in toolchains.iterdir() if path.is_dir())
    compiler = host / "bin" / ("clang.exe" if os.name == "nt" else "clang")
    include_dir, core_src, bearssl = pairing_source_roots()
    groups = {"arm64-v8a": "aarch64-linux-android29", "armeabi-v7a": "armv7a-linux-androideabi29",
              "x86_64": "x86_64-linux-android29", "x86": "i686-linux-android29"}
    for abi, target in groups.items():
        output = work / abi / "libpsmtp_pair.so"
        output.parent.mkdir()
        run(compiler, f"--target={target}", "-std=c11", "-O2", "-fPIC", "-shared", "-Wl,--no-undefined",
            "-fvisibility=hidden", "-I", include_dir, "-I", bearssl / "inc", "-I", bearssl / "src",
            PROJECT / "native/pairing_jni.c", core_src / "lan_spake.c", core_src / "sha256.c",
            bearssl / "src/ec/ec_p256_m31.c", bearssl / "src/codec/ccopy.c", "-o", output)
        yield abi, output


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--sdk", type=Path, default=os.getenv("ANDROID_HOME") or os.getenv("ANDROID_SDK_ROOT"))
    parser.add_argument("--kotlinc", type=Path, default=os.getenv("KOTLINC"))
    parser.add_argument("--d8", type=Path, default=os.getenv("PSMTP_D8_JAR"), help="Path to a compatible R8/D8 JAR")
    parser.add_argument("--output", type=Path, default=PROJECT / "build")
    parser.add_argument("--debug", action="store_true", help="Enable diagnostic Logcat events and adb run-as")
    parser.add_argument("--firebase-config", type=Path, help="Path to the Firebase google-services.json registration")
    parser.add_argument("--firebase-database-url", help="Override the HTTPS Realtime Database base URL")
    parser.add_argument("--version-code", type=int, help="Override Android versionCode")
    parser.add_argument("--version-name", help="Override Android versionName")
    parser.add_argument("--keystore", type=Path, help="Sign using an existing release key (JETBIRD_KS_PASS)")
    parser.add_argument("--key-alias", help="Key alias for --keystore")
    args = parser.parse_args()
    try:
        bundled_config = PROJECT / "google-services.json"
        local_config = bundled_config if bundled_config.is_file() and args.firebase_database_url is None else None
        endpoint = firebase_update_url(args.firebase_config or local_config, args.firebase_database_url)
    except (ValueError, KeyError, OSError) as error:
        parser.error(str(error))
    if args.version_code is not None and not 1 <= args.version_code <= 2_100_000_000:
        parser.error("--version-code must be between 1 and 2100000000")
    if args.keystore and not (args.keystore.is_file() and args.key_alias and os.getenv("JETBIRD_KS_PASS")):
        parser.error("--keystore requires an existing file, --key-alias and JETBIRD_KS_PASS")

    windows = os.name == "nt"
    if args.kotlinc is None:
        compiler = shutil.which("kotlinc")
        if not compiler and windows:
            compiler = Path("C:/Kotlin Setup/kotlinc/bin/kotlinc.bat")
        if not compiler or not Path(compiler).is_file():
            parser.error("Put kotlinc on PATH or pass --kotlinc")
        args.kotlinc = Path(compiler)
    if not args.sdk:
        parser.error("Set ANDROID_HOME or pass --sdk")

    platform = args.sdk / "platforms" / "android-36" / "android.jar"
    build_tools = args.sdk / "build-tools" / "36.0.0"
    if not platform.is_file() or not build_tools.is_dir():
        parser.error("Install Android SDK Platform 36 and Build Tools 36.0.0")
    if args.d8 is None:
        local_d8 = next((p for p in (PROJECT / ".tools/android/r8-9.1.29.jar", ROOT / ".tools/android/r8-9.1.29.jar") if p.is_file()), None)
        args.d8 = local_d8 if local_d8 else build_tools / "lib" / "d8.jar"
    if not args.d8.is_file():
        parser.error("The D8 JAR could not be found")

    suffix = ".exe" if windows else ""
    aapt = build_tools / ("aapt2" + suffix)
    align = build_tools / ("zipalign" + suffix)
    signer = build_tools / "lib" / "apksigner.jar"
    args.output = args.output.resolve()
    args.output.mkdir(parents=True, exist_ok=True)
    key = args.keystore or args.output / "local-signing.keystore"
    signing = ["--ks-key-alias", "androiddebugkey", "--ks-pass", "pass:android"]
    if args.keystore:
        signing = ["--ks-key-alias", args.key_alias, "--ks-pass", "env:JETBIRD_KS_PASS"]
        if os.getenv("JETBIRD_KEY_PASS"):
            signing += ["--key-pass", "env:JETBIRD_KEY_PASS"]
    elif not key.exists():
        run("keytool", "-genkeypair", "-keystore", key, "-storepass", "android", "-keypass", "android",
            "-alias", "androiddebugkey", "-keyalg", "RSA", "-keysize", "2048", "-validity", "3650",
            "-dname", "CN=PS MTP Explorer Local Build")

    with TemporaryDirectory(prefix="assemble-", dir=args.output) as work_path:
        work = Path(work_path)
        classes = work / "app.jar"
        dex = work / "dex"
        dex.mkdir()
        sources = sorted((PROJECT / "src").rglob("*.kt"))
        config = work / "FirebaseReleaseConfig.kt"
        config.write_text(
            f'package {SOURCE_PACKAGE}\ninternal const val FIREBASE_UPDATE_URL = "{endpoint}"\n'
            f'internal const val DIAGNOSTIC_LOGGING = {str(args.debug).lower()}\n',
            encoding="utf-8",
        )
        zxing = PROJECT / "libs" / "zxing-core-3.5.4.jar"
        kotlin_args = work / "kotlinc.args"
        kotlin_args.write_text(
            "\n".join(
                f'"{str(item).replace(chr(92), "/").replace(chr(34), chr(92) + chr(34))}"'
                for item in (
                    "-jvm-target", "1.8", "-no-reflect",
                    "-classpath", os.pathsep.join(map(str, (platform, zxing))),
                    "-include-runtime", "-d", classes, *sources, config,
                )
            ) + "\n",
            encoding="utf-8",
        )
        run(args.kotlinc, f"@{kotlin_args}")
        d8_mode = "--debug" if args.debug else "--release"
        run("java", "-cp", args.d8, "com.android.tools.r8.D8", d8_mode, "--lib", platform,
            "--min-api", "29", "--output", dex, classes, zxing)
        resources = work / "resources.zip"
        run(aapt, "compile", "--dir", PROJECT / "res", "-o", resources)
        unsigned = work / "unsigned.apk"
        debug_flag = ["--debug-mode"] if args.debug else []
        versions = []
        if args.version_code is not None:
            versions += ["--version-code", args.version_code]
        if args.version_name is not None:
            versions += ["--version-name", args.version_name]
        if versions:
            versions.append("--replace-version")
        run(aapt, "link", "-I", platform, "--manifest", MANIFEST,
            "-A", PROJECT / "assets", *debug_flag, *versions, "-o", unsigned, resources)
        with ZipFile(unsigned, "a") as apk_zip:
            for file in sorted(dex.glob("classes*.dex")):
                apk_zip.write(file, file.name)
            for abi, binary in pairing_libraries(args.sdk, work):
                apk_zip.write(binary, f"lib/{abi}/{binary.name}")
            _, _, bearssl = pairing_source_roots()
            apk_zip.write(bearssl / "LICENSE.txt", "assets/licenses/bearssl/LICENSE.txt")
            kotlin_licenses = args.kotlinc.resolve().parent.parent / "license"
            for filename in ("LICENSE.txt", "NOTICE.txt"):
                license_file = kotlin_licenses / filename
                if license_file.is_file():
                    apk_zip.write(license_file, "assets/licenses/kotlin/" + filename)
        aligned = work / "aligned.apk"
        run(align, "-P", "16", "-f", "4", unsigned, aligned)
        apk = args.output / ("JetBird-debug.apk" if args.debug else "JetBird.apk")
        run("java", "-jar", signer, "sign", "--ks", key, *signing, "--out", apk, aligned)
        run("java", "-jar", signer, "verify", "--verbose", apk)
        print(f"Built {apk}")


if __name__ == "__main__":
    main()
