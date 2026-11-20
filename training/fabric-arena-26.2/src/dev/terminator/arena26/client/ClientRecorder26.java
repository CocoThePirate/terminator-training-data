package dev.terminator.arena26.client;

import com.google.gson.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ArrayBlockingQueue;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.*;
import net.minecraft.world.phys.*;

/** Passive 26.2 client capture; never owns or synthesizes game inputs. */
public final class ClientRecorder26 {
    private static final Gson JSON=new GsonBuilder().serializeNulls().create();
    private static ArrayBlockingQueue<String> queue;
    private static Thread writer;
    private static Path directory;
    private static Object world;
    private static long tick,frames,dropped,attacks;
    private static long testEnds;
    private static float yaw,pitch;
    private static volatile String error;
    private static String mode=System.getProperty("terminator.dataset.mode","unassigned"),task="unassigned";
    private static final JsonObject CATALOG=readCatalog();
    static {Runtime.getRuntime().addShutdownHook(new Thread(()->stop("client_shutdown"),"terminator-recorder26-close"));}
    private static JsonObject readCatalog(){try(var in=ClientRecorder26.class.getResourceAsStream("/terminator-catalog.json")){return JsonParser.parseString(new String(in.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();}catch(Exception e){throw new IllegalStateException("Missing recording catalog",e);}}
    private static void message(String text){var mc=Minecraft.getInstance();if(mc.player!=null)mc.player.sendSystemMessage(Component.literal("[Dataset 26.2] "+text));}
    public static boolean chat(String text){
        if(!text.startsWith("!terminator record "))return false;
        try{
            var command=text.substring(19);
            if(command.equals("stop")){stop("manual_stop");message("Recording stopped");}
            else if(command.matches("mode [a-z0-9_]{1,64}")){if(queue!=null)throw new IllegalStateException("Stop before changing mode");var id=command.substring(5);if(!CATALOG.getAsJsonObject("modes").has(id))throw new IllegalArgumentException("Unknown mode");mode=id;task="unassigned";message("Declared mode: "+mode);}
            else if(command.matches("task [a-z0-9_]{1,64}")){if(queue!=null)throw new IllegalStateException("Stop before changing task");var id=command.substring(5);var m=CATALOG.getAsJsonObject("modes").getAsJsonObject(mode);if(m==null||!m.getAsJsonArray("collection_tasks").asList().stream().anyMatch(x->x.getAsString().equals(id)))throw new IllegalArgumentException("Task not listed for declared mode");task=id;message("Task: "+task);}
            else if(command.matches("start [A-Za-z0-9_-]{1,48}"))start(command.substring(6));
            else throw new IllegalArgumentException("Use mode MODE, task TASK, start LABEL or stop");
        }catch(Exception e){error=e.toString();message("Rejected: "+e.getMessage());}
        return true;
    }
    private static synchronized void start(String label)throws Exception{
        var mc=Minecraft.getInstance();if(queue!=null)throw new IllegalStateException("Already recording");
        if(mc.level==null||mc.player==null||!mc.player.isAlive())throw new IllegalStateException("Join a live world first");
        directory=Path.of(System.getProperty("terminator.dataset.output",mc.gameDirectory.toPath().resolve("dataset-recordings-26.2").toString())).resolve(UUID.randomUUID().toString());
        Files.createDirectories(directory.resolve("metadata"));error=null;frames=dropped=attacks=0;world=mc.level;yaw=mc.player.getYRot();pitch=mc.player.getXRot();
        var metadata=new LinkedHashMap<String,Object>();metadata.put("schema","client_dataset_session/v1");metadata.put("minecraft","26.2");
        metadata.put("label",label);metadata.put("data_kind",Boolean.getBoolean("terminator.dataset.test")?"infrastructure_test":"unrated_client_experience");metadata.put("declared_mode",mode);metadata.put("collection_task",task);
        metadata.put("participant_id",System.getProperty("terminator.dataset.participant","unassigned"));metadata.put("participant_split",System.getProperty("terminator.dataset.participantSplit","training"));
        metadata.put("expert_demonstration",false);metadata.put("on_policy_rl_eligible",false);metadata.put("scenario_verified_by_owned_server",false);metadata.put("scenario",null);
        var setup=System.getProperty("terminator.dataset.setup");if(setup!=null)metadata.put("scenario",JsonParser.parseString(Files.readString(Path.of(setup))));
        metadata.put("started_ms",System.currentTimeMillis());metadata.put("capture_semantics","end_of_client_tick_snapshot_with_held_inputs; not pre-action RL transitions");
        Files.writeString(directory.resolve("metadata/session.json"),JSON.toJson(metadata),StandardOpenOption.CREATE_NEW);
        queue=new ArrayBlockingQueue<>(256);var owned=queue;var outPath=directory.resolve("observations.jsonl");
        writer=Thread.ofPlatform().daemon().name("terminator-recorder26-writer").start(()->{try(var out=Files.newBufferedWriter(outPath,StandardOpenOption.CREATE_NEW)){while(true){var row=owned.take();if(row.equals("STOP"))break;out.write(row);out.newLine();}}catch(Exception e){error=e.toString();}});
        message("Recording "+label+"; use !terminator record stop");
    }
    private static synchronized void stop(String reason){
        if(queue==null)return;var old=queue;queue=null;
        try{if(!old.offer("STOP",1,java.util.concurrent.TimeUnit.SECONDS))error="Writer queue could not close";writer.join(1500);if(writer.isAlive()){error="Writer did not flush";writer.interrupt();}
            Files.writeString(directory.resolve("metadata/end.json"),JSON.toJson(Map.of("reason",reason,"frames",frames,"dropped_frames",dropped,"complete",error==null&&dropped==0,"writer_error",error==null?"":error)),StandardOpenOption.CREATE_NEW);
        }catch(Exception e){error=e.toString();}world=null;
    }
    public static void attack(){if(queue!=null)attacks++;}
    private static List<Double> vec(Vec3 v){return List.of(v.x,v.y,v.z);}
    private static List<Map<String,Object>> inventory(){
        var p=Minecraft.getInstance().player;var items=new ArrayList<Map<String,Object>>();
        for(int i=0;i<41;i++){var stack=i<36?p.getInventory().getItem(i):p.getItemBySlot(new EquipmentSlot[]{EquipmentSlot.FEET,EquipmentSlot.LEGS,EquipmentSlot.CHEST,EquipmentSlot.HEAD,EquipmentSlot.OFFHAND}[i-36]);if(stack.isEmpty())continue;
            items.add(Map.of("slot",i,"item",BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(),"count",stack.getCount(),"damage",stack.getDamageValue(),"max_damage",stack.getMaxDamage(),"enchanted",stack.isEnchanted(),"crossbow_charged",CrossbowItem.isCharged(stack)));
        }return items;
    }
    private static Map<String,Object> observation(){
        var mc=Minecraft.getInstance();var p=mc.player;var own=new LinkedHashMap<String,Object>();
        own.put("position",vec(p.position()));own.put("velocity",vec(p.getDeltaMovement()));own.put("yaw",p.getYRot());own.put("pitch",p.getXRot());
        own.put("health",p.getHealth());own.put("absorption",p.getAbsorptionAmount());own.put("food",p.getFoodData().getFoodLevel());own.put("saturation",p.getFoodData().getSaturationLevel());
        own.put("grounded",p.onGround());own.put("fall_distance",p.fallDistance);own.put("sprinting",p.isSprinting());own.put("attack_cooldown",p.getAttackStrengthScale(0));
        own.put("using_item",p.isUsingItem());own.put("item_use_ticks",p.getTicksUsingItem());own.put("blocking",p.isBlocking());own.put("held_item_cooldown",p.getCooldowns().getCooldownPercent(p.getMainHandItem(),0));
        own.put("selected_slot",p.getInventory().getSelectedSlot());own.put("inventory",inventory());
        own.put("effects",p.getActiveEffects().stream().map(e->Map.of("id",BuiltInRegistries.MOB_EFFECT.getKey(e.getEffect().value()).toString(),"amplifier",e.getAmplifier(),"remaining_ticks",e.getDuration())).toList());
        var entities=new ArrayList<net.minecraft.world.entity.Entity>();for(var e:mc.level.entitiesForRendering())if(e!=p)entities.add(e);entities.sort(Comparator.comparingDouble(e->e.distanceToSqr(p)));
        var loaded=new ArrayList<Map<String,Object>>();for(var e:entities.subList(0,Math.min(128,entities.size())))loaded.add(Map.of("uuid",e.getUUID().toString(),"type",BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString(),"position",vec(e.position()),"velocity",vec(e.getDeltaMovement())));
        var result=new LinkedHashMap<String,Object>();result.put("own",own);result.put("loaded_entities",loaded);result.put("dimension",mc.level.dimension().identifier().toString());
        var hit=mc.hitResult;if(hit!=null){var target=new LinkedHashMap<String,Object>();target.put("type",hit.getType().name().toLowerCase(Locale.ROOT));target.put("position",vec(hit.getLocation()));if(hit instanceof BlockHitResult b){target.put("block",List.of(b.getBlockPos().getX(),b.getBlockPos().getY(),b.getBlockPos().getZ()));target.put("face",b.getDirection().getName());}if(hit instanceof EntityHitResult e)target.put("entity_uuid",e.getEntity().getUUID().toString());result.put("crosshair",target);}
        if(frames%5==0){var blocks=new ArrayList<Map<String,Object>>();for(int x=-4;x<=4;x++)for(int y=-1;y<=3;y++)for(int z=-4;z<=4;z++){var pos=p.blockPosition().offset(x,y,z);if(!mc.level.hasChunkAt(pos))continue;var state=mc.level.getBlockState(pos);var properties=new TreeMap<String,String>();for(var property:state.getProperties())properties.put(property.getName(),String.valueOf(state.getValue(property)));blocks.add(Map.of("position",List.of(pos.getX(),pos.getY(),pos.getZ()),"block",BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString(),"properties",properties));}result.put("local_blocks",blocks);result.put("block_snapshot_tick",tick);}
        return result;
    }
    public static void tick(){
        tick++;var mc=Minecraft.getInstance();
        if(Boolean.getBoolean("terminator.dataset.test")&&testEnds==0&&mc.player!=null&&mc.level!=null&&mc.gui.screen()==null
                &&BuiltInRegistries.ITEM.getKey(mc.player.getMainHandItem().getItem()).toString().equals(System.getProperty("terminator.dataset.expectItem","minecraft:bow"))){
            chat("!terminator record mode "+System.getProperty("terminator.dataset.mode","bow"));chat("!terminator record start native_26_2_smoke");testEnds=tick+60;
        }
        if(testEnds>0&&tick>=testEnds){stop("infrastructure_test_complete");mc.stop();return;}
        var current=queue;if(current==null)return;if(mc.level!=world||mc.player==null){stop("world_changed");return;}
        try{var o=mc.options;var p=mc.player;var frame=new LinkedHashMap<String,Object>();frame.put("schema","client_dataset_frame/v1");frame.put("tick",tick);frame.put("time_ms",System.currentTimeMillis());frame.put("observation",observation());
            frame.put("input_owner","unverified_client_inputs");frame.put("menu_open",mc.gui.screen()!=null);
            frame.put("keys",Map.of("forward",o.keyUp.isDown(),"back",o.keyDown.isDown(),"left",o.keyLeft.isDown(),"right",o.keyRight.isDown(),"jump",o.keyJump.isDown(),"sprint",o.keySprint.isDown(),"sneak",o.keyShift.isDown(),"attack_held",o.keyAttack.isDown(),"use_held",o.keyUse.isDown()));
            frame.put("client_attack_attempts",attacks);attacks=0;frame.put("yaw_delta",net.minecraft.util.Mth.wrapDegrees(p.getYRot()-yaw));frame.put("pitch_delta",p.getXRot()-pitch);yaw=p.getYRot();pitch=p.getXRot();if(!current.offer(JSON.toJson(frame)))dropped++;frames++;if(!p.isAlive())stop("player_died");
        }catch(Exception e){error=e.toString();stop("capture_error");message("Recording failed: "+error);}
    }
}
