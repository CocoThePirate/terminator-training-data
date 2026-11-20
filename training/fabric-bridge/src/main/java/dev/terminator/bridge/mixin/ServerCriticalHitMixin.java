package dev.terminator.bridge.mixin;

import dev.terminator.bridge.TrainingBridge;
import net.minecraft.entity.Entity;
import net.minecraft.server.network.ServerPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerPlayerEntity.class)
abstract class ServerCriticalHitMixin {
    @Inject(method = "addCritParticles", at = @At("HEAD"))
    private void terminator$recordCriticalHit(Entity victim, CallbackInfo callback) {
        TrainingBridge.recordCriticalHit((ServerPlayerEntity) (Object) this, victim);
    }
}
