package dev.terminator.bridge.mixin;

import dev.terminator.bridge.TrainingBridge;
import dev.terminator.bridge.NavigationClient;
import net.minecraft.client.MinecraftClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MinecraftClient.class)
abstract class ClientTickMixin {
    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    private void terminator$beforeTick(CallbackInfo callback) {
        NavigationClient.beforeTick((MinecraftClient) (Object) this);
        if (!TrainingBridge.beforeClientTick((MinecraftClient) (Object) this)) {
            callback.cancel();
        }
    }

    @Inject(method = "tick", at = @At("RETURN"))
    private void terminator$afterTick(CallbackInfo callback) {
        TrainingBridge.afterClientTick((MinecraftClient) (Object) this);
        dev.terminator.bridge.DatasetClient.afterTick((MinecraftClient) (Object) this);
    }
}
