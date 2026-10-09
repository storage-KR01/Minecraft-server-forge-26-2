package local.hardcorereset;

import com.mojang.logging.LogUtils;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@Mod("hardcorereset")
public final class HardcoreReset {
    // Change this value, rebuild and restart the service to change the observation period.
    private static final int RESET_DELAY_SECONDS = 30;
    private static final int DISCONNECT_FLUSH_SECONDS = 1;
    private static final int[] NOTICES = {20, 10, 5, 4, 3, 2, 1};
    private static final Logger LOG = LogUtils.getLogger();
    private static final AtomicBoolean TRIGGERED = new AtomicBoolean();
    private enum State { NORMAL, ANNOUNCING, RESET_PENDING, MARKER_FAILED, SHUTTING_DOWN, SHUTDOWN_REQUESTED }
    // These Forge death/tick/player lifecycle callbacks run on the server thread.
    private static State state = State.NORMAL;
    private static MinecraftServer owner;
    private static UUID triggeringPlayerId;
    private static String triggeringPlayerName;
    private static final Set<UUID> deadPlayers = new HashSet<>();
    private static long resetDeadlineNanos;
    private static long shutdownDeadlineNanos;
    private static int nextNotice;

    public HardcoreReset() {
        // EventBus 7.0.5 skips monitor-only buses if its normal listener list is empty.
        LivingDeathEvent.BUS.addListener(event -> {});
        LivingDeathEvent.BUS.addListener((event, cancelled) -> {
            if (!cancelled) onDeath(event);
        });
        TickEvent.ServerTickEvent.Post.BUS.addListener(HardcoreReset::onServerTick);
        PlayerEvent.PlayerRespawnEvent.BUS.addListener(event -> onPlayerReturn(event.getEntity()));
        PlayerEvent.PlayerLoggedInEvent.BUS.addListener(event -> onPlayerReturn(event.getEntity()));
        LOG.info("[HARDCORE] hardcorereset 1.1.0 loaded; observation countdown {} seconds", RESET_DELAY_SECONDS);
    }

    private static void onDeath(LivingDeathEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        var server = player.level().getServer();
        if (!server.isDedicatedServer() || player.connection == null ||
            server.getPlayerList().getPlayer(player.getUUID()) != player) return;
        if (!TRIGGERED.compareAndSet(false, true)) {
            if (server == owner) deadPlayers.add(player.getUUID());
            LOG.info("[HARDCORE] Reset already pending; ignoring death from {} (first death: {})",
                player.getGameProfile().name(), triggeringPlayerName);
            return;
        }
        owner = server;
        triggeringPlayerId = player.getUUID();
        triggeringPlayerName = player.getGameProfile().name();
        deadPlayers.add(triggeringPlayerId);
        try {
            Path root = server.getServerDirectory().toRealPath();
            Path world = server.getWorldPath(LevelResource.ROOT).toRealPath();
            if (!world.startsWith(root) || world.equals(root))
                throw new IllegalStateException("World must be a child of the server directory");
            // Preserve the existing canonical-path + newline protocol exactly.
            Path marker = root.resolve(".reset_world");
            byte[] bytes = (world + "\n").getBytes(StandardCharsets.UTF_8);
            try (var channel = FileChannel.open(marker, StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
                var data = ByteBuffer.wrap(bytes);
                while (data.hasRemaining()) channel.write(data);
                channel.force(true);
            }
            state = State.ANNOUNCING;
            LOG.warn("[HARDCORE] Player {} ({}) died; reset scheduled in {} seconds; marker {}",
                triggeringPlayerName, triggeringPlayerId, RESET_DELAY_SECONDS, marker);
        } catch (Exception failure) {
            state = State.MARKER_FAILED;
            LOG.error("[HARDCORE] Cannot safely create reset marker; stopping on the next server tick. Operator intervention required.", failure);
        }
        // Return to vanilla ServerPlayer.die: normal message/screen/loot remain intact.
        // No halt, kick, respawn or gamemode change is performed in this callback.
    }

    private static void onServerTick(TickEvent.ServerTickEvent.Post event) {
        if (event.server() != owner) return;
        long now = System.nanoTime();
        if (state == State.ANNOUNCING) {
            // Vanilla finishes the death before our post-tick announcement. Grant the
            // full wall-clock period from here, instead of assuming 600 ticks = 30s.
            broadcast("[HARDCORE] " + triggeringPlayerName + " has died! The run is over.");
            broadcast("[HARDCORE] World reset in " + RESET_DELAY_SECONDS + " seconds.");
            resetDeadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(RESET_DELAY_SECONDS);
            state = State.RESET_PENDING;
            return;
        }
        if (state == State.MARKER_FAILED) {
            beginShutdown(false);
            return;
        }
        if (state == State.RESET_PENDING) {
            for (var player : List.copyOf(owner.getPlayerList().getPlayers())) keepDeadPlayerSpectator(player);
            long left = resetDeadlineNanos - now;
            if (left <= 0) {
                beginShutdown(true);
                return;
            }
            int seconds = (int) ((left + TimeUnit.SECONDS.toNanos(1) - 1) / TimeUnit.SECONDS.toNanos(1));
            boolean notify = false;
            while (nextNotice < NOTICES.length && seconds <= NOTICES[nextNotice]) {
                // Skip stale thresholds after a delayed tick; do not spam a backlog.
                if (NOTICES[nextNotice] < RESET_DELAY_SECONDS) notify = true;
                nextNotice++;
            }
            if (notify) {
                broadcast("[HARDCORE] World reset in " + seconds + (seconds == 1 ? " second." : " seconds."));
                LOG.info("[HARDCORE] {} seconds until world reset (first death: {})", seconds, triggeringPlayerName);
            }
        } else if (state == State.SHUTTING_DOWN && now - shutdownDeadlineNanos >= 0) {
            state = State.SHUTDOWN_REQUESTED;
            LOG.warn("[HARDCORE] Requesting Minecraft server shutdown (first death: {})", triggeringPlayerName);
            owner.halt(false);
        }
    }

    private static void onPlayerReturn(net.minecraft.world.entity.player.Player entity) {
        if (!(entity instanceof ServerPlayer player) || player.level().getServer() != owner) return;
        if (state == State.SHUTTING_DOWN || state == State.SHUTDOWN_REQUESTED) {
            player.connection.disconnect(disconnectReason());
            return;
        }
        if (state == State.ANNOUNCING || state == State.RESET_PENDING) keepDeadPlayerSpectator(player);
    }

    private static void keepDeadPlayerSpectator(ServerPlayer player) {
        // Covers replacement player instances/reconnects. Leave the death screen intact.
        if (deadPlayers.contains(player.getUUID()) && !player.isDeadOrDying() && !player.isSpectator()) {
            player.setGameMode(GameType.SPECTATOR);
            LOG.info("[HARDCORE] Keeping {} as Spectator until reset", player.getGameProfile().name());
        }
    }

    private static void beginShutdown(boolean resetReady) {
        state = State.SHUTTING_DOWN;
        broadcast(resetReady ? "[HARDCORE] Resetting world now..." :
            "[HARDCORE] Reset marker failed. Server stopping; contact the administrator.");
        var players = List.copyOf(owner.getPlayerList().getPlayers());
        LOG.warn("[HARDCORE] Disconnecting {} players (first death: {})", players.size(), triggeringPlayerName);
        Component reason = resetReady ? disconnectReason() : Component.literal(
            "Hardcore run failed. Reset marker could not be created. Contact the administrator.");
        for (var player : players) {
            try {
                // Vanilla sends ClientboundDisconnectPacket and closes after its send callback.
                player.connection.disconnect(reason);
            } catch (Exception failure) {
                LOG.error("[HARDCORE] Disconnect failed for {}", player.getGameProfile().name(), failure);
            }
        }
        shutdownDeadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(DISCONNECT_FLUSH_SECONDS);
    }

    private static Component disconnectReason() {
        return Component.literal("Hardcore run failed.\nFirst death: " + triggeringPlayerName +
            ".\nA new world is being generated.\nReconnect in a few seconds.");
    }

    private static void broadcast(String message) {
        owner.getPlayerList().broadcastSystemMessage(Component.literal(message), false);
    }
}
