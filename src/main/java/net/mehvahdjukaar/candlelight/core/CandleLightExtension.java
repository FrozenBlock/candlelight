package net.mehvahdjukaar.candlelight.core;

import org.gradle.api.file.RegularFileProperty;

import org.gradle.api.provider.Property;

public abstract class CandleLightExtension {
    public abstract Property<Boolean> getLogging();
    public abstract Property<Boolean> getClientOnly();
    public abstract Property<Boolean> getServerOnly();
}