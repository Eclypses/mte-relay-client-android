package com.mte.relay;

@SuppressWarnings("unused")
public final class RelayGlobalSettings {
    private final String relayVersion;
    private final String licenseCompanyName;
    private final String licenseKey;

    RelayGlobalSettings(String relayVersion, String licenseCompanyName, String licenseKey) {
        this.relayVersion = relayVersion;
        this.licenseCompanyName = licenseCompanyName;
        this.licenseKey = licenseKey;
    }

    public String getRelayVersion() {
        return relayVersion;
    }

    public String getLicenseCompanyName() {
        return licenseCompanyName;
    }

    public String getLicenseKey() {
        return licenseKey;
    }
}