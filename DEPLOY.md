# Getting this running

Two things need doing on your machine. Neither needs a paid account.

## 1. The portal — about 3 minutes

The portal is one self-contained HTML file with the Supabase URL and anon key
already baked in, so it works the moment it is served. It only needs a **secure
origin**, because WebAuthn refuses to run otherwise. `https://anything` and
`http://localhost` both count. A LAN address like `http://192.168.1.7` does
**not**, which is why local network hosting is the wrong approach here.

### Quickest path, no account

```bash
cd portal
python3 -m http.server 8000
```

Open `http://localhost:8000`. Touch ID on the Mac works from here.

That is enough for the whole demo: the laptop issues and displays the code, the
phone's camera reads it off the screen, and the app talks to Supabase directly
over the internet. The phone never needs to reach your laptop, so no network
between them is required.

### If you want a public URL

Any static host works. GitHub Pages is the one with no new account, since you
already have a repo:

1. Put `portal/index.html` at the root of a branch called `gh-pages`
2. Settings, Pages, source `gh-pages`
3. Wait a minute, then open `https://<user>.github.io/<repo>/`

Cloudflare Pages and Netlify both also do drag-and-drop with a free account.

**Enrol on whichever URL you will demo from.** WebAuthn credentials are bound to
the domain. A passkey created on `localhost` will not work on
`user.github.io`, and vice versa. Pick your demo URL first, then enrol.

## 2. The Android app — about 15 minutes, most of it waiting

There is no prebuilt APK in this drop. Building one needs the Android SDK and a
network to fetch Gradle and the dependencies, so it has to happen on your
machine.

1. Install Android Studio (free) and open the `android/` folder
2. Let it generate the Gradle wrapper and sync — first sync downloads a few
   hundred MB and takes the longest
3. Plug in a phone with USB debugging on, press Run

If Gradle cannot resolve `org.opencv:opencv:4.10.0`, that artifact name is
right but the version may have moved; check Maven Central and bump it in
`app/build.gradle.kts`. That is the single most likely first-build failure.

The app opens straight into the camera with the project already configured.
Open its settings once to set the place label, otherwise the scan log records
your phone model instead of a counter name.

## 3. Enrol a fingerprint

Open the portal, go to Settings, and under "Operator fingerprint" pick one:

- **Use this computer** — Touch ID on the Mac, or Windows Hello elsewhere
- **Use my phone** — shows a QR code; scan it and use the phone's sensor

Until you do this, the portal issues documents in unsigned demo mode and says
so on the issue screen. After enrolling, that banner disappears and each
document carries a real signature over its payload hash.

## 4. First end-to-end run

1. Portal, issue a document, sign with your finger
2. "Show full size on screen"
3. Point the app at the laptop screen
4. Watch the canvas fill in, then the verdict
5. Portal, scan log — the row appears, live

If step 4 stalls, the canvas overlay tells you where. Grey fiducials mean no
corner lock. A ring that reads as a solid arc means the code is displayed too
small. Spots landing between anchors mean the warp is mirrored.


