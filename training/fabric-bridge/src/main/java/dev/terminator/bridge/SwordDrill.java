package dev.terminator.bridge;

import com.google.gson.JsonObject;
import java.util.HashMap;
import java.util.Map;
import dev.terminator.bridge.mixin.CombatCrosshair;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.SpawnReason;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.mob.HuskEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.math.Vec3d;

import static dev.terminator.bridge.ReachAndStop.*;

final class SwordDrill {
    static final String SCHEMA = "sword_drill/v1";
    private static final int APPLE_COUNT = 3;

    private final Vec3d spawn;
    private final boolean movingOpponent;
    private final String opponentStyle;
    private final float startHealth;
    private final boolean visibleTrainingBorder;
    private final int startApples;
    private final float opponentStartHealth;
    private ServerPlayerEntity trainee;
    private long styleTicks;
    private volatile int targetId = -1;
    private HuskEntity target;
    final Telemetry telemetry = new Telemetry();
    private float previousDamage;
    private float previousDamageTaken;
    private int criticalHits;
    private int sprintHits;
    boolean attackHeld;

    SwordDrill(Vec3d spawn, boolean movingOpponent, JsonObject setup) {
        this.spawn = spawn;
        this.movingOpponent = movingOpponent;
        opponentStyle = setup.has("opponent_style") ? setup.get("opponent_style").getAsString() : "pursuit";
        require(java.util.Set.of("pursuit", "rush", "armored", "armored_rush",
                "orbit_left", "orbit_right", "spacing", "pressure_switch").contains(opponentStyle),
                "Unknown opponent style");
        startHealth = setup.has("player_start_health") ? setup.get("player_start_health").getAsFloat() : 20;
        visibleTrainingBorder = setup.has("visible_training_border")
                && setup.get("visible_training_border").getAsBoolean();
        startApples = setup.has("player_start_apples") ? setup.get("player_start_apples").getAsInt() : APPLE_COUNT;
        opponentStartHealth = setup.has("opponent_start_health") ? setup.get("opponent_start_health").getAsFloat() : 20;
        require(startApples >= 0 && startApples <= APPLE_COUNT, "Starting apples must be between 0 and 3");
        require(Float.isFinite(opponentStartHealth) && opponentStartHealth >= 20 && opponentStartHealth <= 100,
                "Opponent health must be between 20 and 100");
        require(Float.isFinite(startHealth) && startHealth >= 1 && startHealth <= 20,
                "Starting health must be between 1 and 20");
    }

    static Vec3d targetPosition(JsonObject setup, Vec3d playerSpawn) {
        vector(setup, "opponent_xz", 2);
        double[] xz = JSON.fromJson(setup.get("opponent_xz"), double[].class);
        var target = new Vec3d(xz[0], FLOOR_Y, xz[1]);
        require(inArena(target.x, target.z) && target.squaredDistanceTo(playerSpawn) >= 1,
                "Invalid sword target spawn");
        return target;
    }

    boolean equipAndSpawn(ServerPlayerEntity player) {
        trainee = player;
        equip(player);
        player.getInventory().setStack(1, startApples == 0 ? ItemStack.EMPTY : new ItemStack(Items.GOLDEN_APPLE, startApples));
        player.setHealth(startHealth);
        var world = player.getServerWorld();
        if (visibleTrainingBorder) {
            world.getWorldBorder().setCenter(8.0, 8.0);
            world.getWorldBorder().setSize(16.0);
        }
        target = EntityType.HUSK.create(world, SpawnReason.COMMAND);
        if (target == null) {
            return false;
        }
        target.setAiDisabled(true);
        target.getAttributeInstance(EntityAttributes.MAX_HEALTH).setBaseValue(opponentStartHealth);
        target.setHealth(opponentStartHealth);
        target.setPersistent();
        target.setPosition(spawn);
        if (movingOpponent) {
            target.equipStack(EquipmentSlot.MAINHAND, new ItemStack(Items.DIAMOND_SWORD));
            if (opponentStyle.equals("rush") || opponentStyle.equals("armored_rush")) {
                target.getAttributeInstance(EntityAttributes.MOVEMENT_SPEED).setBaseValue(0.32);
            }
            if (!opponentStyle.equals("pursuit") && !opponentStyle.equals("rush")) {
                target.equipStack(EquipmentSlot.HEAD, new ItemStack(Items.IRON_HELMET));
                target.equipStack(EquipmentSlot.CHEST, new ItemStack(Items.IRON_CHESTPLATE));
            }
        }
        if (!world.spawnEntity(target)) {
            return false;
        }
        targetId = target.getId();
        return true;
    }

    static void equip(ServerPlayerEntity player) {
        player.getInventory().setSelectedSlot(0);
        player.getInventory().setStack(0, new ItemStack(Items.DIAMOND_SWORD));
        player.getInventory().setStack(1, new ItemStack(Items.GOLDEN_APPLE, APPLE_COUNT));
        player.equipStack(EquipmentSlot.HEAD, new ItemStack(Items.DIAMOND_HELMET));
        player.equipStack(EquipmentSlot.CHEST, new ItemStack(Items.DIAMOND_CHESTPLATE));
        player.equipStack(EquipmentSlot.LEGS, new ItemStack(Items.DIAMOND_LEGGINGS));
        player.equipStack(EquipmentSlot.FEET, new ItemStack(Items.DIAMOND_BOOTS));
    }

    void beginTick() {
        if (movingOpponent && target.isAiDisabled()) {
            target.setAiDisabled(false);
        }
        if (movingOpponent && trainee != null && trainee.isAlive() && target.isAlive()
                && java.util.Set.of("orbit_left", "orbit_right", "spacing", "pressure_switch").contains(opponentStyle)) {
            // Steering waypoints use vanilla navigation, melee AI and world
            // physics. These are scripted mob styles, not human/self-play bots.
            styleTicks++;
            target.setTarget(trainee);
            double dx = target.getX() - trainee.getX();
            double dz = target.getZ() - trainee.getZ();
            double distance = Math.hypot(dx, dz);
            if (distance < 0.01) { dx = 1; dz = 0; distance = 1; }
            double rx = dx / distance, rz = dz / distance;
            double x = trainee.getX(), z = trainee.getZ();
            if (opponentStyle.equals("spacing")) {
                if (styleTicks % 70 >= 28) { x += rx * 4.0; z += rz * 4.0; }
            } else if (!(opponentStyle.equals("pressure_switch") && styleTicks % 80 < 24)
                    && distance < 5.0) {
                double side = opponentStyle.equals("orbit_right") ? -1 : 1;
                if (opponentStyle.equals("pressure_switch")) { side = (styleTicks / 80) % 2 == 0 ? 1 : -1; }
                x += rx * 2.2 - rz * 1.2 * side;
                z += rz * 2.2 + rx * 1.2 * side;
            }
            x = Math.max(0.75, Math.min(15.25, x));
            z = Math.max(0.75, Math.min(15.25, z));
            target.getNavigation().startMovingTo(x, trainee.getY(), z, 1.0);
        }
    }

    boolean clientReady(MinecraftClient client) {
        var player = client.player;
        return client.world.getEntityById(targetId) != null
                && player.getMainHandStack().isOf(Items.DIAMOND_SWORD)
                && (startApples == 0 ? player.getInventory().getStack(1).isEmpty()
                    : player.getInventory().getStack(1).isOf(Items.GOLDEN_APPLE)
                        && player.getInventory().getStack(1).getCount() == startApples)
                && Math.abs(player.getHealth() - startHealth) < 0.0001
                && player.getEquippedStack(EquipmentSlot.HEAD).isOf(Items.DIAMOND_HELMET)
                && player.getEquippedStack(EquipmentSlot.CHEST).isOf(Items.DIAMOND_CHESTPLATE)
                && player.getEquippedStack(EquipmentSlot.LEGS).isOf(Items.DIAMOND_LEGGINGS)
                && player.getEquippedStack(EquipmentSlot.FEET).isOf(Items.DIAMOND_BOOTS);
    }

    boolean usesNaturalRegeneration() {
        return startHealth == 20;
    }

    record CombatPayload(Player player, float pitch_degrees, float health, float attack_cooldown,
                         double[] opponent_position, String objective, int hotbar_slot,
                         int golden_apples, boolean using_item, float absorption,
                         boolean on_ground, double vertical_speed, float fall_distance,
                         boolean sprinting, double[] eye_position, double[] aim_point,
                         String sword_target, double[] world_border_xz, OpponentCues opponent_cues) {}

    CombatPayload observe(MinecraftClient client, Player player) {
        return observe(client, player, client.world.getEntityById(targetId));
    }

    static CombatPayload observe(MinecraftClient client, Player player, Entity opponent) {
        var self = client.player;
        var eye = self.getEyePos();
        var aim = opponent == null ? null : opponent.getBoundingBox().getCenter();
        return new CombatPayload(player, client.player.getPitch(), client.player.getHealth(),
                client.player.getAttackCooldownProgress(0),
                opponent == null ? null : Player.coords(opponent.getPos()), "defeat_opponent",
                client.player.getInventory().selectedSlot, apples(client.player),
                client.player.isUsingItem(), client.player.getAbsorptionAmount(),
                client.player.isOnGround(), client.player.getVelocity().y,
                self.fallDistance, self.isSprinting(), Player.coords(eye),
                aim == null ? null : Player.coords(aim),
                opponent == null ? null : swordTarget(client, opponent, eye, aim),
                new double[] {client.world.getWorldBorder().getBoundWest(),
                        client.world.getWorldBorder().getBoundNorth(),
                        client.world.getWorldBorder().getBoundEast(),
                        client.world.getWorldBorder().getBoundSouth()}, OpponentCues.capture(client, opponent));
    }

    private static String swordTarget(MinecraftClient client, Entity opponent, Vec3d eye, Vec3d aim) {
        var self = client.player;
        if (!client.world.getWorldBorder().contains(opponent.getBlockPos())) {
            return "outside_border";
        }
        if (!self.canInteractWithEntity(opponent, 0)) {
            return "out_of_reach";
        }
        var offset = aim.subtract(eye);
        float yaw = self.getYaw();
        float pitch = self.getPitch();
        try {
            self.setYaw((float) Math.toDegrees(Math.atan2(-offset.x, offset.z)));
            self.setPitch((float) Math.toDegrees(Math.atan2(-offset.y, Math.hypot(offset.x, offset.z))));
            var hit = ((CombatCrosshair) client.gameRenderer).terminator$findCrosshairTarget(
                    self, self.getBlockInteractionRange(), self.getEntityInteractionRange(), 1);
            return hit instanceof EntityHitResult entityHit && entityHit.getEntity() == opponent
                    ? "reachable" : "blocked";
        } finally {
            self.setYaw(yaw);
            self.setPitch(pitch);
        }
    }

    void recordHit(Entity victim, boolean sprint) {
        if (victim == target) {
            if (sprint) {
                sprintHits++;
            } else {
                criticalHits++;
            }
        }
    }

    boolean isTarget(Entity victim) { return victim == target; }

    /** Server-thread counters, scoped to one registered host/episode. Never policy input. */
    static final class Telemetry {
        int attempts, hits, misses, lowCooldown, invulnerableMisses;
        int sprintStarts, sprintStops, sprintRestarts;
        int criticalHits, sprintHits;
        int knockbackFailures;
        float damageDealt, damageTaken;
        private boolean sprinting, stopped;

        void sampleSprint(ServerPlayerEntity player) {
            boolean next = player.isSprinting();
            if (next && !sprinting) {
                sprintStarts++;
                if (stopped) sprintRestarts++;
            } else if (!next && sprinting) {
                sprintStops++;
                stopped = true;
            }
            sprinting = next;
        }

        Map<String, Object> snapshot(ServerPlayerEntity player) {
            var m = new HashMap<String, Object>();
            m.put("attack_attempts", attempts);
            m.put("successful_hits", hits);
            m.put("misses", misses);
            m.put("low_cooldown_attempts", lowCooldown);
            m.put("invulnerable_misses", invulnerableMisses);
            m.put("damage_dealt", damageDealt);
            m.put("damage_taken", damageTaken);
            m.put("actual_damage_dealt", damageDealt);
            m.put("actual_damage_taken", damageTaken);
            m.put("sprint_knockback_failures_proxy", knockbackFailures);
            m.put("critical_hits", criticalHits);
            m.put("sprint_hits", sprintHits);
            m.put("sprint_starts", sprintStarts);
            m.put("sprint_stops", sprintStops);
            m.put("sprint_restarts", sprintRestarts);
            m.put("sprinting", player.isSprinting());
            m.put("health", player.getHealth());
            m.put("absorption", player.getAbsorptionAmount());
            m.put("golden_apples", apples(player));
            return Map.copyOf(m);
        }
    }

    private static int apples(net.minecraft.entity.player.PlayerEntity player) {
        var stack = player.getInventory().getStack(1);
        return stack.isOf(Items.GOLDEN_APPLE) ? stack.getCount() : 0;
    }

    record Result(float opponent_health, float damage_dealt, float player_health, float damage_taken,
                  double opponent_displacement_blocks, int hotbar_slot, int golden_apples,
                  float absorption, boolean regeneration, int critical_hits, int sprint_hits, String outcome) {}

    Result score(ServerPlayerEntity player, long elapsed, long tickLimit) {
        float health = target.getHealth();
        float damage = telemetry.damageDealt - previousDamage;
        float playerHealth = player.getHealth();
        float damageTaken = telemetry.damageTaken - previousDamageTaken;
        previousDamage = telemetry.damageDealt;
        previousDamageTaken = telemetry.damageTaken;
        String outcome = null;
        if (!player.isAlive() || !inArena(player.getX(), player.getZ())) {
            outcome = "failure";
        } else if (!target.isAlive()) {
            outcome = "success";
        } else if (elapsed >= tickLimit) {
            outcome = "timeout";
        }
        return new Result(health, damage, playerHealth, damageTaken,
                Math.hypot(target.getX() - spawn.x, target.getZ() - spawn.z),
                player.getInventory().selectedSlot, apples(player), player.getAbsorptionAmount(),
                player.hasStatusEffect(StatusEffects.REGENERATION), criticalHits, sprintHits, outcome);
    }

    record Metrics(float opponent_health, float damage_dealt, float player_health, float damage_taken,
                   double opponent_displacement_blocks,
                   int client_ticks, int server_ticks, int hotbar_slot, int golden_apples,
                   float absorption, boolean regeneration, int critical_hits, int sprint_hits) {}
}
