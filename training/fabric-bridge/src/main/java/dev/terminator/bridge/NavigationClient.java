package dev.terminator.bridge;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.Map;
import net.fabricmc.api.ClientModInitializer;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ChatScreen;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

/** Opt-in own-client mode. Never runs alongside a training or duel controller. */
public final class NavigationClient implements ClientModInitializer {
    private static NavigationClient active;
    private final NavigationTask task = new NavigationTask();
    private NavigationBackend backend;
    private long tick, lastProgress;
    private double bestDistance;
    private String lastError;
    private boolean showHud;
    private long lastReleaseNanos;
    private boolean demoWorldStarted;
    private String demoWorldId;
    private ClientCombatWorker combat;
    private boolean attackHeld;
    private long lastCombatReply, appliedCombatFrames, rejectedCombatFrames;
    private JsonObject combatDiagnostics;
    private boolean combatSessionRecorded;
    private final Path events = Path.of("terminator-navigation-events.jsonl");

    public static boolean enabled() { return Boolean.getBoolean("terminator.navigation"); }
    public static String demoWorldId() {return active==null?null:active.demoWorldId;}
    public static String inputOwner() {return active==null||!active.task.active()?"human":active.task.phase==NavigationTask.Phase.FIGHTING?"combat_policy":"scripted_navigation";}

    @Override public void onInitializeClient() {
        if (!enabled()) return;
        active = this;
        try { backend = new BaritoneNavigation(); }
        catch (ReflectiveOperationException | LinkageError e) { task.fail("Baritone API unavailable"); lastError = e.toString(); }
        String executable=System.getProperty("terminator.policy.worker"), checkpoint=System.getProperty("terminator.policy.checkpoint");
        if(executable!=null && checkpoint!=null) {
            try {combat=new ClientCombatWorker(Path.of(executable),Path.of(checkpoint));}
            catch(java.io.IOException e) {lastError="Combat worker unavailable: "+e.getMessage();}
        }
    }

    public static boolean localChat(String message) {
        if (active == null || !message.startsWith("!terminator ")) return false;
        active.command(message.substring("!terminator ".length()));
        return true; // Never transmit task text to the server.
    }

    public static void key(int key, int action) {
        if (active == null || key != GLFW.GLFW_KEY_F8 || action != GLFW.GLFW_PRESS) return;
        if (active.task.active() || active.task.phase == NavigationTask.Phase.PAUSED) {
            active.cancel("F8 takeover");
        } else {
            active.showHud = !active.showHud;
            if (active.showHud) active.message("Use !terminator go to X Y Z or !terminator fight USERNAME. F8 cancels.");
        }
    }

    public static Map<String, Object> request(String text) {
        if (active == null) throw new IllegalStateException("Navigation mode is disabled");
        active.command(text);
        return active.snapshot();
    }

    public static Map<String, Object> status() {
        if (active == null) throw new IllegalStateException("Navigation mode is disabled");
        return active.snapshot();
    }

    private void command(String text) {
        var client = MinecraftClient.getInstance();
        try {
            var command = NavigationTask.parse(text);
            if (command.action().equals("cancel")) { cancel("Task cancelled"); return; }
            if (backend == null) throw new IllegalStateException("Baritone is unavailable; check the isolated profile");
            if (client.player == null || client.world == null || !client.player.isAlive()) throw new IllegalStateException("Join a world first");
            if (command.action().equals("go")) {
                var goal = command.goal();
                if (goal.y() < client.world.getBottomY() || goal.y() >= client.world.getTopYInclusive()+1
                        || !client.world.getWorldBorder().contains(new net.minecraft.util.math.BlockPos(goal.x(),goal.y(),goal.z())))
                    throw new IllegalArgumentException("Goal is outside this world's bounds");
                release(client);
                task.start(goal, client.world);
                showHud = true;
                startBackend();
            } else if(command.action().equals("fight")) {
                if(combat==null || !combat.ready()) throw new IllegalArgumentException("Combat policy is unavailable or still loading");
                var target=client.world.getPlayers().stream().filter(p->p!=client.player && p.isAlive() && !p.isSpectator()
                        && p.getGameProfile().getName().equalsIgnoreCase(command.username())).findFirst()
                        .orElseThrow(()->new IllegalArgumentException("Target unavailable: player is not loaded in this client"));
                if(!client.player.getInventory().getStack(0).isOf(net.minecraft.item.Items.DIAMOND_SWORD))
                    throw new IllegalArgumentException("This sword policy needs a diamond sword in hotbar slot 1; put golden apples in slot 2");
                if(!combatSessionRecorded) {
                    append(Map.of("event","combat_session","mode","frozen_own_client","updates",0,"policy",combat.metadata()));
                    combatSessionRecorded=true;
                }
                release(client);task.fight(target.getUuid(),target.getGameProfile().getName(),client.world);
                showHud=true;lastError=null;bestDistance=Double.POSITIVE_INFINITY;lastProgress=tick;
            } else if (command.action().equals("stop")) {
                task.stop("Stopped; resume is available"); release(client);
            } else {
                task.resume(client.world); release(client);
                if(task.target==null) startBackend();
                else {bestDistance=Double.POSITIVE_INFINITY;lastProgress=tick;}
            }
            record("command"); message(task.reason);
        } catch (Exception e) {
            // Unknown text is rejected without executing it. Runtime/backend
            // failures cancel active automation rather than leave it driving.
            if (!(e instanceof IllegalArgumentException)) cancel("Task error");
            lastError = e.getMessage(); message("Rejected: " + lastError); record("error");
        }
    }

    private void startBackend() throws ReflectiveOperationException {
        backend.goTo(task.goal.x(), task.goal.y(), task.goal.z());
        bestDistance = Double.POSITIVE_INFINITY; lastProgress = tick; lastError = null;
    }

    private void cancel(String why) {
        long started=System.nanoTime();
        task.cancel(why); // Revoke intent before any cancellation call.
        showHud = false;
        try { release(MinecraftClient.getInstance()); }
        catch (Exception e) {
            task.fail("Input release failed; disconnected"); lastError = e.toString();
            // A broken delegated writer must not keep driving a connected player.
            MinecraftClient.getInstance().disconnect();
        } finally {lastReleaseNanos=System.nanoTime()-started;}
        record("cancel"); message(task.reason);
    }

    private void release(MinecraftClient client) throws ReflectiveOperationException {
        if(combat!=null) combat.invalidate();
        attackHeld=false;combatDiagnostics=null;lastCombatReply=tick;
        releaseVanilla(client);
        try {
            if (backend != null) backend.cancel(client.player == null ? 0 : client.player.getYaw(), client.player == null ? 0 : client.player.getPitch());
        } finally {
            releaseVanilla(client);
        }
        if (backend != null && backend.hasForcedInputs()) throw new IllegalStateException("Baritone retained forced inputs");
    }

    private static void releaseVanilla(MinecraftClient client) {
        KeyBinding.unpressAll();
        if (client.player != null) {
            client.player.setSprinting(false);
            if (client.interactionManager != null) {
                if (client.player.isUsingItem()) client.interactionManager.stopUsingItem(client.player);
                client.interactionManager.cancelBlockBreaking();
            }
        }
    }

    public static void beforeTick(MinecraftClient client) {
        if (active == null) return;
        active.tick++;
        if (!active.demoWorldStarted && active.backend != null && Boolean.getBoolean("terminator.navigation.demo")
                && client.currentScreen instanceof net.minecraft.client.gui.screen.TitleScreen && client.getOverlay() == null) {
            active.demoWorldStarted = true;
            active.demoWorldId=TrainingBridge.createFlatWorld(client,Boolean.getBoolean("terminator.dataset")?net.minecraft.world.Difficulty.NORMAL:net.minecraft.world.Difficulty.PEACEFUL,true,0L);
            return;
        }
        if (active.task.world != null && (client.world != active.task.world || client.player == null || !client.player.isAlive())) {
            active.cancel("World changed, disconnected or player died"); return;
        }
        if (!active.task.active()) return;
        if (client.currentScreen != null && !(client.currentScreen instanceof ChatScreen)) {
            active.task.stop("Screen opened; use resume");
            try { active.release(client); } catch (Exception e) { active.cancel("Release failed"); }
            active.record("screen_pause"); return;
        }
        if(active.task.target!=null) {active.fightTick(client);return;}
        var goal = active.task.goal;
        double distance = client.player.getPos().squaredDistanceTo(goal.x()+0.5,goal.y(),goal.z()+0.5);
        if (distance < active.bestDistance-0.04) {active.bestDistance=distance;active.lastProgress=active.tick;}
        if (client.player.getBlockPos().equals(new net.minecraft.util.math.BlockPos(goal.x(),goal.y(),goal.z()))) {
            active.task.arrive();
            try {active.release(client);} catch (Exception e) {active.cancel("Arrival release failed");}
            active.record("arrival"); active.message(active.task.reason);
        } else if (active.tick-active.lastProgress > 200) {
            active.cancel("No navigation progress for 10 seconds");
        }
    }

    private void fightTick(MinecraftClient client) {
        // Deliberately only loaded client player entities, never the server list
        // or tab-list coordinates. UUID binding survives display-name changes.
        var target=client.world.getPlayers().stream().filter(p->p.getUuid().equals(task.target)).findFirst().orElse(null);
        if(target==null || !target.isAlive() || target.isSpectator()) {cancel("Target unavailable or no longer active");return;}
        if(combat==null || !combat.ready()) {cancel("Combat policy unavailable");return;}
        try {
            if(task.phase==NavigationTask.Phase.APPROACHING) {
                if(client.player.squaredDistanceTo(target)<=36) {
                    release(client);task.engage();lastCombatReply=tick;record("owner_combat");
                } else {
                    var p=target.getBlockPos();
                    double distance=client.player.squaredDistanceTo(target);
                    if(distance<bestDistance-0.04) {bestDistance=distance;lastProgress=tick;}
                    if(tick-lastProgress>200) {cancel("No approach progress for 10 seconds");return;}
                    if(task.goal==null || (tick%10==0 && p.getSquaredDistance(new net.minecraft.util.math.BlockPos(task.goal.x(),task.goal.y(),task.goal.z()))>=4)) {
                        task.goal=new NavigationTask.Goal(p.getX(),p.getY(),p.getZ());backend.goTo(p.getX(),p.getY(),p.getZ());record("approach_goal");
                    }
                    return;
                }
            }
            if(backend.hasForcedInputs() || backend.isPathing()) {cancel("Navigation did not release combat ownership");return;}
            var result=combat.take();
            if(result!=null) {
                long generation=result.get("generation").getAsLong(),source=result.get("source_tick").getAsLong();
                long age=System.nanoTime()-result.get("submitted_ns").getAsLong();
                if(generation!=task.generation || source>tick || tick-source>3 || age>200_000_000L) rejectedCombatFrames++;
                else {
                    var c=ReachAndStop.Controls.parse(result.getAsJsonObject("controls"),true);
                    var applied=TrainingBridge.applyControls(client,c,true,attackHeld);attackHeld=c.attack();
                    combatDiagnostics=result.getAsJsonObject("diagnostics");lastCombatReply=tick;appliedCombatFrames++;
                    var row=new LinkedHashMap<String,Object>();row.put("event","combat_frame");row.put("time_ms",System.currentTimeMillis());
                    row.put("applied_tick",tick);row.put("target_uuid",task.target.toString());row.put("policy",result);row.put("applied_controls",applied);
                    append(row);
                }
            }
            if(tick-lastCombatReply>4) {cancel("Combat inference stale; control released");return;}
            var observation=new LinkedHashMap<String,Object>();
            observation.put("episode_id","own-client-"+task.generation+"-"+task.target);
            observation.put("tick",tick);observation.put("schema",SwordDrill.SCHEMA);
            observation.put("payload",SwordDrill.observe(client,ReachAndStop.Player.capture(client.player),target));
            var request=new JsonObject();request.addProperty("generation",task.generation);
            request.addProperty("submitted_ns",System.nanoTime());request.add("observation",new Gson().toJsonTree(observation));
            combat.submit(request);
        } catch(Exception e) {lastError=e.toString();cancel("Combat failed; control released");}
    }

    private Map<String, Object> snapshot() {
        var client = MinecraftClient.getInstance();
        var data = new LinkedHashMap<String,Object>();
        data.put("schema","navigation_status/v1"); data.put("tick",tick);
        data.put("phase",task.phase.name().toLowerCase(java.util.Locale.ROOT));
        data.put("owner",task.phase == NavigationTask.Phase.FIGHTING ? "combat" : task.active() ? "navigation" : "human");
        data.put("generation",task.generation); data.put("reason",task.reason);
        data.put("last_cancel_handler_ms",lastReleaseNanos/1_000_000.0);
        data.put("goal",task.goal); data.put("navigation_backend","baritone-1.13.1-scripted");
        data.put("backend",task.phase==NavigationTask.Phase.FIGHTING ? "frozen-neural-policy" : "baritone-1.13.1-scripted");
        data.put("target_uuid",task.target);data.put("target_name",task.targetName);
        data.put("applied_combat_frames",appliedCombatFrames);data.put("rejected_combat_frames",rejectedCombatFrames);
        data.put("policy_ready",combat!=null && combat.ready());data.put("policy_error",combat==null ? null : combat.error());
        data.put("predictions",combatDiagnostics==null ? Map.of() : combatDiagnostics); data.put("error",lastError);
        data.put("screen",client.currentScreen == null ? null : client.currentScreen.getClass().getSimpleName());
        data.put("ready",client.player != null && client.world != null && client.player.isAlive()
                && client.currentScreen == null && client.getOverlay() == null);
        if (client.player != null) {
            data.put("position",new double[]{client.player.getX(),client.player.getY(),client.player.getZ()});
            data.put("yaw",client.player.getYaw());data.put("pitch",client.player.getPitch());
            data.put("dimension",client.world.getRegistryKey().getValue().toString());
        }
        var o=client.options;
        data.put("held_inputs",java.util.List.of(o.forwardKey.isPressed(),o.backKey.isPressed(),o.leftKey.isPressed(),o.rightKey.isPressed(),
                o.jumpKey.isPressed(),o.sprintKey.isPressed(),o.sneakKey.isPressed(),o.attackKey.isPressed(),o.useKey.isPressed()));
        try {data.put("forced_inputs",backend!=null && backend.hasForcedInputs());data.put("pathing",backend!=null && backend.isPathing());}
        catch (Exception e) {data.put("backend_status_error",e.toString());}
        return data;
    }

    private void record(String event) {
        var row=new LinkedHashMap<>(snapshot());row.put("event",event);row.put("time_ms",System.currentTimeMillis());append(row);
    }

    private void append(Map<String,Object> row) {
        try {
            Files.writeString(events,new Gson().toJson(row)+"\n",StandardOpenOption.CREATE,StandardOpenOption.APPEND);
        } catch (java.io.IOException e) {TrainingBridge.LOG.warn("Could not record navigation event",e);}
    }

    private void message(String text) {
        var client=MinecraftClient.getInstance();
        if (client.player!=null) client.player.sendMessage(Text.literal("[Terminator] "+text),false);
    }

    public static void hud(DrawContext draw) {
        if (active==null || !active.showHud) return;
        var client=MinecraftClient.getInstance();
        if (client.player==null) return;
        draw.drawTextWithShadow(client.textRenderer,"Terminator | "+active.task.phase+" | F8: cancel",8,8,0xFFFFFFFF);
        draw.drawTextWithShadow(client.textRenderer,active.task.reason,8,20,0xFFE0E0E0);
        if (active.task.goal!=null) draw.drawTextWithShadow(client.textRenderer,"Goal: "+active.task.goal.x()+", "+active.task.goal.y()+", "+active.task.goal.z()+" | scripted navigation",8,32,0xFFE0E0E0);
        else if(active.task.targetName!=null) draw.drawTextWithShadow(client.textRenderer,"Target: "+active.task.targetName+" | frozen neural combat",8,32,0xFFE0E0E0);
        draw.drawTextWithShadow(client.textRenderer,String.format(java.util.Locale.ROOT,"Position: %.1f, %.1f, %.1f (measured)",
                client.player.getX(),client.player.getY(),client.player.getZ()),8,44,0xFFE0E0E0);
    }
}
