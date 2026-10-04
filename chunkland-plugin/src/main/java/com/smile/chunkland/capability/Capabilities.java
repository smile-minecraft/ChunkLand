package com.smile.chunkland.capability;

import com.smile.acelib.bedrock.BedrockService;
import com.smile.acelib.form.FormService;
import com.smile.acelib.gui.GuiService;
import com.smile.acelib.platform.Platform;
import com.smile.acelib.platform.PlatformCapability;
import com.smile.acelib.scheduler.SafeScheduler;
import java.util.Objects;

/**
 * Immutable bundle of the AceLib v1.3.0 capability services ChunkLand holds for the
 * capability smoke probe. Construction is server-assisted via {@link Builder} so unit tests can
 * assemble the bundle without standing up an AceLib production facade.
 *
 * <p>Lifecycle contract:</p>
 * <ul>
 *   <li>The {@link SafeScheduler} is created by the consumer via
 *       {@code AceLibScheduler.create(plugin, platform, capability)}; ChunkLand calls
 *       {@link SafeScheduler#cancelAll()} on disable to drop any residual tasks scheduled
 *       during the smoke window.</li>
 *   <li>The {@link GuiService} and {@link BedrockService} are <strong>not</strong> shut down
 *       here: those are shared, provider-wide services used by other plugins on the same
 *       server. Local cleanup is confined to dropping our references so the bridge's
 *       {@code release()} finishes without leaking the smoke window's session map.</li>
 * </ul>
 *
 * <p>All fields are required; partial bundles must use {@link #isComplete()} to validate
 * before relying on a specific capability.</p>
 */
public final class Capabilities {

    private final SafeScheduler scheduler;
    private final GuiService guiService;
    private final BedrockService bedrockService;
    private final Platform platform;
    private final PlatformCapability capability;

    /**
     * Permissive constructor: accepts a possibly incomplete builder so {@link #isComplete()}
     * is the single source of truth. Production wiring sets all fields; tests may construct
     * partial bundles to assert the {@link M0CapabilityProbe#fromCapabilities} reject path.
     */
    private Capabilities(Builder b) {
        this.scheduler = b.scheduler;
        this.guiService = b.guiService;
        this.bedrockService = b.bedrockService;
        this.platform = b.platform;
        this.capability = b.capability;
    }

    public SafeScheduler scheduler() {
        return scheduler;
    }

    public GuiService guiService() {
        return guiService;
    }

    public BedrockService bedrockService() {
        return bedrockService;
    }

    public Platform platform() {
        return platform;
    }

    public PlatformCapability capability() {
        return capability;
    }

    /**
     * Compute the {@link FormService} on demand via {@code bedrockService.forms()}. The
     * upstream {@link GuiService} is provisioned whole, but {@code Forms} is a derived view
     * that the bridge does not need to cache.
     */
    public FormService formService() {
        BedrockService b = bedrockService;
        return b == null ? null : b.forms();
    }

    /**
     * Verify the bundle carries every dependency required to exercise the four smoke paths.
     * Mainly a guard for the constructor of {@code M0CapabilityProbe}; production wiring
     * already enforces non-null at the seam, but the same check is useful for unit tests
     * that synthesise a partial bundle.
     */
    public boolean isComplete() {
        return scheduler != null
            && guiService != null
            && bedrockService != null
            && platform != null
            && capability != null;
    }

    /**
     * Run the public cancellation contract for the {@link SafeScheduler} only. GUI and
     * Bedrock are intentionally left untouched (see class Javadoc). Safe to call multiple
     * times; never throws.
     */
    public void release() {
        SafeScheduler s = scheduler;
        if (s != null) {
            try {
                s.cancelAll();
            } catch (RuntimeException ignored) {
                // fail-closed: a scheduler whose cancelAll() throws must not break onDisable.
            }
        }
        // Do NOT call guiService.shutdown() nor bedrockService.shutdown() — those are
        // provider-wide and would tear down other plugins' sessions.
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Mutable builder for {@link Capabilities}; all fields default to {@code null}. */
    public static final class Builder {
        private SafeScheduler scheduler;
        private GuiService guiService;
        private BedrockService bedrockService;
        private Platform platform;
        private PlatformCapability capability;

        private Builder() {
        }

        public Builder scheduler(SafeScheduler v) {
            this.scheduler = v;
            return this;
        }

        public Builder guiService(GuiService v) {
            this.guiService = v;
            return this;
        }

        public Builder bedrockService(BedrockService v) {
            this.bedrockService = v;
            return this;
        }

        public Builder platform(Platform v) {
            this.platform = v;
            return this;
        }

        public Builder capability(PlatformCapability v) {
            this.capability = v;
            return this;
        }

        public Capabilities build() {
            return new Capabilities(this);
        }
    }
}
