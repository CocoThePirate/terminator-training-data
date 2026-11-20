package dev.terminator.bridge;

import java.util.Locale;
import java.util.regex.Pattern;

/** Small deterministic planner. No learned behavior and no Baritone dependency. */
final class NavigationTask {
    enum Phase { IDLE, RUNNING, APPROACHING, FIGHTING, PAUSED, ARRIVED, CANCELLED, FAILED }
    record Goal(int x, int y, int z) {}
    record Command(String action, Goal goal, String username) {
        Command(String action,Goal goal) {this(action,goal,null);}
    }
    private static final Pattern GO = Pattern.compile("go to (-?\\d{1,8})[ ,]+(-?\\d{1,4})[ ,]+(-?\\d{1,8})");
    Phase phase = Phase.IDLE;
    Goal goal;
    long generation;
    Object world;
    java.util.UUID target;
    String targetName;
    String reason = "No task";

    static Command parse(String text) {
        String normalized = text.strip().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
        var matcher = GO.matcher(normalized);
        if (normalized.matches("fight [a-z0-9_]{3,16}")) return new Command("fight",null,normalized.substring(6));
        if (matcher.matches()) {
            var goal = new Goal(Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2)), Integer.parseInt(matcher.group(3)));
            if (Math.abs((long) goal.x) > 29_999_984 || Math.abs((long) goal.z) > 29_999_984 || goal.y < -2048 || goal.y > 2048)
                throw new IllegalArgumentException("Coordinates outside supported bounds");
            return new Command("go", goal);
        }
        if (normalized.equals("stop") || normalized.equals("resume") || normalized.equals("cancel")) return new Command(normalized, null);
        throw new IllegalArgumentException("Use: go to X Y Z, fight USERNAME, stop, resume, or cancel");
    }

    void start(Goal target, Object context) {
        if (context == null) throw new IllegalStateException("Join a world first");
        generation++; goal = target; this.target=null;targetName=null; world = context; phase = Phase.RUNNING; reason = "Navigating";
    }

    void fight(java.util.UUID uuid,String name,Object context) {
        if(context==null) throw new IllegalStateException("Join a world first");
        generation++;target=uuid;targetName=name;goal=null;world=context;
        phase=Phase.APPROACHING;reason="Approaching "+name;
    }
    boolean active() {return phase==Phase.RUNNING || phase==Phase.APPROACHING || phase==Phase.FIGHTING;}
    void engage() {generation++;phase=Phase.FIGHTING;goal=null;reason="Fighting "+targetName;}
    void approach() {generation++;phase=Phase.APPROACHING;goal=null;reason="Approaching "+targetName;}

    void stop(String why) {
        generation++; phase = goal == null && target==null ? Phase.IDLE : Phase.PAUSED; reason = why;
    }

    void resume(Object context) {
        if (phase != Phase.PAUSED || (goal == null && target==null) || world != context) throw new IllegalStateException("No paused task in this world");
        generation++; phase = target==null ? Phase.RUNNING : Phase.APPROACHING; reason = "Resumed";
    }

    void cancel(String why) {
        generation++; phase = Phase.CANCELLED; goal = null; target=null;targetName=null; world = null; reason = why;
    }

    void arrive() { generation++; phase = Phase.ARRIVED; goal = null; world = null; reason = "Arrived"; }
    void fail(String why) { cancel(why); phase = Phase.FAILED; }
}
