package dev.terminator.bridge.mixin;

import dev.terminator.bridge.TrainingBridge;
import net.minecraft.entity.LivingEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LivingEntity.class)
abstract class SprintStateTelemetryMixin {
    @Inject(method = "setSprinting", at = @At("RETURN"))
    private void terminator$sprintState(boolean sprinting, CallbackInfo ci) {
        if ((Object) this instanceof ServerPlayerEntity player) TrainingBridge.recordSprintState(player);
    }
}
