package net.mehvahdjukaar.candlelight.core.processors;

import net.mehvahdjukaar.candlelight.core.CandleLightExtension;

/**
 * Rewrites {@code @ServerOnly} into the loader-specific server-side-only annotation
 * ({@code @Environment(EnvType.SERVER)} on Fabric, {@code @OnlyIn(Dist.DEDICATED_SERVER)} on
 * Forge).
 */
public class ServerOnlyProcessor extends DistOnlyProcessor {

    private static final String SERVER_ONLY = "Lnet/mehvahdjukaar/candlelight/api/ServerOnly;";

    public ServerOnlyProcessor() {
        super(SERVER_ONLY, "DEDICATED_SERVER");
    }

    @Override
    protected boolean isEnabled(CandleLightExtension ext) {
        return ext.getServerOnly().get();
    }
}