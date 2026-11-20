package dev.terminator.bridge;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.InactivityFpsLimit;

import static dev.terminator.bridge.ReachAndStop.*;

/** Controls the separate client through ordinary inputs; never receives enemy
 * health or rewards. The arena sends only public phase/identity/tick metadata. */
final class GuestDuel {
    private static final long MAX_AGE = TimeUnit.MILLISECONDS.toNanos(225);
    private static final Controls NEUTRAL = new Controls(false, false, false, false,
            false, false, false, false, false, 0, 0, null);
    private TrainingBridge.DuelState state;
    private UUID opponentId;
    private long tick, observedTick, observedAt;
    private Controls queued;
    private long queuedSource = -1, appliedSource = -1;
    private String queuedStatus = "no_controls", appliedStatus = "no_controls";
    private boolean attackHeld, hadWorld;
    private Map<String, Object> applied = Map.of();
    private CompletableFuture<Object> poll;
    private final ArrayDeque<Map<String, Object>> frames = new ArrayDeque<>();
    private long discardedFrames;
    private boolean policyJumpPressed;
    private int jumpInputs, policyJumps, emptyJumps;

    void prepareClient(MinecraftClient client) {
        client.options.pauseOnLostFocus = false;
        client.options.getInactivityFpsLimit().setValue(InactivityFpsLimit.MINIMIZED);
    }

    void state(TrainingBridge.DuelState next) {
        if (state != null && !state.episodeId().equals(next.episodeId())) {
            queued = null;
            attackHeld = false;
            frames.clear();
            discardedFrames = 0;
            jumpInputs = policyJumps = emptyJumps = 0;
        }
        state = next;
        opponentId = UUID.fromString(next.hostId());
    }

    void prepareRound(MinecraftClient client) {
        require(state != null && java.util.Set.of("human_won", "terminator_won", "draw").contains(state.phase()),
                "Guest respawn requires a completed duel");
        queued = null;
        attackHeld = false;
        frames.clear();
        TrainingBridge.releaseControls(client);
        if (!client.player.isAlive()) client.player.requestRespawn();
    }

    private String phase(MinecraftClient client) {
        if (hadWorld && client.world == null) return "disconnected";
        return state == null ? "waiting" : state.phase();
    }

    void poll(long source, Controls controls, CompletableFuture<Object> response) {
        require(poll == null, "A guest poll is already pending");
        queued = source == observedTick && tick - observedTick <= 3
                && System.nanoTime() - observedAt <= MAX_AGE && state != null
                && state.phase().equals("fighting") ? controls : null;
        queuedSource = source;
        queuedStatus = controls == null ? "no_controls" : queued != null ? "applied" : "dropped_stale_or_phase";
        poll = response;
    }

    void beforeClientTick(MinecraftClient client) {
        Controls controls = queued;
        queued = null;
        appliedSource = queuedSource; appliedStatus = queuedStatus;
        queuedSource = -1; queuedStatus = "no_controls";
        policyJumpPressed = false;
        if (controls != null && client.player != null && client.player.isAlive()
                && phase(client).equals("fighting") && System.nanoTime() - observedAt <= MAX_AGE) {
            applied = TrainingBridge.applyControls(client, controls, true, attackHeld);
            policyJumpPressed = controls.jump();
            if (policyJumpPressed) jumpInputs++;
            attackHeld = controls.attack();
        } else {
            if (controls != null) appliedStatus = "dropped_expired_or_unavailable";
            TrainingBridge.releaseControls(client);
            applied = client.player == null ? Map.of() : TrainingBridge.applyControls(client, NEUTRAL, true, false);
            attackHeld = false;
        }
    }

    void afterClientTick(MinecraftClient client) {
        policyJumpPressed = false;
        if (client.player != null) TrainingBridge.releaseControls(client);
        tick++;
        String phase = phase(client);
        if (client.world != null) hadWorld = true;
        var frame = new HashMap<String, Object>();
        frame.put("client_tick", tick);
        frame.put("timestamp_ms", System.currentTimeMillis());
        frame.put("phase", phase);
        frame.put("based_on_tick", appliedSource);
        frame.put("controls_status", appliedStatus);
        frame.put("applied_controls", applied);
        frame.put("jump_inputs", jumpInputs);
        frame.put("policy_jumps", policyJumps);
        frame.put("empty_jumps_proxy", emptyJumps);
        frame.put("discarded_frames_total", discardedFrames);
        // Public server clock metadata stays outside the policy payload.
        if (state != null) frame.put("phase_server_tick", state.serverTick());
        if (state != null && client.world != null && client.player != null) {
            var opponent = client.world.getPlayerByUuid(opponentId);
            frame.put("observation", new Observation(state.episodeId(), tick, SwordDrill.SCHEMA,
                    SwordDrill.observe(client, Player.capture(client.player), opponent)));
        }
        if (frames.size() == 256) { frames.removeFirst(); discardedFrames++; }
        frames.addLast(Map.copyOf(frame));
        if (poll == null) return;
        var response = new HashMap<String, Object>(frame);
        response.put("type", "duel_frame");
        response.put("client_frames", List.copyOf(frames));
        response.put("discarded_frames_total", discardedFrames);
        frames.clear();
        if (phase.equals("fighting") && frame.containsKey("observation")) {
            observedTick = tick;
            observedAt = System.nanoTime();
        }
        poll.complete(Map.copyOf(response));
        poll = null;
    }

    void recordJump() {
        if (policyJumpPressed) { policyJumps++; if (!attackHeld) emptyJumps++; }
    }
}
