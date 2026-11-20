package dev.terminator.bridge.mixin;

import net.minecraft.client.render.GameRenderer;
import net.minecraft.entity.Entity;
import net.minecraft.util.hit.HitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(GameRenderer.class)
public interface CombatCrosshair {
    @Invoker("findCrosshairTarget")
    HitResult terminator$findCrosshairTarget(Entity camera, double blockRange, double entityRange, float tickDelta);
}
