package mcopt.bench;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.device.DeviceInfo;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;

/**
 * Scripted, repeatable fps benchmark, off unless -Dmcopt.bench (or mcopt.bench in config/mcopt.properties) names phases.
 * <p>
 * In a world with no screen open it runs: SETTLE (camera held still until the section queue has stayed empty for 2 s, at
 * least settleMin s, at most settleMax s, then settleHold more s), then each listed phase, runs times over:
 * <ul>
 * <li>{@code spin}: yaw turns 360 degrees every spinPeriod s at pitch 0, for spin s.</li>
 * <li>{@code flight}: sprint-flying straight ahead at pitch 0 for flight s (needs a game mode that can fly; skipped
 * otherwise).</li>
 * </ul>
 * Results go to {@code <game dir>/mcopt-bench/bench-<time>.json} (summary, setup) and {@code ...-frames.csv} (every frame
 * time), and a one-line summary to chat and the log.
 * <p>
 * Options (system properties or mcopt.* keys in config/mcopt.properties): mcopt.bench=spin,flight;
 * mcopt.bench.pos=X,Y,Z,YAW,PITCH (sent as /tp first: needs cheats); mcopt.bench.settleMin=5; mcopt.bench.settleMax=60;
 * mcopt.bench.settleHold=0; mcopt.bench.spin=20; mcopt.bench.spinPeriod=10; mcopt.bench.flight=20; mcopt.bench.runs=1;
 * mcopt.bench.quit=false (close the game when done).
 * <p>
 * mcopt reads {@link #phase}, {@link #phaseStart}, {@link #settledAt} and {@link #clockVirtual} by reflection (Recorder,
 * FrameCapture, Cpu, AbShots): keep their names and types.
 */
public final class Bench {
	public enum Phase { IDLE, SETTLE, SPIN, FLIGHT, DONE }

	public static volatile Phase phase = Phase.IDLE;
	/** System.nanoTime() when the current phase began. */
	public static volatile long phaseStart;
	/** System.nanoTime() when SETTLE's condition was first met (0 before). */
	public static volatile long settledAt;
	/** Always 0: this bench runs on the real clock. */
	public static volatile long clockVirtual;

	private static final String PLAN = System.getProperty("mcopt.bench", "").trim();
	static final boolean ON = !PLAN.isEmpty();
	private static final double SETTLE_MIN = dbl("mcopt.bench.settleMin", 5), SETTLE_MAX = dbl("mcopt.bench.settleMax", 60);
	private static final double SETTLE_HOLD = dbl("mcopt.bench.settleHold", 0), SETTLE_QUIET = 2;
	private static final double SPIN = dbl("mcopt.bench.spin", 20), SPIN_PERIOD = dbl("mcopt.bench.spinPeriod", 10);
	private static final double FLIGHT = dbl("mcopt.bench.flight", 20);
	private static final int RUNS = Math.max(1, Integer.getInteger("mcopt.bench.runs", 1));
	private static final String POS = System.getProperty("mcopt.bench.pos", "").trim();
	private static final boolean QUIT = Boolean.getBoolean("mcopt.bench.quit");
	private static final long STUTTER_US = 50_000;

	private static final List<Phase> steps = new ArrayList<>();
	private static int step = -1;
	private static long lastFrame, quietSince;
	private static float yaw, pitch;
	private static boolean started, finished;
	private static String abort, note;
	private static double settleSeconds;
	private static boolean settleTimedOut;
	private static Map<String, Object> setup;
	private static final List<Segment> segments = new ArrayList<>();
	private static Segment current;

	private Bench() {
	}

	private static final class Segment {
		final String name;
		int[] us = new int[1 << 14];
		int n;
		long startNs, endNs;

		Segment(String name, long now) {
			this.name = name;
			this.startNs = now;
		}

		void add(long dtNs) {
			if (this.n == this.us.length) this.us = Arrays.copyOf(this.us, this.n * 2);
			this.us[this.n++] = (int) Math.min(Integer.MAX_VALUE, dtNs / 1000);
		}
	}

	/** Called at the start of every frame (Minecraft.runTick HEAD). */
	public static void frame() {
		if (!ON || finished) return;
		Minecraft mc = Minecraft.getInstance();
		long now = System.nanoTime();
		long dt = lastFrame == 0 ? 0 : now - lastFrame;
		lastFrame = now;
		LocalPlayer player = mc.player;
		if (!started) {
			if (mc.level == null || player == null || mc.gui.screen() != null) return;
			start(mc, player, now);
			return;
		}
		if (mc.level == null || player == null) {
			finish(mc, now, "left the world");
			return;
		}
		if (phase != Phase.SETTLE && mc.gui.screen() != null) {
			finish(mc, now, "a screen opened (pause menu? keep the window focused, or turn off pause on lost focus with F3+P)");
			return;
		}
		if (current != null && dt > 0) current.add(dt);
		double t = (now - phaseStart) / 1e9;
		switch (phase) {
			case SETTLE -> {
				hold(player, yaw, pitch);
				boolean quiet = mc.levelRenderer.hasRenderedAllSections();
				if (!quiet) quietSince = 0;
				else if (quietSince == 0) quietSince = now;
				if (settledAt == 0) {
					boolean met = t >= SETTLE_MIN && quietSince != 0 && (now - quietSince) / 1e9 >= SETTLE_QUIET;
					if (met || t >= SETTLE_MAX) {
						settledAt = now;
						settleSeconds = t;
						settleTimedOut = !met;
					}
				} else if ((now - settledAt) / 1e9 >= SETTLE_HOLD) {
					next(mc, player, now);
				}
			}
			case SPIN -> {
				float y = yaw + (float) (360.0 * t / SPIN_PERIOD);
				hold(player, y, 0);
				if (t >= SPIN) next(mc, player, now);
			}
			case FLIGHT -> {
				hold(player, yaw, 0);
				mc.options.keyUp.setDown(true);
				player.setSprinting(true);
				if (t >= FLIGHT) {
					mc.options.keyUp.setDown(false);
					player.setSprinting(false);
					next(mc, player, now);
				}
			}
			default -> { }
		}
	}

	private static void start(Minecraft mc, LocalPlayer player, long now) {
		started = true;
		for (int r = 0; r < RUNS; r++) {
			for (String s : PLAN.toLowerCase(Locale.ROOT).split(",")) {
				switch (s.trim()) {
					case "spin" -> steps.add(Phase.SPIN);
					case "flight" -> steps.add(Phase.FLIGHT);
					case "", "settle" -> { }
					default -> note = append(note, "unknown phase '" + s.trim() + "' ignored");
				}
			}
		}
		yaw = player.getYRot();
		pitch = player.getXRot();
		if (!POS.isEmpty()) {
			String[] p = POS.split(",");
			if (p.length == 5) {
				player.connection.sendCommand("tp @s " + p[0].trim() + " " + p[1].trim() + " " + p[2].trim() + " " + p[3].trim() + " " + p[4].trim());
				yaw = Float.parseFloat(p[3].trim());
				pitch = Float.parseFloat(p[4].trim());
			} else {
				note = append(note, "mcopt.bench.pos needs X,Y,Z,YAW,PITCH; ignored");
			}
		}
		setup = setup(mc, player);
		chat(mc, "[mcopt-bench] started: settle, then " + PLAN + (RUNS > 1 ? " x" + RUNS : "") + ". Don't touch the mouse or keys.");
		enter(Phase.SETTLE, now);
		current = new Segment("settle", now);
		segments.add(current);
	}

	private static void next(Minecraft mc, LocalPlayer player, long now) {
		if (current != null) current.endNs = now;
		current = null;
		step++;
		while (step < steps.size() && steps.get(step) == Phase.FLIGHT && !player.getAbilities().mayfly) {
			note = append(note, "flight skipped: the game mode can't fly (use creative or spectator)");
			step++;
		}
		if (step >= steps.size()) {
			finish(mc, now, null);
			return;
		}
		Phase p = steps.get(step);
		if (p == Phase.FLIGHT && !player.getAbilities().flying) {
			player.getAbilities().flying = true;
			player.onUpdateAbilities();
		}
		yaw = player.getYRot();
		enter(p, now);
		current = new Segment(p.name().toLowerCase(Locale.ROOT) + (RUNS > 1 ? "#" + (step / Math.max(1, steps.size() / RUNS) + 1) : ""), now);
		segments.add(current);
	}

	private static void enter(Phase p, long now) {
		phaseStart = now;
		phase = p;
	}

	private static void hold(LocalPlayer player, float y, float x) {
		player.setYRot(y);
		player.yRotO = y;
		player.setXRot(x);
		player.xRotO = x;
	}

	private static void finish(Minecraft mc, long now, String reason) {
		finished = true;
		abort = reason;
		if (current != null && current.endNs == 0) current.endNs = now;
		mc.options.keyUp.setDown(false);
		enter(Phase.DONE, now);
		String summary = write(mc);
		chat(mc, summary);
		System.out.println(summary);
		if (QUIT && reason == null) mc.stop();
	}

	// ---- results ----

	private static Map<String, Object> stats(Segment s) {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("name", s.name);
		m.put("frames", s.n);
		if (s.n == 0) return m;
		int[] sorted = Arrays.copyOf(s.us, s.n);
		Arrays.sort(sorted);
		long total = 0;
		for (int i = 0; i < s.n; i++) total += s.us[i];
		double seconds = total / 1e6;
		m.put("seconds", round(seconds, 3));
		m.put("avgFps", round(s.n / seconds, 1));
		m.put("low1Fps", round(lowFps(sorted, 0.01), 1));
		m.put("low01Fps", round(lowFps(sorted, 0.001), 1));
		m.put("p50Ms", round(pct(sorted, 0.50) / 1000.0, 3));
		m.put("p90Ms", round(pct(sorted, 0.90) / 1000.0, 3));
		m.put("p99Ms", round(pct(sorted, 0.99) / 1000.0, 3));
		m.put("p999Ms", round(pct(sorted, 0.999) / 1000.0, 3));
		m.put("maxMs", round(sorted[s.n - 1] / 1000.0, 3));
		int stutters = 0;
		for (int i = 0; i < s.n; i++) if (s.us[i] >= STUTTER_US) stutters++;
		m.put("stutters50ms", stutters);
		return m;
	}

	/** 1000 / mean of the slowest `fraction` of frame times (at least one frame), as fps. */
	private static double lowFps(int[] sortedUs, double fraction) {
		int k = Math.max(1, (int) Math.ceil(sortedUs.length * fraction));
		long sum = 0;
		for (int i = sortedUs.length - k; i < sortedUs.length; i++) sum += sortedUs[i];
		return 1e6 / (sum / (double) k);
	}

	/** Nearest-rank percentile of sorted frame times. */
	private static int pct(int[] sortedUs, double q) {
		int rank = (int) Math.ceil(q * sortedUs.length);
		return sortedUs[Math.min(sortedUs.length - 1, Math.max(0, rank - 1))];
	}

	private static String write(Minecraft mc) {
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("bench", "mcopt-bench 1");
		out.put("time", LocalDateTime.now().toString());
		out.put("completed", abort == null);
		if (abort != null) out.put("aborted", abort);
		if (note != null) out.put("notes", note);
		out.put("settleSeconds", round(settleSeconds, 2));
		out.put("settleTimedOut", settleTimedOut);
		out.put("definitions", "frame time = start of one frame to the next (Minecraft.runTick); avgFps = frames / sum of frame times; "
			+ "lowNFps = 1000 / mean of the slowest N% frame times; pNNMs = nearest-rank percentile; stutters50ms = frames >= 50 ms");
		List<Object> phases = new ArrayList<>();
		StringBuilder line = new StringBuilder("[mcopt-bench] ");
		for (Segment s : segments) {
			Map<String, Object> st = stats(s);
			phases.add(st);
			if (!s.name.equals("settle") && s.n > 0) line.append(s.name).append(": ").append(st.get("avgFps")).append(" fps, 1% low ").append(st.get("low1Fps")).append(" | ");
		}
		out.put("phases", phases);
		out.put("setup", setup);
		String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
		Path dir = mc.gameDirectory.toPath().resolve("mcopt-bench");
		Path json = dir.resolve("bench-" + stamp + ".json");
		try {
			Files.createDirectories(dir);
			Files.writeString(json, Json.write(out) + "\n", StandardCharsets.UTF_8);
			try (Writer w = Files.newBufferedWriter(dir.resolve("bench-" + stamp + "-frames.csv"), StandardCharsets.UTF_8)) {
				w.write("phase,frame,us\n");
				for (Segment s : segments) {
					for (int i = 0; i < s.n; i++) w.write(s.name + "," + i + "," + s.us[i] + "\n");
				}
			}
			line.append("saved ").append(dir.getFileName()).append('/').append(json.getFileName());
		} catch (IOException e) {
			line.append("couldn't save: ").append(e);
		}
		if (abort != null) line.append(" (ABORTED: ").append(abort).append(')');
		return line.toString();
	}

	private static Map<String, Object> setup(Minecraft mc, LocalPlayer player) {
		Map<String, Object> m = new LinkedHashMap<>();
		try {
			DeviceInfo d = RenderSystem.getDevice().getDeviceInfo();
			m.put("gpu", d.name());
			m.put("gpuVendor", d.vendorName());
			m.put("backend", d.backendName());
			m.put("driver", d.driverInfo());
		} catch (Throwable t) {
			m.put("gpu", "unknown: " + t);
		}
		m.put("framebuffer", mc.getWindow().getWidth() + "x" + mc.getWindow().getHeight());
		m.put("fullscreen", mc.options.fullscreen().get());
		m.put("renderDistance", mc.options.renderDistance().get());
		m.put("vsync", mc.options.enableVsync().get());
		m.put("maxFps", mc.options.framerateLimit().get());
		m.put("pos", String.format(Locale.ROOT, "%.1f,%.1f,%.1f,%.1f,%.1f", player.getX(), player.getY(), player.getZ(), yaw, pitch));
		m.put("dimension", mc.level.dimension().identifier().toString());
		m.put("singleplayer", mc.hasSingleplayerServer());
		m.put("os", System.getProperty("os.name") + " " + System.getProperty("os.version") + " " + System.getProperty("os.arch"));
		m.put("java", System.getProperty("java.vm.name") + " " + System.getProperty("java.version"));
		m.put("cpus", Runtime.getRuntime().availableProcessors());
		m.put("maxHeapMb", Runtime.getRuntime().maxMemory() >> 20);
		Map<String, String> flags = new TreeMap<>();
		System.getProperties().stringPropertyNames().stream().filter(k -> k.startsWith("mcopt.")).forEach(k -> flags.put(k, System.getProperty(k)));
		m.put("mcoptFlags", flags);
		Map<String, String> mods = new TreeMap<>();
		FabricLoader.getInstance().getAllMods().forEach(c -> mods.put(c.getMetadata().getId(), c.getMetadata().getVersion().getFriendlyString()));
		m.put("mods", mods);
		return m;
	}

	private static void chat(Minecraft mc, String message) {
		try {
			mc.gui.hud.getChat().addClientSystemMessage(Component.literal(message));
		} catch (Throwable ignored) {
			// chat isn't there: the log line stands
		}
	}

	private static double dbl(String key, double def) {
		try {
			return Double.parseDouble(System.getProperty(key, String.valueOf(def)).trim());
		} catch (NumberFormatException e) {
			return def;
		}
	}

	private static double round(double v, int digits) {
		double f = Math.pow(10, digits);
		return Math.round(v * f) / f;
	}

	private static String append(String a, String b) {
		return a == null ? b : a + "; " + b;
	}
}
