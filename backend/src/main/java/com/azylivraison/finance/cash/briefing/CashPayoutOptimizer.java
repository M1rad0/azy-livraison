package com.azylivraison.finance.cash.briefing;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.azylivraison.finance.service.FinanceConflictException;

/** Exact, bounded optimization. No approximate payouts are silently published if a bound is hit. */
public final class CashPayoutOptimizer {
    public static final String ALGORITHM = "Exact : nombre de vendeurs, montant, anciennete, nombre de transferts. "
            + "Bornes : 100 000 etats de selection, 200 000 etats d'allocation ; sinon preparation manuelle requise.";
    private static final int MAX_SELECTION_STATES = 100_000;
    private static final int MAX_ALLOCATION_NODES = 200_000;

    public record Seller(UUID id, String name, String payoutNumber, long debt, LocalDate oldestDate) {}
    public record Courier(UUID id, String name, long available) {}
    public record Allocation(Seller seller, Courier courier, long amount) {}

    public List<Allocation> optimize(List<Seller> input, List<Courier> couriers) {
        long budget = couriers.stream().mapToLong(Courier::available).reduce(0, Math::addExact);
        List<Seller> sellers = input.stream().filter(s -> s.debt() > 0 && s.debt() <= budget)
                .sorted(Comparator.comparing(Seller::oldestDate).thenComparing(s -> s.id().toString())).toList();
        long cheapest = 0;
        int count = 0;
        for (long amount : sellers.stream().mapToLong(Seller::debt).sorted().toArray()) {
            if (amount > budget - cheapest) break;
            cheapest += amount;
            count++;
        }
        if (count == 0) return List.of();
        // For the same count and sum, the oldest lexicographic seller list dominates every newer list.
        List<Map<Long, List<Seller>>> states = new ArrayList<>();
        for (int i = 0; i <= count; i++) states.add(new HashMap<>());
        states.get(0).put(0L, List.of());
        int stateCount = 1;
        for (Seller seller : sellers) {
            for (int n = count; n > 0; n--) {
                for (var entry : new ArrayList<>(states.get(n - 1).entrySet())) {
                    if (seller.debt() > budget - entry.getKey()) continue;
                    long sum = entry.getKey() + seller.debt();
                    var selected = new ArrayList<>(entry.getValue());
                    selected.add(seller);
                    List<Seller> previous = states.get(n).get(sum);
                    if (previous == null || older(selected, previous)) {
                        if (previous == null && ++stateCount > MAX_SELECTION_STATES) tooComplex();
                        states.get(n).put(sum, List.copyOf(selected));
                    }
                }
            }
        }
        long best = states.get(count).keySet().stream().mapToLong(Long::longValue).max().orElseThrow();
        return allocate(states.get(count).get(best), couriers);
    }

    public List<Allocation> allocate(List<Seller> sellers, List<Courier> input) {
        if (sellers.isEmpty()) return List.of();
        List<Courier> couriers = input.stream().filter(c -> c.available() > 0)
                .sorted(Comparator.comparing(c -> c.id().toString())).toList();
        long debt = sellers.stream().mapToLong(Seller::debt).reduce(0, Math::addExact);
        long available = couriers.stream().mapToLong(Courier::available).reduce(0, Math::addExact);
        if (debt > available) throw new FinanceConflictException("Les caisses confirmees ne couvrent plus les vendeurs reserves.");
        Search search = new Search(sellers, couriers);
        search.visit(sellers.stream().mapToLong(Seller::debt).toArray(),
                couriers.stream().mapToLong(Courier::available).toArray(), new ArrayList<>());
        return search.best;
    }

    private static boolean older(List<Seller> a, List<Seller> b) {
        for (int i = 0; i < a.size(); i++) {
            int compare = a.get(i).oldestDate().compareTo(b.get(i).oldestDate());
            if (compare != 0) return compare < 0;
            compare = a.get(i).id().toString().compareTo(b.get(i).id().toString());
            if (compare != 0) return compare < 0;
        }
        return false;
    }

    private static void tooComplex() {
        throw new FinanceConflictException("La preparation exacte depasse sa borne de complexite. "
                + "Aucun versement n'a ete reserve ; preparez les versements dans Finance.");
    }

    private static final class Search {
        private final List<Seller> sellers;
        private final List<Courier> couriers;
        private final Map<String, Integer> visited = new HashMap<>();
        private List<Allocation> best;
        private int nodes;
        private final int globalLowerBound;

        Search(List<Seller> sellers, List<Courier> couriers) {
            this.sellers = sellers;
            this.couriers = couriers;
            this.globalLowerBound = sellers.size();
            // A feasible north-west corner allocation provides a finite initial upper bound.
            long[] budget = couriers.stream().mapToLong(Courier::available).toArray();
            var greedy = new ArrayList<Allocation>();
            for (Seller seller : sellers) {
                long remainder = seller.debt();
                for (int c = 0; c < budget.length && remainder > 0; c++) {
                    long amount = Math.min(remainder, budget[c]);
                    if (amount > 0) {
                        greedy.add(new Allocation(seller, couriers.get(c), amount));
                        budget[c] -= amount;
                        remainder -= amount;
                    }
                }
            }
            this.best = List.copyOf(greedy);
        }

        void visit(long[] debt, long[] budget, List<Allocation> path) {
            if (best.size() == globalLowerBound) return;
            if (++nodes > MAX_ALLOCATION_NODES) tooComplex();
            int remaining = (int) Arrays.stream(debt).filter(value -> value > 0).count();
            if (remaining == 0) { if (path.size() < best.size()) best = List.copyOf(path); return; }
            if (path.size() + remaining >= best.size()) return;
            String state = Arrays.toString(debt) + Arrays.toString(budget);
            Integer previous = visited.putIfAbsent(state, path.size());
            if (previous != null && previous <= path.size()) return;
            visited.put(state, path.size());
            // Every minimum-support transportation solution has a cycle-free representative.
            // Removing a leaf saturates one endpoint, so enumerate all min(row,column) edges.
            for (int s = 0; s < debt.length; s++) {
                if (debt[s] <= 0) continue;
                Set<Long> equivalentBudgets = new HashSet<>();
                for (int c = 0; c < budget.length; c++) {
                    if (budget[c] <= 0 || !equivalentBudgets.add(budget[c])) continue;
                    long amount = Math.min(debt[s], budget[c]);
                    debt[s] -= amount; budget[c] -= amount;
                    path.add(new Allocation(sellers.get(s), couriers.get(c), amount));
                    visit(debt, budget, path);
                    path.removeLast(); debt[s] += amount; budget[c] += amount;
                }
            }
        }
    }
}
