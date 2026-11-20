package dev.terminator.arena26.mixin;
import net.minecraft.client.gui.screens.ChatScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(ChatScreen.class)
abstract class ClientChatMixin {@Inject(method="handleChatInput",at=@At("HEAD"),cancellable=true) private void chat(String text,boolean history,CallbackInfo ci){if(dev.terminator.arena26.client.ClientRecorder26.chat(text))ci.cancel();}}
