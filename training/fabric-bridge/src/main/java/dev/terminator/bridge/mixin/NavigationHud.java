package dev.terminator.bridge.mixin;

import dev.terminator.bridge.NavigationClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.hud.InGameHud;
import net.minecraft.client.render.RenderTickCounter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(InGameHud.class)
abstract class NavigationHud {
    @Inject(method="render",at=@At("RETURN"))
    private void terminator$taskHud(DrawContext draw,RenderTickCounter counter,CallbackInfo ci) {
        NavigationClient.hud(draw);
    }
}
