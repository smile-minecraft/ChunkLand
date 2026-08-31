package com.smile.chunkland.capability;

import com.smile.acelib.AceLibApi;
import com.smile.acelib.bedrock.BedrockService;
import com.smile.acelib.form.FormResponse;
import com.smile.acelib.form.FormSendResult;
import com.smile.acelib.form.FormService;
import com.smile.acelib.form.FormSpec;
import com.smile.acelib.gui.GuiArgument;
import com.smile.acelib.gui.GuiResult;
import com.smile.acelib.gui.GuiService;
import com.smile.acelib.platform.Platform;
import com.smile.acelib.platform.PlatformCapability;
import com.smile.acelib.scheduler.AceLibScheduler;
import com.smile.acelib.scheduler.SafeScheduler;
import com.smile.acelib.scheduler.ScheduledTask;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Temporary M0-07 capability smoke probe. Per
 * {@code docs/task-breakdown.md#cl-m0-07--acelib-能力冒煙}, M0-07 verifies the four AceLib
 * capability paths available in {@code com.smile.acelib}: {@link SafeScheduler},
 * {@link GuiService}, {@link FormService} (via {@link BedrockService#forms()}), and the
 * {@link SafeScheduler#cancelAll()} cleanup at disable.
 *
 * <p>Hard contract:</p>
 * <ul>
 *   <li>Only the supported public API is referenced (no internal AceLib implementation
 *       class import, no unchecked cast, no per-plugin reflection).</li>
 *   <li>Construction is fail-closed: {@link #tryBuild} returns empty when the API is not
 *       ready or any service is missing; the dispatched command will report
 *       {@code NOT_READY} without throwing.</li>
 *   <li>The scheduler is constructed by the public factory
 *       {@code AceLibScheduler.create(plugin, platform, capability)}; no alternative
 *       scheduler framework is introduced.</li>
 *   <li>On disable ChunkLand releases {@link Capabilities#release()}, which calls
 *       {@link SafeScheduler#cancelAll()}. GUI/Forms are provider-wide and are not shut
 *       down here.</li>
 *   <li>This probe is intentionally <strong>temporary</strong>. It exists for the M0 gate
 *       only and will be retired once the M4 GUI/Form framework refactor and the
 *       structured JUnit concurrency suite replace these ad-hoc verifications.</li>
 * </ul>
 *
 * <p>Runtime placement (which Folia region a {@code runForPlayer}/{@code runAtLocation}
 * task lands in) is not observable without a live Folia server; the main agent's manual
 * verification covers that, while this probe only verifies the wiring and the fail-closed
 * paths.</p>
 */
public final class M0CapabilityProbe {

    /** Title for the {@code /chunkland m0test} command's clarification on the four modes. */
    public static final String COMMAND_DESCRIPTION =
        "/chunkland m0test [scheduler|gui|form|cancelall] — M0-07 temporary capability smoke.";

    private final Capabilities capabilities;

    /**
     * Production constructor used by {@link #tryBuild}; takes the live AceLib facade so
     * the {@link SafeScheduler} can be constructed at the seam.
     */
    private M0CapabilityProbe(Capabilities capabilities) {
        this.capabilities = capabilities;
    }

    // ---------- Static seam ----------

    /**
     * Server-independent readiness check. A probe is buildable when the API is non-null,
     * reports ready, and exposes a non-null {@link GuiService} and {@link BedrockService}.
     */
    public static boolean isApiUsable(AceLibApi api) {
        return api != null
            && api.isReady()
            && api.getGuiService() != null
            && api.getBedrockService() != null;
    }

    /**
     * Build the probe after a ready AceLib API is acquired. Returns empty (never throws)
     * when the API is missing or not ready, or when {@code AceLibScheduler.create(...)}
     * fails.
     */
    public static Optional<M0CapabilityProbe> tryBuild(JavaPlugin plugin, AceLibApi api) {
        if (!isApiUsable(api)) {
            return Optional.empty();
        }
        try {
            Platform platform = api.getPlatform();
            PlatformCapability capability = api.getPlatformCapability();
            SafeScheduler scheduler = AceLibScheduler.create(plugin, platform, capability);
            Capabilities caps = Capabilities.builder()
                .scheduler(scheduler)
                .guiService(api.getGuiService())
                .bedrockService(api.getBedrockService())
                .platform(platform)
                .capability(capability)
                .build();
            return Optional.of(new M0CapabilityProbe(caps));
        } catch (RuntimeException e) {
            // fail-closed: a scheduler construction failure must not surface as NPE or
            // pretend the capability is ready.
            return Optional.empty();
        }
    }

    /**
     * Adapt a pre-built {@link Capabilities} into an {@link Optional<M0CapabilityProbe>}.
     * Empty when the bundle is incomplete. Tests use this to avoid touching the live
     * {@code AceLibScheduler.create(...)} seam.
     */
    public static Optional<M0CapabilityProbe> fromCapabilities(Capabilities caps) {
        if (caps == null || !caps.isComplete()) {
            return Optional.empty();
        }
        return Optional.of(new M0CapabilityProbe(caps));
    }

    // ---------- Result records ----------

    /**
     * Outcome of {@link #testScheduler()}. {@code errorCount} is captured BEFORE
     * {@code cancelAll()} so a main-agent observer can compare with the post-cleanup
     * snapshot in {@link #testCancelAll()}.
     */
    public record SchedulerReport(boolean ready,
                                  String platform,
                                  boolean regionScheduling,
                                  String taskCounts,
                                  int errorCount,
                                  String message) {
    }

    /** Outcome of {@link #testGui(Player, String)}. */
    public record GuiReport(boolean ready,
                            String openState,
                            String errorCode,
                            Long sessionGeneration,
                            String message) {
    }

    /** Outcome of {@link #testForm(Player, String, Consumer)}. */
    public record FormReport(boolean ready,
                             String sendResult,
                             boolean bedrockPlayer,
                             String moduleStatus,
                             String message) {
    }

    /** Outcome of {@link #testCancelAll()}. Confirms {@code cancelAll()} ran without error. */
    public record CancelAllReport(boolean ready,
                                  String taskCounts,
                                  int errorCount,
                                  String message) {
    }

    // ---------- Capabilities accessors (for tests) ----------

    /** @return the underlying capabilities bundle; non-null when the probe was built. */
    public Capabilities capabilities() {
        return capabilities;
    }

    // ---------- Four smoke paths ----------

    /**
     * Scheduler smoke: confirm the scheduler is non-null, dispatch one region-scoped task via
     * {@link SafeScheduler#runForPlayer(Player, Runnable)} and one via
     * {@link SafeScheduler#runAtLocation(Location, Runnable)} using the player's location, then
     * return a structured report. The two dispatched {@link ScheduledTask} handles are captured
     * in the per-instance {@link #scheduledHandles} list so {@link #testCancelAll()} can
     * compute the residual from real scheduler state. Does NOT call {@code cancelAll()} here —
     * that path lives in {@link #testCancelAll()}.
     */
    public SchedulerReport testScheduler(Player player) {
        SafeScheduler scheduler = capabilities.scheduler();
        if (scheduler == null) {
            return new SchedulerReport(false, "n/a", false, "0", 0, "scheduler 為 null");
        }
        if (player == null) {
            return new SchedulerReport(false, capabilities.platform().name(), false, "0", 0,
                "缺少 Player 參數（region-scoped dispatch 必須綁定到玩家）");
        }
        Location location = player.getLocation();
        if (location == null) {
            // Even if runForPlayer succeeds, runAtLocation would fail. Refuse early so the
            // smoke window stays consistent.
            return new SchedulerReport(false, capabilities.platform().name(), false, "0", 0,
                "玩家尚未綁定 Location，runAtLocation 無法執行");
        }
        AtomicLong dispatched = new AtomicLong();
        ScheduledTask forPlayer = null;
        ScheduledTask atLocation = null;
        try {
            forPlayer = scheduler.runForPlayer(player, dispatched::incrementAndGet);
            atLocation = scheduler.runAtLocation(location, dispatched::incrementAndGet);
        } catch (RuntimeException e) {
            return new SchedulerReport(false, capabilities.platform().name(), false,
                "0", 0, "scheduling failed: " + e.getMessage());
        }
        // Capture the handles on the instance so testCancelAll() can compute the residual from
        // their observable isCancelled() state without reaching into static tracking.
        captureHandle(forPlayer);
        captureHandle(atLocation);
        int errors;
        try {
            errors = scheduler.getRecorderErrors(0).size();
        } catch (RuntimeException e) {
            errors = 0;
        }
        return new SchedulerReport(true,
            capabilities.platform().name(),
            capabilities.capability().regionScheduling(),
            "player+location",
            errors,
            "scheduler 已建立；runForPlayer / runAtLocation 已排入。Runtime 區域驗證需至 Folia 真服執行。");
    }

    /**
     * GUI smoke: open an empty chest (size 9 = one row) for {@code playerUuid}, capture the
     * {@link GuiResult} and report it. The session, if accepted, remains active and is
     * NOT closed by the probe; lifecycle is left to the player / real UI flow.
     */
    public GuiReport testGui(UUID playerUuid, String title) {
        if (playerUuid == null) {
            return new GuiReport(false, "n/a", null, null, "缺少 Player 參數（UUID）");
        }
        GuiService gui = capabilities.guiService();
        if (gui == null) {
            return new GuiReport(false, "n/a", null, null, "GuiService 為 null");
        }
        GuiArgument arg;
        try {
            // Use the (UUID, ...) overload so the smoke stays decoupled from a live Player
            // object (the production path still passes a Player when invoked from /chunkland).
            arg = GuiArgument.of(playerUuid, title, 9, java.util.Set.of());
        } catch (RuntimeException e) {
            return new GuiReport(false, "rejected", "INVALID_INPUT", null,
                "建立 GuiArgument 失敗: " + e.getMessage());
        }
        GuiResult result;
        try {
            result = gui.openInventory(arg);
        } catch (RuntimeException e) {
            return new GuiReport(false, "failed", "OPERATION_FAILED", null,
                "openInventory 拋出例外: " + e.getMessage());
        }
        Long generation = null;
        if (result != null && result.session() != null) {
            generation = result.session().generation();
        }
        return new GuiReport(true,
            result == null ? "n/a" : result.state().name(),
            result == null ? null : result.errorCode(),
            generation,
            "GuiService 已呼叫；session 已開啟並保留，玩家可稍後手動關閉。實際 UI 顯示需在 Folia 真服人工觀察。");
    }

    /**
     * Form smoke: send a Modal form to {@code playerUuid}, capture the {@link FormSendResult},
     * and accept a response callback when the upstream pipeline delivers a
     * {@link FormResponse}. The callback is best-effort; not every AceLib backend will
     * invoke it during the smoke window. The return never throws.
     */
    public FormReport testForm(UUID playerUuid, String title, Consumer<FormResponse> responseSink) {
        if (playerUuid == null) {
            return new FormReport(false, "n/a", false, "n/a", "缺少 Player 參數（UUID）");
        }
        BedrockService bedrock = capabilities.bedrockService();
        FormService forms = bedrock == null ? null : bedrock.forms();
        if (forms == null) {
            return new FormReport(false, "n/a", false, "n/a", "FormService 為 null");
        }
        boolean bedrockPlayer;
        try {
            bedrockPlayer = bedrock.isBedrockPlayer(playerUuid);
        } catch (RuntimeException e) {
            bedrockPlayer = false;
        }
        FormSpec spec;
        try {
            spec = FormSpec.modal(title)
                .content("M0-07 form capability smoke (Bedrock=" + bedrockPlayer + ")")
                .button1("yes")
                .button2("no")
                .build();
        } catch (RuntimeException e) {
            return new FormReport(false, "rejected", bedrockPlayer,
                safeModuleStatus(forms), "建立 FormSpec 失敗: " + e.getMessage());
        }
        FormSendResult result;
        try {
            result = responseSink == null
                ? forms.sendForm(playerUuid, spec)
                : forms.sendForm(playerUuid, spec, responseSink);
        } catch (RuntimeException e) {
            return new FormReport(false, "rejected", bedrockPlayer,
                safeModuleStatus(forms), "sendForm 拋出例外: " + e.getMessage());
        }
        return new FormReport(true,
            result == null ? "n/a" : result.name(),
            bedrockPlayer,
            safeModuleStatus(forms),
            "BedrockService.forms() 已取得；Modal Form 已送出。回應回收需在 Folia + Geyser/Floodgate 真服人工點擊。");
    }

    /**
     * Disable-time cleanup verification: snapshot the {@link ScheduledTask} handles captured
     * during the probe window (see {@link #testScheduler(Player)} and the best-effort
     * {@code runLater} below), invoke {@link SafeScheduler#cancelAll()}, then count how many of
     * the captured handles are <em>not</em> cancelled. The residual is therefore derived from
     * observable scheduler state, not from a static list or a hardcoded value. Safe to call
     * when the scheduler is null.
     */
    public CancelAllReport testCancelAll() {
        SafeScheduler scheduler = capabilities.scheduler();
        if (scheduler == null) {
            return new CancelAllReport(false, "0", 0, "scheduler 為 null，無 cancelAll() 路徑");
        }
        // Snapshot the handles captured during the smoke window before issuing cancelAll() so
        // we can compare their isCancelled() state afterwards.
        ScheduledTask[] captured = drainCapturedHandles();
        try {
            scheduler.cancelAll();
        } catch (RuntimeException e) {
            return new CancelAllReport(false, "?", 0,
                "cancelAll() 拋出例外: " + e.getMessage());
        }
        long residual = 0L;
        for (ScheduledTask t : captured) {
            if (t != null && !t.isCancelled()) {
                residual++;
            }
        }
        int errors;
        try {
            errors = scheduler.getRecorderErrors(0).size();
        } catch (RuntimeException e) {
            errors = 0;
        }
        return new CancelAllReport(true, Long.toString(residual), errors,
            "SafeScheduler.cancelAll() 已執行；residual 由 captured handles 計算。disable 時 Capabilities.release() 將再次呼叫 cancelAll()");
    }

    // ---------- Command dispatcher ----------

    /** Smoke modes supported by {@code /chunkland m0test}. */
    public enum Mode {
        SCHEDULER,
        GUI,
        FORM,
        CANCELALL;

        /**
         * Strict parse: only the four known keywords map to modes; anything else is left
         * empty so {@code /chunkland m0test} (no arg) defaults safely.
         */
        public static Optional<Mode> fromString(String raw) {
            if (raw == null) {
                return Optional.empty();
            }
            return switch (raw.toLowerCase(Locale.ROOT)) {
                case "scheduler" -> Optional.of(SCHEDULER);
                case "gui" -> Optional.of(GUI);
                case "form" -> Optional.of(FORM);
                case "cancelall" -> Optional.of(CANCELALL);
                default -> Optional.empty();
            };
        }
    }

    /**
     * Temporary {@code /chunkland m0test [mode]} dispatcher. Always returns {@code true}
     * once the entry-point is recognised (so Bukkit stops scanning aliases); rejects
     * unrecognised verbs with a usage hint instead of running an unknown test.
     */
    public static boolean handleCommand(CommandSender sender,
                                        String[] args,
                                        Optional<M0CapabilityProbe> probeOpt) {
        Mode requested = parseModeArg(args);
        if (probeOpt.isEmpty()) {
            sender.sendMessage("ChunkLand 能力冒煙尚未就緒（AceLib 未 ready 或服務缺失）。");
            return true;
        }
        if (requested == null) {
            sender.sendMessage(
                "M0-07 m0test 用法：" + COMMAND_DESCRIPTION);
            return true;
        }
        M0CapabilityProbe probe = probeOpt.get();
        switch (requested) {
            case SCHEDULER -> {
                if (!(sender instanceof Player p)) {
                    sender.sendMessage("m0test scheduler 須由玩家執行（region-scoped dispatch 需綁定到玩家）。");
                    return true;
                }
                sender.sendMessage(formatScheduler(probe.testScheduler(p)));
            }
            case GUI -> {
                if (!(sender instanceof Player p)) {
                    sender.sendMessage("m0test gui 須由玩家執行（需觀察開啟 GUI）。");
                    return true;
                }
                sender.sendMessage(formatGui(probe.testGui(p.getUniqueId(), "M0 GUI smoke")));
            }
            case FORM -> {
                if (!(sender instanceof Player p)) {
                    sender.sendMessage("m0test form 須由玩家執行。");
                    return true;
                }
                // Wire the Consumer<FormResponse> overload so a delivered AceLib response reaches
                // the same CommandSender as a concise stable notice. The callback is best-effort;
                // formatting / sendMessage failures must not crash the command dispatch.
                Consumer<FormResponse> sink = response -> {
                    try {
                        sender.sendMessage(formatFormResponse(response));
                    } catch (RuntimeException ignored) {
                        // callback / formatting / sendMessage failures are absorbed; the initial
                        // form-prefixed send-result line is the authoritative dispatch output.
                    }
                };
                sender.sendMessage(formatForm(probe.testForm(p.getUniqueId(), "M0 Form smoke", sink)));
            }
            case CANCELALL -> {
                sender.sendMessage(formatCancelAll(probe.testCancelAll()));
            }
        }
        return true;
    }

    private static Mode parseModeArg(String[] args) {
        if (args == null) {
            return null;
        }
        for (int i = 1; i < args.length; i++) {
            Optional<Mode> parsed = Mode.fromString(args[i]);
            if (parsed.isPresent()) {
                return parsed.get();
            }
        }
        return null;
    }

    // ---------- Pretty printers ----------

    private static String formatScheduler(SchedulerReport r) {
        if (!r.ready()) {
            return "[scheduler] NOT_READY：" + r.message();
        }
        return "[scheduler] OK platform=" + r.platform()
            + " regionScheduling=" + r.regionScheduling()
            + " tasks=" + r.taskCounts()
            + " errors=" + r.errorCount()
            + " — " + r.message();
    }

    private static String formatGui(GuiReport r) {
        if (!r.ready()) {
            return "[gui] NOT_READY：" + r.message();
        }
        return "[gui] state=" + r.openState()
            + " errorCode=" + r.errorCode()
            + " generation=" + r.sessionGeneration()
            + " — " + r.message();
    }

    private static String formatForm(FormReport r) {
        if (!r.ready()) {
            return "[form] NOT_READY：" + r.message();
        }
        return "[form] sendResult=" + r.sendResult()
            + " bedrockPlayer=" + r.bedrockPlayer()
            + " moduleStatus=" + r.moduleStatus()
            + " — " + r.message();
    }

    /** Render a delivered {@link FormResponse} as a concise, stable notice for the originating
     *  CommandSender. The format intentionally omits transient {@code FormValue} payload so the
     *  prefix {@code [form response]} and {@code status=/button=} keys stay stable across
     *  backends. Null-safe so a misbehaving pipeline cannot crash the dispatch. */
    private static String formatFormResponse(FormResponse r) {
        if (r == null) {
            return "[form response] status=n/a button=n/a";
        }
        String button = r.clickedButton().isPresent()
            ? r.clickedButton().get().toString()
            : "n/a";
        return "[form response] status=" + r.status().name()
            + " button=" + button;
    }

    private static String formatCancelAll(CancelAllReport r) {
        if (!r.ready()) {
            return "[cancelAll] NOT_READY：" + r.message();
        }
        return "[cancelAll] OK residualTasks=" + r.taskCounts()
            + " errors=" + r.errorCount()
            + " — " + r.message();
    }

    private static String safeModuleStatus(FormService forms) {
        try {
            return forms.getModuleStatus();
        } catch (RuntimeException e) {
            return "n/a";
        }
    }

    /**
     * Per-instance list of {@link ScheduledTask} handles captured during the smoke window.
     * The probe stores the live handles it dispatched (via {@link #captureHandle(ScheduledTask)})
     * and drains them for {@link #testCancelAll()} so the residual count is derived from each
     * handle's observable {@link ScheduledTask#isCancelled()} state, not from a shared static
     * list that could leak across probe instances or commands.
     */
    private final CopyOnWriteArrayList<ScheduledTask> scheduledHandles = new CopyOnWriteArrayList<>();

    /**
     * Append a handle to the per-instance tracked list. {@code null} handles (some backends may
     * return null for cancelled-on-dispatch tasks) are skipped.
     */
    void captureHandle(ScheduledTask t) {
        if (t != null) {
            scheduledHandles.add(t);
        }
    }

    /**
     * Drain (snapshot + clear) the per-instance handle list. The caller takes ownership of the
     * returned array; subsequent dispatches start a new tracking window for this probe.
     */
    ScheduledTask[] drainCapturedHandles() {
        ScheduledTask[] snapshot = scheduledHandles.toArray(new ScheduledTask[0]);
        scheduledHandles.clear();
        return snapshot;
    }

    /** Visible for testing only. */
    int capturedHandleCountForTesting() {
        return scheduledHandles.size();
    }
}
