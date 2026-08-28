package com.smile.chunkland.adapter;

import com.smile.acelib.AceLibApi;
import java.util.Objects;
import java.util.function.Supplier;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.ServicesManager;

/**
 * Fail-closed adapter that obtains the AceLib public API exclusively through the
 * Bukkit/Paper {@link ServicesManager}.
 *
 * <p>Design rules:</p>
 * <ul>
 *   <li>Only the supported public contract is used: {@link AceLibApi.AceLibProvider}
 *       obtained from {@link ServicesManager}, then {@link AceLibApi.AceLibProvider#api()},
 *       then {@link AceLibApi#isReady()}. No implementation class (e.g. AceLibPlugin)
 *       is referenced and no unchecked cast is performed.</li>
 *   <li>Acquisition is re-runnable: {@link #reacquire(ProviderResolver)} always performs a
 *       fresh lookup so a reload on AceLib's side is reflected instead of a stale facade.</li>
 *   <li>Missing provider, null API, or not-ready API are all treated as "not acquired"
 *       without throwing; the caller decides how to fail closed.</li>
 * </ul>
 */
public final class AceLibBridge {

    /**
     * Narrow seam that resolves the currently registered AceLib provider.
     *
     * <p>Injected so the bridge is fully testable without a live Bukkit server. The
     * production implementation is {@link #fromServicesManager(ServicesManager)}.</p>
     */
    @FunctionalInterface
    public interface ProviderResolver {
        /**
         * @return the registered {@link AceLibApi.AceLibProvider}, or {@code null} when
         *         AceLib is not (yet) registered as a service.
         */
        AceLibApi.AceLibProvider resolve();
    }

    /**
     * Production resolver backed by the real Bukkit {@link ServicesManager}.
     *
     * <p>Type-safe: {@code getRegistration(AceLibApi.AceLibProvider.class)} returns a
     * parameterized {@link RegisteredServiceProvider}, so no cast is required.</p>
     */
    public static ProviderResolver fromServicesManager(ServicesManager services) {
        Objects.requireNonNull(services, "services");
        return () -> {
            RegisteredServiceProvider<AceLibApi.AceLibProvider> registration =
                services.getRegistration(AceLibApi.AceLibProvider.class);
            return registration == null ? null : registration.getProvider();
        };
    }

    private volatile AceLibApi api;

    public AceLibBridge() {
        this.api = null;
    }

    /**
     * Acquire (or refresh) the AceLib API.
     *
     * @return {@code true} only when a non-null, ready API was obtained.
     */
    public boolean acquire(ProviderResolver resolver) {
        Objects.requireNonNull(resolver, "resolver");
        try {
            AceLibApi.AceLibProvider provider = resolver.resolve();
            if (provider == null) {
                this.api = null;
                return false;
            }
            AceLibApi candidate = provider.api();
            if (candidate == null || !candidate.isReady()) {
                this.api = null;
                return false;
            }
            this.api = candidate;
            return true;
        } catch (RuntimeException e) {
            // Any failure at the ServicesManager / provider / API boundary is treated
            // as "not acquired" so the lifecycle can fail closed instead of surfacing
            // an unhandled exception to Bukkit. State is cleared to avoid a stale facade.
            this.api = null;
            return false;
        }
    }

    /**
     * Re-acquire by performing a fresh {@link ServicesManager} lookup, discarding any
     * previously held facade first. Used after AceLib reloads so a new ready API is
     * adopted instead of a stale one.
     *
     * @return {@code true} only when a non-null, ready API was obtained.
     */
    public boolean reacquire(ProviderResolver resolver) {
        this.api = null;
        try {
            return acquire(resolver);
        } catch (RuntimeException e) {
            // Fail closed: never surface an exception or retain a stale facade.
            this.api = null;
            return false;
        }
    }

    /**
     * Trigger AceLib's reload and re-acquire the resulting facade.
     *
     * <p>AceLib's {@link AceLibApi#reload()} only fires its reload callback; the new
     * facade is observed through a subsequent {@link #reacquire(ProviderResolver)}. Safe
     * no-op when nothing is acquired.</p>
     */
    public void reload(ProviderResolver resolver) {
        try {
            // Validated inside the try so an invalid resolver fails closed (clears state)
            // instead of throwing past the bridge boundary.
            Objects.requireNonNull(resolver, "resolver");
            AceLibApi current = api;
            if (current != null) {
                current.reload();
            }
            reacquire(resolver);
        } catch (RuntimeException e) {
            // Never retain a stale facade after a failed reload or reacquire.
            this.api = null;
        }
    }

    public boolean isAcquired() {
        return api != null;
    }

    public boolean isReady() {
        AceLibApi current = api;
        if (current == null) {
            return false;
        }
        boolean ready;
        try {
            ready = current.isReady();
        } catch (RuntimeException e) {
            // A failing readiness check means the held facade can no longer be trusted;
            // fail closed and discard it rather than surfacing the exception or keeping
            // a stale reference.
            this.api = null;
            return false;
        }
        if (!ready) {
            // Not-ready facade is also discarded so a later acquire/reacquire adopts a
            // fresh one instead of a stale facade.
            this.api = null;
            return false;
        }
        return true;
    }

    /**
     * @return the currently held API, or {@code null} when not acquired.
     */
    public AceLibApi getApi() {
        return api;
    }

    /**
     * Release all held state. Idempotent and safe to call multiple times (e.g. during
     * plugin disable, which may be invoked more than once by Bukkit).
     */
    public void release() {
        this.api = null;
    }
}
