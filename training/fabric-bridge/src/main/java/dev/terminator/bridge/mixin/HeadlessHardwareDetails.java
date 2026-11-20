package dev.terminator.bridge.mixin;

import net.minecraft.util.SystemDetails;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Optional crash-report hardware inventory only; never game state or physics. */
@Mixin(SystemDetails.class)
public abstract class HeadlessHardwareDetails {
    @Inject(method = "addHardwareGroup", at = @At("HEAD"), cancellable = true)
    private void terminator$skipHeadlessHardware(oshi.SystemInfo ignored, CallbackInfo ci) {
        if (Boolean.getBoolean("terminator.headless.skipHardwareDetails")) ci.cancel();
    }
}
