package dev.terminator.arena26;
import java.nio.file.*;
import java.util.UUID;
import com.google.gson.*;
import com.mojang.authlib.GameProfile;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.*;
import net.minecraft.server.network.*;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.*;

/** Test-only unconnected player; never adds a participant to human recordings. */
final class NativeCheck {
    static JsonObject run(MinecraftServer server,Path config)throws Exception{
        var document=JsonParser.parseString(Files.readString(config)).getAsJsonObject();
        var profile=new GameProfile(UUID.randomUUID(),"PortProbe");var p=new ServerPlayer(server,server.overworld(),profile,ClientInformation.createDefault());
        p.connection=new ServerGamePacketListenerImpl(server,new Connection(PacketFlow.SERVERBOUND),p,CommonListenerCookie.createInitial(profile,false));
        p.setPos(8.5,80,8.5);var source=server.createCommandSourceStack().withEntity(p);var kitResults=new JsonArray();
        for(var entry:document.getAsJsonArray("kits")){
            var kit=entry.getAsJsonObject();server.getCommands().performPrefixedCommand(source,"function "+kit.get("function").getAsString());Arena26.reset(p);
            for(var element:kit.getAsJsonArray("inventory")){
                var item=element.getAsJsonObject();int slot=item.get("slot").getAsInt();
                var actual=slot<36?p.getInventory().getItem(slot):p.getItemBySlot(new EquipmentSlot[]{EquipmentSlot.FEET,EquipmentSlot.LEGS,EquipmentSlot.CHEST,EquipmentSlot.HEAD,EquipmentSlot.OFFHAND}[slot-36]);
                var id=net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(actual.getItem()).toString();
                if(!id.equals(item.get("item").getAsString())||actual.getCount()!=item.get("count").getAsInt())throw new IllegalStateException("Kit mismatch "+kit.get("id")+" slot "+slot+" actual "+id+":"+actual.getCount());
            }
            if(p.getFoodData().getFoodLevel()!=20||p.getFoodData().getSaturationLevel()!=5||p.getHealth()!=20)throw new IllegalStateException("Reset parity failed");
            kitResults.add(kit.get("id").getAsString());
        }
        var charge=new ItemStack(Items.FIRE_CHARGE,8);p.getInventory().setItem(0,charge);p.getInventory().setSelectedSlot(0);p.addTag("terminator_fireball");
        int before=Arena26.launches;p.gameMode.useItem(p,p.level(),charge,InteractionHand.MAIN_HAND);
        if(charge.getCount()!=7||Arena26.launches!=before+1||!p.getCooldowns().isOnCooldown(charge))throw new IllegalStateException("Fireball launch failed");
        p.gameMode.useItem(p,p.level(),charge,InteractionHand.MAIN_HAND);
        if(charge.getCount()!=7||Arena26.launches!=before+1)throw new IllegalStateException("Fireball cooldown failed");
        p.removeTag("terminator_fireball");p.getCooldowns().removeCooldown(p.getCooldowns().getCooldownGroup(charge));
        p.gameMode.useItem(p,p.level(),charge,InteractionHand.MAIN_HAND);
        if(charge.getCount()!=7||Arena26.launches!=before+1)throw new IllegalStateException("Custom mechanic leaked outside tagged mode");
        var report=new JsonObject();report.addProperty("schema","terminator_native_port_check/v1");report.addProperty("minecraft","26.2");report.addProperty("passed",true);report.add("kits_verified",kitResults);
        report.addProperty("reset_health_food_saturation_verified",true);report.addProperty("fireball_use_cooldown_and_scope_verified",true);report.addProperty("human_gameplay_test",false);return report;
    }
}
