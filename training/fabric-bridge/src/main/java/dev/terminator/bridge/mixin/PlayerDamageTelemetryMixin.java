package dev.terminator.bridge.mixin;

import dev.terminator.bridge.TrainingBridge;
import java.util.ArrayList;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(PlayerEntity.class)
abstract class PlayerDamageTelemetryMixin {
    @Unique private final ArrayList<float[]> terminator$damage = new ArrayList<>();

    @Inject(method = "applyDamage", at = @At("HEAD"))
    private void terminator$beforeDamage(ServerWorld world, DamageSource source, float amount, CallbackInfo ci) {
        var player = (PlayerEntity) (Object) this;
        terminator$damage.add(new float[]{player.getHealth(), player.getAbsorptionAmount()});
    }

    @Inject(method = "applyDamage", at = @At("RETURN"))
    private void terminator$afterDamage(ServerWorld world, DamageSource source, float amount, CallbackInfo ci) {
        float[] before = terminator$damage.removeLast();
        if ((Object) this instanceof ServerPlayerEntity player) {
            TrainingBridge.recordAppliedDamage(player, before[0], before[1]);
        }
    }
}
