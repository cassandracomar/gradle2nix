package org.nixos.gradle2nix;

import org.gradle.api.artifacts.Dependency;
import org.gradle.internal.impldep.javax.annotation.Nullable;
import org.gradle.api.internal.artifacts.dependencies.DefaultExternalModuleDependency;

/**
 * Workarounds for APIs improperly marked with @NonNullApi.
 */
interface ApiHack {
    static Dependency defaultExternalModuleDependency(String group, String name, @Nullable String version) {
        return new DefaultExternalModuleDependency(group, name, version);
    }
}
