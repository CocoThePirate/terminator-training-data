package dev.terminator.bridge;

import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;

/** Received client entity/animation state, gated by a client-world sight ray.
 * No server entity, opponent health, cooldown, hidden inventory or predictions.
 * Null values mean unavailable; false means observed inactive.
 */
record OpponentCues(String schema, String target_uuid, boolean available,
                    Boolean line_of_sight, Boolean swinging, Float swing_progress,
                    Boolean using_item, Boolean sprinting, String main_hand_item,
                    String off_hand_item, String active_item, String active_hand) {
    static OpponentCues capture(MinecraftClient client, Entity target) {
        if (!(target instanceof LivingEntity living) || living.isRemoved()) {
            return new OpponentCues("client_opponent_cues/v2", null, false,
                    null, null, null, null, null, null, null, null, null);
        }
        boolean sight = client.player.canSee(living);
        if (!sight) {
            return new OpponentCues("client_opponent_cues/v2", living.getUuidAsString(), false,
                    false, null, null, null, null, null, null, null, null);
        }
        boolean using = living.isUsingItem();
        // Remote entities need not populate activeItemStack. Their tracked use
        // hand plus received equipment is what the local renderer can observe.
        return new OpponentCues("client_opponent_cues/v2", living.getUuidAsString(), true,
                true, living.handSwinging, living.getHandSwingProgress(1.0f),
                using, living.isSprinting(), item(living.getMainHandStack()),
                item(living.getOffHandStack()), using ? item(living.getStackInHand(living.getActiveHand())) : null,
                using ? living.getActiveHand().name().toLowerCase(java.util.Locale.ROOT) : null);
    }

    private static String item(ItemStack stack) {
        return Registries.ITEM.getId(stack.getItem()).toString();
    }
}
