package dev.terminator.arena26.mixin;
import dev.terminator.arena26.Arena26;
import net.minecraft.server.level.*;
import net.minecraft.world.*;
import net.minecraft.world.level.Level;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
@Mixin(ServerPlayerGameMode.class)
abstract class ItemUseMixin {
    @Inject(method="useItem",at=@At("HEAD"),cancellable=true)
    private void use(ServerPlayer p,Level l,ItemStack s,InteractionHand hand,CallbackInfoReturnable<InteractionResult> ci){var r=Arena26.use(p,l,s);if(r!=null)ci.setReturnValue(r);}
    @Inject(method="useItemOn",at=@At("HEAD"),cancellable=true)
    private void block(ServerPlayer p,Level l,ItemStack s,InteractionHand hand,BlockHitResult hit,CallbackInfoReturnable<InteractionResult> ci){var r=Arena26.use(p,l,s);if(r!=null)ci.setReturnValue(r);}
}
