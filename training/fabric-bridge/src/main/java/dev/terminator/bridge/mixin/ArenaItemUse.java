package dev.terminator.bridge.mixin;
import dev.terminator.bridge.ArenaMechanics;
import net.minecraft.server.network.ServerPlayerInteractionManager;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.util.Hand;
import net.minecraft.util.ActionResult;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
@Mixin(ServerPlayerInteractionManager.class)
abstract class ArenaItemUse {
    @Inject(method="interactItem",at=@At("HEAD"),cancellable=true)
    private void terminator$use(ServerPlayerEntity player,World world,ItemStack stack,Hand hand,CallbackInfoReturnable<ActionResult> ci){
        var result=ArenaMechanics.use(player,world,stack);if(result!=null)ci.setReturnValue(result);
    }
    @Inject(method="interactBlock",at=@At("HEAD"),cancellable=true)
    private void terminator$block(ServerPlayerEntity player,World world,ItemStack stack,Hand hand,BlockHitResult hit,CallbackInfoReturnable<ActionResult> ci){
        var result=ArenaMechanics.use(player,world,stack);if(result!=null)ci.setReturnValue(result);
    }
}
