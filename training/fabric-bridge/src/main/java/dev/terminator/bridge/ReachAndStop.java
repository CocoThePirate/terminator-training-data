package dev.terminator.bridge;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.util.math.Vec3d;

final class ReachAndStop {
    static final String SCHEMA = "reach_and_stop/v1";
    static final String PROFILE = "java-1.21.4-fabric-0.16.10-vanilla";
    static final String TEMPLATE = "flat-arena-v1";
    static final Gson JSON = new GsonBuilder().serializeNulls().create();
    static final double FLOOR_Y = -60;
    private static final double ARENA_EDGE = 16;

    record Goal(double[] target_xz, double radius_blocks, double max_speed_blocks_per_tick,
                int dwell_ticks) {}

    record Setup(double[] spawn_xyz, float spawn_yaw_degrees, Goal goal) {
        Vec3d spawn() {
            return new Vec3d(spawn_xyz[0], spawn_xyz[1], spawn_xyz[2]);
        }
    }

    record Scenario(String episode_id, String game_profile, String world_template,
                    String generator_version, Setup resolved_setup, long tick_limit) {
        static Scenario parse(JsonObject value) {
            // Validate presence and type before deserializing; Gson defaults absent primitives.
            JsonObject setup = value.getAsJsonObject("resolved_setup");
            vector(setup, "spawn_xyz", 3);
            number(setup, "spawn_yaw_degrees");
            positiveInteger(value, "tick_limit", Integer.MAX_VALUE);

            Scenario scenario = JSON.fromJson(value, Scenario.class);
            require(PROFILE.equals(scenario.game_profile) && TEMPLATE.equals(scenario.world_template)
                    && (SCHEMA.equals(scenario.generator_version) || scenario.swordDrill()),
                    "Unsupported scenario profile");
            require(scenario.episode_id != null && !scenario.episode_id.isBlank(), "Missing episode ID");
            Setup s = scenario.resolved_setup;
            require(s.spawn().y == FLOOR_Y && inArena(s.spawn().x, s.spawn().z)
                    && Float.isFinite(s.spawn_yaw_degrees), "Invalid spawn");
            if (scenario.swordDrill()) {
                SwordDrill.targetPosition(setup, s.spawn());
                return scenario;
            }
            JsonObject goal = setup.getAsJsonObject("goal");
            vector(goal, "target_xz", 2);
            number(goal, "radius_blocks");
            number(goal, "max_speed_blocks_per_tick");
            positiveInteger(goal, "dwell_ticks", Integer.MAX_VALUE);
            Goal g = s.goal;
            require(inArena(g.target_xz[0], g.target_xz[1]) && g.radius_blocks > 0
                    && g.max_speed_blocks_per_tick >= 0, "Invalid goal");
            require(distance(s.spawn(), g) > g.radius_blocks, "Spawn must be outside the target");
            return scenario;
        }

        boolean swordDrill() {
            return SwordDrill.SCHEMA.equals(generator_version);
        }
    }

    record Controls(boolean forward, boolean back, boolean left, boolean right, boolean jump,
                    boolean sprint, boolean sneak, boolean attack, boolean use_item,
                    float yaw_delta_degrees, float pitch_delta_degrees, Integer hotbar_slot) {
        static Controls parse(JsonObject value, boolean swordDrill) {
            for (String key : new String[]{"forward", "back", "left", "right", "jump", "sprint",
                    "sneak", "attack", "use_item"}) {
                require(value.has(key) && value.get(key).isJsonPrimitive()
                        && value.getAsJsonPrimitive(key).isBoolean(), "Expected boolean " + key);
            }
            number(value, "yaw_delta_degrees");
            number(value, "pitch_delta_degrees");
            if (value.has("hotbar_slot") && !value.get("hotbar_slot").isJsonNull()) {
                number(value, "hotbar_slot");
                int slot = value.get("hotbar_slot").getAsBigDecimal().intValueExact();
                require(slot >= 0 && slot <= 8, "Hotbar slot must be between 0 and 8");
            }
            Controls c = JSON.fromJson(value, Controls.class);
            require(!(c.forward && c.back) && !(c.left && c.right), "Opposing movement buttons");
            require(!c.sneak
                    && Math.abs(c.yaw_delta_degrees) <= 180
                    && (swordDrill ? Math.abs(c.pitch_delta_degrees) <= 180 && !(c.attack && c.use_item)
                                  : !c.sprint && !c.jump && !c.attack && !c.use_item && c.hotbar_slot == null
                                        && c.pitch_delta_degrees == 0),
                    "Unsupported control for this scenario");
            return c;
        }
    }

    record Player(double[] position, double[] velocity, float yaw_degrees, boolean grounded) {
        static Player capture(ClientPlayerEntity player) {
            return new Player(coords(player.getPos()), coords(player.getVelocity()),
                    player.getYaw(), player.isOnGround());
        }

        static double[] coords(Vec3d vector) {
            return new double[]{vector.x, vector.y, vector.z};
        }
    }

    record Payload(Player player, Goal goal) {}
    record Observation(String episode_id, long tick, String schema, Object payload) {}
    record Metrics(int dwell_count, double distance_blocks, double speed_blocks_per_tick,
                   int client_ticks, int server_ticks) {}
    record StepResult(Observation observation, Controls applied_controls, String outcome,
                      Object metrics) {}

    private int dwell;

    StepResult score(Scenario scenario, long elapsed, long tick, Player player, Controls controls,
                     Vec3d previous, Vec3d current, int clientTicks, int serverTicks) {
        Goal goal = scenario.resolved_setup.goal;
        double distance = distance(current, goal);
        double speed = Math.hypot(current.x - previous.x, current.z - previous.z);
        dwell = distance <= goal.radius_blocks && speed <= goal.max_speed_blocks_per_tick ? dwell + 1 : 0;
        String outcome = null;
        if (!inArena(current.x, current.z)) {
            outcome = "failure";
        } else if (dwell >= goal.dwell_ticks) {
            outcome = "success";
        } else if (elapsed >= scenario.tick_limit) {
            outcome = "timeout";
        }
        return new StepResult(observation(scenario, tick, player), controls, outcome,
                new Metrics(dwell, distance, speed, clientTicks, serverTicks));
    }

    static Observation observation(Scenario scenario, long tick, Player player) {
        return new Observation(scenario.episode_id, tick, SCHEMA, new Payload(player, scenario.resolved_setup.goal));
    }

    private static double distance(Vec3d position, Goal goal) {
        return Math.hypot(position.x - goal.target_xz[0], position.z - goal.target_xz[1]);
    }

    static boolean inArena(double x, double z) {
        return x >= 0 && x < ARENA_EDGE && z >= 0 && z < ARENA_EDGE;
    }

    static void vector(JsonObject object, String key, int length) {
        var values = object.getAsJsonArray(key);
        require(values.size() == length, "Invalid " + key);
        for (var value : values) {
            require(value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber()
                    && Double.isFinite(value.getAsDouble()), "Invalid " + key);
        }
    }

    private static void number(JsonObject object, String key) {
        var value = object.get(key);
        require(value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber()
                && Double.isFinite(value.getAsDouble()), "Invalid " + key);
    }

    static long positiveInteger(JsonObject object, String key, long max) {
        number(object, key);
        long number = object.get(key).getAsBigDecimal().longValueExact();
        require(number > 0 && number <= max, "Invalid " + key);
        return number;
    }

    static void require(boolean valid, String message) {
        if (!valid) {
            throw new IllegalArgumentException(message);
        }
    }
}
