package dev.terminator.bridge;

/** Replaceable navigation capability. No combat or planner decisions live here. */
interface NavigationBackend {
    void goTo(int x, int y, int z) throws ReflectiveOperationException;
    void cancel(float yaw, float pitch) throws ReflectiveOperationException;
    boolean hasForcedInputs() throws ReflectiveOperationException;
    boolean isPathing() throws ReflectiveOperationException;
}
