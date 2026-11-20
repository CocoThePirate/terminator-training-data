package dev.terminator.bridge.mixin;

import dev.terminator.bridge.TrainingBridge;
import net.minecraft.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LivingEntity.class)
abstract class PlayerJumpMixin {
    @Inject(method = "jump", at = @At("TAIL"))
    private void terminator$recordJump(CallbackInfo callback) {
        TrainingBridge.recordClientJump((LivingEntity) (Object) this);
    }
}
