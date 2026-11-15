# ØchreCL Game Bridge 0.3.0 alpha

Optional client bridge for the Minecraft 26.x family. Native validation: **26.2 vanilla and Fabric Loader 0.19.5**. Forge, NeoForge and Quilt have not been tested as complete installations. Other 26.x versions remain unverified and negotiate actual public API availability; there is no blanket compatibility guarantee.

The launcher asks the player to install and authorize this component for one instance. It is attached as a Java agent on the next game launch. It is not a Fabric JAR to copy into `mods`, and does not modify Minecraft archives. Disable it in 操作游戏 → 停用组件; the current game must exit to fully remove the in-process component.

Build: `python3 launcher-dev/scripts/build-bridge.py` from the project root. `gradle.command` also calls this before building the launcher. The bundled resource is `HMCL/src/main/resources/assets/bridge/OchreBridge.jar`.

## Protocol 1

One bounded request/response per TCP connection to 127.0.0.1. Frame: big-endian magic `0x4f434852`, int field count (0–256), then int UTF-8 byte lengths and byte data for each key/value. Each field ≤32 KiB and total text ≤128 KiB. No Java object deserialization, HTTP, shell, script execution, arbitrary file or outbound network API.

Requests include `protocol=1`, `command`, and the per-launch 256-bit token. Tokens are owner-only metadata files, never part of JVM command arguments. Startup `-javaagent` argument carries only a Base64-encoded config path. Endpoint records contain port, process ID and protocol. Each instance has independent consent and credentials. The launcher checks process liveness and reauthenticates every request, including after launcher restart.

Commands:

- `hello`: actual game version, component version, and capabilities.
- `state.read`: language, whether a world exists, and current player position/angles/UUID if available. No remote player data is fabricated.
- `options.get`: supported sound/video values, using protocol option keys.
- `options.set`: whitelisted `option.<key>` fields only. Validates all names and values before mutation; runs through the actual client Executor. Supported keys: `soundCategory_master`, `soundCategory_music`, `soundCategory_weather`, `renderDistance`, `maxFps`, `gamma`, `fov` (normalized -1..1), `fullscreen`. Game options are saved through their public API.

Responses include `ok`, protocol, actual game version, component version, and capabilities; errors do not imply a successful mutation. Unknown commands/scopes and invalid tokens are rejected. Game thread timeout cancels a request that has not begun executing. Two handler threads and a four-request bounded queue avoid unbounded background load.

The launcher development build interprets a bounded DSL and adds state/inventory/nearby-player reads and basic camera/input control behind separate per-run permissions. Precise limb transforms and pathfinding remain unavailable. Protocol versions must remain explicit; unknown major versions fail closed rather than being treated as compatible.

## Validation

`python3 launcher-dev/bridge/smoke-native.py` creates a temporary game directory and uses a fake offline account. It tests installed 26.2 client classes with no mod-loader entry point, performs a real handshake, reads/writes master volume, gamma, FOV and render distance, checks wrong-token and unknown-command rejection, then terminates only its own child process. No real world or account is used. Reports: `build/native-smoke-vanilla.json` and `build/native-smoke-fabric.json`. Pass `--loader fabric` to exercise the real Knot/Mixin entry point with an empty temporary mods directory. The required local official 26.2 assets, libraries and Java 25 must already exist at the paths in the test script.

Windows and Linux native gameplay bridge testing remains outstanding. Automated launcher tests cover protocol bounds, version gating, options-file preservation, update verification and server import preservation separately.

## Script runner development

The launcher now interprets an English, brace-based `.ochre` DSL. It never executes JavaScript/Python/Lua or exposes arbitrary Java calls. See `hmcl-source/docs/OCHRE_SCRIPT.md`. New registered commands: `script.inventory`, `script.players`, `script.look`, `script.move`, `script.hit`, `script.release`. Per-run launcher permissions are separate from component consent. Movement input uses an owner-scoped 750ms lease and an independent watchdog, including idempotent toggle-sprint release. Target limb transforms and pathfinding remain explicitly unavailable. The server retains normal authority over interaction results.

`test/io/ochre/bridge/ScriptActionsTest.java` is a standalone contract test with inert fake client objects. It verifies state, inventory identifiers, sorted target selection, rotation, ownership, lease expiry, toggle sprint, and refusal to steal manual input. Native 26.2 validation so far covers handshake/options and rejecting actions outside a world, not full in-world script automation.


## Mod-loader dependency isolation

The agent relocates ASM core/tree into `io.ochre.bridge.internal.asm`, including every class reference and descriptor. It exposes no `org/objectweb/asm` classes, loader service discovery entries or module descriptor. Builds validate the outer and nested archives and retain the ASM license. Input fingerprints include source files, packaging code, dependencies and the bootstrap callback source.

Only the small JDK-only `io.ochre.bridge.bootstrap.v1.OverlayHooks` callback archive is appended to the bootstrap search. The agent, ASM and game APIs remain outside that archive. Method handles bind callbacks to the original agent helpers, avoiding a duplicate bridge class in Knot or other isolated loaders. Named game modules receive only a read edge to the callback module when needed. Hook signatures and helper visibility are checked before transformation; a mismatch keeps original game bytes. Loader classes are never transformed, and class-path isolation is never disabled.

`python3 launcher-dev/bridge/test-loader-isolation.py` checks all 10 locally installed ASM generations (9.1 through 9.10.1), the actual Fabric `LoaderUtil.verifyClasspath`, private transformer operation, mismatched game signatures, and an intentionally inaccessible helper. It does not claim complete Forge/NeoForge/Quilt gameplay validation. Existing consented, checksum-valid bridge images refresh from the launcher bundle before the next game process starts; disabled consent and corrupt images remain rejected. Running game images remain in place.
