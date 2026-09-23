package com.jodk.acx.common.sandbox;

/** Result of sandbox state derivation (state + a hard-coded reason). */
public record SandboxStateInfo(String state, String reason) {
}
