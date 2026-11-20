package dev.terminator.arena26.mixin;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(Minecraft.class)
abstract class ClientTickMixin {@Inject(method="tick",at=@At("RETURN")) private void tick(CallbackInfo ci){dev.terminator.arena26.client.ClientRecorder26.tick();}}
