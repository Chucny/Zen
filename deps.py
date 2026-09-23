#!/usr/bin/env python3
"""zen-setup.py — download and install everything Zen needs to build APKs.

Handles: Windows, Linux, Raspberry Pi, macOS.

What it installs:
  - pip dependencies listed in requirements.txt (Zen itself has none)
  - JDK 17  (from Adoptium, for the current OS/CPU)
  - Gradle 8.9
  - Android SDK platform-tools + platforms;android-35 + build-tools;34.0.0

Everything lands in a toolchain folder that zen.py finds automatically.

  py zen-setup.py               (Windows)
  python3 zen-setup.py          (Linux / Raspberry Pi / macOS)

Flags:
  --steps pip,java,gradle,sdk   what to do (default: all)
  --dest PATH                   toolchain folder (default: ../toolchain)
  --requirements FILE           pip requirements file (default: next to this script)
  --force                       re-download and overwrite existing installs
  --no-verify                   skip the final version checks
"""

import argparse
import os
import platform
import shutil
import subprocess
import sys
import tempfile
import urllib.request
import zipfile

GRADLE_VERSION = "8.9"
SDK_PACKAGES = ["platform-tools", "platforms;android-35", "build-tools;34.0.0"]

ADOPTIUM_URL = ("https://api.adoptium.net/v3/binary/latest/17/ga/"
                "{osname}/{arch}/jdk/hotspot/normal/eclipse?project=jdk")
GRADLE_URL = "https://services.gradle.org/distributions/gradle-{v}-bin.zip"
CMD_TOOLS = {
    "windows": "commandlinetools-win-11076708_latest.zip",
    "darwin": "commandlinetools-mac-11076708_latest.zip",
    "linux": "commandlinetools-linux-11076708_latest.zip",
}
ANDROID_LICENSE_HASHES = [
    "8933bad161af4178b1185d1a37fbf41ea5269c55",
    "d56f5187479451eabf01fb78af6dfcb131a6481e",
    "24333f8a63b6825ea9c5514f83c2829b004d1fee",
    "84831b9409646a918e30573bab4c9c91346d8abd",
]
DL_ANDROID = "https://dl.google.com/android/repository/"


def log(msg):
    print("[zen-setup] " + msg)


def err(msg):
    print("[zen-setup] ERROR: " + msg)


def detect():
    system = platform.system().lower()
    machine = platform.machine().lower()
    if machine in ("aarch64", "arm64"):
        arch = "aarch64"
    elif "arm" in machine:
        arch = "arm"
    else:
        arch = "x64"
    osname = "windows" if system == "windows" else ("mac" if system == "darwin" else "linux")
    rpi = False
    board = ""
    try:
        with open("/proc/device-tree/model", "rb") as f:
            board = f.read().decode(errors="replace").strip("\x00")
        rpi = "raspberry" in board.lower()
    except Exception:
        pass
    return {"system": system, "osname": osname, "arch": arch, "rpi": rpi, "board": board}


def java_exe_name():
    return "java.exe" if os.name == "nt" else "java"


def gradle_exe_name():
    return "gradle.bat" if os.name == "nt" else "gradle"


def gradle_dir(bin_path):
    return os.path.dirname(os.path.dirname(bin_path))


def run(cmd, env=None):
    log("running: " + " ".join(str(c) for c in cmd))
    return subprocess.call([str(c) for c in cmd], env=env)


def run_java(java_home, cmd):
    env = dict(os.environ)
    env["JAVA_HOME"] = java_home
    env["PATH"] = os.path.join(java_home, "bin") + os.pathsep + env.get("PATH", "")
    return run(cmd, env=env)


def download(url, dest, label):
    log("downloading " + label + " ...")
    tmp = dest + ".part"
    try:
        with urllib.request.urlopen(url, timeout=60) as src:
            total = int(src.headers.get("Content-Length") or 0)
            got = 0
            with open(tmp, "wb") as out:
                while True:
                    chunk = src.read(1 << 16)
                    if not chunk:
                        break
                    out.write(chunk)
                    got += len(chunk)
                    if total:
                        sys.stdout.write("\r  {:.1f} MB / {:.1f} MB ({:d}%)".format(
                            got / 1048576, total / 1048576, got * 100 // total))
                        sys.stdout.flush()
        sys.stdout.write("\n")
        os.replace(tmp, dest)
    finally:
        if os.path.exists(tmp):
            os.remove(tmp)
    log("saved " + dest + " (" + str(os.path.getsize(dest) // 1048576) + " MB)")


def unzip_existing(zip_path, dest):
    log("extracting " + os.path.basename(zip_path) + " ...")
    with zipfile.ZipFile(zip_path) as z:
        z.extractall(dest)


def first_dir_with_bin(td):
    for d in sorted(os.listdir(td)):
        p = os.path.join(td, d)
        if os.path.isdir(os.path.join(p, "bin")):
            return p
    return td


def cmd_pip(requirements):
    log("step: pip dependencies")
    if not os.path.isfile(requirements):
        log("no requirements file at '%s' — nothing to install" % requirements)
        return
    run([sys.executable, "-m", "ensurepip", "--upgrade"])
    if run([sys.executable, "-m", "pip", "--version"]) != 0:
        err("pip is unavailable; install it first (e.g. `sudo apt install python3-pip`)")
        return
    run([sys.executable, "-m", "pip", "install", "--user", "-r", requirements])


def cmd_java(tc, force):
    log("step: JDK 17")
    if os.path.isdir(tc):
        hits = [d for d in os.listdir(tc) if d.startswith("jdk-")]
        if hits and not force:
            home = os.path.join(tc, sorted(hits)[0])
            log("JDK already present: " + home)
            return home
    system_jdk = find_java_17()
    if system_jdk and not force:
        log("reusing JDK 17 from the system: " + system_jdk)
        return system_jdk
    os.makedirs(tc, exist_ok=True)
    info = detect()
    url = ADOPTIUM_URL.format(osname=info["osname"], arch=info["arch"])
    with tempfile.TemporaryDirectory(prefix="zenjdk_") as td:
        f = os.path.join(td, "jdk.zip")
        download(url, f, "JDK 17")
        unzip_existing(f, td)
        src = first_dir_with_bin(td)
        target = os.path.join(tc, os.path.basename(src.rstrip(os.sep)))
        if os.path.isdir(target):
            shutil.rmtree(target)
        shutil.move(src, target)
        return target


def find_java_17():
    try:
        out = subprocess.check_output([java_exe_name(), "-version"],
                                      stderr=subprocess.STDOUT, text=True, timeout=30)
        if "17" in out:
            home = os.environ.get("JAVA_HOME", "")
            if home and os.path.isfile(os.path.join(home, "bin", java_exe_name())):
                return home
    except Exception:
        pass
    return None


def cmd_gradle(tc, force):
    log("step: Gradle " + GRADLE_VERSION)
    script = os.path.join(tc, "gradle-" + GRADLE_VERSION, "bin", gradle_exe_name())
    if os.path.isfile(script) and not force:
        log("Gradle already present: " + gradle_dir(script))
        return gradle_dir(script)
    os.makedirs(tc, exist_ok=True)
    url = GRADLE_URL.format(v=GRADLE_VERSION)
    with tempfile.TemporaryDirectory(prefix="zengradle_") as td:
        f = os.path.join(td, "gradle.zip")
        download(url, f, "Gradle " + GRADLE_VERSION)
        unzip_existing(f, td)
        src = first_dir_with_bin(td)
        target = os.path.join(tc, os.path.basename(src.rstrip(os.sep)))
        if os.path.isdir(target):
            shutil.rmtree(target)
        shutil.move(src, target)
        return target


def write_licenses(sdk_root):
    lic = os.path.join(sdk_root, "licenses")
    os.makedirs(lic, exist_ok=True)
    hashes = "\n".join(ANDROID_LICENSE_HASHES) + "\n"
    with open(os.path.join(lic, "android-sdk-license"), "w") as f:
        f.write(hashes)
    with open(os.path.join(lic, "android-sdk-preview-license"), "w") as f:
        f.write(hashes)


def cmd_sdk(tc, java_home, force):
    log("step: Android SDK")
    info = detect()
    if info["osname"] == "linux" and info["arch"] == "arm":
        log("NOTE: Google ships the Android tools for x86_64 Linux only. On 32-bit ARM "
            "(older Raspberry Pi) the SDK manager needs qemu-user-static; continuing anyway.")
    sdk_root = os.path.join(tc, "sdk")
    os.makedirs(sdk_root, exist_ok=True)
    latest = os.path.join(sdk_root, "cmdline-tools", "latest")
    if not os.path.isdir(os.path.join(latest, "bin")) or force:
        url = DL_ANDROID + CMD_TOOLS[info["system"] if info["system"] in CMD_TOOLS else "linux"]
        with tempfile.TemporaryDirectory(prefix="zensdk_") as td:
            f = os.path.join(td, "cmdline.zip")
            download(url, f, "Android command-line tools")
            unzip_existing(f, td)
            src = os.path.join(td, "cmdline-tools")
            shutil.rmtree(latest, ignore_errors=True)
            os.makedirs(latest, exist_ok=True)
            for item in os.listdir(src):
                shutil.move(os.path.join(src, item), os.path.join(latest, item))
    write_licenses(sdk_root)
    sdk = os.path.join(latest, "bin", "sdkmanager.bat" if os.name == "nt" else "sdkmanager")
    if not os.path.isfile(sdk):
        err("sdkmanager not found at " + sdk)
        return
    env = dict(os.environ)
    env["JAVA_HOME"] = java_home
    env["PATH"] = os.path.join(java_home, "bin") + os.pathsep + env.get("PATH", "")
    log("accepting SDK licenses")
    subprocess.run([sdk, "--sdk_root=" + sdk_root, "--licenses"], env=env, input=b"y\n" * 40)
    rc = run([sdk, "--sdk_root=" + sdk_root] + SDK_PACKAGES, env=env)
    if rc != 0:
        err("sdkmanager failed (rc=%d). On a Raspberry Pi try first:" % rc)
        err("  sudo apt install qemu-user-static binfmt-support")
        err("  then re-run zen-setup.py")


def verify(tc):
    log("verifying ...")
    jdks = []
    if os.path.isdir(tc):
        jdks = [os.path.join(tc, d) for d in sorted(os.listdir(tc)) if d.startswith("jdk-")]
    if jdks:
        run_java(jdks[0], [os.path.join(jdks[0], "bin", java_exe_name()), "-version"])
    else:
        log("no JDK under " + tc + " (the system java will be used instead)")
    if not jdks:
        return
    g = os.path.join(tc, "gradle-" + GRADLE_VERSION, "bin", gradle_exe_name())
    if os.path.isfile(g):
        run_java(jdks[0], [g, "--version"])
    sdk = os.path.join(tc, "sdk")
    parts = [d for d in ["platforms", "build-tools", "platform-tools"]
             if os.path.isdir(os.path.join(sdk, d))]
    log("sdk components present: " + (", ".join(parts) if parts else "none"))


def main():
    script_dir = os.path.dirname(os.path.abspath(__file__))
    default_dest = os.path.join(script_dir, "..", "toolchain")
    p = argparse.ArgumentParser(prog="zen-setup", description="Install the Zen build toolchain")
    p.add_argument("--steps", default="pip,java,gradle,sdk",
                   help="comma list of pip,java,gradle,sdk (default: all)")
    p.add_argument("--dest", default=default_dest, help="toolchain folder")
    p.add_argument("--requirements", default=os.path.join(script_dir, "requirements.txt"),
                   help="pip requirements file")
    p.add_argument("--force", action="store_true", help="re-download everything")
    p.add_argument("--no-verify", action="store_true", help="skip final version checks")
    args = p.parse_args()

    tc = os.path.abspath(args.dest)
    os.makedirs(tc, exist_ok=True)
    info = detect()
    log("platform: {system} / {arch}{rpi}".format(
        system=info["system"], arch=info["arch"],
        rpi="  (Raspberry Pi)" if info["rpi"] else ""))
    log("toolchain folder: " + tc)

    steps = [s.strip() for s in args.steps.split(",") if s.strip()]

    try:
        if "pip" in steps:
            cmd_pip(args.requirements)
        java_home = ""
        if "java" in steps:
            java_home = cmd_java(tc, args.force)
        if not java_home:
            java_home = find_java_17() or ""
        if "gradle" in steps:
            cmd_gradle(tc, args.force)
        if "sdk" in steps:
            cmd_sdk(tc, java_home, args.force)
        log("done.")
        if not args.no_verify:
            verify(tc)
    except KeyboardInterrupt:
        err("cancelled")
        sys.exit(2)
    except Exception as e:
        err(str(e))
        sys.exit(1)
    print()
    log("next: cd to the folder with zen.py and run")
    log('  py zen.py build your-app.zip --name "Your App"'
        if os.name == "nt"
        else '  python3 zen.py build your-app.zip --name "Your App"')


if __name__ == "__main__":
    main()
