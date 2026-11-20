package dev.terminator.bridge;

/** Optional API-only adapter. Invoke methods on public API interfaces, since
 * implementation names in the official API JAR are obfuscated. No game-class
 * method names or internal Baritone classes are reflected. */
final class BaritoneNavigation implements NavigationBackend {
    private final Object baritone;

    BaritoneNavigation() throws ReflectiveOperationException {
        Object provider = api("BaritoneAPI").getMethod("getProvider").invoke(null);
        baritone = api("IBaritoneProvider").getMethod("getPrimaryBaritone").invoke(provider);
        setting("allowBreak", false); setting("allowPlace", false);
        setting("allowBreakAnyway", new java.util.ArrayList<>());
        setting("allowInventory", false); setting("autoTool", false);
        setting("allowParkour", false); setting("allowParkourPlace", false);
        setting("allowWaterBucketFall", false); setting("chunkCaching", false);
        setting("chatControl", false); setting("chatControlAnyway", false);
        setting("prefixControl", false); setting("doBedWaypoints", false);
        setting("doDeathWaypoints", false); setting("antiCheatCompatibility", false);
        setting("randomLooking", 0.0); setting("randomLooking113", 0.0);
        setting("smoothLook", false); setting("freeLook", true);
    }

    private static Class<?> api(String name) throws ClassNotFoundException {
        return Class.forName("baritone.api." + name);
    }

    private static void setting(String name, Object value) throws ReflectiveOperationException {
        Object settings = api("BaritoneAPI").getMethod("getSettings").invoke(null);
        Object setting = api("Settings").getField(name).get(settings);
        api("Settings$Setting").getField("value").set(setting, value);
    }

    private Object component(String name) throws ReflectiveOperationException {
        return api("IBaritone").getMethod(name).invoke(baritone);
    }

    @Override public void goTo(int x, int y, int z) throws ReflectiveOperationException {
        setting("freeLook", false);
        Object goal = api("pathing.goals.GoalBlock").getConstructor(int.class, int.class, int.class).newInstance(x, y, z);
        api("process.ICustomGoalProcess").getMethod("setGoalAndPath", api("pathing.goals.Goal"))
                .invoke(component("getCustomGoalProcess"), goal);
    }

    @Override public void cancel(float yaw, float pitch) throws ReflectiveOperationException {
        // Soft cancellation may finish a movement. This isolated, parkour-off
        // adapter deliberately uses the API emergency path; it is tested live.
        api("utils.IInputOverrideHandler").getMethod("clearAllKeys").invoke(component("getInputOverrideHandler"));
        try {
            api("behavior.IPathingBehavior").getMethod("forceCancel").invoke(component("getPathingBehavior"));
        } finally {
            api("utils.IInputOverrideHandler").getMethod("clearAllKeys").invoke(component("getInputOverrideHandler"));
        }
        // Replace any queued look with a NONE-mode target at the current view.
        // Otherwise a target queued earlier in the tick can turn after cancel.
        setting("freeLook", true);
        if(net.minecraft.client.MinecraftClient.getInstance().player!=null) {
            Object rotation = api("utils.Rotation").getConstructor(float.class, float.class).newInstance(yaw, pitch);
            api("behavior.ILookBehavior").getMethod("updateTarget", api("utils.Rotation"), boolean.class)
                    .invoke(component("getLookBehavior"), rotation, false);
        }
    }

    @Override public boolean hasForcedInputs() throws ReflectiveOperationException {
        Object handler = component("getInputOverrideHandler");
        Class<?> input = api("utils.input.Input");
        for (Object key : input.getEnumConstants()) {
            if ((boolean) api("utils.IInputOverrideHandler").getMethod("isInputForcedDown", input).invoke(handler, key)) return true;
        }
        return false;
    }

    @Override public boolean isPathing() throws ReflectiveOperationException {
        return (boolean) api("behavior.IPathingBehavior").getMethod("isPathing").invoke(component("getPathingBehavior"));
    }
}
