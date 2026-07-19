# GagMate — Android Code Review

**Scope:** Static review of `app/src/main/**` (54 Kotlin files, ~7,000 LOC) + Gradle config.
**Method:** Manual read of the data/protocol/repository/session/UI layers. No build was executed.
**Verdict:** The architecture is well-layered (Room + Retrofit + WebSocket + Compose), but the app as written **will not launch** due to one critical initialization-order bug, and carries a large block of dead duplicate code plus several medium correctness issues.

---

## 🔴 Critical (blocker — app cannot start)

### 1. `AppContainer.init()` reads `machineSession` before it is assigned
`app/.../data/repository/AppContainer.kt` (lines 43–54):

```kotlin
fun init(context: Context) {
    db = AppDatabase.getInstance(context)
    localRepo = LocalDataRepository(db)
    syncManager = SyncManager(localRepo, MachineRepository(), machineSession) // ← reads lateinit here
    machineSession = MachineSessionManager()                                  // ← assigned only here
    ...
}
```

`machineSession` is a `lateinit var`. Passing it as an argument evaluates the property getter **before** it is initialized → `UninitializedPropertyAccessException`. `AppContainer.init(this)` is called in `MainActivity.onCreate`, so this throws on **every launch** → the app crashes at startup (the installed `CrashLogger` will log it, but the process still dies).

**Fix — reorder so `machineSession` is created first:**
```kotlin
machineSession = MachineSessionManager()
syncManager = SyncManager(localRepo, MachineRepository(), machineSession)
sensorRepo  = SensorRepository(machineSession)
shotRepo    = ShotRepository(machineSession)
profileRepo = ProfileRepository(localRepo, MachineRepository(), machineSession, syncManager)
```

---

## 🟠 High

### 2. `GaggiuinoV3Client` is dead, duplicated code (~337 LOC) — and its profile parser is broken
`app/.../data/api/GaggiuinoV3Client.kt` is **never instantiated anywhere** (only its definition exists). It is a near-complete re-implementation of `MachineSessionManager` + `ProtoDecoder`: it re-declares `SystemState`, `SensorSnapshot`, `ShotSnapshot`, `ShotIndexEntry` and re-implements every protobuf parser, the WS listener, `buildProfileBytes`, etc.

Two concrete defects hide in this dead code (would bite if it were ever wired in):
- `handleDActProf()` never reads the profile **name** (the `name` local stays `""`) and skips the name bytes with `offset += nameLen` from the wrong base, so phase parsing starts inside the name string.
- `updateActiveProfile()` always sends a **hardcoded** `cachedPhases` template, overwriting the real profile phases.

**Fix:** Delete `GaggiuinoV3Client` entirely (the live path is `MachineSessionManager` + `ProtoDecoder`/`ProtoMessage`). If a second client is genuinely needed, it should reuse the shared `ProtoDecoder`.

### 3. WebSocket host never refreshes after a Settings change
`MachineSessionManager.start()` (line 84) reads `GgboardApiClient.getCurrentBaseUrl()` **once** at launch. In `SettingsViewModel.saveAndApply()` / `testConnection()` the new host/port is pushed to `GgboardApiClient.updateBaseUrl(...)` (REST only). There is **no path** to re-read the URL into the live WebSocket, so after changing the machine IP in Settings the REST client points at the new host while the WebSocket keeps the old (or default) host. The only way to apply it is to kill and restart the app.

**Fix:** Add `MachineSessionManager.restart(host)` (cancel reconnect, close socket, re-read URL, reconnect) and call it from `saveAndApply()`.

---

## 🟡 Medium

### 4. `SyncManager.syncShots()` iterates `1..latestShotId` with one sequential HTTP call per shot
`app/.../data/repository/SyncManager.kt` (lines 180–197):
```kotlin
for (shotId in 1..latestIdInt) {
    val detail = machineRepo.getShotDetail(shotId.toString()).getOrNull()
    ...
}
```
If the latest shot id is large (hundreds/thousands), this issues that many blocking sequential requests (each up to the 10 s read timeout) and hammers the machine. Also deleted shot ids return 404 and are silently skipped. **Fix:** use the machine's range/pagination endpoint, or at minimum cap the count and run with bounded concurrency.

### 5. `GgboardApiClient` debug log uses escaped `$` → logs literal text
`app/.../data/api/GgboardApiClient.kt` (line 59):
```kotlin
DebugLogState.add("HTTP \${request.method}", "\$path \${response.code} \${responseBody.take(120)}")
```
The backslashes make these literal strings, so the in-app debug overlay shows `${request.method}` etc. instead of real values. **Fix:** drop the backslashes (`"HTTP ${request.method}"`).

### 6. WebSocket host extraction only strips `http://`
`MachineSessionManager.start()` line 84: `getCurrentBaseUrl().removePrefix("http://").removeSuffix("/")`. An `https://` base URL would produce `ws://https://host/ws` (malformed). **Fix:** parse scheme/host/port with `URI(...)` or a regex that handles both schemes.

### 7. `onClosed` does not schedule a reconnect
In `MachineSessionManager.connect()` only `onFailure` calls `scheduleReconnect`. A normal (non-1000) close from the machine sets `DISCONNECTED` and leaves the app **permanently disconnected** until restart. **Fix:** also `scheduleReconnect` from `onClosed` unless the close was initiated by `stop()`.

### 8. Reconnect leaks an `OkHttpClient`/dispatcher per attempt
Each `connect()` builds a brand-new `OkHttpClient`; the previous client's executor is only shut down in `stop()`. On a persistently-offline machine this leaks executors over time. **Fix:** create the `OkHttpClient` once (in `start()`) and reuse it; only replace the `WebSocket`.

### 9. `SettingsRepository.getConnectionUrl()` returns a hardcoded default and is unused
`app/.../data/repository/SettingsRepository.kt` (lines 54–56) always returns `http://192.168.0.186:80/` regardless of saved settings. It is also never called (dead code). **Fix:** remove it, or return the saved `host`/`port`.

---

## 🟢 Low / Cleanup

- **10. Profile import newline handling is broken.** `ProfileRepository.parseProfilesJson` uses `"\\r\\n"` (a literal backslash-r-backslash-n, not real CR/LF) and `Regex("\\}\\s*\\\\n+\\s*\\{")` which matches a literal `\n`. The concatenated-profile JSON fallback will not split correctly. Use `"\r\n"` / `"}\s*\n+\s*{"`.
- **11. `ProfileRepository.deleteProfile`** wraps a single suspend call in a pointless `coroutineScope { }` and silently discards the `deleteProfile` `Result`. The `return@coroutineScope` only exits the lambda.
- **12. Duplicate sample-profile logic** in `ProfilesViewModel.createSampleProfile()` and `ProfileRepository.createSampleProfile()` (same 4 phases defined twice).
- **13. `ProfilesViewModel.importProfileFromJson`** doesn't use `use { }` → leaks the `InputStream`/`Reader` (and NPEs if `inputStream` is null) on read error.
- **14. `AppDatabase` uses `fallbackToDestructiveMigration()` + `exportSchema = false`** (version 3). Any future schema bump silently wipes all locally cached profiles/shots. Add real migrations before shipping.
- **15. `ProfileDao.getPendingUploads()` / count** query hardcodes `sync_status` string literals; brittle if the enum is renamed. Prefer passing the enum or a constant.
- **16. Unused leftovers from refactors:** `GaggiuinoV3Client`, `ProfilesResponse`, `ApiResponse` (model), `SettingsRepository.getConnectionUrl()`. Prune to reduce confusion.
- **17. UI collection lifecycle.** `DashboardViewModel` subscribes to global session flows in `init{}`. Because the flows are app-lifetime singletons, collection runs even when the Dashboard isn't composed. Prefer collecting with `repeatOnLifecycle(STARTED)` / `collectAsStateWithLifecycle` inside the composable. (Low risk today since the VM is destination-scoped and old collectors cancel on dispose.)
- **18. Logging hygiene.** `HttpLoggingInterceptor.Level.BODY` plus `NetworkLogger`/`ApiDebugLogger` write full request/response bodies to disk. Fine for LAN debugging, but guard these behind `BuildConfig.DEBUG` before release. Also confirm `network_security_config.xml` scopes `cleartextTrafficPermitted` to the LAN domain, not globally.

---

## ✅ What's good

- Clean layering: `api` / `protocol` / `local` (Room) / `repository` / `session` / `ui`. Repositories expose `Flow`s; the UI is Compose with `ViewModel`s.
- Sensible use of `StateFlow`/`SharedFlow`, `runCatching` in the repository network calls, and `tryEmit` for fire-and-forget WS frames.
- `CrashLogger` (uncaught-handler + device/version context) and a shareable combined log — genuinely useful for field debugging.
- Bilingual UI via `stringResource` + `LocaleHelper`, with a language switcher.
- The protobuf decoder is reasonably defensive (bounds checks, `try/catch` per message).

---

## 📋 Suggested fix order
1. **#1** reorder `AppContainer.init()` — unblocks the app.
2. **#3** make Settings changes reach the live WebSocket.
3. **#2** delete/consolidate `GaggiuinoV3Client`.
4. **#4–#8** sync scaling + reconnect robustness.
5. **#9, #10–#16** cleanup and dead-code removal.
6. **#14, #18** production hardening (migrations, release logging).
