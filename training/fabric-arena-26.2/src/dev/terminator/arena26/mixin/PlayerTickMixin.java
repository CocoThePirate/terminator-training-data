package dev.terminator.arena26.mixin;
import dev.terminator.arena26.Arena26;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(ServerPlayer.class)
abstract class PlayerTickMixin {
    @Inject(method="tick",at=@At("HEAD")) private void tick(CallbackInfo ci){Arena26.reset((ServerPlayer)(Object)this);}
}
