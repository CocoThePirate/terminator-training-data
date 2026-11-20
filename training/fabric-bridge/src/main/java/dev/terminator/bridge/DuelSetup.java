package dev.terminator.bridge;

import com.google.gson.JsonObject;
import java.util.Map;
import static dev.terminator.bridge.ReachAndStop.*;

/** Arena initialization only. Never added to a policy observation. */
record DuelSetup(float hostHealth, int hostApples, float guestHealth, int guestApples,
                 double distance, boolean mirror, ArenaScenario scenario) {
    DuelSetup(float hostHealth,int hostApples,float guestHealth,int guestApples,double distance,boolean mirror) {
        this(hostHealth,hostApples,guestHealth,guestApples,distance,mirror,null);
    }
    static final DuelSetup DEFAULT = new DuelSetup(20, 3, 20, 3, 5, false);
    DuelSetup {
        require(Float.isFinite(hostHealth) && hostHealth >= 1 && hostHealth <= 20
                && Float.isFinite(guestHealth) && guestHealth >= 1 && guestHealth <= 20
                && hostApples >= 0 && hostApples <= (scenario==null?3:64) && guestApples >= 0 && guestApples <= (scenario==null?3:64)
                && Double.isFinite(distance) && distance >= 2 && distance <= 10, "Invalid duel setup");
    }
    static DuelSetup parse(JsonObject json) {
        if (json == null) return DEFAULT;
        var keys=new java.util.HashSet<>(java.util.Set.of("host_health", "host_apples", "guest_health", "guest_apples", "distance", "mirror"));
        if(json.has("scenario"))keys.add("scenario");
        require(json.keySet().equals(keys),
                "A duel setup must specify health/apples for both players, distance and mirror");
        require(json.get("host_apples").getAsDouble() == json.get("host_apples").getAsInt()
                && json.get("guest_apples").getAsDouble() == json.get("guest_apples").getAsInt(), "Apple counts must be integers");
        return new DuelSetup(json.get("host_health").getAsFloat(), json.get("host_apples").getAsInt(),
                json.get("guest_health").getAsFloat(), json.get("guest_apples").getAsInt(),
                json.get("distance").getAsDouble(), json.get("mirror").getAsBoolean(),
                json.has("scenario")?new ArenaScenario(json.getAsJsonObject("scenario")):null);
    }
    Map<String, Object> feedback() {
        var feedback=new java.util.HashMap<String,Object>(Map.of("host_health", hostHealth, "host_apples", hostApples,
                "guest_health", guestHealth, "guest_apples", guestApples, "distance", distance, "mirror", mirror));
        if(scenario!=null)feedback.put("scenario",scenario.description());return feedback;
    }
}
