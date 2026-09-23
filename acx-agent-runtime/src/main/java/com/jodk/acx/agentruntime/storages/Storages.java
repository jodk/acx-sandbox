package com.jodk.acx.agentruntime.storages;

import java.util.List;

/** Storage helpers. */
public final class Storages {

    private Storages() {
    }

    public static boolean isPureReadOnly(List<String> accessModes) {
        if (accessModes == null || accessModes.isEmpty()) {
            return false;
        }
        for (String mode : accessModes) {
            if (!mode.endsWith("ReadOnlyMany") && !mode.equals("ReadOnly")) {
                return false;
            }
        }
        return true;
    }
}
