package com.jodk.acx.manager.model;

/** A dynamic mount attached to a running sandbox, shown in the management UI. */
public class ActiveMount {

    public String mountId;
    public String driver;
    /** PV the mount came from; {@code null} when the source is not a pre-created PV. */
    public String pvName;
    /**
     * Source label for non-PV dynamic mounts: the bound host directory (host) or {@code tmpfs}.
     * Left {@code null} for PV-backed mounts whose source is {@link #pvName}.
     */
    public String source;
    public String subPath;
    public boolean readOnly;
    /** Path visible inside the app container, under the shared mount root. */
    public String containerPath;
    /** Pod UID the mount belongs to; a changed UID means the mount vanished with the old pod. */
    public String podUid;
    public String mountedAt;

    public ActiveMount() {
    }
}
