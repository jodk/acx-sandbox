package com.jodk.acx.common.route;

/** Resource-version ordering helpers (numeric comparison with unsigned semantics). */
public final class ResourceVersions {

    private ResourceVersions() {
    }

    /**
     * Returns true when {@code newVersion} is considered newer-or-equal than {@code oldVersion}.
     * Ported from pkg/utils/expectations/resource_version_expectation.go.
     */
    public static boolean isResourceVersionNewer(String oldVersion, String newVersion) {
        if (oldVersion == null || oldVersion.isEmpty()) {
            return true;
        }
        long oldCount;
        try {
            oldCount = Long.parseUnsignedLong(oldVersion);
        } catch (NumberFormatException e) {
            return true;
        }
        long newCount;
        try {
            newCount = Long.parseUnsignedLong(newVersion);
        } catch (NumberFormatException e) {
            return false;
        }
        return Long.compareUnsigned(newCount, oldCount) >= 0;
    }
}
