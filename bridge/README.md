# ØchreCL Game Bridge 0.1.0 alpha

Optional client bridge for the Minecraft 26.x family. First native test target: **26.2 vanilla main class, no Fabric/Forge**. Other 26.x versions remain unverified and negotiate actual public API availability; there is no blanket compatibility guarantee.

The launcher asks the player to install and authorize this component for one instance. It is attached as a Java agent on the next game launch. It is not a Fabric JAR to copy into `mods`, and does not modify Minecraft archives. Disable it in 操作游戏 → 停用组件; the current game must exit to fully remove the in-process component.

Build: `python3 packaging/build-bridge.py` from the project root. Run it before Gradle when changing bridge source. The bundled resource is `HMCL/src/main/resources/assets/bridge/OchreBridge.jar`.

## Protocol 1

One bounded request/response per TCP connection to 127.0.0.1. Frame: big-endian magic `0x4f434852`, int field count (0–256), then int UTF-8 byte lengths and byte data for each key/value. Each field ≤32 KiB and total text ≤128 KiB. No Java object deserialization, HTTP, shell, script execution, arbitrary file or outbound network API.

Requests include `protocol=1`, `command`, and the per-launch 256-bit token. Tokens are owner-only metadata files, never part of JVM command arguments. Startup `-javaagent` argument carries only a Base64-encoded config path. Endpoint records contain port, process ID and protocol. Each instance has independent consent and credentials. The launcher checks process liveness and reauthenticates every request, including after launcher restart.

Commands:

- `hello`: actual game version, component version, and capabilities.
- `state.read`: language, whether a world exists, and current player position/angles/UUID if available. No remote player data is fabricated.
- `options.get`: supported sound/video values, using protocol option keys.
- `options.set`: whitelisted `option.<key>` fields only. Validates all names and values before mutation; runs through the actual client Executor. Supported keys: `soundCategory_master`, `soundCategory_music`, `soundCategory_weather`, `renderDistance`, `maxFps`, `gamma`, `fov` (normalized -1..1), `fullscreen`. Game options are saved through their public API.

Responses include `ok`, protocol, actual game version, component version, and capabilities; errors do not imply a successful mutation. Unknown commands/scopes and invalid tokens are rejected. Game thread timeout cancels a request that has not begun executing. Two handler threads and a four-request bounded queue avoid unbounded background load.

Future script engine, time/inventory/nearby-entity APIs, camera/movement control and pathfinding need separate capabilities and permissions; currently unsupported. The script workspace only saves text drafts. Protocol versions must remain explicit; unknown major versions fail closed rather than being treated as compatible.

## Validation

`python3 bridge/smoke-native.py` creates a temporary game directory and uses a fake offline account. It tests installed 26.2 client classes with no mod-loader entry point, performs a real handshake, reads/writes master volume, gamma, FOV and render distance, checks wrong-token and unknown-command rejection, then terminates only its own child process. No real world or account is used. Report: `build/native-smoke.json`. The required local official 26.2 assets, libraries and Java 25 must already exist at the paths in the test script.

Windows and Linux native gameplay bridge testing remains outstanding. Automated launcher tests cover protocol bounds, version gating, options-file preservation, update verification and server import preservation separately.
