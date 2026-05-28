# Static debug APK update channel

We will implement the About-screen update feature first as a debug-only APK update channel backed by static files on the user's Aliyun server. A dedicated Gradle task, `:app:publishDebugUpdate`, will assemble the debug APK, inject a UTC timestamp `debugUpdateCode`, generate `latest.json`, upload the versioned APK plus metadata with system `ssh` / `scp`, and keep only the most recent debug APKs on the server.

This avoids running an API service before the channel needs one, keeps frequent debug publishes separate from Android `versionCode`, and lets the app compare debug update metadata without inflating the app's normal versioning. The initial source may use debug-only HTTP by bare IP so the channel can start before domain or certificate setup exists, but release builds must not expose this channel and a future release update channel should use independent metadata and at least HTTPS hosting.
