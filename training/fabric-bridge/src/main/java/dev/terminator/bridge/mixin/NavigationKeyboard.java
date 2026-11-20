package dev.terminator.bridge.mixin;

import dev.terminator.bridge.NavigationClient;
import net.minecraft.client.Keyboard;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Keyboard.class)
abstract class NavigationKeyboard {
    @Inject(method="onKey",at=@At("HEAD"),cancellable=true)
    private void terminator$key(long window,int key,int scancode,int action,int modifiers,CallbackInfo ci) {
        if (NavigationClient.enabled() && key == org.lwjgl.glfw.GLFW.GLFW_KEY_F8) {
            NavigationClient.key(key,action);
            ci.cancel();
        }
    }
}
