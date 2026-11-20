package dev.terminator.arena26;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.level.Level;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.entity.projectile.hurtingprojectile.LargeFireball;
import java.nio.file.*;
import com.google.gson.*;
import dev.terminator.arena26.mixin.PlayerFoodAccess;

/** Private data-arena adapter, independent of navigation or learned controllers. */
public final class Arena26 {
    private static boolean initialized,checked;
    static int launches;
    public static boolean enabled(){return Boolean.getBoolean("terminator.trainingArena");}
    public static InteractionResult use(ServerPlayer p,Level level,ItemStack stack){
        if(!enabled()||!(level instanceof ServerLevel world)||!p.entityTags().contains("terminator_fireball")||!stack.is(Items.FIRE_CHARGE))return null;
        if(!p.isAlive()||p.isSpectator()||p.getCooldowns().isOnCooldown(stack))return InteractionResult.FAIL;
        var direction=p.getLookAngle().normalize();var ball=new LargeFireball(world,p,direction.scale(0.6),1);
        ball.setPos(p.getEyePosition().add(direction.scale(0.8)));ball.setDeltaMovement(direction.scale(0.6));
        if(!world.addFreshEntity(ball))return InteractionResult.FAIL;
        p.getCooldowns().addCooldown(stack,20);stack.consume(1,p);launches++;return InteractionResult.SUCCESS;
    }
    public static void reset(ServerPlayer p){
        if(!enabled()||!p.entityTags().contains("terminator_reset"))return;
        p.removeTag("terminator_reset");p.stopUsingItem();p.removeAllEffects();p.setAbsorptionAmount(0);p.setHealth(20);p.clearFire();
        ((PlayerFoodAccess)p).terminator$food(new net.minecraft.world.food.FoodData());
        p.setDeltaMovement(Vec3.ZERO);p.fallDistance=0;p.setSprinting(false);p.resetAttackStrengthTicker();p.getInventory().setSelectedSlot(0);
        for(int i=0;i<p.getInventory().getContainerSize();i++){var stack=p.getInventory().getItem(i);if(!stack.isEmpty())p.getCooldowns().removeCooldown(p.getCooldowns().getCooldownGroup(stack));}
    }
    public static void tick(MinecraftServer server){
        if(!enabled()||server.overworld()==null||!server.isReady())return;
        if(!initialized){initialized=true;server.getCommands().performPrefixedCommand(server.createCommandSourceStack(),"data modify storage terminator:runtime adapter set value \"local_fireball_26_2_v1\"");}
        var config=System.getProperty("terminator.portCheck");if(config!=null&&!checked){checked=true;
            var result=new JsonObject();try{result=NativeCheck.run(server,Path.of(config));}catch(Throwable e){result.addProperty("passed",false);result.addProperty("error",e.toString());e.printStackTrace();}
            try{Files.writeString(Path.of(System.getProperty("terminator.portReport")),new GsonBuilder().setPrettyPrinting().create().toJson(result));}catch(Exception e){throw new RuntimeException(e);}
        }
    }
}
