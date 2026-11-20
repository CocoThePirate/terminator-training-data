package dev.terminator.bridge.mixin;

import java.util.function.BooleanSupplier;
import dev.terminator.bridge.TrainingBridge;
import net.minecraft.server.integrated.IntegratedServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(IntegratedServer.class)
abstract class IntegratedServerTickMixin {
    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    private void terminator$beforeTick(BooleanSupplier keepTicking, CallbackInfo callback) {
        if (!TrainingBridge.beforeServerTick((IntegratedServer) (Object) this)) {
            callback.cancel();
        }
    }

    @Inject(method = "tick", at = @At("RETURN"))
    private void terminator$afterTick(BooleanSupplier keepTicking, CallbackInfo callback) {
        TrainingBridge.afterServerTick((IntegratedServer) (Object) this);
        dev.terminator.bridge.DatasetClient.serverTick((IntegratedServer) (Object) this);
    }
}
