package dev.terminator.bridge.mixin;

import dev.terminator.bridge.TrainingBridge;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(PlayerEntity.class)
abstract class SprintKnockbackMixin {
    @Inject(method = "attack", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/entity/player/PlayerEntity;setSprinting(Z)V"))
    private void terminator$recordSprintKnockback(Entity victim, CallbackInfo callback) {
        if ((Object) this instanceof ServerPlayerEntity attacker) {
            TrainingBridge.recordSprintHit(attacker, victim);
        }
    }
}
