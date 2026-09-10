package net.mehvahdjukaar.candlelight.core.processors;

import net.mehvahdjukaar.candlelight.core.TransformContext;

/**
 * Rewrites {@code @ClientOnly} into the loader-specific client-side-only annotation
 * ({@code @Environment(EnvType.CLIENT)} on Fabric, {@code @OnlyIn(Dist.CLIENT)} on
 * Forge).
 */
public class ClientOnlyProcessor extends DistOnlyProcessor {

    private static final String CLIENT_ONLY = "Lnet/mehvahdjukaar/candlelight/api/ClientOnly;";

    public ClientOnlyProcessor() {
        super(CLIENT_ONLY, "CLIENT");
    }

    @Override
    protected boolean isEnabled(TransformContext ctx) {
        return ctx.isClientOnly();
    }
}