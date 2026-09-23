#!/usr/bin/env python3
"""Zen - Python wrapper that turns a webpage zip into an Android app.

Usage:
  zen.py init <name> [--out DIR]
  zen.py build <webpage.zip> [--name APP --package PKG --out DIR --no-build]
  zen.py ide  <webpage.zip> [--name APP --out DIR --port PORT]
  zen.py apidoc

Optional files inside the zip (all optional, all removed from the shipped assets):
  icons/app-icon.png          launcher icon (replaces the built-in "Z")
  permissions/permissions.json  extra android permissions (JSON array of names)
  package/manifest.json       {name, packageId, versionCode, versionName, minSdk, targetSdk}
"""

import argparse
import glob
import io
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
import time
import zipfile
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer

ZEN_VERSION = "1.1"
TOKEN_PKG = "{{PKG}}"
TOKEN_APP = "{{APP}}"
PKG_DIR_MARKER = "__pkgdir__"
TEXT_EXT = {".html", ".htm", ".js", ".css", ".json", ".txt", ".zen"}
WEB_REL = os.path.join("app", "src", "main", "assets", "web")
INDEX_HTML = "index.html"
META_DIRS = {"icons", "permissions", "package"}

APIDOC = """\
Zen JS API (window.Zen)

READ-ONLY / MISC
  Zen.version()                    string  wrapper version
  Zen.log(msg)                     void    print to logcat
  Zen.toast(msg)                   void    short Android toast
  Zen.checkPermission(perm)        bool    is an android permission already granted
  Zen.requestPermission(perm)      string  "granted" / "requested" / "denied"

LOCATION
  Zen.getLocation()                string  JSON {latitude,longitude,altitude,accuracy,speed,bearing,time} or "null"
  Zen.setLocationCallback(ms)      void    start streaming; each fix calls window.__zenOnLocation(json)
  Zen.clearLocationCallback()      void    stop streaming

NOTIFICATIONS
  Zen.notify(title, body)          void    system notification (prompts for POST_NOTIFICATIONS)

MOCK LOCATION
  Zen.setMockLocation(lat, lng)    string  "ok" or "ERROR: ..." (app must be picked in Developer options > Mock location app)

ROOT
  Zen.isRoot()                     bool    true if 'su' works
  Zen.runRoot(cmd)                 string  JSON {exit, out} of `su -c "<cmd>"`

OVERLAY
  Zen.showOverlay(text)            void    floating text overlay (needs SYSTEM_ALERT_WINDOW)
  Zen.hideOverlay()                void    remove overlay

BACKGROUND
  Zen.startBackground()            void    start hidden WebView foreground service so JS keeps running
  Zen.stopBackground()             void    stop it

Location streaming: define window.__zenOnLocation = function(loc){...} in your page.
"""


def log(msg):
    print("[zen] " + msg)


def sanitize_ident(name, fallback="app"):
    s = re.sub(r"[^A-Za-z0-9_]+", "_", name).strip("_")
    if not s:
        s = fallback
    if s[0].isdigit():
        s = "_" + s
    return s


def make_package(name, explicit):
    if explicit:
        return explicit.replace("-", "_").lower()
    return "dev.zen." + sanitize_ident(name.lower(), "app")


def find_toolchain():
    root = os.path.dirname(os.path.abspath(__file__))
    tc = os.path.join(root, "..", "toolchain")
    gradle = os.environ.get("ZEN_GRADLE", "")
    if not gradle and os.path.isdir(os.path.join(tc, "gradle-8.9")):
        gradle = os.path.join(tc, "gradle-8.9")
    sdk = os.environ.get("ZEN_SDK", "")
    if not sdk and os.path.isdir(os.path.join(tc, "sdk")):
        sdk = os.path.join(tc, "sdk")
    java = os.environ.get("ZEN_JAVA", "")
    if not java:
        hits = glob.glob(os.path.join(tc, "jdk-*"))
        if hits:
            java = hits[0]
    if not java:
        java = os.environ.get("JAVA_HOME", "")
    return gradle, sdk, java


def check_toolchain(gradle, sdk, java):
    problems = []
    if not gradle or not os.path.isfile(os.path.join(gradle, "bin", "gradle.bat")):
        problems.append("gradle not found (set ZEN_GRADLE)")
    if not sdk or not os.path.isdir(sdk):
        problems.append("android sdk not found (set ZEN_SDK)")
    if not java or not os.path.isdir(java):
        problems.append("jdk not found (set JAVA_HOME or ZEN_JAVA)")
    if problems:
        for p in problems:
            print("[zen] ERROR: " + p)
        return False
    return True


def extract_web(zip_path, work):
    found_index = False
    with zipfile.ZipFile(zip_path) as z:
        for info in z.infolist():
            if info.is_dir():
                continue
            parts = [p for p in info.filename.replace("\\", "/").split("/") if p and p not in (".", "..")]
            if not parts:
                continue
            head = parts[0].lower()
            if head in META_DIRS:
                dest = os.path.join(work, "meta", *parts)
                os.makedirs(os.path.dirname(dest), exist_ok=True)
                with z.open(info) as src, open(dest, "wb") as dst:
                    dst.write(src.read())
                continue
            name = parts[-1]
            new_name = re.sub(r"\.zen\b", ".js", name)
            sub = os.path.join(work, "web", *parts[:-1]) if len(parts) > 1 else os.path.join(work, "web")
            os.makedirs(sub, exist_ok=True)
            with z.open(info) as src, open(os.path.join(sub, new_name), "wb") as dst:
                data = src.read()
                if new_name.lower().endswith(tuple(TEXT_EXT)):
                    try:
                        text = data.decode("utf-8")
                    except UnicodeDecodeError:
                        text = data.decode("latin-1")
                    dst.write(re.sub(r"\.zen\b", ".js", text).encode("utf-8"))
                else:
                    dst.write(data)
            if new_name == INDEX_HTML:
                found_index = True
    if not found_index:
        print("[zen] ERROR: zip must contain " + INDEX_HTML)
        return False
    return True


def copy_tree(src, dst, pkg, label, extras=None):
    pkg_dir = pkg.replace(".", os.sep)
    for cur, dirs, files in os.walk(src):
        for f in files:
            full = os.path.join(cur, f)
            rel = os.path.relpath(full, src).replace(PKG_DIR_MARKER, pkg_dir)
            target = os.path.join(dst, rel)
            os.makedirs(os.path.dirname(target), exist_ok=True)
            with open(full, "r", encoding="utf-8") as r:
                text = r.read()
            text = text.replace(TOKEN_PKG, pkg).replace(TOKEN_APP, label)
            if extras:
                for k, v in extras.items():
                    text = text.replace(k, v)
            with open(target, "w", encoding="utf-8", newline="\n") as w:
                w.write(text)


def default_out_dir():
    out = os.environ.get("ZEN_OUT", "")
    if out:
        return out
    return os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "out")


def load_build_meta(zip_stem, args, work):
    meta = {}
    p = os.path.join(work, "meta", "package", "manifest.json")
    if os.path.isfile(p):
        try:
            with open(p, "r", encoding="utf-8") as f:
                meta = json.load(f)
            if not isinstance(meta, dict):
                raise ValueError("json root must be an object")
        except Exception as e:
            log("warning: ignoring package/manifest.json (%s)" % e)
            meta = {}
    if "name" in meta:
        name = meta["name"]
    label = args.name or name or zip_stem
    pkg = args.package or meta.get("packageId") or make_package(zip_stem, None)
    pkg = pkg.replace("-", "_").lower().strip(".")
    try:
        vc = int(meta.get("versionCode", 1))
    except Exception:
        vc = 1
    vn = str(meta.get("versionName", "1.0"))
    try:
        minsdk = int(meta.get("minSdk", 26))
    except Exception:
        minsdk = 26
    try:
        target = int(meta.get("targetSdk", 35))
    except Exception:
        target = 35
    return {
        "label": label,
        "pkg": pkg,
        "versionCode": vc,
        "versionName": vn,
        "minSdk": minsdk,
        "targetSdk": target,
    }


def load_permissions(work):
    p = os.path.join(work, "meta", "permissions", "permissions.json")
    if not os.path.isfile(p):
        return ""
    try:
        with open(p, "r", encoding="utf-8") as f:
            data = json.load(f)
    except Exception as e:
        log("warning: ignoring permissions/permissions.json (%s)" % e)
        return ""
    perms = data if isinstance(data, list) else data.get("permissions", [])
    lines = []
    for x in perms:
        if isinstance(x, str) and x.strip():
            lines.append('    <uses-permission android:name="%s" />' % x.strip())
    if lines:
        log("permissions added: " + ", ".join(x.strip().split('"')[1] for x in lines))
    return "\n".join(lines)


def apply_icon(work, proj):
    src = os.path.join(work, "meta", "icons", "app-icon.png")
    if not os.path.isfile(src):
        return
    anydpi = os.path.join(proj, "app", "src", "main", "res", "mipmap-anydpi-v26", "ic_launcher.xml")
    if os.path.isfile(anydpi):
        os.remove(anydpi)
    dpi = os.path.join(proj, "app", "src", "main", "res", "mipmap-xxxhdpi")
    os.makedirs(dpi, exist_ok=True)
    shutil.copy2(src, os.path.join(dpi, "ic_launcher.png"))
    log("custom launcher icon: icons/app-icon.png")


def write_local_properties(project_dir, sdk):
    sdk_esc = sdk.replace("\\", "\\\\").replace(":", "\\:")
    with open(os.path.join(project_dir, "local.properties"), "w", encoding="utf-8") as f:
        f.write("sdk.dir=" + sdk_esc + "\n")


def build_project(project_dir, java, gradle):
    env = dict(os.environ)
    env["JAVA_HOME"] = java
    env["PATH"] = os.path.join(java, "bin") + os.pathsep + env.get("PATH", "")
    gradle_bat = os.path.join(gradle, "bin", "gradle.bat")
    cmd = ["cmd", "/c", gradle_bat, "assembleDebug", "--console=plain"]
    log("running: " + " ".join(cmd))
    return subprocess.run(cmd, cwd=project_dir, env=env).returncode


def copy_assets(work_web, project_dir):
    dst = os.path.join(project_dir, *WEB_REL.split(os.sep))
    os.makedirs(dst, exist_ok=True)
    for cur, dirs, files in os.walk(work_web):
        rel = os.path.relpath(cur, work_web)
        out = os.path.join(dst, rel) if rel != "." else dst
        os.makedirs(out, exist_ok=True)
        for f in files:
            shutil.copy2(os.path.join(cur, f), os.path.join(out, f))


def cmd_init(args):
    out_dir = os.path.abspath(args.out) if args.out else os.getcwd()
    base = os.path.join(out_dir, sanitize_ident(args.name, "sample"))
    os.makedirs(base, exist_ok=True)
    files = {
        "index.html": INIT_HTML,
        "style.css": INIT_CSS,
        "app.zen": INIT_JS,
    }
    for name, content in files.items():
        content = content.replace("{{APP}}", args.name)
        with open(os.path.join(base, name), "w", encoding="utf-8", newline="\n") as f:
            f.write(content)
        log("created " + os.path.join(base, name))
    log("now zip the folder and run:  zen.py build <zip> --name \"%s\"" % args.name)


def cmd_build(args):
    if not os.path.isfile(args.zip):
        print("[zen] ERROR: no such file: " + args.zip)
        sys.exit(1)
    gradle, sdk, java = find_toolchain()
    if not check_toolchain(gradle, sdk, java):
        sys.exit(1)
    zip_stem = os.path.splitext(os.path.basename(args.zip))[0]
    out_root = os.path.abspath(args.out) if args.out else os.path.abspath(default_out_dir())
    os.makedirs(out_root, exist_ok=True)

    work = tempfile.mkdtemp(prefix="zen_")
    try:
        if not extract_web(args.zip, work):
            sys.exit(1)
        meta = load_build_meta(zip_stem, args, work)
        name = meta["label"]
        pkg = meta["pkg"]
        proj = os.path.join(out_root, sanitize_ident(name, "app"))
        if os.path.isdir(proj):
            shutil.rmtree(proj)
        os.makedirs(proj)
        template = os.path.join(os.path.dirname(os.path.abspath(__file__)), "template")
        extras = {
            "{{PERMS}}": load_permissions(work),
            "{{VERSION_CODE}}": str(meta["versionCode"]),
            "{{VERSION_NAME}}": meta["versionName"],
            "{{MIN_SDK}}": str(meta["minSdk"]),
            "{{TARGET_SDK}}": str(meta["targetSdk"]),
        }
        copy_tree(template, proj, pkg, name, extras)
        apply_icon(work, proj)
        copy_assets(os.path.join(work, "web"), proj)
        write_local_properties(proj, sdk)
        log("project: %s (%s)" % (proj, pkg))
        log("app \"%s\" v%s (code %d), minSdk %d, targetSdk %d" % (
            name, meta["versionName"], meta["versionCode"], meta["minSdk"], meta["targetSdk"]))
        if args.no_build:
            log("--no-build: done")
            return
        t0 = time.time()
        rc = build_project(proj, java, gradle)
        dt = time.time() - t0
        apk = os.path.join(proj, "app", "build", "outputs", "apk", "debug", "app-debug.apk")
        if rc == 0 and os.path.isfile(apk):
            final = os.path.join(out_root, sanitize_ident(name, "app") + ".apk")
            shutil.copy2(apk, final)
            log("BUILD OK in %.1fs -> %s (%.0f KB)" % (dt, final, os.path.getsize(final) / 1024))
        else:
            log("BUILD FAILED rc=%d after %.1fs" % (rc, dt))
            sys.exit(rc or 1)
    finally:
        shutil.rmtree(work, ignore_errors=True)


def cmd_apidoc(args):
    print(APIDOC)


SHIM_URL = "/__zen/zen-debug.js"

DEBUG_SHIM = """\
(function () {
  var lastLoc = null;
  var watchIds = [];
  var overlay = null;
  var toast = null;
  function toastDiv(msg) {
    if (!toast) {
      toast = document.createElement('div');
      toast.style.cssText = 'position:fixed;left:50%;bottom:24px;transform:translateX(-50%);background:rgba(20,24,28,.92);color:#fff;padding:10px 16px;border-radius:8px;font:14px system-ui,sans-serif;z-index:2147483646;max-width:80%;pointer-events:none;opacity:0;transition:opacity .2s;';
      document.body.appendChild(toast);
    }
    toast.textContent = String(msg);
    toast.style.opacity = '1';
    clearTimeout(toast._t);
    toast._t = setTimeout(function () { toast.style.opacity = '0'; }, 2200);
  }
  function geo(pos) {
    var c = pos.coords;
    return JSON.stringify({
      latitude: c.latitude,
      longitude: c.longitude,
      altitude: (c.altitude != null) ? c.altitude : 0,
      accuracy: c.accuracy,
      speed: (c.speed != null) ? c.speed : 0,
      bearing: (c.heading != null) ? c.heading : 0,
      time: pos.timestamp
    });
  }
  window.Zen = {
    version: function () { return '1.1-debug'; },
    log: function (m) { console.log('[Zen]', m); },
    toast: function (m) { toastDiv(m); },
    getLocation: function () { return lastLoc ? lastLoc : 'null'; },
    setLocationCallback: function (ms) {
      if (!navigator.geolocation) { toastDiv('geolocation unavailable in this browser'); return; }
      watchIds.push(navigator.geolocation.watchPosition(function (pos) {
        try {
          lastLoc = geo(pos);
          window.__zenOnLocation && window.__zenOnLocation(JSON.parse(lastLoc));
        } catch (e) {}
      }, function (e) { toastDiv('GPS error: ' + ((e && e.message) || e)); }, {
        enableHighAccuracy: true, maximumAge: 0, timeout: 10000
      }));
    },
    clearLocationCallback: function () {
      watchIds.splice(0).forEach(function (id) { navigator.geolocation.clearWatch(id); });
    },
    notify: function (title, body) {
      function send() { new Notification(String(title), { body: String(body) }); }
      if ('Notification' in window) {
        if (Notification.permission === 'granted') send();
        else if (Notification.permission === 'default') {
          Notification.requestPermission().then(function (p) { if (p === 'granted') send(); });
        } else toastDiv('[notify] ' + title + ': ' + body);
      } else {
        toastDiv('[notify] ' + title + ': ' + body);
      }
    },
    setMockLocation: function (lat, lng) { return 'ok (debug: mock not applied to real GPS)'; },
    isRoot: function () { return false; },
    runRoot: function (cmd) { return JSON.stringify({ exit: -1, out: 'not available in debug' }); },
    showOverlay: function (t) {
      if (!overlay) {
        overlay = document.createElement('div');
        overlay.style.cssText = 'position:fixed;top:16px;left:50%;transform:translateX(-50%);background:rgba(0,0,0,.78);color:#fff;padding:8px 14px;border-radius:8px;font:14px system-ui,sans-serif;z-index:2147483647;pointer-events:none;';
        document.body.appendChild(overlay);
      }
      overlay.textContent = String(t);
    },
    hideOverlay: function () { if (overlay) { overlay.remove(); overlay = null; } },
    startBackground: function () { console.log('[Zen] startBackground is a no-op in debug'); },
    stopBackground: function () { console.log('[Zen] stopBackground is a no-op in debug'); },
    checkPermission: function () { return true; },
    requestPermission: function (p) { return 'granted'; }
  };
})();
"""


def make_handler(web_dir):
    class ZenHandler(SimpleHTTPRequestHandler):
        def __init__(self, *args, **kwargs):
            super().__init__(*args, directory=web_dir, **kwargs)

        def log_message(self, fmt, *args):
            pass

        def send_head(self):
            path = self.path.split("?")[0]
            if path in ("/", "/index.html"):
                f = os.path.join(self.directory, INDEX_HTML)
                if os.path.isfile(f):
                    with open(f, "r", encoding="utf-8", errors="replace") as fp:
                        text = fp.read()
                    tag = '<script src="' + SHIM_URL + '"></script>'
                    idx = text.lower().find("<head")
                    if idx != -1:
                        idx = text.find(">", idx)
                        text = text[:idx + 1] + tag + text[idx + 1:]
                    else:
                        text = tag + text
                    data = text.encode("utf-8")
                    self.send_response(200)
                    self.send_header("Content-Type", "text/html; charset=utf-8")
                    self.send_header("Content-Length", str(len(data)))
                    self.end_headers()
                    return io.BytesIO(data)
            return super().send_head()

    return ZenHandler


def cmd_ide(args):
    if not os.path.isfile(args.zip):
        print("[zen] ERROR: no such file: " + args.zip)
        sys.exit(1)
    zip_stem = os.path.splitext(os.path.basename(args.zip))[0]
    name = args.name or zip_stem
    out_root = os.path.abspath(args.out) if args.out else os.path.abspath(default_out_dir())
    web_dir = os.path.join(out_root, "ide", sanitize_ident(name, "app"), "web")
    work = tempfile.mkdtemp(prefix="zen_ide_")
    try:
        if not extract_web(args.zip, work):
            sys.exit(1)
        if os.path.isdir(web_dir):
            shutil.rmtree(web_dir)
        os.makedirs(os.path.dirname(web_dir), exist_ok=True)
        shutil.move(os.path.join(work, "web"), web_dir)
    finally:
        shutil.rmtree(work, ignore_errors=True)
    shim_dir = os.path.join(web_dir, "__zen")
    os.makedirs(shim_dir, exist_ok=True)
    with open(os.path.join(shim_dir, "zen-debug.js"), "w", encoding="utf-8", newline="\n") as f:
        f.write(DEBUG_SHIM)
    port = args.port or 8765
    httpd = ThreadingHTTPServer(("127.0.0.1", port), make_handler(web_dir))
    print()
    print("[zen] Zen IDE - previewing '" + name + "'")
    print("[zen]   http://127.0.0.1:%d/" % port)
    print("[zen]   Ctrl+C to stop")
    print("[zen]   files: " + web_dir)
    print("[zen]   window.Zen is simulated in the browser; phone-only features")
    print("[zen]   (mock location, root, background service, overlays) are stubs.")
    try:
        httpd.serve_forever()
    except KeyboardInterrupt:
        print()
        log("ide stopped")


INIT_HTML = """<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1, user-scalable=no">
<title>{{APP}}</title>
<link rel="stylesheet" href="style.css">
<script src="app.zen"></script>
</head>
<body>
<div id="app">
  <h1>{{APP}}</h1>
  <p class="sub">A webpage running as an Android app via Zen.</p>
  <div id="status">Ready</div>
  <div id="buttons">
    <button onclick="Zen.toast('hi')">Toast</button>
    <button onclick="Zen.notify('Zen','Hello from JS')">Notify</button>
    <button onclick="getLoc()">Get location</button>
    <button onclick="Zen.setLocationCallback(1000)">Stream loc</button>
    <button onclick="Zen.clearLocationCallback()">Stop stream</button>
    <button onclick="mock()">Mock loc</button>
    <button onclick="checkRoot()">isRoot</button>
    <button onclick="Zen.showOverlay('overlay')">Overlay on</button>
    <button onclick="Zen.hideOverlay()">Overlay off</button>
    <button onclick="Zen.startBackground()">BG on</button>
    <button onclick="Zen.stopBackground()">BG off</button>
  </div>
</div>
<script>
window.__zenOnLocation = function (loc) {
  status('lat=' + loc.latitude.toFixed(6) + ' lng=' + loc.longitude.toFixed(6));
};
function status(t) { document.getElementById('status').textContent = t; }
function getLoc() {
  var loc = Zen.getLocation();
  status(loc && loc !== 'null' ? JSON.stringify(loc) : 'No location yet');
}
function mock() {
  var lat = 55.6761 + (Math.random() - 0.5) / 100;
  var lng = 12.5683 + (Math.random() - 0.5) / 100;
  status(Zen.setMockLocation(lat, lng));
}
function checkRoot() {
  status('root=' + Zen.isRoot() + ' ver=' + Zen.version());
}
</script>
</body>
</html>
"""

INIT_CSS = """* { box-sizing: border-box; margin: 0; padding: 0; }
html, body { height: 100%; }
body {
  font-family: system-ui, sans-serif;
  background: #101418; color: #e8eef5;
  display: flex; align-items: center; justify-content: center;
  padding: 24px;
}
#app { width: 100%; max-width: 420px; text-align: center; }
h1 { font-size: 44px; letter-spacing: 2px; }
.sub { color: #8aa0b5; margin: 8px 0 24px; }
#status {
  min-height: 52px; padding: 12px; margin-bottom: 16px;
  background: #1a2129; border-radius: 10px;
  font-family: monospace; font-size: 13px; word-break: break-all;
}
#buttons { display: grid; grid-template-columns: 1fr 1fr; gap: 10px; }
button {
  padding: 14px; border: 0; border-radius: 10px;
  background: #1E88E5; color: #fff; font-size: 15px;
}
button:active { opacity: .7; }
"""

INIT_JS = """console.log('Zen sample loaded, version ' + Zen.version());
"""


def main():
    p = argparse.ArgumentParser(prog="zen", description="Webpage zip -> Android app wrapper")
    sub = p.add_subparsers(dest="cmd", required=True)

    pi = sub.add_parser("init", help="create a sample webpage folder")
    pi.add_argument("name")
    pi.add_argument("--out", default=None, help="output directory (default: cwd)")
    pi.set_defaults(func=cmd_init)

    pb = sub.add_parser("build", help="build an Android app from a webpage zip")
    pb.add_argument("zip")
    pb.add_argument("--name", default=None, help="app name (default: zip filename)")
    pb.add_argument("--package", default=None, help="application id (default: dev.zen.<name>)")
    pb.add_argument("--out", default=None, help="output directory (default: ../out)")
    pb.add_argument("--no-build", action="store_true", help="only generate the project")
    pb.set_defaults(func=cmd_build)

    pd = sub.add_parser("apidoc", help="print the Zen.* JS API reference")
    pd.set_defaults(func=cmd_apidoc)

    pi2 = sub.add_parser("ide", help="preview a webpage zip in the browser before packaging")
    pi2.add_argument("zip")
    pi2.add_argument("--name", default=None, help="label for the preview (default: zip filename)")
    pi2.add_argument("--out", default=None, help="output directory (default: ../out)")
    pi2.add_argument("--port", type=int, default=8765, help="local http port (default: 8765)")
    pi2.set_defaults(func=cmd_ide)

    args = p.parse_args()
    args.func(args)


if __name__ == "__main__":
    main()