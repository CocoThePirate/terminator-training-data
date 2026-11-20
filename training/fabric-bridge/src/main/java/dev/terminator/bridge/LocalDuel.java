package dev.terminator.bridge;

import com.mojang.authlib.GameProfile;
import java.io.IOException;
import java.net.InetAddress;
import java.util.Map;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.InactivityFpsLimit;
import net.minecraft.server.WhitelistEntry;
import net.minecraft.server.integrated.IntegratedServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Uuids;

import static dev.terminator.bridge.ReachAndStop.*;

/** Owns a single normal-speed duel. All game mutations run on a game thread. */
final class LocalDuel {
    private static final long CONTROL_MAX_AGE = TimeUnit.MILLISECONDS.toNanos(225);
    private volatile String id = UUID.randomUUID().toString();
    private volatile CompletableFuture<Object> roundReset;
    private DuelSetup setup = DuelSetup.DEFAULT;
    private int resetDeadline;
    private final int gamePort;
    private final int tickLimit;
    private final boolean automated;
    private volatile boolean hostControllerReady, guestControllerReady;
    private boolean playersPrepared;
    private final GameProfile guest;
    private final CompletableFuture<Object> opening;
    private volatile IntegratedServer server;
    private volatile String phase = "waiting";
    private boolean openingScheduled;
    private int fightAt;
    private boolean reportedBotDamage;
    private int criticalHits;
    private int sprintHits;
    private SwordDrill.Telemetry botTelemetry = new SwordDrill.Telemetry();
    private SwordDrill.Telemetry humanTelemetry = new SwordDrill.Telemetry();
    private volatile Map<String, Object> serverMetrics = Map.of();
    private final List<Map<String, Object>> clientFrames = new ArrayList<>();
    private static final Controls NEUTRAL = new Controls(false, false, false, false, false,
            false, false, false, false, 0, 0, null);
    private long queuedBasedOn = -1;
    private String queuedStatus = "no_controls";
    private Map<String, Object> appliedControls = Map.of();
    private long appliedBasedOn = -1;
    private String appliedStatus = "no_controls";
    private int clientSprintStarts, clientSprintStops, sprintInputReleases, sprintInputRestarts;
    private boolean clientSprinting, previousSprintInput, releasedSprintInput;

    private long tick;
    private long observedTick;
    private long observedAt;
    private Controls queued;
    private boolean attackHeld;
    private boolean policyJumpPressed;
    private int jumpInputs;
    private int policyJumps;
    private int emptyJumps;
    private boolean reportedJumps;
    private boolean reportedMovement;
    private boolean reportedLook;
    private CompletableFuture<Object> poll;

    LocalDuel(int gamePort, String username, CompletableFuture<Object> opening) {
        this(gamePort, username, opening, 0);
    }

    LocalDuel(int gamePort, String username, CompletableFuture<Object> opening, int tickLimit) {
        this(gamePort, username, opening, tickLimit, false);
    }

    LocalDuel(int gamePort, String username, CompletableFuture<Object> opening, int tickLimit, boolean automated) {
        this(gamePort, username, opening, tickLimit, automated, DuelSetup.DEFAULT);
    }

    LocalDuel(int gamePort, String username, CompletableFuture<Object> opening, int tickLimit, boolean automated, DuelSetup setup) {
        require(username.matches("[A-Za-z0-9_]{3,16}") && !username.equalsIgnoreCase("Terminator"),
                "Enter your Java profile name, which must differ from Terminator");
        this.gamePort = gamePort;
        this.tickLimit = tickLimit;
        this.automated = automated;
        this.setup = setup;
        this.guest = Uuids.getOfflinePlayerProfile(username);
        this.opening = opening;
    }

    void prepareClient(MinecraftClient client) {
        client.options.getInactivityFpsLimit().setValue(InactivityFpsLimit.MINIMIZED);
    }

    void hostControllerReady() {
        require(automated, "Controller readiness is only used in automated duels");
        hostControllerReady = true;
    }

    void resetRound(MinecraftClient client, CompletableFuture<Object> response, DuelSetup nextSetup) {
        require(automated && finished() && !phase.equals("disconnected") && roundReset == null,
                "Round reset requires a completed automated duel with both clients connected");
        TrainingBridge.releaseControls(client);
        queued = null;
        attackHeld = false;
        clientFrames.clear();
        jumpInputs = policyJumps = emptyJumps = 0;
        clientSprintStarts = clientSprintStops = sprintInputReleases = sprintInputRestarts = 0;
        clientSprinting = previousSprintInput = releasedSprintInput = false;
        reportedJumps = reportedMovement = reportedLook = false;
        if (client.player != null && !client.player.isAlive()) client.player.requestRespawn();
        // Server mutation and readiness reset occur on the server thread.
        server.execute(() -> {
            setup = nextSetup;
            hostControllerReady = guestControllerReady = false;
            phase = "resetting";
            resetDeadline = server.getTicks() + 200;
            roundReset = response;
        });
    }

    void guestControllerReady(ServerPlayerEntity player) {
        if (automated && player.getUuid().equals(guest.getId())) guestControllerReady = true;
    }

    void poll(long basedOnTick, Controls controls, CompletableFuture<Object> response) {
        require(poll == null, "A duel poll is already pending");
        queued = basedOnTick == observedTick && tick - observedTick <= 3
                && System.nanoTime() - observedAt <= CONTROL_MAX_AGE
                && phase.equals("fighting") ? controls : null;
        queuedBasedOn = basedOnTick;
        queuedStatus = controls == null ? "no_controls" : queued != null ? "applied" : "dropped_stale_or_phase";
        poll = response;
    }

    void beforeClientTick(MinecraftClient client) {
        Controls controls = queued;
        queued = null;
        appliedBasedOn = queuedBasedOn;
        appliedStatus = queuedStatus;
        queuedBasedOn = -1;
        queuedStatus = "no_controls";
        policyJumpPressed = false;
        if (client.player != null && client.player.isAlive() && phase.equals("fighting")
                && controls != null && System.nanoTime() - observedAt <= CONTROL_MAX_AGE) {
            policyJumpPressed = controls.jump();
            if (policyJumpPressed) {
                jumpInputs++;
            }
            appliedControls = TrainingBridge.applyControls(client, controls, true, attackHeld);
            if (!reportedMovement && (controls.forward() || controls.back() || controls.left() || controls.right())) {
                reportedMovement = true;
                TrainingBridge.LOG.info("Duel policy applied movement at client tick {}", tick + 1);
            }
            if (!reportedLook && (Math.abs(controls.yaw_delta_degrees()) >= 1
                    || Math.abs(controls.pitch_delta_degrees()) >= 1)) {
                reportedLook = true;
                TrainingBridge.LOG.info("Duel policy applied a look adjustment at client tick {}", tick + 1);
            }
            attackHeld = controls.attack();
        } else {
            if (controls != null) appliedStatus = "dropped_expired_or_unavailable";
            TrainingBridge.releaseControls(client);
            appliedControls = client.player == null ? Map.of()
                    : TrainingBridge.applyControls(client, NEUTRAL, true, false);
            attackHeld = false;
        }
        boolean sprintInput = Boolean.TRUE.equals(appliedControls.get("sprint"));
        if (!sprintInput && previousSprintInput) {
            sprintInputReleases++;
            releasedSprintInput = true;
        } else if (sprintInput && !previousSprintInput && releasedSprintInput) sprintInputRestarts++;
        previousSprintInput = sprintInput;
    }

    void afterClientTick(MinecraftClient client) {
        policyJumpPressed = false;
        if (client.player != null) {
            TrainingBridge.releaseControls(client);
        }
        if (!reportedJumps && finished()) {
            reportedJumps = true;
            TrainingBridge.LOG.info("Duel jump summary: {} applied jump inputs, {} policy jumps",
                    jumpInputs, policyJumps);
        }
        if (!openingScheduled && client.player != null && client.getServer() != null
                && client.currentScreen == null) {
            openingScheduled = true;
            server = client.getServer();
            GameProfile host = client.player.getGameProfile();
            server.execute(() -> open(server, host));
        }
        tick++;
        var frame = new HashMap<String, Object>();
        frame.put("client_tick", tick);
        frame.put("timestamp_ms", System.currentTimeMillis());
        Map<String, Object> snapshot = serverMetrics;
        frame.put("phase", snapshot.getOrDefault("phase", phase));
        frame.put("applied_controls", appliedControls);
        frame.put("based_on_tick", appliedBasedOn);
        frame.put("controls_status", appliedStatus);
        frame.put("server_metrics", snapshot);
        frame.put("policy_jumps", policyJumps);
        frame.put("jump_inputs", jumpInputs);
        frame.put("empty_jumps_proxy", emptyJumps);
        frame.put("sprint_input_releases", sprintInputReleases);
        frame.put("sprint_input_restarts", sprintInputRestarts);
        if (client.player != null && client.world != null) {
            boolean sprinting = client.player.isSprinting();
            if (sprinting && !clientSprinting) clientSprintStarts++;
            if (!sprinting && clientSprinting) clientSprintStops++;
            clientSprinting = sprinting;
            frame.put("client_sprinting", sprinting);
            frame.put("client_sprint_starts", clientSprintStarts);
            frame.put("client_sprint_stops", clientSprintStops);
            var opponent = client.world.getPlayerByUuid(guest.getId());
            frame.put("observation", new Observation(id, tick, SwordDrill.SCHEMA,
                    SwordDrill.observe(client, Player.capture(client.player), opponent)));
        }
        clientFrames.add(Map.copyOf(frame));
        if (poll == null) {
            return;
        }
        // A terminal phase is usable only once its final immutable snapshot is published.
        if (finished() && !phase.equals(snapshot.get("phase"))) return;
        String status = (String) frame.get("phase");
        var response = new HashMap<String, Object>(frame);
        response.put("type", "duel_frame");
        response.put("client_frames", List.copyOf(clientFrames));
        clientFrames.clear();
        if ("fighting".equals(status) && client.player != null && client.world != null) {
            var opponent = client.world.getPlayerByUuid(guest.getId());
            var payload = SwordDrill.observe(client, Player.capture(client.player), opponent);
            observedTick = tick;
            observedAt = System.nanoTime();
            response.put("observation", new Observation(id, tick, SwordDrill.SCHEMA, payload));
        }
        poll.complete(Map.copyOf(response));
        poll = null;
    }

    void recordJump() {
        if (policyJumpPressed) {
            if (!attackHeld) emptyJumps++;
            TrainingBridge.LOG.info("Terminator executed policy jump {} at client tick {}",
                    ++policyJumps, tick + 1);
        }
    }

    private void open(IntegratedServer server, GameProfile host) {
        var players = server.getPlayerManager();
        players.getWhitelist().add(new WhitelistEntry(host));
        players.getWhitelist().add(new WhitelistEntry(guest));
        players.setWhitelistEnabled(true);
        server.setOnlineMode(false);
        server.setPvpEnabled(true);
        var border = server.getOverworld().getWorldBorder();
        border.setCenter(8.5, 8.5);
        // Movement rounds the collision wall out to whole blocks, while the
        // server checks an attack target's block position against exact bounds.
        border.setSize(25);
        try {
            server.getNetworkIo().bind(InetAddress.getByAddress(new byte[]{127, 0, 0, 1}), gamePort);
            opening.complete(Map.of("type", "duel_ready", "address", "127.0.0.1:" + gamePort));
            TrainingBridge.LOG.info("Private duel listening on 127.0.0.1:{}", gamePort);
        } catch (IOException failure) {
            phase = "failed";
            opening.complete(TrainingBridge.error("duel_bind_failed", failure.getMessage()));
        }
    }

    void afterServerTick(IntegratedServer current) {
        if (server != current || phase.equals("failed") || finished()) {
            return;
        }
        var players = current.getPlayerManager();
        var bot = players.getPlayer(current.getHostProfile().getId());
        var human = players.getPlayer(guest.getId());
        if (roundReset != null) {
            if (bot != null && human != null && bot.isAlive() && human.isAlive()) {
                id = UUID.randomUUID().toString();
                fightAt = 0;
                playersPrepared = false;
                reportedBotDamage = false;
                botTelemetry = new SwordDrill.Telemetry();
                humanTelemetry = new SwordDrill.Telemetry();
                criticalHits = sprintHits = 0;
                phase = "waiting";
                var response = roundReset;
                roundReset = null;
                // Continue below: prepare both players before publishing waiting.
                preparePlayers(current, bot, human);
                playersPrepared = true;
                // Death drops from the prior round must not refill inventories.
                for (var item : current.getOverworld().getEntitiesByClass(net.minecraft.entity.ItemEntity.class,
                        new net.minecraft.util.math.Box(-6, -64, -6, 23, 0, 23), e -> true)) item.discard();
                publishMetrics(current, bot, human);
                response.complete(Map.of("type", "duel_round_ready", "episode_id", id));
            } else if (current.getTicks() >= resetDeadline) {
                roundReset.complete(TrainingBridge.error("round_reset_failed", "Players did not respawn"));
                roundReset = null;
                phase = "disconnected";
                publishMetrics(current, bot, human);
            } else publishMetrics(current, bot, human);
            return;
        }
        if (bot == null || human == null) {
            if (fightAt != 0) {
                phase = "disconnected";
            }
            publishMetrics(current, bot, human);
            return;
        }
        if (fightAt == 0) {
            if (!playersPrepared) {
                preparePlayers(current, bot, human);
                playersPrepared = true;
            }
            if (automated && (!hostControllerReady || !guestControllerReady)) {
                phase = "waiting";
                publishMetrics(current, bot, human);
                return;
            }
            fightAt = current.getTicks() + 60;
            phase = "countdown";
            human.sendMessage(Text.literal("Sword duel starts in 3 seconds."), false);
            publishMetrics(current, bot, human);
            return;
        }
        if (phase.equals("countdown") && current.getTicks() >= fightAt) {
            current.getGameRules().get(net.minecraft.world.GameRules.NATURAL_REGENERATION).set(true, current);
            bot.setInvulnerable(false);
            human.setInvulnerable(false);
            phase = "fighting";
            human.sendMessage(Text.literal("Fight!"), false);
        }
        if (phase.equals("fighting") && !reportedBotDamage && bot.getHealth() < bot.getMaxHealth()) {
            reportedBotDamage = true;
            TrainingBridge.LOG.info("Duel host confirmed damage to Terminator");
        }
        if (phase.equals("fighting") && (!human.isAlive() || !bot.isAlive())) {
            phase = human.isAlive() ? "human_won" : bot.isAlive() ? "terminator_won" : "draw";
            human.sendMessage(Text.literal(switch (phase) {
                case "human_won" -> "You defeated Terminator.";
                case "terminator_won" -> "Terminator won the duel.";
                default -> "The duel ended in a draw.";
            }), false);
        } else if (phase.equals("fighting") && tickLimit > 0 && current.getTicks() - fightAt >= tickLimit) {
            phase = "draw";
        }
        publishMetrics(current, bot, human);
    }

    private void publishMetrics(IntegratedServer current, ServerPlayerEntity bot, ServerPlayerEntity human) {
        var snapshot = new HashMap<String, Object>(serverMetrics);
        snapshot.put("server_tick", current.getTicks());
        snapshot.put("timestamp_ms", System.currentTimeMillis());
        snapshot.put("phase", phase);
        snapshot.put("tick_limit", tickLimit);
        snapshot.put("automated", automated);
        snapshot.put("round_setup_feedback", setup.feedback());
        if(setup.scenario()!=null)snapshot.put("scenario_reset_feedback",setup.scenario().audit());
        snapshot.put("host_controller_ready", hostControllerReady);
        snapshot.put("guest_controller_ready", guestControllerReady);
        snapshot.put("host_id", current.getHostProfile().getId().toString());
        if (bot != null) {
            botTelemetry.sampleSprint(bot);
            snapshot.put("host", botTelemetry.snapshot(bot));
            snapshot.put("host_hunger_feedback", hungerFeedback(bot));
        }
        if (human != null) {
            humanTelemetry.sampleSprint(human);
            snapshot.put("human", humanTelemetry.snapshot(human));
            snapshot.put("guest_hunger_feedback", hungerFeedback(human));
        }
        serverMetrics = Map.copyOf(snapshot);
        TrainingBridge.sendDuelState(human, id, phase, current.getHostProfile().getId(), current.getTicks());
    }

    SwordDrill.Telemetry telemetry(IntegratedServer current, ServerPlayerEntity player) {
        if (server != current || !phase.equals("fighting")) return null;
        if (player.getUuid().equals(current.getHostProfile().getId())) return botTelemetry;
        return player.getUuid().equals(guest.getId()) ? humanTelemetry : null;
    }

    boolean isOpponent(ServerPlayerEntity attacker, net.minecraft.entity.Entity victim) {
        return attacker.getUuid().equals(guest.getId())
                ? victim.getUuid().equals(server.getHostProfile().getId())
                : victim.getUuid().equals(guest.getId());
    }

    private boolean finished() {
        return switch (phase) {
            case "human_won", "terminator_won", "draw", "disconnected" -> true;
            default -> false;
        };
    }

    void recordHit(IntegratedServer current, net.minecraft.entity.Entity victim, boolean sprint) {
        if (server == current && phase.equals("fighting") && victim.getUuid().equals(guest.getId())) {
            if (sprint) {
                TrainingBridge.LOG.info("Terminator landed sprint knockback hit {} at server tick {}",
                        ++sprintHits, current.getTicks());
            } else {
                TrainingBridge.LOG.info("Terminator landed critical sword hit {} at server tick {}",
                        ++criticalHits, current.getTicks());
            }
        }
    }

    private void preparePlayers(IntegratedServer current, ServerPlayerEntity bot, ServerPlayerEntity human) {
        // Low-health starts must not regenerate while clients join or count down.
        if (automated) current.getGameRules().get(net.minecraft.world.GameRules.NATURAL_REGENERATION).set(false, current);
        double sign = setup.mirror() ? -1 : 1;
        if(setup.scenario()!=null) {
            setup.scenario().reset(current.getOverworld());
        }
        prepare(bot, 8.5 - sign * setup.distance() / 2, (float)(-90 * sign), setup.hostHealth(), setup.hostApples());
        prepare(human, 8.5 + sign * setup.distance() / 2, (float)(90 * sign), setup.guestHealth(), setup.guestApples());
    }

    private void prepare(ServerPlayerEntity player, double x, float yaw, float health, int apples) {
        player.setInvulnerable(true);
        player.stopUsingItem();
        player.clearStatusEffects();
        player.setAbsorptionAmount(0);
        player.setHealth(health);
        var freshHunger = new net.minecraft.nbt.NbtCompound();
        new net.minecraft.entity.player.HungerManager().writeNbt(freshHunger);
        player.getHungerManager().readNbt(freshHunger);
        player.setFireTicks(0);
        player.setVelocity(net.minecraft.util.math.Vec3d.ZERO);
        player.fallDistance = 0;
        player.setSprinting(false);
        player.getInventory().clear();
        if(setup.scenario()==null) {
            SwordDrill.equip(player);
            player.getInventory().setStack(1, apples == 0 ? net.minecraft.item.ItemStack.EMPTY
                    : new net.minecraft.item.ItemStack(net.minecraft.item.Items.GOLDEN_APPLE, apples));
        } else setup.scenario().equip(player,apples);
        if(setup.scenario()==null)player.networkHandler.requestTeleport(x,FLOOR_Y,8.5,yaw,0);
        else {int index=player.getUuid().equals(server.getHostProfile().getId())?0:1;
            var spawn=setup.scenario().spawn(index,setup.distance(),setup.mirror());setup.scenario().checkSpawn(spawn.x,spawn.z);
            player.networkHandler.requestTeleport(spawn.x,spawn.y,spawn.z,setup.scenario().yaw(index,setup.distance(),setup.mirror()),0);}
    }

    private static Map<String, Object> hungerFeedback(ServerPlayerEntity player) {
        var nbt = new net.minecraft.nbt.NbtCompound();
        player.getHungerManager().writeNbt(nbt);
        // Privileged audit feedback, never included in SwordDrill observations.
        return Map.of("food", nbt.getInt("foodLevel"), "saturation", nbt.getFloat("foodSaturationLevel"),
                "exhaustion", nbt.getFloat("foodExhaustionLevel"), "timer", nbt.getInt("foodTickTimer"));
    }
}
