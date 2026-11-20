package dev.terminator.bridge;

import com.google.gson.JsonObject;
import java.util.*;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.util.ActionResult;
import net.minecraft.world.World;
import static dev.terminator.bridge.ReachAndStop.require;

/** Explicit server-side arena rules. Never a policy skill or general client feature. */
public final class ArenaMechanics {
    private record Rules(int cooldown,int power,double speed,Map<UUID,Long> next,long[] launches) {}
    private static final Map<ServerWorld,Rules> WORLDS=Collections.synchronizedMap(new WeakHashMap<>());
    static void validate(JsonObject config){
        if(config==null)return;
        require(config.keySet().equals(Set.of("adapter","cooldown_ticks","explosion_power","launch_speed")),"Unknown mechanics fields");
        require(config.get("adapter").getAsString().equals("local_fireball_v1")&&config.get("cooldown_ticks").getAsDouble()==20
                &&config.get("explosion_power").getAsDouble()==1&&config.get("launch_speed").getAsDouble()==0.6,"Unsupported fireball rules");
    }
    static void install(ServerWorld world,JsonObject config){
        validate(config);if(config==null){WORLDS.remove(world);return;}
        WORLDS.put(world,new Rules(20,1,0.6,new HashMap<>(),new long[1]));
        world.getGameRules().get(net.minecraft.world.GameRules.DO_MOB_GRIEFING).set(true,world.getServer());
    }
    public static ActionResult use(ServerPlayerEntity player,World world,ItemStack stack){
        if(!(world instanceof ServerWorld server)||!stack.isOf(Items.FIRE_CHARGE))return null;
        var rules=WORLDS.get(server);if(rules==null)return null;
        if(!player.isAlive()||player.isSpectator()||rules.next.getOrDefault(player.getUuid(),0L)>world.getTime())return ActionResult.FAIL;
        var direction=player.getRotationVec(1).normalize();
        var ball=new net.minecraft.entity.projectile.FireballEntity(world,player,direction.multiply(rules.speed),rules.power);
        ball.setPosition(player.getEyePos().add(direction.multiply(0.8)));ball.setVelocity(direction.multiply(rules.speed));
        if(!server.spawnEntity(ball))return ActionResult.FAIL;
        player.getItemCooldownManager().set(stack,rules.cooldown);
        stack.decrementUnlessCreative(1,player);rules.next.put(player.getUuid(),world.getTime()+rules.cooldown);rules.launches[0]++;
        return ActionResult.SUCCESS;
    }
    static Map<String,Object> feedback(ServerWorld world){var r=WORLDS.get(world);return Map.of("adapter",r==null?"vanilla":"local_fireball_v1","fireballs_launched",r==null?0:r.launches[0]);}
}
