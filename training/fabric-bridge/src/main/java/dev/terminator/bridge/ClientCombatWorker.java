package dev.terminator.bridge;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.atomic.AtomicReference;

/** Frozen Rust policy process. All pipe I/O and inference stay off the game
 * thread. Late results carry a task generation and cannot reacquire inputs. */
final class ClientCombatWorker implements AutoCloseable {
    private final Process process;
    private final ArrayBlockingQueue<JsonObject> requests=new ArrayBlockingQueue<>(1);
    private final AtomicReference<JsonObject> result=new AtomicReference<>();
    private volatile boolean ready, closed;
    private volatile String error;
    private volatile JsonObject metadata;

    ClientCombatWorker(Path executable,Path checkpoint) throws IOException {
        process=new ProcessBuilder(executable.toString(),checkpoint.toString())
                .redirectError(Path.of("terminator-policy-stderr.log").toFile()).start();
        Thread.ofPlatform().daemon().name("terminator-frozen-policy").start(()->{
            try (var reader=new BufferedReader(new InputStreamReader(process.getInputStream(),StandardCharsets.UTF_8));
                 var writer=new BufferedWriter(new OutputStreamWriter(process.getOutputStream(),StandardCharsets.UTF_8))) {
                metadata=read(reader);
                if (!metadata.get("type").getAsString().equals("policy_ready")) throw new IOException("Policy initialization failed");
                ready=true;
                while(!closed) {
                    var request=requests.take();writer.write(request.toString());writer.newLine();writer.flush();
                    var response=read(reader);
                    if (!response.get("type").getAsString().equals("policy_frame")) throw new IOException(response.toString());
                    response.add("observation",request.get("observation"));
                    response.add("submitted_ns",request.get("submitted_ns"));
                    result.set(response);
                }
            } catch(Exception e) {if(!closed) error=e.toString();}
            finally {ready=false;process.destroy();}
        });
        Runtime.getRuntime().addShutdownHook(new Thread(this::close,"terminator-policy-shutdown"));
    }

    private static JsonObject read(BufferedReader reader) throws IOException {
        var line=reader.readLine();if(line==null) throw new EOFException("Policy exited");
        return JsonParser.parseString(line).getAsJsonObject();
    }
    boolean ready() {return ready && error==null;}
    String error() {return error;}
    JsonObject metadata() {return metadata;}
    void submit(JsonObject request) {requests.poll();requests.offer(request);}
    JsonObject take() {return result.getAndSet(null);}
    void invalidate() {requests.clear();result.set(null);}
    @Override public void close() {closed=true;requests.clear();process.destroyForcibly();}
}
