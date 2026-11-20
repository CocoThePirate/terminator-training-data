package dev.terminator.bridge.mixin;
import net.minecraft.client.MinecraftClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
@Mixin(MinecraftClient.class)
abstract class DatasetAttack {
    @Inject(method="doAttack",at=@At("HEAD"))
    private void terminator$recordAttempt(CallbackInfoReturnable<Boolean> ci) {dev.terminator.bridge.DatasetClient.attack();}
}
