package dev.terminator.bridge.mixin;

import dev.terminator.bridge.TrainingBridge;
import java.util.ArrayList;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(PlayerEntity.class)
abstract class SwordAttackTelemetryMixin {
    @Unique private final ArrayList<TrainingBridge.AttackSample> terminator$attacks = new ArrayList<>();

    @Inject(method = "attack", at = @At("HEAD"))
    private void terminator$beforeAttack(Entity victim, CallbackInfo ci) {
        terminator$attacks.add((Object) this instanceof ServerPlayerEntity player
                ? TrainingBridge.beforeSwordAttack(player, victim) : null);
    }

    @Inject(method = "attack", at = @At("RETURN"))
    private void terminator$afterAttack(Entity victim, CallbackInfo ci) {
        TrainingBridge.afterSwordAttack(terminator$attacks.removeLast());
    }
}
