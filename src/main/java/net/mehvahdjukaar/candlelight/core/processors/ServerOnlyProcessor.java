package net.mehvahdjukaar.candlelight.core.processors;

import net.mehvahdjukaar.candlelight.core.TransformContext;

/**
 * Rewrites {@code @ServerOnly} into the loader-specific server-side-only annotation
 * ({@code @Environment(EnvType.SERVER)} on Fabric, {@code @OnlyIn(Dist.DEDICATED_SERVER)} on
 * Forge).
 */
public class ServerOnlyProcessor extends DistOnlyProcessor {

    private static final String SERVER_ONLY = "Lnet/mehvahdjukaar/candlelight/api/ServerOnly;";

    public ServerOnlyProcessor() {
        super(SERVER_ONLY, "SERVER", "DEDICATED_SERVER");
    }

    @Override
    protected boolean isEnabled(TransformContext ctx) {
        return ctx.isServerOnly();
    }
}