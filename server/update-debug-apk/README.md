# Debug APK Update Channel

This directory documents the static Aliyun hosting shape used by
`:app:publishDebugUpdate`.

## Server layout

Prepare a static web root like this:

```text
/var/www/everythingdone-updates/
└── debug/
    ├── latest.json
    └── apk/
        └── app-debug-<debugUpdateCode>.apk
```

The app reads:

```text
http://<host>/everythingdone-updates/debug/latest.json
```

`latest.json` points at the current versioned APK file under `debug/apk/`.
The publish task keeps only the five newest debug APK files by default.

## Local Gradle configuration

Keep connection details in untracked `local.properties`:

```properties
everythingdone.update.host=1.2.3.4
everythingdone.update.user=root
everythingdone.update.remoteDir=/var/www/everythingdone-updates
everythingdone.update.baseUrl=http://1.2.3.4/everythingdone-updates
everythingdone.update.sshKey=C:\\Users\\ywwynm\\.ssh\\aliyun_ed25519
# everythingdone.update.port=22
# everythingdone.update.metadataUrl=http://1.2.3.4/everythingdone-updates/debug/latest.json
```

`metadataUrl` is optional. If omitted, debug builds use
`<baseUrl>/debug/latest.json`.

## Publish

Short notes:

```powershell
.\gradlew.bat :app:publishDebugUpdate -PdebugUpdateNotes="Debug build notes"
```

Long notes:

```powershell
.\gradlew.bat :app:publishDebugUpdate -PdebugUpdateNotesFile=memory\debug-update-notes.md
```

The notes file takes precedence when both note inputs are present.

## HTTPS hardening

The initial channel may use debug-only HTTP by bare IP. Before exposing update
support to release builds, create an independent release channel and host it
over HTTPS.
