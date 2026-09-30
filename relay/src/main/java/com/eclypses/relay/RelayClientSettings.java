package com.eclypses.relay;

import java.util.Objects;

@SuppressWarnings("unused")
public final class RelayClientSettings {

    public static final int DEFAULT_MIN_PAIRS = 5;
    public static final int DEFAULT_BASE_PAIRS = 8;
    public static final int DEFAULT_MAX_PAIRS = 15;
    public static final int DEFAULT_KEEP_ALIVE_INTERVAL_SECONDS = 300;
    public static final double DEFAULT_ACQUISITION_WAIT_TIME = 1.0d;

    private final int minPairs;
    private final int basePairs;
    private final int maxPairs;
    private final int keepAliveIntervalSeconds;
    private final double acquisitionWaitTime;

    public RelayClientSettings() {
        this(
                DEFAULT_MIN_PAIRS,
                DEFAULT_BASE_PAIRS,
                DEFAULT_MAX_PAIRS,
                DEFAULT_KEEP_ALIVE_INTERVAL_SECONDS,
                DEFAULT_ACQUISITION_WAIT_TIME);
    }

    public RelayClientSettings(
            int minPairs,
            int basePairs,
            int maxPairs,
            int keepAliveIntervalSeconds,
            double acquisitionWaitTime) {
        validate(minPairs, basePairs, maxPairs, keepAliveIntervalSeconds, acquisitionWaitTime);
        this.minPairs = minPairs;
        this.basePairs = basePairs;
        this.maxPairs = maxPairs;
        this.keepAliveIntervalSeconds = keepAliveIntervalSeconds;
        this.acquisitionWaitTime = acquisitionWaitTime;
    }

    public static RelayClientSettings defaults() {
        return new RelayClientSettings();
    }

    public int getMinPairs() {
        return minPairs;
    }

    public int getBasePairs() {
        return basePairs;
    }

    public int getMaxPairs() {
        return maxPairs;
    }

    public int getKeepAliveIntervalSeconds() {
        return keepAliveIntervalSeconds;
    }

    public double getAcquisitionWaitTime() {
        return acquisitionWaitTime;
    }

    public Builder buildUpon() {
        return new Builder(this);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof RelayClientSettings)) {
            return false;
        }
        RelayClientSettings that = (RelayClientSettings) other;
        return minPairs == that.minPairs
                && basePairs == that.basePairs
                && maxPairs == that.maxPairs
                && keepAliveIntervalSeconds == that.keepAliveIntervalSeconds
                && Double.compare(that.acquisitionWaitTime, acquisitionWaitTime) == 0;
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                minPairs,
                basePairs,
                maxPairs,
                keepAliveIntervalSeconds,
                acquisitionWaitTime);
    }

    @Override
    public String toString() {
        return "RelayClientSettings(" +
                "minPairs=" + minPairs +
                ", basePairs=" + basePairs +
                ", maxPairs=" + maxPairs +
                ", keepAliveIntervalSeconds=" + keepAliveIntervalSeconds +
                ", acquisitionWaitTime=" + acquisitionWaitTime +
                ')';
    }

    private static void validate(
            int minPairs,
            int basePairs,
            int maxPairs,
            int keepAliveIntervalSeconds,
            double acquisitionWaitTime) {
        if (minPairs <= 0) {
            throw new IllegalArgumentException("minPairs must be greater than 0");
        }
        if (basePairs < minPairs) {
            throw new IllegalArgumentException("basePairs must be greater than or equal to minPairs");
        }
        if (maxPairs < basePairs) {
            throw new IllegalArgumentException("maxPairs must be greater than or equal to basePairs");
        }
        if (keepAliveIntervalSeconds < 60 || keepAliveIntervalSeconds > 600) {
            throw new IllegalArgumentException("keepAliveIntervalSeconds must be between 60 and 600");
        }
        if (acquisitionWaitTime < 0) {
            throw new IllegalArgumentException("acquisitionWaitTime must be greater than or equal to 0");
        }
    }

    public static final class Builder {
        private int minPairs;
        private int basePairs;
        private int maxPairs;
        private int keepAliveIntervalSeconds;
        private double acquisitionWaitTime;

        public Builder() {
            this(RelayClientSettings.defaults());
        }

        private Builder(RelayClientSettings settings) {
            minPairs = settings.minPairs;
            basePairs = settings.basePairs;
            maxPairs = settings.maxPairs;
            keepAliveIntervalSeconds = settings.keepAliveIntervalSeconds;
            acquisitionWaitTime = settings.acquisitionWaitTime;
        }

        public Builder setMinPairs(int value) {
            minPairs = value;
            return this;
        }

        public Builder setBasePairs(int value) {
            basePairs = value;
            return this;
        }

        public Builder setMaxPairs(int value) {
            maxPairs = value;
            return this;
        }

        public Builder setKeepAliveIntervalSeconds(int value) {
            keepAliveIntervalSeconds = value;
            return this;
        }

        public Builder setAcquisitionWaitTime(double value) {
            acquisitionWaitTime = value;
            return this;
        }

        public RelayClientSettings build() {
            return new RelayClientSettings(
                    minPairs,
                    basePairs,
                    maxPairs,
                    keepAliveIntervalSeconds,
                    acquisitionWaitTime);
        }
    }
}