package dev.terminator.bridge;

/** Dependency-free planner contract checks; run with javac/java, no game needed. */
public final class NavigationTaskTest {
    private static void check(boolean value) {if (!value) throw new AssertionError();}
    private static void rejects(Runnable action) {
        try {action.run();throw new AssertionError("Expected rejection");}
        catch (IllegalArgumentException | IllegalStateException expected) {}
    }
    public static void main(String[] args) {
        var task=new NavigationTask();var world=new Object();
        var command=NavigationTask.parse(" GO   TO -10, -60, 20 ");
        check(command.goal().equals(new NavigationTask.Goal(-10,-60,20)));
        task.start(command.goal(),world);long first=task.generation;
        task.stop("test");check(task.phase==NavigationTask.Phase.PAUSED && task.generation>first);
        rejects(()->task.resume(new Object()));task.resume(world);check(task.phase==NavigationTask.Phase.RUNNING);
        task.cancel("F8");check(task.goal==null && task.world==null);rejects(()->task.resume(world));
        rejects(()->NavigationTask.parse("follow me"));
        rejects(()->NavigationTask.parse("go to 0 0 0 then mine"));
        rejects(()->NavigationTask.parse("go to 30000000 0 0"));
        rejects(()->NavigationTask.parse("go to 0 9999 0"));
        rejects(()->NavigationTask.parse("go to NaN 0 0"));
        task.start(command.goal(),world);task.arrive();rejects(()->task.resume(world));
        check(NavigationTask.parse("fight Some_Player").username().equals("some_player"));
        rejects(()->NavigationTask.parse("fight bad/name"));
        task.fight(java.util.UUID.randomUUID(),"Some_Player",world);check(task.phase==NavigationTask.Phase.APPROACHING);
        task.engage();check(task.phase==NavigationTask.Phase.FIGHTING && task.active());
        task.stop("test");task.resume(world);check(task.phase==NavigationTask.Phase.APPROACHING);
        task.cancel("F8");check(task.target==null && task.targetName==null);rejects(()->task.resume(world));
        System.out.println("Navigation planner checks passed");
    }
}
