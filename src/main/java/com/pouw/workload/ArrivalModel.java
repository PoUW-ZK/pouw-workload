package com.pouw.workload;

import com.fasterxml.jackson.annotation.JsonCreator;

import java.util.Locale;
import java.util.SplittableRandom;

public interface ArrivalModel {
    long nextDelayNanos(long elapsedNanos);

    static ArrivalModel create(Kind kind, double ratePerSecond, long burstPeriodSeconds) {
        if (kind == null) {
            throw new IllegalArgumentException("arrivalModel must not be null");
        }
        if (!Double.isFinite(ratePerSecond) || ratePerSecond <= 0 || ratePerSecond > 1_000_000_000) {
            throw new IllegalArgumentException("ratePerSecond must be in (0, 1000000000]");
        }
        SplittableRandom random = new SplittableRandom();
        return switch (kind) {
            case CONSTANT -> new Constant(ratePerSecond);
            case POISSON -> new Poisson(ratePerSecond, random);
            case BURST -> new Burst(ratePerSecond, burstPeriodSeconds, random);
        };
    }

    enum Kind {
        CONSTANT,
        POISSON,
        BURST;

        @JsonCreator
        public static Kind fromString(String value) {
            if (value == null) {
                throw new IllegalArgumentException("arrivalModel must not be null");
            }
            return valueOf(value.toUpperCase(Locale.ROOT));
        }
    }

    final class Constant implements ArrivalModel {
        private final long intervalNanos;

        private Constant(double ratePerSecond) {
            intervalNanos = Math.max(1, (long) Math.ceil(1_000_000_000d / ratePerSecond));
        }

        @Override
        public long nextDelayNanos(long elapsedNanos) {
            return intervalNanos;
        }
    }

    final class Poisson implements ArrivalModel {
        private final double ratePerSecond;
        private final SplittableRandom random;

        private Poisson(double ratePerSecond, SplittableRandom random) {
            this.ratePerSecond = ratePerSecond;
            this.random = random;
        }

        @Override
        public long nextDelayNanos(long elapsedNanos) {
            double hazard = -Math.log1p(-random.nextDouble());
            return Math.max(1, (long) Math.ceil(hazard * 1_000_000_000d / ratePerSecond));
        }
    }

    final class Burst implements ArrivalModel {
        private static final double HIGH_RATE_FACTOR = 16d / 9;
        private static final double LOW_RATE_FACTOR = 2d / 9;

        private final double baseRate;
        private final long cycleNanos;
        private final long halfCycleNanos;
        private final SplittableRandom random;

        private Burst(double ratePerSecond, long burstPeriodSeconds, SplittableRandom random) {
            if (burstPeriodSeconds <= 1 || burstPeriodSeconds > Long.MAX_VALUE / 1_000_000_000L) {
                throw new IllegalArgumentException("burstPeriodSeconds must be greater than one");
            }
            baseRate = ratePerSecond;
            cycleNanos = burstPeriodSeconds * 1_000_000_000L;
            halfCycleNanos = cycleNanos / 2;
            this.random = random;
        }

        @Override
        public long nextDelayNanos(long elapsedNanos) {
            double remainingHazard = -Math.log1p(-random.nextDouble());
            long cursor = elapsedNanos;
            while (true) {
                long phaseNanos = Math.floorMod(cursor, cycleNanos);
                boolean highRatePhase = phaseNanos < halfCycleNanos;
                long untilBoundary = highRatePhase
                        ? halfCycleNanos - phaseNanos
                        : cycleNanos - phaseNanos;
                double rate = baseRate * (highRatePhase ? HIGH_RATE_FACTOR : LOW_RATE_FACTOR);
                double availableHazard = rate * untilBoundary / 1_000_000_000d;
                if (remainingHazard <= availableHazard) {
                    long delay = (long) Math.ceil(remainingHazard * 1_000_000_000d / rate);
                    return Math.max(1, delay);
                }
                remainingHazard -= availableHazard;
                cursor += untilBoundary;
            }
        }
    }
}
