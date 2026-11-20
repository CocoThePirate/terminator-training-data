package dev.terminator.arena26.mixin;
import dev.terminator.arena26.Arena26;
import net.minecraft.server.MinecraftServer;
import java.util.function.BooleanSupplier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(MinecraftServer.class)
abstract class ServerTickMixin {
    @Inject(method="tickServer",at=@At("RETURN")) private void tick(BooleanSupplier keepTicking,CallbackInfo ci){Arena26.tick((MinecraftServer)(Object)this);}
}
