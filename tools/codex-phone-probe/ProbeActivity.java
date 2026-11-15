package dev.soundsoftware.codexprobe;

import android.app.Activity;
import android.os.Bundle;
import android.widget.TextView;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.TimeUnit;

public class ProbeActivity extends Activity {
    private File files;
    private static final String CSV = "task,status\nsynthetic todo,draft\n";
    private static final String CANARY = "outside-only\n";

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        TextView view = new TextView(this);
        view.setText("Running Codex + Node on the phone");
        setContentView(view);
        files = getFilesDir();
        new Thread(() -> {
            String report;
            try {
                report = runProbe();
            } catch (Throwable error) {
                report = android.util.Log.getStackTraceString(error);
            }
            try {
                Files.write(new File(files, "probe-result.txt").toPath(), report.getBytes(StandardCharsets.UTF_8));
            } catch (Exception error) {
                android.util.Log.e("SoundCodexProbe", "Could not save result", error);
            }
            final String screen = report;
            runOnUiThread(() -> view.setText(screen));
        }).start();
    }

    private static JSONObject object(Object... fields) throws Exception {
        JSONObject value = new JSONObject();
        for (int i = 0; i < fields.length; i += 2) value.put((String) fields[i], fields[i + 1]);
        return value;
    }

    private static JSONObject call(BufferedReader input, BufferedWriter output, Integer id,
                                   String method, JSONObject params) throws Exception {
        JSONObject request = object("method", method, "params", params);
        if (id != null) request.put("id", id);
        output.write(request.toString());
        output.newLine();
        output.flush();
        if (id == null) return null;
        String line;
        while ((line = input.readLine()) != null) {
            JSONObject packet = new JSONObject(line);
            if (packet.optInt("id", -1) != id) continue;
            if (packet.has("error")) throw new IllegalStateException(packet.toString());
            return packet.getJSONObject("result");
        }
        throw new IllegalStateException("Codex closed stdout before " + method);
    }

    private String runProbe() throws Exception {
        File root = Files.createTempDirectory(files.toPath(), "run-").toFile();
        File job = new File(root, "job");
        File outside = new File(root, "outside");
        Files.createDirectory(job.toPath());
        Files.createDirectory(outside.toPath());
        File home = new File(job, "home");
        Files.createDirectory(home.toPath());
        File canary = new File(outside, "canary.txt");
        Files.write(canary.toPath(), CANARY.getBytes(StandardCharsets.UTF_8));
        Files.write(new File(job, "input.txt").toPath(), "synthetic todo\n".getBytes(StandardCharsets.UTF_8));
        try (java.io.InputStream script = getAssets().open("probe.cjs")) {
            Files.copy(script, new File(job, "probe.cjs").toPath());
        }

        File runtime = new File(getApplicationInfo().nativeLibraryDir);
        File stderr = new File(job, "codex-stderr.txt");
        ProcessBuilder builder = new ProcessBuilder(
            new File(runtime, "libprobe_sandbox.so").getAbsolutePath(), job.getAbsolutePath(),
            runtime.getAbsolutePath(), new File(runtime, "libprobe_codex.so").getAbsolutePath(),
            "-c", "web_search=\"disabled\"", "app-server", "--listen", "stdio://");
        builder.directory(job);
        builder.redirectError(stderr);
        builder.environment().clear();
        builder.environment().put("HOME", home.getAbsolutePath());
        builder.environment().put("CODEX_HOME", home.getAbsolutePath());
        builder.environment().put("TMPDIR", home.getAbsolutePath());
        builder.environment().put("PATH", "/system/bin");
        builder.environment().put("SHELL", "/system/bin/sh");
        Process process = builder.start();
        String report;
        try (BufferedReader input = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
             BufferedWriter output = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8))) {
            JSONObject hello = call(input, output, 1, "initialize",
                object("clientInfo", object("name", "sound_android_probe", "version", "0.1")));
            call(input, output, null, "initialized", new JSONObject());
            JSONArray argv = new JSONArray()
                .put(new File(runtime, "libprobe_node.so").getAbsolutePath()).put("probe.cjs").put(canary.getAbsolutePath());
            JSONObject result = call(input, output, 2, "command/exec", object("command", argv,
                "cwd", job.getAbsolutePath(), "timeoutMs", 10000, "outputBytesCap", 5000, "sandboxPolicy",
                object("type", "externalSandbox", "networkAccess", "restricted")));
            if (result.getInt("exitCode") != 0 ||
                !CSV.equals(new String(Files.readAllBytes(new File(job, "result.csv").toPath()), StandardCharsets.UTF_8)) ||
                !CANARY.equals(new String(Files.readAllBytes(canary.toPath()), StandardCharsets.UTF_8)) ||
                new File(outside, "created.txt").exists()) {
                throw new IllegalStateException(result.toString());
            }
            report = "PASS appUid=" + android.os.Process.myUid() + "\n" +
                hello.getString("userAgent") + "\n" + result.getString("stdout");
        } finally {
            if (!process.waitFor(3, TimeUnit.SECONDS)) process.destroyForcibly();
            android.util.Log.i("SoundCodexProbe", new String(Files.readAllBytes(stderr.toPath()), StandardCharsets.UTF_8));
        }
        return report;
    }
}
