package dev.terminator.bridge;

import com.google.gson.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ArrayBlockingQueue;
import net.fabricmc.api.ClientModInitializer;
import net.minecraft.client.MinecraftClient;
import net.minecraft.registry.Registries;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;

/** Passive client-only capture. Server access below is isolated training-world
 * setup/reset only; no server state is placed in observation frames. */
public final class DatasetClient implements ClientModInitializer {
    private static DatasetClient active;
    private final Gson json=new GsonBuilder().serializeNulls().create();
    private DuelSetup setup;
    private volatile Object ownedServer;
    private volatile Map<String,Object> mechanicsFeedback=Map.of();
    private String collectionTask="unassigned";
    private boolean testCrossbowCharge;
    private BlockPos testMiningTarget;
    private boolean initializing,arenaReady;
    private long tick,readyAt,frames,dropped,attacks;
    private Path directory;
    private ArrayBlockingQueue<String> queue;
    private Thread writer;
    private Object recordingWorld;
    private float lastYaw,lastPitch;
    private String label;
    private volatile String writeError;
    private volatile String dirtyToken;

    @Override public void onInitializeClient() {
        if(!Boolean.getBoolean("terminator.dataset"))return;
        active=this;
        String file=System.getProperty("terminator.dataset.setup");
        if(file!=null)try {setup=DuelSetup.parse(JsonParser.parseString(Files.readString(Path.of(file))).getAsJsonObject());
            if(setup.scenario()==null)throw new IllegalArgumentException("Use a versioned arena scenario");}
        catch(Exception e){throw new IllegalStateException("Invalid dataset arena configuration",e);}
        Runtime.getRuntime().addShutdownHook(new Thread(()->stop("client_shutdown"),"terminator-dataset-close"));
    }
    public static boolean localChat(String message) {
        if(active==null || !(message.startsWith("!terminator record ")||message.startsWith("!terminator arena ")))return false;
        active.command(message.substring("!terminator ".length()));return true;
    }
    public static Map<String,Object> request(String command) {
        if(active==null)throw new IllegalStateException("Dataset mode is disabled");
        active.command(command);return active.status();
    }
    public static void attack() {if(active!=null&&active.queue!=null)active.attacks++;}
    private void message(String text) {var p=MinecraftClient.getInstance().player;if(p!=null)p.sendMessage(Text.literal("[Dataset] "+text),false);}
    private void command(String text) {
        try {
            if(text.equals("record stop")) {stop("manual_stop");message("Recording stopped");}
            else if(text.matches("record task [a-z0-9_]{1,64}")){
                if(queue!=null)throw new IllegalStateException("Stop the recording before changing its task");
                var task=text.substring(12);var mode=setup==null?null:setup.scenario().description().getAsJsonObject("mode");
                if(mode==null||!mode.getAsJsonArray("collection_tasks").asList().stream().anyMatch(e->e.getAsString().equals(task)))throw new IllegalArgumentException("Task is not listed for this mode");
                collectionTask=task;message("Collection task: "+task);
            }
            else if(text.matches("record start [a-zA-Z0-9_-]{1,48}"))start(text.substring(13));
            else if(text.equals("arena reset"))resetArena();
            else if(text.equals("arena dirty")&&Boolean.getBoolean("terminator.navigation.test"))dirtyArena();
            else if(text.matches("arena test_use [0-8]")&&Boolean.getBoolean("terminator.navigation.test")){
                var client=MinecraftClient.getInstance();client.player.getInventory().setSelectedSlot(Integer.parseInt(text.substring(15)));
                var stack=client.player.getMainHandStack();testCrossbowCharge=stack.isOf(net.minecraft.item.Items.CROSSBOW)&&!net.minecraft.item.CrossbowItem.isCharged(stack);
                client.options.useKey.setPressed(stack.isOf(net.minecraft.item.Items.BOW)||stack.isOf(net.minecraft.item.Items.SHIELD)||testCrossbowCharge);
                client.interactionManager.interactItem(client.player,net.minecraft.util.Hand.MAIN_HAND);
            }
            else if(text.equals("arena test_release")&&Boolean.getBoolean("terminator.navigation.test")){var client=MinecraftClient.getInstance();client.options.useKey.setPressed(false);client.options.attackKey.setPressed(false);client.interactionManager.stopUsingItem(client.player);client.interactionManager.cancelBlockBreaking();}
            else if(text.equals("arena test_mine")&&Boolean.getBoolean("terminator.navigation.test")){
                var client=MinecraftClient.getInstance();client.player.getInventory().setSelectedSlot(2);client.player.setYaw(180);client.player.setPitch(0);
                testMiningTarget=client.player.getBlockPos().add(0,1,-2);client.options.attackKey.setPressed(true);
            }
            else if(!text.equals("status"))throw new IllegalArgumentException("Use record start LABEL, record stop, or arena reset");
        }catch(Exception e){writeError=e.toString();message("Rejected: "+e.getMessage());}
    }
    private void resetArena() {
        var client=MinecraftClient.getInstance();var server=client.getServer();
        if(setup==null||setup.scenario()==null||server==null||server!=ownedServer)throw new IllegalStateException("Reset is restricted to the generated dataset arena");
        if(initializing)throw new IllegalStateException("Arena reset already in progress");
        if(client.player==null||!client.player.isAlive())throw new IllegalStateException("Respawn before resetting the arena");
        if(NavigationClient.enabled())NavigationClient.request("cancel");
        stop("arena_reset");initializing=true;arenaReady=false;
        var uuid=client.player.getUuid();
        server.execute(()->{
            try {
                var p=server.getPlayerManager().getPlayer(uuid);if(p==null)throw new IllegalStateException("Player unavailable");
                p.setInvulnerable(true);p.stopUsingItem();p.clearStatusEffects();p.setAbsorptionAmount(0);p.setFireTicks(0);
                setup.scenario().reset(server.getOverworld());p.getInventory().clear();setup.scenario().equip(p,setup.hostApples());
                p.setHealth(setup.hostHealth());var fresh=new net.minecraft.nbt.NbtCompound();
                new net.minecraft.entity.player.HungerManager().writeNbt(fresh);p.getHungerManager().readNbt(fresh);
                p.setVelocity(net.minecraft.util.math.Vec3d.ZERO);p.fallDistance=0;p.setSprinting(false);
                var spawn=setup.scenario().spawn(0,setup.distance(),setup.mirror());
                p.networkHandler.requestTeleport(spawn.x,spawn.y,spawn.z,setup.scenario().yaw(0,setup.distance(),setup.mirror()),0);p.setInvulnerable(false);
                client.execute(()->{initializing=false;readyAt=tick+10;});
            }catch(Exception e){client.execute(()->{initializing=false;readyAt=Long.MAX_VALUE;arenaReady=false;writeError=e.toString();});}
        });
    }
    private void dirtyArena() {
        var client=MinecraftClient.getInstance();var server=client.getServer();
        if(server==null||server!=ownedServer||initializing)throw new IllegalStateException("No owned test arena");
        server.execute(()->{
            var world=server.getOverworld();world.setBlockState(new BlockPos(2,-30,2),net.minecraft.block.Blocks.COBBLESTONE.getDefaultState());
            var spawn=setup.scenario().spawn(0,setup.distance(),setup.mirror());
            world.setBlockState(new BlockPos((int)spawn.x,(int)spawn.y+3,(int)spawn.z),net.minecraft.block.Blocks.WATER.getDefaultState());
            var item=new net.minecraft.entity.ItemEntity(world,spawn.x,spawn.y+1,spawn.z,new net.minecraft.item.ItemStack(net.minecraft.item.Items.COBBLESTONE));item.setPickupDelay(32767);world.spawnEntity(item);
            client.execute(()->dirtyToken=UUID.randomUUID().toString());
        });
    }
    private void start(String name)throws java.io.IOException {
        var client=MinecraftClient.getInstance();
        if(queue!=null)throw new IllegalStateException("Stop the current recording first");
        if(client.world==null||client.player==null||!client.player.isAlive()||initializing||(ownedServer==client.getServer()&&ownedServer!=null&&!arenaReady))throw new IllegalStateException("Wait for a live, ready world");
        directory=Path.of(System.getProperty("terminator.dataset.output","dataset-recordings")).resolve(UUID.randomUUID().toString());
        Files.createDirectories(directory.resolve("metadata"));label=name;frames=dropped=attacks=0;writeError=null;
        recordingWorld=client.world;lastYaw=client.player.getYaw();lastPitch=client.player.getPitch();
        var metadata=new LinkedHashMap<String,Object>();metadata.put("schema","client_dataset_session/v1");metadata.put("minecraft","1.21.4");
        metadata.put("label",label);metadata.put("data_kind",Boolean.getBoolean("terminator.navigation.test")?"infrastructure_test":"unrated_client_experience");
        metadata.put("collection_task",collectionTask);
        metadata.put("server_mechanics_feedback_at_start",mechanicsFeedback);
        metadata.put("participant_id",System.getProperty("terminator.dataset.participant","unassigned"));
        metadata.put("participant_split",System.getProperty("terminator.dataset.participantSplit","training"));
        metadata.put("expert_demonstration",false);metadata.put("on_policy_rl_eligible",false);metadata.put("started_ms",System.currentTimeMillis());
        metadata.put("capture_semantics","end_of_client_tick_snapshot_with_held_inputs; not pre-action RL transitions");
        metadata.put("inventory_schema","basic_inventory/v1; enchantment presence only, no component payloads");
        metadata.put("scenario",setup==null?null:setup.feedback());metadata.put("server_setup_feedback",setup==null?null:setup.scenario().audit());
        metadata.put("scenario_verified_by_owned_server",ownedServer!=null&&ownedServer==client.getServer()&&arenaReady);
        Files.writeString(directory.resolve("metadata/session.json"),json.toJson(metadata),StandardOpenOption.CREATE_NEW);
        queue=new ArrayBlockingQueue<>(256);var ownedQueue=queue;var file=directory.resolve("observations.jsonl");
        writer=Thread.ofPlatform().daemon().name("terminator-dataset-writer").start(()->{
            try(var out=Files.newBufferedWriter(file,StandardOpenOption.CREATE_NEW)) {
                while(true){var line=ownedQueue.take();if(line.equals("STOP"))break;out.write(line);out.newLine();}
            }catch(Exception e){writeError=e.toString();}
        });
        message("Recording "+label+"; stop with !terminator record stop");
    }
    private void stop(String reason) {
        if(queue==null)return;
        var old=queue;queue=null;
        try {
            // Capture never waits on disk. Stop may wait briefly to flush a shard.
            if(!old.offer("STOP",1,java.util.concurrent.TimeUnit.SECONDS))writeError="Writer queue could not close";
            writer.join(1500);
            if(writer.isAlive()){writeError="Writer did not finish flushing";writer.interrupt();}
            Files.writeString(directory.resolve("metadata/end.json"),json.toJson(Map.of("reason",reason,"frames",frames,
                    "dropped_frames",dropped,"complete",writeError==null&&dropped==0,"writer_error",writeError==null?"":writeError,
                    "server_mechanics_feedback",mechanicsFeedback)),StandardOpenOption.CREATE_NEW);
        }catch(Exception e){writeError=e.toString();}
        recordingWorld=null;
    }
    private List<Map<String,Object>> inventory() {
        var p=MinecraftClient.getInstance().player;var items=new ArrayList<Map<String,Object>>();
        for(int i=0;i<p.getInventory().size();i++) {
            var stack=p.getInventory().getStack(i);if(stack.isEmpty())continue;
            items.add(Map.of("slot",i,"item",Registries.ITEM.getId(stack.getItem()).toString(),"count",stack.getCount(),
                    "damage",stack.getDamage(),"max_damage",stack.getMaxDamage(),"enchanted",stack.hasEnchantments(),"crossbow_charged",net.minecraft.item.CrossbowItem.isCharged(stack)));
        }return items;
    }
    private Map<String,Object> observation() {
        var client=MinecraftClient.getInstance();var p=client.player;var own=new LinkedHashMap<String,Object>();
        own.put("position",ReachAndStop.Player.coords(p.getPos()));own.put("velocity",ReachAndStop.Player.coords(p.getVelocity()));
        own.put("yaw",p.getYaw());own.put("pitch",p.getPitch());own.put("health",p.getHealth());own.put("absorption",p.getAbsorptionAmount());
        own.put("food",p.getHungerManager().getFoodLevel());own.put("saturation",p.getHungerManager().getSaturationLevel());
        own.put("grounded",p.isOnGround());own.put("fall_distance",p.fallDistance);own.put("sprinting",p.isSprinting());
        own.put("attack_cooldown",p.getAttackCooldownProgress(0));own.put("using_item",p.isUsingItem());
        own.put("item_use_ticks",p.getItemUseTime());own.put("blocking",p.isBlocking());
        own.put("held_item_cooldown",p.getItemCooldownManager().getCooldownProgress(p.getMainHandStack(),0));
        own.put("selected_slot",p.getInventory().selectedSlot);own.put("inventory",inventory());
        own.put("effects",p.getStatusEffects().stream().map(e->Map.of("id",Registries.STATUS_EFFECT.getId(e.getEffectType().value()).toString(),
                "amplifier",e.getAmplifier(),"remaining_ticks",e.getDuration())).toList());
        var entities=new ArrayList<Map<String,Object>>();
        var candidates=new ArrayList<net.minecraft.entity.Entity>();for(var e:client.world.getEntities())if(e!=p)candidates.add(e);
        candidates.sort(Comparator.comparingDouble(e->e.squaredDistanceTo(p)));
        for(var e:candidates.subList(0,Math.min(128,candidates.size())))entities.add(Map.of("uuid",e.getUuidAsString(),
                "type",Registries.ENTITY_TYPE.getId(e.getType()).toString(),"position",ReachAndStop.Player.coords(e.getPos()),
                "velocity",ReachAndStop.Player.coords(e.getVelocity())));
        var result=new LinkedHashMap<String,Object>();result.put("own",own);result.put("loaded_entities",entities);
        result.put("dimension",client.world.getRegistryKey().getValue().toString());
        if(client.crosshairTarget!=null){var hit=client.crosshairTarget;var target=new LinkedHashMap<String,Object>();
            target.put("type",hit.getType().name().toLowerCase(java.util.Locale.ROOT));target.put("position",ReachAndStop.Player.coords(hit.getPos()));
            if(hit instanceof net.minecraft.util.hit.BlockHitResult b){target.put("block",List.of(b.getBlockPos().getX(),b.getBlockPos().getY(),b.getBlockPos().getZ()));target.put("face",b.getSide().getName());}
            if(hit instanceof net.minecraft.util.hit.EntityHitResult e)target.put("entity_uuid",e.getEntity().getUuidAsString());
            result.put("crosshair",target);
        }
        if(frames%5==0) {
            var blocks=new ArrayList<Map<String,Object>>();var origin=p.getBlockPos();
            for(int x= -4;x<=4;x++)for(int y= -1;y<=3;y++)for(int z= -4;z<=4;z++) {
                var pos=origin.add(x,y,z);if(!client.world.isChunkLoaded(pos.getX()>>4,pos.getZ()>>4))continue;
                var state=client.world.getBlockState(pos);var properties=new TreeMap<String,String>();
                state.getEntries().forEach((key,value)->properties.put(key.getName(),value.toString()));
                blocks.add(Map.of("position",List.of(pos.getX(),pos.getY(),pos.getZ()),"block",Registries.BLOCK.getId(state.getBlock()).toString(),"properties",properties));
            }result.put("local_blocks",blocks);result.put("block_snapshot_tick",tick);
        }
        return result;
    }
    public static void afterTick(MinecraftClient client) {
        if(active==null)return;active.tick++;
        if(active.testCrossbowCharge&&client.player!=null&&net.minecraft.item.CrossbowItem.isCharged(client.player.getMainHandStack())){client.options.useKey.setPressed(false);active.testCrossbowCharge=false;}
        if(active.setup!=null&&active.ownedServer==null&&client.getServer()!=null&&client.player!=null) {
            var server=client.getServer();var folder=server.getSavePath(net.minecraft.util.WorldSavePath.ROOT).normalize().getFileName().toString();
            if(folder.equals(NavigationClient.demoWorldId())) {active.ownedServer=server;active.resetArena();}
        }
        if(active.ownedServer!=null&&client.getServer()!=active.ownedServer)active.arenaReady=false;
        else if(active.ownedServer!=null&&!active.initializing&&active.tick>=active.readyAt)active.arenaReady=true;
        if(active.queue==null)return;
        if(client.world!=active.recordingWorld||client.player==null) {active.stop("world_changed");return;}
        var o=client.options;var frame=new LinkedHashMap<String,Object>();frame.put("schema","client_dataset_frame/v1");
        frame.put("tick",active.tick);frame.put("time_ms",System.currentTimeMillis());frame.put("observation",active.observation());
        frame.put("input_owner",Boolean.getBoolean("terminator.navigation.test")?"infrastructure_test":NavigationClient.inputOwner());
        frame.put("menu_open",client.currentScreen!=null);
        frame.put("keys",Map.of("forward",o.forwardKey.isPressed(),"back",o.backKey.isPressed(),"left",o.leftKey.isPressed(),"right",o.rightKey.isPressed(),
                "jump",o.jumpKey.isPressed(),"sprint",o.sprintKey.isPressed(),"sneak",o.sneakKey.isPressed(),"attack_held",o.attackKey.isPressed(),"use_held",o.useKey.isPressed()));
        frame.put("client_attack_attempts",active.attacks);active.attacks=0;
        frame.put("yaw_delta",net.minecraft.util.math.MathHelper.wrapDegrees(client.player.getYaw()-active.lastYaw));
        frame.put("pitch_delta",client.player.getPitch()-active.lastPitch);active.lastYaw=client.player.getYaw();active.lastPitch=client.player.getPitch();
        if(!active.queue.offer(active.json.toJson(frame)))active.dropped++;active.frames++;
        if(!client.player.isAlive())active.stop("player_died");
    }
    public static void serverTick(net.minecraft.server.integrated.IntegratedServer server){if(active!=null&&server==active.ownedServer)active.mechanicsFeedback=ArenaMechanics.feedback(server.getOverworld());}
    private Map<String,Object> status() {
        var map=new LinkedHashMap<String,Object>();map.put("schema","dataset_status/v1");map.put("recording",queue!=null);
        map.put("directory",directory==null?null:directory.toAbsolutePath().toString());map.put("frames",frames);map.put("dropped_frames",dropped);
        map.put("error",writeError);map.put("arena_ready",arenaReady);map.put("resetting",initializing);map.put("dirty_token",dirtyToken);
        map.put("server_mechanics_feedback",mechanicsFeedback);map.put("collection_task",collectionTask);
        var client=MinecraftClient.getInstance();map.put("client_ready",client.player!=null&&client.world!=null&&client.currentScreen==null&&client.getOverlay()==null);
        if(client.player!=null)map.put("blocking",client.player.isBlocking());
        if(testMiningTarget!=null&&client.world!=null)map.put("test_mining_block",Registries.BLOCK.getId(client.world.getBlockState(testMiningTarget).getBlock()).toString());
        map.put("scenario",setup==null?null:setup.feedback());map.put("reset_feedback",setup==null?null:setup.scenario().audit());
        if(MinecraftClient.getInstance().player!=null)map.put("inventory",inventory());return map;
    }
}
