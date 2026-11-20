package dev.terminator.bridge.mixin;

import dev.terminator.bridge.NavigationClient;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPlayNetworkHandler.class)
abstract class NavigationChat {
    @Inject(method="sendChatMessage",at=@At("HEAD"),cancellable=true)
    private void terminator$localTask(String message,CallbackInfo ci) {
        if (dev.terminator.bridge.DatasetClient.localChat(message)||NavigationClient.localChat(message)) ci.cancel();
    }
}
