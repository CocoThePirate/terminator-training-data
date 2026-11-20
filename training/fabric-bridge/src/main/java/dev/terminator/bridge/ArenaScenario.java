package dev.terminator.bridge;

import com.google.gson.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import static dev.terminator.bridge.ReachAndStop.require;

/** Reproducible private-world setup. Never a policy observation or a learned skill. */
final class ArenaScenario {
    private record Stack(int slot,String item,int count) {}
    private record Box(int x0,int y0,int z0,int x1,int y1,int z1,BlockState state) {}
    private final JsonObject spec;
    private final List<Stack> inventory=new ArrayList<>();
    private final List<Box> boxes=new ArrayList<>();
    private final BlockState floor;
    private final int size,feet;
    private final boolean expanded,voidFloor;
    private final List<net.minecraft.util.math.Vec3d> spawns=new ArrayList<>();
    private Map<String,Object> resetAudit=Map.of();

    ArenaScenario(JsonObject source) {
        require(source!=null && source.toString().length()<65536,"Missing or oversized arena scenario");
        spec=source.deepCopy();
        expanded=spec.get("schema").getAsString().equals("arena_scenario/v2");
        require(expanded||spec.get("schema").getAsString().equals("arena_scenario/v1"),"Unsupported scenario schema");
        require(spec.get("id").getAsString().matches("[a-z0-9_.-]{1,120}"),"Invalid scenario id");
        require(Set.of("training","validation","evaluation").contains(spec.get("split").getAsString()),"Invalid dataset split");
        var unsigned=spec.deepCopy();unsigned.remove("configuration_sha256");
        require(sha(canonical(unsigned)).equals(spec.get("configuration_sha256").getAsString()),"Scenario configuration hash mismatch");
        var env=spec.getAsJsonObject("environment");
        size=integer(env.get("border_size"));feet=integer(env.get("feet_y"));
        require(env.get("difficulty").getAsString().equals("normal")&&env.get("natural_regeneration").getAsBoolean()
                &&size>=17&&size<=49&&size%2==1&&feet>=-60&&feet<=0
                &&(expanded||(size==17&&feet==-60))
                &&env.getAsJsonArray("center").size()==2&&env.getAsJsonArray("center").get(0).getAsDouble()==size/2.0
                &&env.getAsJsonArray("center").get(1).getAsDouble()==size/2.0,"Unsupported arena environment");
        var kit=spec.getAsJsonObject("kit");var slots=new HashSet<Integer>();
        require(kit.get("version").getAsInt()==1,"Unsupported kit version");
        require(kit.getAsJsonArray("inventory").size()<=41,"Too many inventory entries");
        for(var entry:kit.getAsJsonArray("inventory")) {
            var s=entry.getAsJsonObject();int slot=integer(s.get("slot")),count=integer(s.get("count"));
            var id=id(s.get("item").getAsString());
            require(slot>=0&&slot<=40&&slots.add(slot)&&Registries.ITEM.containsId(id),"Invalid or duplicate kit slot/item");
            require(count>=1&&count<=new ItemStack(Registries.ITEM.get(id)).getMaxCount(),"Invalid item count");
            inventory.add(new Stack(slot,id.toString(),count));
        }
        var map=spec.getAsJsonObject("map");require(map.get("version").getAsInt()==(expanded?2:1),"Unsupported map version");
        voidFloor=expanded&&map.get("void").getAsBoolean();
        floor=block(map.get("floor").getAsString());require(voidFloor?floor.isAir():floor.isSolidBlock(net.minecraft.world.EmptyBlockView.INSTANCE,BlockPos.ORIGIN),"Invalid map floor");
        require(map.getAsJsonArray("boxes").size()<=64,"Too many map features");
        for(var element:map.getAsJsonArray("boxes")) {
            var b=element.getAsJsonObject();var min=b.getAsJsonArray("min");var max=b.getAsJsonArray("max");
            require(min.size()==3&&max.size()==3,"Map box needs XYZ bounds");
            int x0=integer(min.get(0)),y0=integer(min.get(1)),z0=integer(min.get(2));
            int x1=integer(max.get(0)),y1=integer(max.get(1)),z1=integer(max.get(2));
            require(x0>=(expanded?0:1)&&x1<=size-(expanded?1:2)&&z0>=(expanded?0:1)&&z1<=size-(expanded?1:2)&&y0>=(expanded?-8:0)&&y1<=(expanded?8:4)&&feet+y0>=-64&&x0<=x1&&y0<=y1&&z0<=z1,"Map feature exceeds arena bounds");
            boxes.add(new Box(x0,y0,z0,x1,y1,z1,block(b.get("block").getAsString())));
        }
        if(expanded){
            require(map.getAsJsonArray("spawns").size()==2,"Exactly two spawns required");
            for(var element:map.getAsJsonArray("spawns")){
                var a=element.getAsJsonArray();require(a.size()==3,"Spawn needs XYZ");
                var p=new net.minecraft.util.math.Vec3d(a.get(0).getAsDouble(),a.get(1).getAsDouble(),a.get(2).getAsDouble());
                require(Double.isFinite(p.x)&&Double.isFinite(p.z)&&p.x>=1&&p.x<size-1&&p.z>=1&&p.z<size-1&&p.y==feet,"Invalid spawn");
                checkSpawn(p.x,p.z);require(!desired((int)Math.floor(p.x),feet-1,(int)Math.floor(p.z),-64).isAir(),"Unsupported spawn");spawns.add(p);
            }
        }
        ArenaMechanics.validate(spec.has("mechanics")?spec.getAsJsonObject("mechanics"):null);
    }
    private static int integer(JsonElement v) {int n=v.getAsInt();require(v.getAsDouble()==n,"Expected integer");return n;}
    private static Identifier id(String value) {var id=Identifier.tryParse(value);require(id!=null&&id.getNamespace().equals("minecraft"),"Use a vanilla registry id");return id;}
    private static BlockState block(String value) {var id=id(value);require(Registries.BLOCK.containsId(id),"Unknown block");return Registries.BLOCK.get(id).getDefaultState();}
    private static String canonical(JsonElement v) {
        if(v.isJsonObject()) {var parts=new ArrayList<String>();for(var key:new TreeSet<>(v.getAsJsonObject().keySet()))parts.add(new JsonPrimitive(key)+":"+canonical(v.getAsJsonObject().get(key)));return "{"+String.join(",",parts)+"}";}
        if(v.isJsonArray()) {var parts=new ArrayList<String>();for(var e:v.getAsJsonArray())parts.add(canonical(e));return "["+String.join(",",parts)+"]";}
        return v.toString();
    }
    private static String sha(String text) {
        try {return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));}
        catch(java.security.NoSuchAlgorithmException impossible) {throw new IllegalStateException(impossible);}
    }
    private BlockState desired(int x,int y,int z,int bottom) {
        if(expanded){
            if((x==-1||x==size||z==-1||z==size)&&y>=feet&&y<=feet+5)return Blocks.STONE.getDefaultState();
            for(int i=boxes.size()-1;i>=0;i--){var b=boxes.get(i);if(x>=b.x0&&x<=b.x1&&z>=b.z0&&z<=b.z1&&y-feet>=b.y0&&y-feet<=b.y1)return b.state;}
            if(voidFloor)return Blocks.AIR.getDefaultState();
            if(y==bottom)return Blocks.BEDROCK.getDefaultState();
            if(y<feet-1)return Blocks.DIRT.getDefaultState();
            return y==feet-1?floor:Blocks.AIR.getDefaultState();
        }
        if(y==bottom)return Blocks.BEDROCK.getDefaultState();
        if(y< -61)return Blocks.DIRT.getDefaultState();
        if(y== -61)return floor;
        if((x== -1||x==17||z== -1||z==17)&&y<= -55)return Blocks.STONE.getDefaultState();
        for(int i=boxes.size()-1;i>=0;i--) {var b=boxes.get(i);if(x>=b.x0&&x<=b.x1&&z>=b.z0&&z<=b.z1&&y+60>=b.y0&&y+60<=b.y1)return b.state;}
        return Blocks.AIR.getDefaultState();
    }
    Map<String,Object> reset(ServerWorld world) {
        var server=world.getServer();server.setDifficulty(net.minecraft.world.Difficulty.NORMAL,true);
        world.getGameRules().get(net.minecraft.world.GameRules.NATURAL_REGENERATION).set(true,server);
        world.getWorldBorder().setCenter(size/2.0,size/2.0);world.getWorldBorder().setSize(size);
        ArenaMechanics.install(world,spec.has("mechanics")?spec.getAsJsonObject("mechanics"):null);
        var discard=new ArrayList<net.minecraft.entity.Entity>();
        for(var entity:world.iterateEntities())if(!(entity instanceof ServerPlayerEntity))discard.add(entity);
        for(var entity:discard)entity.discard();
        int changed=0,checked=0;var pos=new BlockPos.Mutable();var digest=new StringBuilder();
        for(int x= -1;x<=size;x++)for(int z= -1;z<=size;z++)for(int y=world.getBottomY();y<=world.getTopYInclusive();y++) {
            pos.set(x,y,z);var expected=desired(x,y,z,world.getBottomY());
            if(!world.getBlockState(pos).equals(expected)) {world.setBlockState(pos,expected,3);changed++;}
            checked++;
        }
        for(int x= -1;x<=size;x++)for(int z= -1;z<=size;z++)for(int y=world.getBottomY();y<=world.getTopYInclusive();y++) {
            pos.set(x,y,z);var state=world.getBlockState(pos);
            require(state.equals(desired(x,y,z,world.getBottomY())),"Arena geometry failed reset verification");
            if(!state.isAir())digest.append(x).append(',').append(y).append(',').append(z).append(':').append(state).append(';');
        }
        resetAudit=Map.of("verified",true,"checked_cells",checked,"changed_cells",changed,
                "removed_entities",discard.size(),"actual_world_sha256",sha(digest.toString()),
                "difficulty",world.getDifficulty().getName(),"natural_regeneration",world.getGameRules().getBoolean(net.minecraft.world.GameRules.NATURAL_REGENERATION));
        return resetAudit;
    }
    void equip(ServerPlayerEntity player,int apples) {
        for(var s:inventory)player.getInventory().setStack(s.slot,new ItemStack(Registries.ITEM.get(id(s.item)),s.count));
        var slot=inventory.stream().filter(s->s.slot==1).findFirst();
        require(slot.isEmpty()||slot.get().item.equals("minecraft:golden_apple")||apples==0,"Apple override would replace another item");
        if(slot.isEmpty()||slot.get().item.equals("minecraft:golden_apple"))player.getInventory().setStack(1,
                apples==0?ItemStack.EMPTY:new ItemStack(net.minecraft.item.Items.GOLDEN_APPLE,apples));
        player.getInventory().setSelectedSlot(0);
        for(int i=0;i<player.getInventory().size();i++){var stack=player.getInventory().getStack(i);if(!stack.isEmpty())player.getItemCooldownManager().remove(player.getItemCooldownManager().getGroup(stack));}
    }
    void checkSpawn(double x,double z) {
        int ix=(int)Math.floor(x),iz=(int)Math.floor(z);
        require(desired(ix,feet,iz,-64).isAir()&&desired(ix,feet+1,iz,-64).isAir(),"Scenario blocks a spawn");
    }
    net.minecraft.util.math.Vec3d spawn(int index,double distance,boolean mirror){
        if(expanded)return spawns.get(mirror?1-index:index);
        return new net.minecraft.util.math.Vec3d(8.5+(index==0?-1:1)*(mirror?-1:1)*distance/2,feet,8.5);
    }
    float yaw(int index,double distance,boolean mirror){var a=spawn(index,distance,mirror);var b=spawn(1-index,distance,mirror);return (float)Math.toDegrees(Math.atan2(-(b.x-a.x),b.z-a.z));}
    JsonObject description() {return spec;}
    Map<String,Object> audit() {return resetAudit;}
}
