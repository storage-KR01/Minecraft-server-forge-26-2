package local.hardcoreresettest;

import com.mojang.authlib.GameProfile;
import io.netty.channel.ChannelFutureListener;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.common.ClientboundDisconnectPacket;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.listener.Priority;
import net.minecraftforge.fml.common.Mod;
import java.nio.file.*;
import java.util.*;
import java.util.function.Predicate;

// TEST FIXTURE ONLY: installed solely inside a private mount/PID namespace.
@Mod("hardcoreresettest")
public class ResetIntegration {
    private static MinecraftServer server;
    private static Path root, world;
    private static int stage;
    private static long deathTime;
    private static ServerPlayer first, second, survivor;
    private static List<ServerPlayer> players;
    private static Map<UUID, ServerPlayer> playersById;
    private static final List<RecordingConnection> connections = new ArrayList<>();
    private static boolean respawnChecked, loginChecked, flushChecked, completed;
    private static int milestone;
    private static final int[] ALIVE_MILESTONES = {5, 15, 29};

    public ResetIntegration() {
        ServerStartedEvent.BUS.addListener(ResetIntegration::start);
        TickEvent.ServerTickEvent.Post.BUS.addListener(Priority.LOWEST, ResetIntegration::tick);
        ServerStoppingEvent.BUS.addListener(event -> {
            if (players != null) { players.clear(); playersById.clear(); }
        });
    }
    private static void require(boolean pass, String message) {
        if (!pass) throw new AssertionError(message);
        System.out.println("[RESET TEST] PASS " + message);
    }
    private static Object modField(String name) throws Exception {
        var f = Class.forName("local.hardcorereset.HardcoreReset").getDeclaredField(name);
        f.setAccessible(true);
        return f.get(null);
    }
    public static class RecordingConnection extends Connection {
        private final io.netty.channel.embedded.EmbeddedChannel testChannel = new io.netty.channel.embedded.EmbeddedChannel();
        @Override public io.netty.channel.Channel channel() { return testChannel; }
        @Override public java.net.SocketAddress getRemoteAddress() { return new java.net.InetSocketAddress("127.0.0.1", 0); }
        final List<String> chat = new ArrayList<>();
        long announcedAt, kickedAt;
        int kicks;
        String reason;
        RecordingConnection() { super(PacketFlow.SERVERBOUND); }
        private void record(Packet<?> packet) {
            if (packet instanceof ClientboundSystemChatPacket message) {
                String text = message.content().getString();
                chat.add(text);
                if (text.equals("[HARDCORE] World reset in 30 seconds.")) announcedAt = System.nanoTime();
            }
            if (packet instanceof ClientboundDisconnectPacket disconnect) {
                kicks++;
                if (kickedAt == 0) kickedAt = System.nanoTime();
                reason = disconnect.reason().getString();
            }
        }
        @Override public void send(Packet<?> packet) { record(packet); }
        @Override public void send(Packet<?> packet, ChannelFutureListener listener) { record(packet); }
        @Override public void send(Packet<?> packet, ChannelFutureListener listener, boolean flush) { record(packet); }
        @Override public void flushChannel() {}
        @Override public void setReadOnly() {}
        @Override public void handleDisconnection() {}
        @Override public boolean isConnected() { return kickedAt == 0; }
    }
    private static ServerPlayer connect(String name) {
        var profile = new GameProfile(UUID.randomUUID(), name);
        var player = new ServerPlayer(server, server.overworld(), profile, ClientInformation.createDefault());
        var connection = new RecordingConnection();
        connections.add(connection);
        player.connection = new ServerGamePacketListenerImpl(server, connection, player,
            CommonListenerCookie.createInitial(profile, false));
        player.setInvulnerable(true);
        player.setGameMode(GameType.SURVIVAL);
        players.add(player);
        playersById.put(player.getUUID(), player);
        return player;
    }
    private static void start(ServerStartedEvent event) {
        server = event.getServer();
        root = server.getServerDirectory().toAbsolutePath().normalize();
        if (!Files.isRegularFile(root.resolve("ISOLATED_TEST_ONLY")))
            throw new IllegalStateException("Test refuses to run without isolation sentinel");
        try {
            world = server.getWorldPath(LevelResource.ROOT);
            Path stageFile = root.resolve("test-stage");
            stage = Files.exists(stageFile) ? Integer.parseInt(Files.readString(stageFile)) : 0;
            Files.writeString(root.resolve("seeds.txt"), server.overworld().getSeed() + "\n",
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            if (stage > 0) {
                require(!Files.exists(world.resolve("old-run-sentinel")), "failed world completely removed");
                require(!Files.exists(root.resolve(".reset_world")), "marker removed before restart");
            }
            Files.writeString(stageFile, Integer.toString(stage + 1));
            if (stage == 0) {
                Files.writeString(world.resolve("old-run-sentinel"), "wrapper test");
                Files.writeString(root.resolve(".reset_world"), world.toRealPath() + "\n");
                server.halt(false);
                return;
            }
            if (stage == 3) {
                require(Files.exists(root.resolve("COUNTDOWN_PASSED")), "countdown and shutdown assertions completed");
                require(Files.exists(root.resolve("CRASH_DURING_COUNTDOWN")), "crash during countdown recovered");
                var seeds = Files.readAllLines(root.resolve("seeds.txt"));
                require(seeds.size() == 4 && seeds.stream().distinct().count() == 4, "four unique random worlds");
                Files.writeString(root.resolve("TEST_PASSED"), "30s observation, packet broadcasts, respawn, duplicate deaths, flush, crash recovery\n");
                server.halt(false);
                return;
            }
            var listField = PlayerList.class.getDeclaredField("players");
            var mapField = PlayerList.class.getDeclaredField("playersByUUID");
            listField.setAccessible(true); mapField.setAccessible(true);
            @SuppressWarnings("unchecked") var list = (List<ServerPlayer>) listField.get(server.getPlayerList());
            @SuppressWarnings("unchecked") var map = (Map<UUID, ServerPlayer>) mapField.get(server.getPlayerList());
            players = list; playersById = map;
            var damage = server.overworld().damageSources().generic();
            var mob = new Zombie(EntityTypes.ZOMBIE, server.overworld());
            LivingDeathEvent.BUS.post(new LivingDeathEvent(mob, damage));
            require(!Files.exists(root.resolve(".reset_world")), "mob death ignored");
            var dummy = new ServerPlayer(server, server.overworld(), new GameProfile(UUID.randomUUID(), "Unconnected"), ClientInformation.createDefault());
            LivingDeathEvent.BUS.post(new LivingDeathEvent(dummy, damage));
            require(!Files.exists(root.resolve(".reset_world")), "unconnected synthetic player ignored");
            first = connect("FirstDeath");
            second = connect("SecondDeath");
            survivor = connect("Observer");
            var cancellation = LivingDeathEvent.BUS.addListener(Priority.LOWEST, (Predicate<LivingDeathEvent>) e -> true);
            LivingDeathEvent.BUS.post(new LivingDeathEvent(first, damage));
            require(!Files.exists(root.resolve(".reset_world")), "cancelled death ignored");
            LivingDeathEvent.BUS.removeListener(cancellation);
            Files.writeString(world.resolve("old-run-sentinel"), "failed run");
            deathTime = System.nanoTime();
            first.setHealth(0);
            first.die(damage);
            Path marker = root.resolve(".reset_world");
            require(Files.readString(marker).equals(world.toRealPath() + "\n"), "canonical marker created at death");
            require(server.isRunning() && connections.stream().allMatch(c -> c.kicks == 0), "death does not halt or disconnect");
            require(!first.isSpectator(), "death screen not bypassed by immediate spectator change");
            var markerTime = Files.getLastModifiedTime(marker);
            second.setHealth(0);
            second.die(damage);
            require(Files.getLastModifiedTime(marker).equals(markerTime), "second death leaves original marker untouched");
            require(modField("triggeringPlayerId").equals(first.getUUID()), "first player's identity retained");
            require(modField("state").toString().equals("ANNOUNCING"), "one reset pending before next tick");
        } catch (Exception failure) { throw new RuntimeException(failure); }
    }
    private static void tick(TickEvent.ServerTickEvent.Post event) {
        if (event.server() != server || (stage != 1 && stage != 2) || completed) return;
        try {
            long now = System.nanoTime();
            double elapsed = (now - deathTime) / 1e9;
            var observer = connections.get(2);
            if (!respawnChecked && elapsed >= 1) {
                for (var c : connections) {
                    require(c.announcedAt > 0, "client receives 30-second announcement");
                    require(c.chat.contains("[HARDCORE] FirstDeath has died! The run is over."), "client receives first player's name");
                    int vanilla = c.chat.indexOf("FirstDeath died");
                    int hardcore = c.chat.indexOf("[HARDCORE] FirstDeath has died! The run is over.");
                    require(vanilla >= 0 && vanilla < hardcore, "vanilla death message arrives before Hardcore announcement");
                }
                // Real Minecraft respawn pipeline fires the actual Forge respawn event.
                first = server.getPlayerList().respawn(first, false, Entity.RemovalReason.KILLED);
                require(first.isSpectator(), "real PlayerList.respawn keeps triggering player Spectator");
                require(survivor.gameMode.isSurvival(), "surviving observer remains Survival");
                respawnChecked = true;
            }
            if (!loginChecked && elapsed >= 2) {
                first.setGameMode(GameType.SURVIVAL);
                PlayerEvent.PlayerLoggedInEvent.BUS.post(new PlayerEvent.PlayerLoggedInEvent(first));
                require(first.isSpectator(), "relogin during countdown cannot bypass Spectator");
                loginChecked = true;
                if (stage == 2) {
                    require(server.isRunning() && connections.stream().allMatch(c -> c.kicks == 0), "crash occurs while countdown is still pending");
                    Files.writeString(root.resolve("CRASH_DURING_COUNTDOWN"), "hard exit 23 after death marker; before 30s\n");
                    Runtime.getRuntime().halt(23); // disposable namespace JVM ONLY
                }
            }
            if (milestone < ALIVE_MILESTONES.length && elapsed >= ALIVE_MILESTONES[milestone]) {
                require(server.isRunning() && connections.stream().allMatch(c -> c.kicks == 0), "all players still connected at " + ALIVE_MILESTONES[milestone] + "s");
                require(Files.exists(world.resolve("old-run-sentinel")) && Files.exists(root.resolve(".reset_world")), "world remains loaded and marker pending");
                milestone++;
            }
            if (observer.kickedAt != 0 && !flushChecked && now - observer.kickedAt >= 500_000_000L) {
                require(server.isRunning(), "server still running 0.5s after disconnect for packet flush");
                flushChecked = true;
            }
            if (!server.isRunning()) {
                for (var c : connections) {
                    require(c.kicks == 1, "each player disconnected exactly once");
                    require(c.kickedAt - c.announcedAt >= 30_000_000_000L, "full 30-second observation period before disconnect");
                    require(c.reason.contains("First death: FirstDeath") && c.reason.contains("Reconnect in a few seconds"), "clear disconnect reason sent");
                    require(now - c.kickedAt >= 1_000_000_000L, "at least 1 second between disconnect and shutdown");
                    for (int value : new int[]{30,20,10,5,4,3,2,1}) {
                        String msg = "[HARDCORE] World reset in " + value + (value == 1 ? " second." : " seconds.");
                        require(Collections.frequency(c.chat, msg) == 1, "countdown " + value + " sent once per client");
                    }
                    require(Collections.frequency(c.chat, "[HARDCORE] FirstDeath has died! The run is over.") == 1, "one failure announcement per client");
                }
                require(flushChecked && milestone == 3, "observation and flush checkpoints all ran");
                require(modField("state").toString().equals("SHUTDOWN_REQUESTED"), "single shutdown transition");
                Files.writeString(root.resolve("COUNTDOWN_PASSED"), "observationSeconds=" + ((observer.kickedAt-observer.announcedAt)/1e9) + "\nflushSeconds=" + ((now-observer.kickedAt)/1e9) + "\n");
                completed = true;
            }
        } catch (Exception failure) { throw new RuntimeException(failure); }
    }
}
