package dev.terminator.arena26.mixin;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
@Mixin(Minecraft.class)
abstract class ClientAttackMixin {@Inject(method="startAttack",at=@At("HEAD")) private void attack(CallbackInfoReturnable<Boolean> ci){dev.terminator.arena26.client.ClientRecorder26.attack();}}
