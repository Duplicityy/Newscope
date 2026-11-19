package io.ruin.model.activities.automation;

import java.util.Random;

/** Offline checks for the exact authorization, planning and bounded retry policy. */
public final class OwnerAutomationPolicyChecks {
    private static int assertions;

    private OwnerAutomationPolicyChecks() {
    }

    public static void main(String[] args) {
        permissionsAndGoals();
        boundedRecovery();
        ranking();
        conservation();

        double checksum = 0;
        final int decisions = 200_000;
        final long began = System.nanoTime();
        for (int decision = 0; decision < decisions; decision++) {
            // Vary the inputs and consume the result to prevent constant folding.
            int distance = decision % 13;
            int chance = 1 + decision % 99;
            checksum += AutomationPolicy.rate(17.5 + decision % 6, chance, distance, .2, 12);
            checksum += AutomationPolicy.depositAmount(2 + decision % 4, 5, 1, 6);
        }
        long elapsed = System.nanoTime() - began;
        check(checksum > decisions, "benchmark checksum");
        System.out.printf("PASS %,d assertions. %,d policy pairs in %.3f ms (%.1f ns/pair).%n",
                assertions, decisions, elapsed / 1_000_000.0, (double) elapsed / decisions);
    }

    private static void permissionsAndGoals() {
        for (int bits = 0; bits < 8; bits++) {
            boolean featureEnabled = (bits & 1) != 0;
            boolean publicWorld = (bits & 2) != 0;
            boolean ownerRank = (bits & 4) != 0;
            // All non-owner ranks, including administrator, use ownerRank=false.
            check(OwnerAutomation.owner(featureEnabled, publicWorld, ownerRank) == (bits == 5),
                    "authorization truth table");
        }
        for (int level = -1000; level <= 1000; level++) {
            check(AutomationPolicy.validGoal(level) == (level >= 2 && level <= 99), "goal limits");
        }
        check(!AutomationPolicy.validGoal(Integer.MIN_VALUE), "minimum integer rejected");
        check(!AutomationPolicy.validGoal(Integer.MAX_VALUE), "maximum integer rejected");
    }

    private static void boundedRecovery() {
        long timeout = AutomationPolicy.NO_PROGRESS_TICKS;
        check(timeout > AutomationPolicy.ROUTE_TICKS, "watchdog permits native routing time");
        for (long started = 0; started < 5000; started += 3) {
            AutomationPolicy.Budget budget = new AutomationPolicy.Budget(started);
            check(!budget.expired(started + timeout - 1), "watchdog before timeout");
            check(budget.expired(started + timeout), "watchdog at timeout");
            for (int attempts = 1; attempts <= AutomationPolicy.MAX_ROUTE_FAILURES + 1; attempts++) {
                check(budget.retry() == (attempts < AutomationPolicy.MAX_ROUTE_FAILURES), "bounded retries");
                check(budget.expired(started + timeout), "retry cannot refresh watchdog");
            }
            long resumedProgress = started + timeout + 20;
            budget.progress(resumedProgress);
            check(!budget.expired(resumedProgress + timeout - 1), "genuine progress refreshes deadline");
            for (int attempts = 1; attempts <= AutomationPolicy.MAX_ROUTE_FAILURES; attempts++) {
                check(budget.retry() == (attempts < AutomationPolicy.MAX_ROUTE_FAILURES), "progress resets retry budget");
            }
            check(budget.expired(resumedProgress + timeout), "renewed deadline still bounded");
        }
    }

    private static void ranking() {
        check(AutomationPolicy.rate(25, 0, 1, .2, 10) == 0, "zero success rate");
        for (int chance = 1; chance < 100; chance++) {
            for (int distance = 0; distance < 12; distance++) {
                int respawn = 3 + chance % 35;
                double value = AutomationPolicy.rate(25, chance, distance, .2, respawn);
                check(Double.isFinite(value) && value > 0, "finite positive score");
                check(value > AutomationPolicy.rate(25, chance, distance + 1, .2, respawn), "cost of extra movement");
                check(value > AutomationPolicy.rate(25, chance, distance, .3, respawn), "cost of depletion");
                check(value > AutomationPolicy.rate(25, chance, distance, .2, respawn + 1), "cost of longer respawn");
                check(value < AutomationPolicy.rate(25, chance + 1, distance, .2, respawn), "pickaxe success improves score");
                check(value < AutomationPolicy.rate(30, chance, distance, .2, respawn), "more XP improves score");
            }
        }
        check(AutomationPolicy.rate(17.5, 85, 1, .125, 5)
                > AutomationPolicy.rate(100.0, 5, 8, .4, 30),
                "nearby reliable copper may beat distant runite");
    }

    private static void conservation() {
        Random random = new Random(0x184242727L);
        check(AutomationPolicy.depositAmount(10, 5, 10, 7) == 0, "inventory below protected amount");
        check(AutomationPolicy.depositAmount(Integer.MAX_VALUE, Integer.MAX_VALUE, 1, Integer.MAX_VALUE)
                == Integer.MAX_VALUE - 1, "large valid quantity");
        for (int scenario = 0; scenario < 12_000; scenario++) {
            int[] stacks = new int[1 + random.nextInt(28)];
            int total = 0;
            for (int slot = 0; slot < stacks.length; slot++) {
                stacks[slot] = 1 + random.nextInt(10);
                total += stacks[slot];
            }
            int protectedAmount = random.nextInt(total + 1);
            int acquired = random.nextInt(total + 1);
            int bankCapacity = random.nextInt(total + 1);
            int inventoryAfter = total;
            int unmatchedAcquired = acquired;
            int banked = 0;
            for (int stack : stacks) {
                int offered = AutomationPolicy.depositAmount(stack, unmatchedAcquired, protectedAmount, inventoryAfter);
                check(offered >= 0 && offered <= stack, "deposit stays within slot");
                check(offered <= unmatchedAcquired, "deposit stays within proven acquisition");
                int moved = Math.min(offered, bankCapacity - banked);
                banked += moved;
                inventoryAfter -= moved;
                unmatchedAcquired -= moved;
            }
            int feasible = Math.min(bankCapacity, Math.min(acquired, total - protectedAmount));
            check(banked == feasible, "deposit transfers exactly the feasible quantity");
            check(banked + inventoryAfter == total, "inventory plus bank is conserved");
            check(inventoryAfter >= protectedAmount, "pre-existing ore remains");
        }
    }

    private static void check(boolean value, String description) {
        assertions++;
        if (!value) throw new AssertionError(description);
    }
}
