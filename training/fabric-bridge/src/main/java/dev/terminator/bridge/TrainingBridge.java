package dev.terminator.bridge;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.terminator.bridge.mixin.ClientAttack;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.AccessibilityOnboardingScreen;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.resource.DataConfiguration;
import net.minecraft.resource.featuretoggle.FeatureFlags;
import net.minecraft.server.integrated.IntegratedServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.Difficulty;
import net.minecraft.world.GameMode;
import net.minecraft.world.GameRules;
import net.minecraft.world.gen.GeneratorOptions;
import net.minecraft.world.gen.WorldPresets;
import net.minecraft.world.level.LevelInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import static dev.terminator.bridge.ReachAndStop.*;

public final class TrainingBridge implements ClientModInitializer {
    static final Logger LOG = LoggerFactory.getLogger("terminator-bridge");
    private static volatile TrainingBridge active;

    private record TickMarker(long stepId) implements CustomPayload {
        static final Id<TickMarker> ID = new Id<>(Identifier.of("terminator_bridge", "tick_marker"));
        static final PacketCodec<RegistryByteBuf, TickMarker> CODEC = PacketCodec.of(
                (marker, buffer) -> buffer.writeLong(marker.stepId), buffer -> new TickMarker(buffer.readLong()));

        @Override
        public Id<TickMarker> getId() {
            return ID;
        }
    }

    record DuelState(String episodeId, String phase, String hostId, long serverTick) implements CustomPayload {
        static final Id<DuelState> ID = new Id<>(Identifier.of("terminator_bridge", "duel_state"));
        static final PacketCodec<RegistryByteBuf, DuelState> CODEC = PacketCodec.of(
                (state, buffer) -> { buffer.writeString(state.episodeId); buffer.writeString(state.phase);
                    buffer.writeString(state.hostId); buffer.writeLong(state.serverTick); },
                buffer -> new DuelState(buffer.readString(64), buffer.readString(32), buffer.readString(64), buffer.readLong()));
        @Override public Id<DuelState> getId() { return ID; }
    }

    private record DuelAgentReady() implements CustomPayload {
        static final Id<DuelAgentReady> ID = new Id<>(Identifier.of("terminator_bridge", "duel_agent_ready"));
        static final PacketCodec<RegistryByteBuf, DuelAgentReady> CODEC = PacketCodec.of(
                (ready, buffer) -> {}, buffer -> new DuelAgentReady());
        @Override public Id<DuelAgentReady> getId() {return ID;}
    }

    private record StepRequest(String episodeId, long id, long fromTick, Controls controls) {}
    private record CachedStep(StepRequest request, Map<String, Object> response) {}

    private static final class Step {
        final StepRequest request;
        final CompletableFuture<Object> response;
        volatile Player player;
        SwordDrill.CombatPayload combatPayload;
        Map<String, Object> appliedControls;
        long appliedAt;
        volatile boolean markerReceived;
        long worldTime;

        Step(StepRequest request, CompletableFuture<Object> response) {
            this.request = request;
            this.response = response;
        }
    }

    private static final class Episode {
        final Scenario scenario;
        final CompletableFuture<Object> reset;
        final ReachAndStop scoring = new ReachAndStop();
        final SwordDrill sword;
        final long seed;
        final Map<Long, CachedStep> completed = new HashMap<>();
        volatile IntegratedServer server;
        volatile boolean ready;
        boolean spawnRequested;
        volatile boolean spawnVerified;
        volatile Vec3d serverPosition;
        volatile Step pending;
        volatile Map<String, Object> serverMetrics = Map.of();
        long tick;
        boolean terminal;
        boolean previousSprintInput, releasedSprintInput;
        int sprintInputReleases, sprintInputRestarts, jumps, emptyJumps;

        Episode(Scenario scenario, SwordDrill sword, long seed, CompletableFuture<Object> reset) {
            this.scenario = scenario;
            this.sword = sword;
            this.seed = seed;
            this.reset = reset;
        }
    }

    private volatile Episode episode;
    private volatile LocalDuel duel;
    private GuestDuel guestDuel;
    private DuelState latestDuelState;
    private boolean greeted;
    private Integer port;

    @Override
    public void onInitializeClient() {
        port = Integer.getInteger("terminator.bridge.port");
        if (port == null) {
            return;
        }
        PayloadTypeRegistry.playC2S().register(TickMarker.ID, TickMarker.CODEC);
        PayloadTypeRegistry.playC2S().register(DuelAgentReady.ID, DuelAgentReady.CODEC);
        PayloadTypeRegistry.playS2C().register(DuelState.ID, DuelState.CODEC);
        ClientPlayNetworking.registerGlobalReceiver(DuelState.ID, (state, context) -> {
            context.client().execute(() -> {
                latestDuelState = state;
                if (guestDuel != null) guestDuel.state(state);
            });
        });
        ServerPlayNetworking.registerGlobalReceiver(TickMarker.ID, (marker, context) -> {
            Episode current = episode;
            Step step = current == null ? null : current.pending;
            if (step != null && current.server == context.server() && step.request.id == marker.stepId) {
                step.markerReceived = true;
            }
        });
        ServerPlayNetworking.registerGlobalReceiver(DuelAgentReady.ID, (ready, context) -> {
            if (duel != null) duel.guestControllerReady(context.player());
        });
        active = this;
    }

    private void serve(int port) {
        try (var listener = new ServerSocket(port, 1, InetAddress.getByName("127.0.0.1"))) {
            LOG.info("Terminator training bridge listening on 127.0.0.1:{}", listener.getLocalPort());
            while (!Thread.currentThread().isInterrupted()) {
                try (var peer = listener.accept();
                     var reader = new BufferedReader(new InputStreamReader(peer.getInputStream(), StandardCharsets.UTF_8));
                     var writer = new BufferedWriter(new OutputStreamWriter(peer.getOutputStream(), StandardCharsets.UTF_8))) {
                    peer.setTcpNoDelay(true);
                    greeted = false;
                    String line;
                    while ((line = reader.readLine()) != null) {
                        var reply = new CompletableFuture<Object>();
                        String request = line;
                        MinecraftClient client = MinecraftClient.getInstance();
                        client.execute(() -> handle(client, request, reply));
                        Object result;
                        try {
                            result = reply.get(120, TimeUnit.SECONDS);
                        } catch (java.util.concurrent.TimeoutException timeout) {
                            for (var thread : ManagementFactory.getThreadMXBean().dumpAllThreads(false, false, 12)) {
                                if (thread.getThreadName().equals("Render thread")
                                        || thread.getThreadName().equals("Server thread")
                                        || thread.getThreadName().startsWith("Netty Local")) {
                                    LOG.error("Timed-out bridge thread: {}", thread);
                                }
                            }
                            throw timeout;
                        }
                        writer.write(JSON.toJson(result));
                        writer.newLine();
                        writer.flush();
                        if (result.equals(Map.of("type", "stopped"))) {
                            client.execute(() -> stopClient(client));
                            return;
                        }
                    }
                } catch (IOException failure) {
                    LOG.warn("Training supervisor disconnected", failure);
                }
            }
        } catch (Exception failure) {
            LOG.error("Terminator bridge stopped", failure);
            MinecraftClient client = MinecraftClient.getInstance();
            client.execute(() -> stopClient(client));
        }
    }

    private void stopClient(MinecraftClient client) {
        stopEpisodeServer(client);
        client.scheduleStop();
    }

    private void stopEpisodeServer(MinecraftClient client) {
        // The server must be able to tick while it enters the shutdown path.
        episode = null;
        duel = null;
        guestDuel = null;
        latestDuelState = null;
        releaseControls(client);
        IntegratedServer oldServer = client.getServer();
        if (oldServer != null) {
            oldServer.stop(false);
        }
    }

    private void handle(MinecraftClient client, String line, CompletableFuture<Object> reply) {
        try {
            JsonObject request = JsonParser.parseString(line).getAsJsonObject();
            String type = request.get("type").getAsString();
            if ("hello".equals(type)) {
                require(positiveInteger(request, "protocol_version", 1) == 1, "Unsupported protocol");
                greeted = true;
                reply.complete(Map.of("type", "hello", "protocol_version", 1, "game_profile", PROFILE));
                return;
            }
            require(greeted, "Send hello before other requests");
            require(!NavigationClient.enabled() || java.util.Set.of("navigation_command", "navigation_status",
                    "navigation_key", "navigation_test_world", "navigation_test_ready", "dataset_command", "shutdown").contains(type),
                    "Navigation mode cannot own a training or duel controller");
            switch (type) {
                case "dataset_command" -> reply.complete(DatasetClient.request(request.get("text").getAsString()));
                case "navigation_command" -> reply.complete(NavigationClient.request(request.get("text").getAsString()));
                case "navigation_status" -> reply.complete(NavigationClient.status());
                case "navigation_key" -> {
                    require(NavigationClient.enabled() && Boolean.getBoolean("terminator.navigation.test"), "Test key injection disabled");
                    NavigationClient.key(org.lwjgl.glfw.GLFW.GLFW_KEY_F8, org.lwjgl.glfw.GLFW.GLFW_PRESS);
                    reply.complete(NavigationClient.status());
                }
                case "navigation_test_ready" -> {
                    require(NavigationClient.enabled() && Boolean.getBoolean("terminator.navigation.test") && client.getNetworkHandler()!=null,
                            "Own-client test readiness disabled");
                    ClientPlayNetworking.send(new DuelAgentReady());
                    reply.complete(Map.of("type","navigation_test_ready"));
                }
                case "navigation_test_world" -> {
                    require(NavigationClient.enabled() && Boolean.getBoolean("terminator.navigation.test") && client.world == null,
                            "Fresh isolated navigation test worker required");
                    createFlatWorld(client, Difficulty.PEACEFUL, true, 0L);
                    reply.complete(Map.of("type", "navigation_world_starting"));
                }
                case "reset" -> {
                    require(duel == null && guestDuel == null, "This worker is controlling a local duel");
                    startWorld(client, request.getAsJsonObject("scenario"), reply);
                }
                case "step" -> queueStep(request, reply);
                case "duel_start" -> {
                    require(duel == null && guestDuel == null && episode == null && client.world == null,
                            "The duel requires a fresh worker");
                    int gamePort = (int) positiveInteger(request, "game_port", 65535);
                    require(gamePort >= 1024, "Use an unprivileged game port");
                    int tickLimit = request.has("tick_limit") ? (int) positiveInteger(request, "tick_limit", 72000) : 0;
                    boolean automated = request.has("automated") && request.get("automated").getAsBoolean();
                    var setup=DuelSetup.parse(request.getAsJsonObject("setup"));
                    require(automated || !request.has("setup") || setup.scenario()!=null,"Custom legacy setups require automated duels");
                    duel = new LocalDuel(gamePort, request.get("username").getAsString(), reply, tickLimit, automated,
                            setup);
                    duel.prepareClient(client);
                    createFlatWorld(client, Difficulty.NORMAL, true, 0L);
                }
                case "duel_round_reset" -> {
                    require(duel != null, "Start an automated local duel first");
                    duel.resetRound(client, reply, DuelSetup.parse(request.getAsJsonObject("setup")));
                }
                case "duel_guest_respawn" -> {
                    require(guestDuel != null && client.player != null, "Guest is not connected");
                    guestDuel.prepareRound(client);
                    reply.complete(Map.of("type", "duel_guest_respawning"));
                }
                case "duel_poll" -> {
                    require(duel != null, "Start a local duel first");
                    JsonObject controls = request.getAsJsonObject("controls");
                    long tick = controls == null ? -1 : positiveInteger(request, "based_on_tick", Long.MAX_VALUE);
                    duel.poll(tick, controls == null ? null : Controls.parse(controls, true), reply);
                }
                case "duel_guest_start" -> {
                    require(guestDuel == null && duel == null && episode == null && client.getServer() == null,
                            "Guest controller requires a separate client");
                    int gamePort = (int) positiveInteger(request, "game_port", 65535);
                    require(gamePort >= 1024 && client.getCurrentServerEntry() != null
                            && client.getCurrentServerEntry().address.equals("127.0.0.1:" + gamePort),
                            "Guest controller is restricted to the requested loopback arena");
                    guestDuel = new GuestDuel();
                    guestDuel.prepareClient(client);
                    if (latestDuelState != null) guestDuel.state(latestDuelState);
                    reply.complete(Map.of("type", "duel_guest_ready"));
                }
                case "duel_guest_poll" -> {
                    require(guestDuel != null, "Start the guest controller first");
                    JsonObject controls = request.getAsJsonObject("controls");
                    long tick = controls == null ? -1 : positiveInteger(request, "based_on_tick", Long.MAX_VALUE);
                    guestDuel.poll(tick, controls == null ? null : Controls.parse(controls, true), reply);
                }
                case "duel_agent_ready" -> {
                    if (duel != null) duel.hostControllerReady();
                    else {
                        require(guestDuel != null && client.getNetworkHandler() != null, "Guest client is not connected");
                        ClientPlayNetworking.send(new DuelAgentReady());
                    }
                    reply.complete(Map.of("type", "duel_controller_ready"));
                }
                case "shutdown" -> reply.complete(Map.of("type", "stopped"));
                default -> throw new IllegalArgumentException("Unknown request type");
            }
        } catch (com.google.gson.JsonParseException | IllegalArgumentException | IllegalStateException
                 | ArithmeticException | ClassCastException | NullPointerException malformed) {
            reply.complete(error("invalid_request", malformed.getMessage()));
        }
    }

    private void startWorld(MinecraftClient client, JsonObject value, CompletableFuture<Object> reply) {
        Scenario scenario = Scenario.parse(value);
        // Scenario's legacy record omits seed. Preserve all u64 bits for GeneratorOptions.
        long seed = value.has("seed") ? Long.parseUnsignedLong(value.get("seed").getAsString()) : 0L;
        JsonObject setup = value.getAsJsonObject("resolved_setup");
        boolean moving = false;
        if (scenario.swordDrill() && setup.has("moving_opponent")) {
            var option = setup.get("moving_opponent");
            require(option.isJsonPrimitive() && option.getAsJsonPrimitive().isBoolean(),
                    "Expected boolean moving_opponent");
            moving = option.getAsBoolean();
        }
        SwordDrill sword = scenario.swordDrill()
                ? new SwordDrill(SwordDrill.targetPosition(setup, scenario.resolved_setup().spawn()), moving, setup)
                : null;
        Episode previous = episode;
        require(previous == null || !previous.scenario.episode_id().equals(scenario.episode_id()),
                "Reset requires a fresh episode ID");
        stopEpisodeServer(client);
        if (client.world != null) {
            client.disconnect();
        }
        episode = new Episode(scenario, sword, seed, reply);
        createFlatWorld(client, sword == null ? Difficulty.PEACEFUL : Difficulty.NORMAL,
                sword == null || sword.usesNaturalRegeneration(), seed);
    }

    static String createFlatWorld(MinecraftClient client, Difficulty difficulty, boolean naturalRegeneration, long seed) {
        client.options.pauseOnLostFocus = false;
        client.options.getViewDistance().setValue(3);
        client.options.getSimulationDistance().setValue(5);
        var rules = new GameRules(FeatureFlags.DEFAULT_ENABLED_FEATURES);
        rules.get(GameRules.DO_MOB_SPAWNING).set(false, null);
        rules.get(GameRules.NATURAL_REGENERATION).set(naturalRegeneration, null);
        rules.get(GameRules.DO_DAYLIGHT_CYCLE).set(false, null);
        rules.get(GameRules.DO_WEATHER_CYCLE).set(false, null);
        rules.get(GameRules.RANDOM_TICK_SPEED).set(0, null);
        rules.get(GameRules.SPAWN_RADIUS).set(0, null);
        var info = new LevelInfo("Terminator", GameMode.SURVIVAL, false, difficulty,
                false, rules, DataConfiguration.SAFE_MODE);
        String worldId="terminator-"+UUID.randomUUID();
        client.createIntegratedServerLoader().createAndStart(worldId, info,
                new GeneratorOptions(seed, false, false),
                registries -> registries.getOrThrow(RegistryKeys.WORLD_PRESET).getOrThrow(WorldPresets.FLAT)
                        .value().createDimensionsRegistryHolder(), new TitleScreen());
        return worldId;
    }

    private void queueStep(JsonObject request, CompletableFuture<Object> reply) {
        Episode current = episode;
        require(current != null && current.ready, "Reset before stepping");
        String id = request.get("episode_id").getAsString();
        require(current.scenario.episode_id().equals(id), "Stale episode ID");
        var step = new StepRequest(id, positiveInteger(request, "step_id", Long.MAX_VALUE),
                positiveInteger(request, "from_tick", Long.MAX_VALUE),
                Controls.parse(request.getAsJsonObject("controls"), current.sword != null));
        CachedStep cached = current.completed.get(step.id);
        if (cached != null) {
            require(cached.request.equals(step), "Conflicting reuse of step ID");
            reply.complete(cached.response);
            return;
        }
        require(step.fromTick == current.tick, "Stale starting tick");
        require(!current.terminal && current.pending == null, "Episode cannot accept a new step");
        current.pending = new Step(step, reply);
    }

    public static boolean beforeClientTick(MinecraftClient client) {
        TrainingBridge bridge = active;
        if (bridge != null && bridge.guestDuel != null) {
            bridge.guestDuel.beforeClientTick(client);
            return true;
        }
        LocalDuel localDuel = active == null ? null : active.duel;
        if (localDuel != null) {
            localDuel.beforeClientTick(client);
            return true;
        }
        Episode current = active == null ? null : active.episode;
        if (current == null || !current.ready) {
            return true;
        }
        Step step = current.pending;
        if (step == null || step.player != null) {
            return false;
        }
        Controls c = step.request.controls;
        step.appliedControls = applyControls(client, c, current.sword != null,
                current.sword != null && current.sword.attackHeld);
        step.appliedAt = System.currentTimeMillis();
        if (!c.sprint() && current.previousSprintInput) {
            current.sprintInputReleases++;
            current.releasedSprintInput = true;
        } else if (c.sprint() && !current.previousSprintInput && current.releasedSprintInput) {
            current.sprintInputRestarts++;
        }
        current.previousSprintInput = c.sprint();
        if (current.sword != null) {
            current.sword.attackHeld = c.attack();
        }
        return true;
    }

    static Map<String, Object> applyControls(MinecraftClient client, Controls c, boolean combat, boolean attackHeld) {
        float oldPitch = client.player.getPitch();
        client.options.forwardKey.setPressed(c.forward());
        client.options.backKey.setPressed(c.back());
        client.options.leftKey.setPressed(c.left());
        client.options.rightKey.setPressed(c.right());
        client.player.setYaw(MathHelper.wrapDegrees(client.player.getYaw() + c.yaw_delta_degrees()));
        if (combat) {
            client.options.jumpKey.setPressed(c.jump());
            client.options.sprintKey.setPressed(c.sprint());
            if (c.hotbar_slot() != null) {
                client.player.getInventory().setSelectedSlot(c.hotbar_slot());
            }
            client.options.useKey.setPressed(c.use_item());
            client.player.setPitch(MathHelper.clamp(client.player.getPitch() + c.pitch_delta_degrees(), -90, 90));
            if (c.attack() && !attackHeld) {
                client.gameRenderer.updateCrosshairTarget(1);
                ((ClientAttack) client).terminator$attack();
            }
        }
        var applied = new HashMap<String, Object>();
        applied.put("forward", c.forward());
        applied.put("back", c.back());
        applied.put("left", c.left());
        applied.put("right", c.right());
        applied.put("jump", combat && c.jump());
        applied.put("sprint", combat && c.sprint());
        applied.put("sneak", false);
        applied.put("attack", combat && c.attack());
        applied.put("attack_triggered", combat && c.attack() && !attackHeld);
        applied.put("use_item", combat && c.use_item());
        applied.put("yaw_delta_degrees", c.yaw_delta_degrees());
        applied.put("pitch_delta_degrees", client.player.getPitch() - oldPitch);
        applied.put("hotbar_slot", client.player.getInventory().selectedSlot);
        applied.put("sprinting_before_tick", client.player.isSprinting());
        return Map.copyOf(applied);
    }

    public static void afterClientTick(MinecraftClient client) {
        TrainingBridge bridge = active;
        if (bridge == null) {
            return;
        }
        if (bridge.port != null && client.currentScreen instanceof AccessibilityOnboardingScreen
                && client.getOverlay() == null) {
            client.setScreen(new TitleScreen());
        }
        if (bridge.port != null && (client.currentScreen instanceof TitleScreen || client.world != null) && client.getOverlay() == null) {
            int listeningPort = bridge.port;
            bridge.port = null;
            Thread.ofVirtual().name("terminator-bridge").start(() -> bridge.serve(listeningPort));
        }
        if (bridge.duel != null) {
            bridge.duel.afterClientTick(client);
            return;
        }
        if (bridge.guestDuel != null) {
            bridge.guestDuel.afterClientTick(client);
            return;
        }
        Episode current = bridge.episode;
        if (current == null || client.player == null || client.getServer() == null) {
            return;
        }
        if (!current.ready) {
            active.prepareSpawn(client, current);
            return;
        }
        Step step = current.pending;
        if (step == null || step.player != null) {
            return;
        }
        releaseControls(client);
        step.player = Player.capture(client.player);
        if (current.sword != null) {
            step.combatPayload = current.sword.observe(client, step.player);
        }
        // Ordered behind vanilla movement packets on the same connection.
        ClientPlayNetworking.send(new TickMarker(step.request.id));
    }

    static void releaseControls(MinecraftClient client) {
        client.options.forwardKey.setPressed(false);
        client.options.backKey.setPressed(false);
        client.options.leftKey.setPressed(false);
        client.options.rightKey.setPressed(false);
        client.options.jumpKey.setPressed(false);
        client.options.sprintKey.setPressed(false);
        client.options.useKey.setPressed(false);
    }

    static void sendDuelState(ServerPlayerEntity guest, String id, String phase,
                              UUID hostId, long serverTick) {
        if (guest != null && ServerPlayNetworking.canSend(guest, DuelState.ID)) {
            ServerPlayNetworking.send(guest, new DuelState(id, phase, hostId.toString(), serverTick));
        }
    }

    public static void recordCriticalHit(ServerPlayerEntity attacker, Entity victim) {
        recordSwordHit(attacker, victim, false);
    }

    public static void recordSprintHit(ServerPlayerEntity attacker, Entity victim) {
        if (attacker.isSprinting() && attacker.getMainHandStack().isOf(net.minecraft.item.Items.DIAMOND_SWORD)) {
            recordSwordHit(attacker, victim, true);
        }
    }

    private static void recordSwordHit(ServerPlayerEntity attacker, Entity victim, boolean sprint) {
        TrainingBridge bridge = active;
        var telemetry = telemetry(attacker);
        if (telemetry != null && bridge != null
                && (bridge.duel != null ? bridge.duel.isOpponent(attacker, victim)
                : bridge.episode.sword.isTarget(victim))) {
            if (sprint) telemetry.sprintHits++;
            else telemetry.criticalHits++;
        }
        if (bridge == null || !(attacker.getServer() instanceof IntegratedServer server)
                || !attacker.getGameProfile().getId().equals(server.getHostProfile().getId())) {
            return;
        }
        Episode current = bridge.episode;
        if (current != null && current.server == server && current.sword != null) {
            current.sword.recordHit(victim, sprint);
        }
        LocalDuel duel = bridge.duel;
        if (duel != null) {
            duel.recordHit(server, victim, sprint);
        }
    }

    private static SwordDrill.Telemetry telemetry(ServerPlayerEntity player) {
        TrainingBridge bridge = active;
        if (bridge == null || !(player.getServer() instanceof IntegratedServer server)) return null;
        LocalDuel duel = bridge.duel;
        if (duel != null) return duel.telemetry(server, player);
        Episode current = bridge.episode;
        return current != null && current.server == server && current.ready && !current.terminal
                && current.sword != null && player.getUuid().equals(server.getHostProfile().getId())
                ? current.sword.telemetry : null;
    }

    /** Captures the direct victim only. Air swings never invoke server PlayerEntity.attack.
     * Loss is measured within the attack call, including absorption, not between ticks:
     * regeneration cannot cancel it. Does not infer raw/pre-armor damage or sweeping damage. */
    public static final class AttackSample {
        final SwordDrill.Telemetry metrics;
        final LivingEntity victim;
        final float health, absorption;
        final boolean invulnerable;
        final boolean sprinting;
        final int sprintHits;
        AttackSample(SwordDrill.Telemetry metrics, LivingEntity victim, boolean sprinting) {
            this.metrics = metrics;
            this.victim = victim;
            health = victim.getHealth();
            absorption = victim.getAbsorptionAmount();
            invulnerable = victim.isInvulnerable();
            this.sprinting = sprinting;
            sprintHits = metrics.sprintHits;
        }
    }

    public static AttackSample beforeSwordAttack(ServerPlayerEntity attacker, Entity victim) {
        SwordDrill.Telemetry metrics = telemetry(attacker);
        if (metrics == null || !(victim instanceof LivingEntity living)
                || !attacker.getMainHandStack().isOf(net.minecraft.item.Items.DIAMOND_SWORD)) return null;
        TrainingBridge bridge = active;
        if (bridge.duel != null && !bridge.duel.isOpponent(attacker, victim)) return null;
        if (bridge.duel == null && !bridge.episode.sword.isTarget(victim)) return null;
        metrics.attempts++;
        if (attacker.getAttackCooldownProgress(0.5F) < 0.9F) metrics.lowCooldown++;
        return new AttackSample(metrics, living, attacker.isSprinting());
    }

    public static void afterSwordAttack(AttackSample sample) {
        if (sample == null) return;
        float damage = Math.max(0, sample.health - sample.victim.getHealth())
                + Math.max(0, sample.absorption - sample.victim.getAbsorptionAmount());
        if (damage > 0) {
            sample.metrics.hits++;
            sample.metrics.damageDealt += damage;
            if (sample.sprinting && sample.metrics.sprintHits == sample.sprintHits)
                sample.metrics.knockbackFailures++;
        } else {
            sample.metrics.misses++;
            if (sample.invulnerable) sample.metrics.invulnerableMisses++;
        }
    }

    public static void recordAppliedDamage(ServerPlayerEntity victim, float health, float absorption) {
        var metrics = telemetry(victim);
        if (metrics != null) metrics.damageTaken += Math.max(0, health - victim.getHealth())
                + Math.max(0, absorption - victim.getAbsorptionAmount());
    }

    public static void recordSprintState(ServerPlayerEntity player) {
        var metrics = telemetry(player);
        if (metrics != null) metrics.sampleSprint(player);
    }

    public static void recordClientJump(LivingEntity jumper) {
        TrainingBridge bridge = active;
        LocalDuel duel = bridge == null ? null : bridge.duel;
        if (duel != null && jumper == MinecraftClient.getInstance().player) {
            duel.recordJump();
        } else if (bridge != null && bridge.guestDuel != null && jumper == MinecraftClient.getInstance().player) {
            bridge.guestDuel.recordJump();
        } else if (bridge != null && jumper == MinecraftClient.getInstance().player) {
            Episode current = bridge.episode;
            if (current != null && current.ready && current.pending != null) {
                current.jumps++;
                if (!current.pending.request.controls.attack()) current.emptyJumps++;
            }
        }
    }

    private void prepareSpawn(MinecraftClient client, Episode current) {
        if (client.currentScreen != null || client.player.age < 5) {
            return;
        }
        Setup setup = current.scenario.resolved_setup();
        current.server = client.getServer();
        if (!current.spawnRequested) {
            current.spawnRequested = true;
            current.server.execute(() -> {
                ServerPlayerEntity player = current.server.getPlayerManager().getPlayer(client.getSession().getUuidOrNull());
                if (player == null) {
                    current.reset.complete(error("setup_failed", "Server player missing"));
                    return;
                }
                if (current.sword != null && !current.sword.equipAndSpawn(player)) {
                    current.reset.complete(error("setup_failed", "Sword target did not spawn"));
                    return;
                }
                Vec3d spawn = setup.spawn();
                player.networkHandler.requestTeleport(spawn.x, spawn.y, spawn.z, setup.spawn_yaw_degrees(), 0);
            });
            return;
        }
        if (current.spawnVerified && client.player.isOnGround()
                && client.player.getPos().squaredDistanceTo(setup.spawn()) < 1e-8
                && (current.sword == null || current.sword.clientReady(client))) {
            current.tick = 1;
            current.ready = true;
            Player player = Player.capture(client.player);
            Observation initial = current.sword == null ? observation(current.scenario, current.tick, player)
                    : new Observation(current.scenario.episode_id(), current.tick, SwordDrill.SCHEMA,
                            current.sword.observe(client, player));
            current.reset.complete(Map.of("type", "ready", "observation", initial,
                    "runtime_setup", Map.of("world_seed", current.seed,
                            "scenario_seed_u64", Long.toUnsignedString(current.seed))));
        }
    }

    public static boolean beforeServerTick(IntegratedServer server) {
        Episode current = active == null ? null : active.episode;
        if (current == null || current.server != server || !current.ready || server.isStopping()) {
            return true;
        }
        Step step = current.pending;
        if (step == null || !step.markerReceived) {
            return false;
        }
        step.worldTime = server.getOverworld().getTime();
        if (current.sword != null) {
            current.sword.beginTick();
        }
        return true;
    }

    public static void afterServerTick(IntegratedServer server) {
        LocalDuel localDuel = active == null ? null : active.duel;
        if (localDuel != null) {
            localDuel.afterServerTick(server);
            return;
        }
        Episode current = active == null ? null : active.episode;
        if (current == null || current.server != server || server.isStopping()) {
            return;
        }
        var players = server.getPlayerManager().getPlayerList();
        if (players.size() != 1) {
            return;
        }
        var player = players.getFirst();
        Vec3d position = player.getPos();
        if (!current.ready) {
            if (position.squaredDistanceTo(current.scenario.resolved_setup().spawn()) < 1e-8
                    && player.isOnGround()) {
                current.serverPosition = position;
                current.spawnVerified = true;
            }
            return;
        }
        Step step = current.pending;
        if (step == null || !step.markerReceived) {
            return;
        }
        // Consume the server permit before another server loop can run.
        step.markerReceived = false;
        long advanced = server.getOverworld().getTime() - step.worldTime;
        Vec3d previous = current.serverPosition;
        current.serverPosition = position;
        SwordDrill.Result combat = current.sword == null ? null
                : current.sword.score(player, step.request.fromTick, current.scenario.tick_limit());
        // Freeze server-owned values before handing the response to the client thread.
        Map<String, Object> serverMetrics;
        if (current.sword != null) {
            current.sword.telemetry.sampleSprint(player);
            var snapshot = new HashMap<String, Object>(current.sword.telemetry.snapshot(player));
            snapshot.put("server_tick", server.getTicks());
            snapshot.put("timestamp_ms", System.currentTimeMillis());
            snapshot.put("world_seed", current.seed);
            snapshot.put("scenario_seed_u64", Long.toUnsignedString(current.seed));
            serverMetrics = Map.copyOf(snapshot);
        } else serverMetrics = Map.of();
        current.serverMetrics = serverMetrics;
        MinecraftClient.getInstance().execute(() -> {
            if (advanced != 1 || position.squaredDistanceTo(new Vec3d(
                    step.player.position()[0], step.player.position()[1], step.player.position()[2])) > 1e-6) {
                current.terminal = true;
                current.pending = null;
                step.response.complete(error("step_alignment", "Client/server movement or world tick diverged"));
                return;
            }
            current.tick++;
            StepResult result;
            if (combat == null) {
                result = current.scoring.score(current.scenario, current.tick - 1, current.tick, step.player,
                        step.request.controls, previous, position, 1, (int) advanced);
            } else {
                var observed = new Observation(current.scenario.episode_id(), current.tick,
                        SwordDrill.SCHEMA, step.combatPayload);
                var extended = new HashMap<String, Object>();
                extended.put("opponent_health", combat.opponent_health());
                extended.put("damage_dealt", combat.damage_dealt());
                extended.put("player_health", combat.player_health());
                extended.put("damage_taken", combat.damage_taken());
                extended.put("opponent_displacement_blocks", combat.opponent_displacement_blocks());
                extended.put("client_ticks", 1);
                extended.put("server_ticks", (int) advanced);
                extended.put("hotbar_slot", combat.hotbar_slot());
                extended.put("golden_apples", combat.golden_apples());
                extended.put("absorption", combat.absorption());
                extended.put("regeneration", combat.regeneration());
                extended.put("critical_hits", combat.critical_hits());
                extended.put("sprint_hits", combat.sprint_hits());
                extended.put("server_metrics", serverMetrics);
                extended.put("applied_controls", step.appliedControls);
                extended.put("based_on_tick", step.request.fromTick);
                extended.put("client_tick", current.tick);
                extended.put("applied_timestamp_ms", step.appliedAt);
                extended.put("client_sprinting_after_tick", step.combatPayload.sprinting());
                extended.put("sprint_input_releases", current.sprintInputReleases);
                extended.put("sprint_input_restarts", current.sprintInputRestarts);
                extended.put("jumps", current.jumps);
                extended.put("empty_jumps_proxy", current.emptyJumps);
                result = new StepResult(observed, step.request.controls, combat.outcome(), Map.copyOf(extended));
            }
            current.terminal = result.outcome() != null;
            Map<String, Object> response = Map.of("type", "stepped", "step_id", step.request.id, "result", result);
            current.completed.put(step.request.id, new CachedStep(step.request, response));
            current.pending = null;
            step.response.complete(response);
        });
    }

    static Map<String, String> error(String code, String message) {
        return Map.of("type", "error", "code", code, "message", message == null ? code : message);
    }
}
