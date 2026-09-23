# Zen

Turn any webpage into a real Android app. No Android knowledge needed.

---

## Table of contents

1. [Zen, explained for complete beginners](#1-zen-explained-for-complete-beginners)
2. [What you need before you start](#2-what-you-need-before-you-start)
3. [The very first thing you'll build (hello world)](#3-the-very-first-thing-youll-build-hello-world)
4. [The Zen commands](#4-the-zen-commands)
5. [What goes inside your zip file](#5-what-goes-inside-your-zip-file)
6. [The three special files](#6-the-three-special-files)
7. [What happens when you run `zen.py build`](#7-what-happens-when-you-run-zenpy-build)
8. [How your page is different from a normal website](#8-how-your-page-is-different-from-a-normal-website)
9. [The `.zen` file trick](#9-the-zen-file-trick)
10. [The `window.Zen` API, every function explained](#10-the-windowzen-api-every-function-explained)
11. [Testing on your computer first (the Zen IDE)](#11-testing-on-your-computer-first-the-zen-ide)
12. [Putting it on a real phone](#12-putting-it-on-a-real-phone)
13. [Common problems and how to fix them](#13-common-problems-and-how-to-fix-them)
14. [Handy one-page API reference](#14-handy-one-page-api-reference)
15. [License](#15-license)

---

## 1. Zen, explained for complete beginners

Let's start from zero.

### What is an app, really?

A normal Android app (like WhatsApp or Candy Crush) is a package of files that
Android knows how to run. That package has the file ending **`.apk`**. You can
put an `.apk` on a phone, install it like any app, and a little icon appears on
the home screen. When you tap the icon, the app opens in full screen — no
browser address bar, no tabs, nothing. Just the app.

### What is Zen?

Zen is a tool (a program) that makes those `.apk` files for you — but the "app"
you're packaging is **just a webpage**.

If you know how to write a webpage (HTML, CSS, JavaScript), you already know
how to write a Zen app. Zen takes the folder of files that make your webpage,
wraps them up, compiles them into an `.apk` file, and that's your app.

### The magic part: `window.Zen`

Here's the part that makes Zen useful. A normal webpage runs inside a browser,
and it can only do what a browser lets it do. But a Zen app doesn't run in a
browser — it runs inside a thing called a **WebView**.

Think of a WebView as a browser engine that's been stuffed inside your app, with
the steering wheel taken off. The user can't see an address bar, can't type a
URL, can't navigate away. They just see your page, full screen, exactly like a
real app.

And because your page is running inside the app (not in a browser), Zen gives
your JavaScript a special helper object called **`window.Zen`**. Through it,
your webpage can do things ordinary webpages can't:

- find the phone's GPS position,
- show system notifications,
- pretend the phone is somewhere else (mock GPS),
- run commands as root (if the phone is rooted),
- keep running in the background,
- and more.

So:

> **Zen = "write a webpage, get an app, plus phone powers in JavaScript."**

### A very quick mental picture

```
  Your files (HTML/CSS/JS)  ──▶  zen.py build  ──▶  Yourapp.apk  ──▶  phone

  Inside the APK, your page runs in a hidden WebView.
  Your JavaScript talks to the phone through window.Zen.
```

---

## 2. What you need before you start

You need three things. Most people already have two of them.

### 2.1 A computer (PC or Mac)

Any computer. The whole tool is written in Python, which runs everywhere.

### 2.2 Python

Python is a (free) programming language. Zen is written in it.

- **On Windows**: open a terminal and type `py --version`. If you see something
  like `Python 3.13.9`, you're fine. If Windows says "Python was not found",
  install it from <https://www.python.org/downloads/> — and while installing,
  tick the box that says **"Add python.exe to PATH"**.
  - Note: on Windows, `py` is the command you use. Plain `python` sometimes
    opens the Microsoft Store instead of real Python. Always type `py`.
- **On Mac/Linux**: open a terminal and type `python3 --version`.

Zen needs Python **3.8 or newer**.

### 2.3 The "toolchain" (this is the clever name for three tools)

To turn your webpage into an `.apk`, Zen uses three free tools behind the
scenes:

| Tool | What it does | Human explanation |
|------|--------------|-------------------|
| **JDK 17** | Java developer kit | The language the app shell is written in |
| **Gradle 8.9** | Build machine | Gives the orders, collects all the pieces, and presses the "make APK" button |
| **Android SDK 35** | Google's Android toolbox | The official rules/files for making Android apps |

You don't need to understand these. You just need them on your computer.

Zen looks for them in two places, in this order:

1. **Environment variables** (advanced): `ZEN_GRADLE`, `ZEN_SDK`, `ZEN_JAVA`.
2. **A `toolchain` folder** sitting right next to `zen.py`:

```
toolchain/
  jdk-17.0.20.1+1/     ← JDK 17
  gradle-8.9/          ← Gradle
  sdk/                 ← Android SDK
```

If your JDK is newer than 17 (for example 26), Gradle will refuse to work and
the build will print `Unsupported class file major version ...`. Fix that by
making `ZEN_JAVA` point at a JDK 17 folder, e.g. in PowerShell:

```
$env:ZEN_JAVA = "C:\Users\you\toolchain\jdk-17.0.20.1+1"
```

### 2.4 An Android phone (for the last step only)

To actually run your app you need an Android phone. You don't need it for the
building or the testing-in-the-browser parts.

---

## 3. The very first thing you'll build (hello world)

Let's make your first app. It takes about two minutes. All you need is a text
editor (like Notepad) and a way to make a zip file.

### Step 1 — make a folder

Create a new folder anywhere, for example:

```
HelloApp/
```

### Step 2 — put a webpage inside it

Inside that folder, create a file called **`index.html`** (exact spelling:
lowercase, no spaces). Open it in a text editor and paste this:

```html
<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Hello</title>
</head>
<body style="font-family:sans-serif;text-align:center;padding-top:30vh">
  <h1>Hello world!</h1>
  <p>This is my first Zen app.</p>
</body>
</html>
```

Save it. That's it. That's a whole app now.

### Step 3 — zip the folder's *contents*

**Important:** zip the stuff *inside* `HelloApp`, not the `HelloApp` folder
itself. The zip must have `index.html` at its very top level.

- **Windows**: open the `HelloApp` folder, select everything inside it
  (Ctrl+A), right-click, choose **Send to → Compressed (zipped) folder**, and
  rename the result to `HelloApp.zip`.
- **Anywhere with a terminal**: `cd` into the folder and run
  `zip -r ../HelloApp.zip .`

### Step 4 — build the app

Open a terminal in the same place as `zen.py` and run:

```
py zen.py build HelloApp.zip --name "Hello App"
```

(On Mac/Linux use `python3 zen.py ...` instead of `py`.)

After about 30 seconds you'll see something like:

```
[zen] project: ...\out\Hello_App (dev.zen.helloapp)
[zen] BUILD OK in 29.3s -> ...\out\Hello_App.apk
```

That red text at the end — **`out/Hello_App.apk`** — is your app.

### Step 5 — put it on a phone

See [section 12](#12-putting-it-on-a-real-phone). Or, if you just want to look
at it on your computer first, jump to [section 11](#11-testing-on-your-computer-first-the-zen-ide).

---

## 4. The Zen commands

All the commands start with `py zen.py`. Here they all are:

| Command | Example | What it does |
|---------|---------|--------------|
| `build` | `py zen.py build myapp.zip --name "My App"` | Turns a webpage zip into an `.apk` |
| `ide` | `py zen.py ide myapp.zip` | Shows your app in the browser so you can test it before building |
| `init` | `py zen.py init MyApp` | Creates a sample webpage (a demo app) for you to play with |
| `apidoc` | `py zen.py apidoc` | Prints a short list of all the `Zen.*` functions |

### `build` in detail

```
py zen.py build <the zip file> [options]
```

Options:

| Option | What it does |
|--------|--------------|
| `--name "Some Name"` | The name of the app (the label under the icon). Default: the zip file's name. |
| `--package com.example.x` | The app's "package id" — a technical name Android uses. Usually fine to leave alone. |
| `--out some/folder` | Where the finished `.apk` goes. Default: `out/` next to `zen.py`. |
| `--no-build` | Generate the Android project but don't compile it yet (handy for the curious). |

The interesting files after a build:

| Path | What it is |
|------|-----------|
| `out/My_App/` | The full Android project (you normally never look at this) |
| `out/My_App.apk` | Your app. This is the file you install. |

### Useful environment variables

These are optional and override Zen's automatic searching:

| Variable | Overrides |
|----------|-----------|
| `ZEN_GRADLE` | where Gradle lives |
| `ZEN_SDK` | where the Android SDK lives |
| `ZEN_JAVA` | where a *JDK 17* lives |
| `ZEN_OUT` | the default output folder |

In PowerShell you set them like this:

```
$env:ZEN_JAVA = "C:\Users\you\toolchain\jdk-17.0.20.1+1"
```

---

## 5. What goes inside your zip file

This is the complete list of everything Zen understands in your zip. Only ONE
thing is required — everything else is optional.

```
myapp.zip
  index.html                ← REQUIRED. The app's start page.
  style.css                 ← optional. Any other files travel along as they are.
  app.js                    ← optional. More files, folders, images… anything.
  icons/
    app-icon.png            ← optional. Your app's launcher icon.
  permissions/
    permissions.json        ← optional. Extra Android permissions.
  package/
    manifest.json           ← optional. Your app's name / version / package.
```

Some rules to remember:

- The zip's top level must contain `index.html`. If it's missing, `build`
  prints `ERROR: zip must contain index.html` and stops.
- The three special folders (`icons/`, `permissions/`, `package/`) are read
  during the build and then **removed** — they never become part of your web
  app's files.
- The output folder `out/` is never inside the zip. Zip only your webpage.

---

## 6. The three special files

### 6.1 `package/manifest.json` — the "about this app" file

Put this file at `package/manifest.json` inside your zip. Writing it is
optional, but it's the only way to control the app *name* and *version* from
inside the zip.

```json
{
  "name": "My App",
  "packageId": "com.example.myapp",
  "versionCode": 2,
  "versionName": "1.5",
  "minSdk": 26,
  "targetSdk": 35
}
```

Every field, explained simply:

| Field | Default | Easy explanation |
|-------|---------|------------------|
| `name` | zip filename | The name shown under the app's icon on the phone. |
| `packageId` | `dev.zen.<name>` | A code Android uses to tell apps apart. Like a fingerprint. Once installed, it can't change. (You can also set it on the command line with `--package`.) |
| `versionCode` | `1` | A *number* for Android to compare versions. Must increase when you update the app. Doubles/halves don't matter, just bigger. |
| `versionName` | `"1.0"` | The version *people see* — can be anything, like `"2.4 Beta"`. |
| `minSdk` | `26` | The *oldest* Android version your app will support. 26 covers roughly phones from 2017 onward. Lower = more phones, more work. |
| `targetSdk` | `35` | Which Android rules your app follows. Leave at 35 unless you know why you're changing it. |

Two notes:

- `packageId` characters become lowercase, and `-` becomes `_`.
- If you pass `--name` or `--package` on the command line, those **win** over
  the file.

### 6.2 `permissions/permissions.json` — letting your app use phone features

Android is nosey: apps have to *ask* before using the camera, microphone,
location, and so on. Each askable thing has a name like
`android.permission.CAMERA`.

If your JavaScript wants to use something extra (say, the camera stream via a
web API, or the mic), declare it here:

```json
[
  "android.permission.CAMERA",
  "android.permission.RECORD_AUDIO"
]
```

(A list is the simplest form. Zen also accepts
`{"permissions": ["...", "..."]}`.)

Zen adds each entry to the app's "manifest" — the official file that tells
Android what the app may use. Two things you must know:

1. **Declaring is not the same as allowing.** Declaring in this file just puts
   your app on the "could use camera" list. The *actual* yes/no is asked at
   runtime, by you, in JavaScript:

   ```js
   if (Zen.checkPermission("android.permission.CAMERA")) {
     // the user already said yes
   } else {
     Zen.requestPermission("android.permission.CAMERA");
     // the system asks the user; check Zen.checkPermission again later
   }
   ```

2. Some permissions don't need this file because Zen already declares them:
   internet, location, notifications, foreground service, mock location,
   overlays, wake lock. You only add the ones *you* need beyond those.

### 6.3 `icons/app-icon.png` — the picture on the home screen

Put a square PNG (say 512×512) at `icons/app-icon.png` inside the zip. Zen uses
it as the app's launcher icon — the picture people tap.

- If you include it, it replaces Zen's built-in blue **Z** icon.
- If you leave it out, you get the built-in **Z**, and that's totally fine.

---

## 7. What happens when you run `zen.py build`

It's worth knowing, in order, what's going on — it makes confusing errors much
easier to read.

1. **Unzip.** Zen opens your zip into a scratch folder.
2. **Kind-of-rename `.zen` files.** If a file is named `something.zen`, Zen
   renames it to `something.js`, and also rewrites every `word.zen` it finds
   inside text files to `word.js`. (See [section 9](#9-the-zen-file-trick).)
3. **Read the special folders** — `icons/`, `permissions/`, `package/` — and
   apply icon, permissions and manifest settings.
4. **Copy the template.** Zen keeps a ready-made "skeleton" Android project. It
   copies the skeleton and stamps your settings into it: your label, your
   package id, your versions, your permissions.
5. **Drop in your webpage.** Your (renamed) files become the app's bundled
   content, at `assets/web/`.
6. **Summon Gradle.** Zen starts Gradle with `assembleDebug`. Gradle downloads
   any small missing pieces the first time, compiles the Java + your assets, and
   produces `app-debug.apk`.
7. **Deliver.** Zen copies that APK to `out/YourApp.apk` and tells you how long
   it took.

The first build of a new machine is slower (Gradle downloads its parts once).
Later builds are faster. `BUILD OK in 8.9s` is normal once things are warm.

---

## 8. How your page is different from a normal website

This is the section that saves people hours of confusion. Your page is a normal
website in *most* ways — HTML, CSS, JavaScript all work exactly the same. But a
few things are different, because the page lives on the phone, offline, inside
a WebView.

### 8.1 It's offline

Normal website ➜ files live on a server on the internet. Zen app ➜ files live
**inside the app itself**.

Consequences:

- The app works with no internet at all. Ever.
- Things like `https://cdn.example.com/library.js` or `<img src="https://...">`
  will only load when (a) there is internet AND (b) your code points at
  `https://` URLs. (`http://` is blocked by default.)
- **Rule of thumb:** put every file you need inside the zip. Relative links like
  `<img src="duck.png">` or `fetch("data.json")` always work because the files
  are right there next to `index.html`.

### 8.2 The page has its own "virtual address"

The WebView pretends your page lives at a special fake address:

```
https://appassets.androidplatform.net/assets/web/
```

You never type this, but it matters for two things:

- Relative links and `fetch()` work (same-folder = same fake address). ✓
- Tools that check "is this a real website?" will see a fake address. If your
  code does things like `if (location.hostname === "appassets.androidplatform.net")`,
  that's a reliable way to detect "I'm inside the app, not a browser".

### 8.3 No address bar, no tabs, no "back to the internet"

The user sees only your page. There is:

- no address bar,
- no navigation buttons,
- no tabs,
- no way to leave (except pressing the phone's Home button),
- and no way for them to open other websites from your app.

Pressing the phone's Back button just sends the app to the background (like
pressing Home).

### 8.4 Almost everything still works

Still works the same as any website:

- all of CSS, flexbox, animations...
- DOM, events, `addEventListener`...
- `setTimeout`, `setInterval`, `fetch` (to bundled files), `XMLHttpRequest`,
  WebSockets (with internet),
- `localStorage`, IndexedDB (saved per app, survives restarts).

### 8.5 These do NOT work (browser-only things)

| Browser feature | Zen app |
|-----------------|---------|
| Opening tabs / `window.open` / `target="_blank"` | Does nothing (no tabs anywhere) |
| Sending the user to another website | Not possible from the UI |
| **Service Workers** | Not available |
| **Web Push / FCM notifications** | Not available (use `Zen.notify`) |
| The website-`Notification` object | Blocked (use `Zen.notify`) |
| `navigator.geolocation` | Unreliable in WebView (use the `Zen` location functions) |

### 8.6 A little warning about `window.Zen` calls

Every `Zen.*` call is a phone order shouted through a tiny window into the app's
Java brain. Important details:

- The calls are **synchronous**. `Zen.getLocation()` gives you the answer
  *immediately*.
- They return only **strings, numbers, booleans** (no promises, no functions).
- If a call is slow (like `Zen.runRoot("...")`), the page waits until it's
  done. Keep heavy calls short.
- Streams (things that repeat) are delivered to a global function *you* define,
  like `window.__zenOnLocation`.

---

## 9. The `.zen` file trick

Zen lets you name your JavaScript files `*.zen` instead of `*.js`. When you
build, Zen:

1. renames `app.zen` → `app.js`, and
2. rewrites references inside text files, so
   `<script src="app.zen"></script>` becomes `<script src="app.js"></script>`.

Why does this exist? So your webpage can live alongside, say, a website project
that already uses `*.js` files without any mix-ups — or just because you like
it. You can ignore it completely and name everything `.js`. Zen treats both the
same.

---

## 10. The `window.Zen` API, every function explained

This is the heart. Copy-paste the examples, tweak the values, and you're on
your way. Each function is explained with:

> `Zen.something(argument)` — **returns** *type*

---

### 10.1 `Zen.version()` — what's under the hood

```js
Zen.version();   // "1.1"
```

- **Returns:** a string.
- **Point:** mostly for you to confirm whether you're on a real phone (`"1.1"`)
  or in the IDE preview (`"1.1-debug"` — note the `-debug`).

---

### 10.2 `Zen.log(msg)` — write a note to the phone's log

```js
Zen.log("button pressed");
```

- **Args:** anything.
- **Returns:** nothing.
- **Point:** a `console.log` for Android. On a real phone these appear in
  `logcat` (see [section 13](#13-common-problems-and-how-to-fix-them) for how
  to read them). In the IDE they go to your browser's console.

---

### 10.3 `Zen.toast(msg)` — a little pop-up message

```js
Zen.toast("Saved!");
```

- **Args:** anything.
- **Returns:** nothing.
- **Point:** the small grey "flash" message at the bottom of the screen — the
  one that appears on top of everything and disappears on its own. Perfect for
  "done!", "error", "waiting..." feedback. In the IDE it becomes a small dark
  box at the bottom of the page instead.

---

### 10.4 `Zen.checkPermission(perm)` — have we already asked?

```js
let ok = Zen.checkPermission("android.permission.CAMERA");   // true or false
```

- **Args:** a permission name string, like `"android.permission.CAMERA"`.
- **Returns:** `true` (already allowed) or `false` (not yet).
- **Point:** checking without annoying the user. Always returns `true` in the
  IDE.
- **Important:** it only *checks*. To make the phone ask, use
  `Zen.requestPermission`.

---

### 10.5 `Zen.requestPermission(perm)` — ask the phone nicely

```js
let answer = Zen.requestPermission("android.permission.CAMERA");
// answer is "granted" (already allowed) or "requested" (question was asked)
```

- **Args:** a permission name string.
- **Returns:** `"granted"` or `"requested"`.
  - `"granted"` — the phone says yes (or it's an old Android that doesn't ask).
  - `"requested"` — a system pop-up appeared to the user. Zen does **not** wait
    for the answer; your code keeps running.
- **Point:** so your app can use the camera/mic/etc. The classic pattern:

```js
function useCamera() {
  if (Zen.checkPermission("android.permission.CAMERA")) {
    startCamera();
  } else {
    Zen.requestPermission("android.permission.CAMERA");
    setTimeout(function () {
      if (Zen.checkPermission("android.permission.CAMERA")) startCamera();
      else alert("Camera permission denied");
    }, 5000);
  }
}
```

---

### 10.6 `Zen.getLocation()` — where is the phone right now?

```js
let raw = Zen.getLocation();          // a STRING, e.g. '{"latitude":55.68,...}'
let fix  = (raw === "null") ? null : JSON.parse(raw);
if (fix) console.log(fix.latitude, fix.longitude);
```

- **Returns:** a string. Either `"null"` (never got a fix) or JSON text:
  `{"latitude", "longitude", "altitude", "accuracy", "speed", "bearing", "time"}`.
  `latitude`/`longitude` are the big ones; `time` is milliseconds-since-1970
  (see `new Date(fix.time)`).
- **Point:** a one-time, instant "where are we?" — from whatever the phone last
  knew. It does **not** wait for the GPS to warm up; use the streaming version
  below if you need fresh data.
- **Perms:** the app already declares location; the user can still have location
  off in Settings, in which case you get `"null"`.

---

### 10.7 `Zen.setLocationCallback(ms)` — stream the GPS live

```js
window.__zenOnLocation = function (loc) {
  console.log(loc.latitude, loc.longitude, loc.time);
};
Zen.setLocationCallback(1000);   // as often as ~every 1 second
```

- **Args:** `ms` — the *minimum* time between updates (can't go below ~100 ms).
- **Returns:** nothing.
- **Point:** gets you a fresh location again and again. Every time the phone has
  a fix, Zen calls the global `window.__zenOnLocation` you defined, with a real
  JavaScript object (not a string!).
- **Details:** listens on GPS, network, and "fused" providers — if one is off,
  the others still report. Calling it twice doesn't stack listeners; the first
  wins. In the IDE it uses the browser's real GPS (usually the computer's, or
  the phone's browser).

---

### 10.8 `Zen.clearLocationCallback()` — stop the GPS stream

```js
Zen.clearLocationCallback();
```

- **Returns:** nothing.
- **Point:** the opposite of `setLocationCallback`. Good hygiene: stop listening
  when your feature closes, to save battery.

---

### 10.9 `Zen.notify(title, body)` — a real notification

```js
Zen.notify("Order ready", "Your pizza is downstairs.");
```

- **Args:** `title`, `body` (strings).
- **Returns:** nothing.
- **Point:** a proper Android notification in the shade, with the app's icon.
  On Android 13+ the app asks for "notifications" permission on first launch —
  if the user taps "no", `Zen.notify` silently does nothing. In the IDE the
  browser's `Notification` is used (it also asks), and if that's blocked, it
  falls back to a toast.

---

### 10.10 `Zen.setMockLocation(lat, lng)` — fake the GPS

```js
let r = Zen.setMockLocation(55.6761, 12.5683);
if (r === "ok") console.log("phone now pretends to be there");
else console.log(r);   // an "ERROR: ..." message
```

- **Args:** `lat` (between -90 and 90), `lng` (between -180 and 180).
- **Returns:** `"ok"` or an `"ERROR: ..."` string.
- **Point:** makes the phone report that it's somewhere it isn't. Useful for
  testing location-based stuff, or "teleporting" a game.
- **BIG RULE:** Android only lets apps do this if the app is chosen in
  `Developer options → Mock location app`. Until then you get an error string
  saying exactly that. See [section 12](#12-putting-it-on-a-real-phone) for the
  3-click setup. The IDE accepts the call but doesn't fake anything.

---

### 10.11 `Zen.isRoot()` — is this phone "rooted"?

```js
if (Zen.isRoot()) console.log("superpowers available");
else console.log("normal phone");
```

- **Returns:** `true` or `false`.
- **Point:** many phones are "rooted" (jailbroken, basically) and have a
  command called `su`. This just checks if `su` answers. Always `false` in the
  IDE, and harmless on normal phones.

---

### 10.12 `Zen.runRoot(cmd)` — run a command as root

```js
let res = JSON.parse(Zen.runRoot("getprop ro.build.version.release"));
if (res.exit === 0) console.log("Android version:", res.out);
```

- **Args:** one shell command string.
- **Returns:** a string holding JSON — `{"exit": number, "out": "text"}`. `exit`
  is the command's result code (0 = clean success), `out` is everything the
  command said.
- **Point:** control the phone at a low level (needs root). **Caution:** this is
  a powerful hammer — treat `cmd` as untrusted input, never paste user text into
  it carelessly.
- **Note:** this call blocks until the command finishes. Keep the command quick.
- **In the IDE:** returns `{"exit":-1,"out":"not available in debug"}`.

---

### 10.13 `Zen.showOverlay(text)` — text that floats above everything

```js
Zen.showOverlay("Recording...");
```

- **Args:** a string.
- **Returns:** nothing.
- **Point:** places a small label at the top-center of the *entire screen*, even
  above other apps — like a floating "live" badge. Calling it again with new
  text just updates the same badge.
- **Permission:** needs "display over other apps". First call tries to ask for
  it; if that pop-up happened, call again after the user allows. In the IDE it's
  a box stuck to the top of the page.

---

### 10.14 `Zen.hideOverlay()` — remove the floating text

```js
Zen.hideOverlay();
```

- **Returns:** nothing.
- **Point:** takes down whatever `Zen.showOverlay` put up. No-op when nothing's
  showing.

---

### 10.15 `Zen.startBackground()` — keep running when you leave the app

```js
Zen.startBackground();   // called while the page is visible
```

- **Returns:** nothing.
- **Point:** starts a "foreground service" — Android's way of saying "this app
  is doing something important, keep it alive". It shows a permanent tiny
  notification and keeps a hidden copy of your page running, so your timers and
  code continue while the app is in the background or the screen is off.
- **Very important rules:**
  - Call it while your page is visible (Android blocks starting it from the
    background).
  - Android 14+ limits such services to about **6 hours per day**.
  - The hidden copy is a *second* instance of your page — avoid duplicate work,
    or write your code so running twice is harmless.
  - In the IDE it just prints a console note.

---

### 10.16 `Zen.stopBackground()` — stop the background copy

```js
Zen.stopBackground();
```

- **Returns:** nothing.
- **Point:** the polite way to stop the service and remove its notification.
  No-op in the IDE.

---

### 10.17 Pattern: "auto-repeat in background"

A small realistic recipe combining bits above — a location logger that keeps
running after you leave the app:

```js
window.__zenOnLocation = function (loc) {
  var log = JSON.parse(localStorage.getItem("log") || "[]");
  log.push({ lat: loc.latitude, lng: loc.longitude, t: loc.time });
  localStorage.setItem("log", JSON.stringify(log.slice(-200)));
  Zen.notify("Logged", "position " + loc.latitude.toFixed(4));
};

Zen.setLocationCallback(30000);      // every ~30 s
Zen.startBackground();               // survive leaving the app
```

---

## 11. Testing on your computer first (the Zen IDE)

"You can test apps in the browser" is a life-saver. The IDE extracts your zip
**exactly** like a build would (same `.zen`→`.js` renaming, same layout), then
serves it to your browser with a fake-but-mostly-real `window.Zen`.

### Run it

```
py zen.py ide HelloApp.zip
```

Then open **http://127.0.0.1:8765/** in Chrome/Firefox/Edge. To stop: press
**Ctrl+C** in the terminal.

Options: `--port 9000` changes the port, `--name` the title shown, `--out`
where the preview files go.

### What's real vs. fake in the IDE

| `Zen.*` | Real phone | IDE (browser) |
|---------|-----------|---------------|
| `getLocation` | phone's GPS last fix | browser GPS cache |
| `setLocationCallback` | GPS/network/fused listeners | browser `watchPosition` (real GPS!) |
| `toast` | Android toast | floating box in the page |
| `notify` | system notification | browser `Notification` |
| `setMockLocation` | mock providers | answers `ok`, does nothing |
| `isRoot` | checks `su` | `false` |
| `runRoot` | runs `su -c ...` | `{"exit":-1,...}` |
| `showOverlay`/`hideOverlay` | real system overlay | div stuck to page |
| `startBackground`/`stopBackground` | real service | console notes |
| `checkPermission`/`requestPermission` | real runtime ask | always `true`/`"granted"` |
| `version` | `"1.1"` | `"1.1-debug"` |

**Workflow we recommend:**

1. `py zen.py ide myapp.zip` — click through, watch the console.
2. Fix bugs.
3. `py zen.py build myapp.zip` — one command, done.

One nerd-note: the IDE injects a script into `index.html` at
`__zen/zen-debug.js`. If your zip happens to contain a file with that exact
path, it gets overwritten *for the preview only* (never in your APK).

---

## 12. Putting it on a real phone

### Easiest way — copy the file

1. Plug in / ftp / email yourself the `out/YourApp.apk`.
2. On the phone, tap the file.
3. Allow "install from unknown sources" if asked, then **Install**.
4. Tap **Open** (or find the icon on your home screen).

### Fancier way — with a USB cable and `adb`

`adb` is a free tool that talks to Android phones (it ships with the Android
SDK, in `platform-tools`).

```
adb devices                    # does the phone show up?
adb install -r out\YourApp.apk # install it
```

### Enabling developer mode (you need this once)

For mock location especially:

1. Open **Settings → About phone**.
2. Tap **Build number** seven times. "You are now a developer!"
3. Back in Settings there's now **Developer options**.

### Using mock location

1. In **Developer options**, tap **Select mock location app**.
2. Pick your app.
3. Now `Zen.setMockLocation(...)` returns `"ok"`.

### Using `adb logcat` (reading `Zen.log`)

```
adb logcat -s Zen:*            # shows only your Zen logs, live
```

---

## 13. Common problems and how to fix them

### "ERROR: zip must contain index.html"
Your zip contains a *folder* called `HelloApp` at the top, and `index.html` is
inside it. Re-zip so `index.html` sits at the very top level. (Zip the *inside*
of the folder.)

### "Unsupported class file major version 70" (or any "major version" error)
Your `JAVA_HOME` points at a too-new JDK. Point `ZEN_JAVA` at a **JDK 17**
folder: `$env:ZEN_JAVA = "C:\...\jdk-17.0.20.1+1"`.

### "Python was not found" / the Store opens on Windows
Type `py`, not `python`. If `py` also fails, install Python and tick **"Add
python.exe to PATH"**.

### Build takes forever the first time
That's Gradle fetching its parts once. Rebuilds will be fast.

### `Zen.setMockLocation` returns an "ERROR: not selected..."
Done in [section 12](#12-putting-it-on-a-real-phone):
Developer options → Mock location app → pick your app. Then retry.

### `Zen.notify` does nothing on Android 13+
The user tapped "don't allow" for notifications. Reinstall or go to
`Settings → Apps → your app → Notifications` and switch it on.

### Images / libraries from the internet don't show
The app is offline. Put the files inside the zip, or use `https://` URLs (and
the phone needs internet). Plain `http://` is blocked by default.

### Two copies of my code are running / double notifications
You called `Zen.startBackground()`. The hidden service runs a *second* instance
of your page. Either avoid duplicate side effects, or stop the service with
`Zen.stopBackground()` when it's not needed.

### The screen never covers the very top/bottom
The page doesn't handle the notch/fullscreen insets. Add the standard
«viewport-fit=cover» + `env(safe-area-inset-*)` CSS padding to your page:

```html
<meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">
```

```css
body { padding-top: env(safe-area-inset-top); padding-bottom: env(safe-area-inset-bottom); }
```

---

## 14. Handy one-page API reference

```
version()               → "1.1" | "1.1-debug"
log(msg)                → writes to logcat / console
toast(msg)              → flash message
checkPermission(perm)   → true | false
requestPermission(perm) → "granted" | "requested"
getLocation()           → '{"latitude",...}' | "null"
setLocationCallback(ms) → calls window.__zenOnLocation(loc)
clearLocationCallback() → stops streaming
notify(title, body)     → system notification
setMockLocation(lat,lng)→ "ok" | "ERROR: ..."
isRoot()                → true | false
runRoot(cmd)            → '{"exit":n,"out":"..."}'
showOverlay(text)       → floating label
hideOverlay()           → remove label
startBackground()       → keep running in background
stopBackground()        → stop it
```

Location shape (object handed to `__zenOnLocation`; same fields in
`getLocation` JSON):

```js
{
  latitude: 55.6761, longitude: 12.5683, altitude: 0,
  accuracy: 5, speed: 0, bearing: 0, time: 1699999999999
}
```

---

## 15. License

Zen is released under the **MIT License** — you may use it, change it, and put
it inside your own projects, free and without worry. The full license text is
in the `LICENSE` file.