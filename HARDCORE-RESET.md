# Hardcore automatic world reset

Installed for Minecraft 26.2, Forge 65.1.3, Java 25 in `/home/ubuntu/minecraft-26-2`.

## Operation

Version **1.1.0** keeps the existing deletion protocol and adds a 30-second
observation period. A connected server player's uncancelled `LivingDeathEvent`
passes the process-wide `AtomicBoolean` guard, records their UUID/name, and creates
`.reset_world` immediately with the canonical loaded world path plus newline.
The death callback returns without halting, disconnecting, forcing respawn or
changing gamemode. Vanilla death processing and its normal chat message continue.

At the next `TickEvent.ServerTickEvent.Post`, the mod announces the first player's
name and grants a full 30 seconds from that announcement. It uses `System.nanoTime()`
and checks deadlines on server ticks, not an assumption of 20 TPS. Under a server
stall the deadline is handled on the next available tick; it is never shortened.
There is no `Thread.sleep()` or background Minecraft access. Chat reminders are sent
at 20, 10, 5, 4, 3, 2 and 1 seconds; delayed ticks skip stale reminders rather than
spamming a backlog. Additional deaths never change the first player's identity,
rewrite the marker, or create another countdown.

Dead players keep their normal death screen. When they respawn, the actual Forge
`PlayerRespawnEvent` puts them in Spectator. `PlayerLoggedInEvent` handles reconnects,
and a tick check keeps living replacement instances of dead players in Spectator
until reset. This also applies to players who die later during the countdown.
Survivors remain in their existing gamemode and can continue observing/moving.

At zero the mod announces the reset, sends each player a vanilla disconnect packet
with the first death's name and reconnect instructions, then waits at least one
second through ticks for packet flushing. Only then does the tick callback call
`MinecraftServer.halt(false)` once. Late joiners during shutdown get the same reason.
The mod only writes the marker; it does not delete world files, launch Java,
call systemd, or change the wrapper's behavior. A marker-write error fails closed:
log the problem, disconnect and stop via subsequent ticks for operator intervention.

Mobs, unconnected synthetic players, cancelled deaths, and integrated servers are
ignored. Metadata retains `displayTest="IGNORE_SERVER_VERSION"`; clients do not need
this utility JAR. No custom network channel or client resource is required.

`server-wrapper.sh` launches `hardcore-reset/lifecycle.py`. It uses the existing
`run.sh` and its JVM arguments. Only after the entire child process group exits does
it process the marker, permanently delete the configured world, remove the marker,
wait four seconds, and restart Forge. This removes dimensions, player inventories,
advancements, stats, world data and world-local datapacks together. It makes no world
backup. `level-seed` must remain empty so Minecraft chooses a fresh random seed.

`server.properties` is read by the Java helper using Java Properties and UTF-8,
matching Minecraft's parsing (including escaped keys and continued lines). The
wrapper defaults to `world` only when `level-name` is absent. For deletion safety,
world names must be one ordinary directory name: ASCII letters/numbers first,
then letters/numbers/spaces/underscore/dot/hyphen, at most 128 characters, no trailing
dot/space. Administrative directories are reserved. Nested paths, absolute paths,
traversal, symlinks and mounts are rejected. The marker must match that exact world;
changing `level-name` during a run blocks deletion. Another wrapper, live Java
process using this directory, or active POSIX `session.lock` blocks deletion.

## Commands

```bash
sudo systemctl start minecraft
sudo systemctl stop minecraft
sudo systemctl restart minecraft
sudo systemctl status minecraft --no-pager
journalctl -u minecraft -f
```

The service uses `Restart=on-failure`, `RestartSec=10`, `KillMode=mixed`, and
`TimeoutStopSec=120`. The wrapper owns death-triggered restarts; systemd owns crash
restarts. The old `SuccessExitStatus=0 1` was removed so actual exit-code-1 failures
are recognized. Exit 78 prevents restart loops on invalid paths/deletion failures.

A normal stop or crash without a death marker never deletes a world. A manual stop
while a death reset is pending preserves the marker and defers its processing until
the next start. A pending marker at startup is processed before loading the world.
If deletion fails, the marker stays and the service fails closed. Correct the cause,
then use `sudo systemctl reset-failed minecraft` and `sudo systemctl start minecraft`.
If the mod cannot create a valid marker, it logs the failure and stops; the operator
must resolve the problem. Do not create an empty marker: its content must identify
the loaded world exactly. A missing world with a valid marker is safe to resume.

The retired `reset-world.sh` now prints a notice and exits. Its former backup/move
behavior must not be used together with this wrapper. The original script and service
unit are retained as configuration references in `hardcore-reset/`; no world copy
was made.

## Source and builds

- Mod source: `hardcorereset-src/src/main/java/local/hardcorereset/HardcoreReset.java`
- Metadata: `hardcorereset-src/src/main/resources/META-INF/mods.toml`
- Built JAR: `hardcorereset-src/build/libs/hardcorereset-1.1.0.jar`
- Installed JAR: `mods/hardcorereset-1.1.0.jar`
- Lifecycle: `server-wrapper.sh`, `hardcore-reset/lifecycle.py`
- Properties helper: `hardcore-reset/ReadProperties.java` and `.class`
- Service source: `hardcore-reset/minecraft.service`
- Active unit: `/etc/systemd/system/minecraft.service`

```bash
cd /home/ubuntu/minecraft-26-2/hardcorereset-src
./gradlew --offline --no-daemon --max-workers=2 -p local-build build
cd /home/ubuntu/minecraft-26-2
javac -d hardcore-reset hardcore-reset/ReadProperties.java
python3 hardcore-reset/test_lifecycle.py
```

The reproducible local Gradle build compiles against this installation's exact Forge
65.1.3 server and library JARs, without downloading client dependencies. The full
Forge 65.1.3 MDK project is also retained at the source root; its remote dependency
build was not completed because downloads were slow. `build-1.1.0.log` records the
successful installed-library build. No remapping is needed for this unobfuscated
Minecraft 26.2 server.

Forge EventBus 7.0.5 skips monitor-only buses when its normal listener list is empty.
The mod registers a non-cancelling no-op listener plus the final cancellation-result
monitor, and this path was verified with actual `ServerPlayer.die()` calls in an
isolated server. The harness uses simulated player connections, not a human client.
Forge can also emit a crash report and exit with code 0; the wrapper treats a newly
written crash report as failure so systemd can restart it without wiping the world.

The test mod in `hardcore-reset/tests/` is ONLY for disposable private namespace
fixtures. Never copy `hardcoreresettest.jar` to the production `mods/` directory.

## Disable automatic resets

Stop the service, remove the utility JAR, and restore the original service launcher:

```bash
sudo systemctl stop minecraft
cd /home/ubuntu/minecraft-26-2
rm -- mods/hardcorereset-1.1.0.jar
sudo install -m 644 hardcore-reset/minecraft.service.original /etc/systemd/system/minecraft.service
sudo systemctl daemon-reload
sudo systemctl start minecraft
```

Restoring the direct launcher means it does not process any pending `.reset_world`.
Inspect and remove that marker before re-enabling automatic reset if you explicitly
intend to cancel an already-requested wipe. The restored original unit includes its
original `SuccessExitStatus=0 1`. Server hardcore mode remains enabled until you
explicitly change it. A normal restart with the wrapper and mod still installed does
not disable the feature.

## Change the countdown later

Edit `RESET_DELAY_SECONDS = 30` near the top of
`hardcorereset-src/src/main/java/local/hardcorereset/HardcoreReset.java`.
`DISCONNECT_FLUSH_SECONDS = 1` controls the separate packet-flush interval.
Then build and deploy the same version (the source constant is not a live setting):

```bash
cd /home/ubuntu/minecraft-26-2/hardcorereset-src
./gradlew --offline --no-daemon --max-workers=2 -p local-build build
sudo systemctl stop minecraft
cd /home/ubuntu/minecraft-26-2
install -m 644 hardcorereset-src/build/libs/hardcorereset-1.1.0.jar mods/hardcorereset-1.1.0.jar
sudo systemctl start minecraft
```

A death marker already created during a countdown is NOT cancelled by a manual
stop. The unchanged wrapper preserves it and processes it on the next start.
A process crash during the countdown also leaves the marker authorizing the wipe.

## Validation for 1.1.0

- `hardcore-reset/safety-tests-1.1.0.log`: existing destructive-path protections.
- `hardcore-reset/process-tests-1.1.0.log`: normal stop/crash, pending reset, exit codes.
- `hardcore-reset/integration-tests-1.1.0.log`: real Forge test JVMs with packet capture,
  real `ServerPlayer.die()` and `PlayerList.respawn()`, full countdown, duplicate
  deaths, reconnect/Spectator handling, one-second flush, and a forced JVM crash
  before the countdown expires followed by successful world regeneration.
- `hardcore-reset/validation-1.1.0.json`: measured timings and tested JAR checksum.
- `hardcore-reset/verification-1.1.0.txt`: deployment checks.

The integration harness uses simulated connections and captures outgoing packets;
it does not claim a visual test on human clients. Test worlds are freshly generated
inside private mount/PID namespaces, never copied from production. The test JAR
must never be installed into the live `mods/` directory.

To run the isolated suite after building the mod:

```bash
cd /home/ubuntu/minecraft-26-2
bash hardcore-reset/tests/build-test.sh
python3 hardcore-reset/tests/run-validation.py
```

API references: [Forge 26.2 tick events](https://github.com/MinecraftForge/MinecraftForge/blob/26.2/src/main/java/net/minecraftforge/event/TickEvent.java)
and [player lifecycle events](https://github.com/MinecraftForge/MinecraftForge/blob/26.2/src/main/java/net/minecraftforge/event/entity/player/PlayerEvent.java).
Signatures were also inspected directly in the installed 65.1.3 JARs.

