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
    public static final String ALGORITHM = "Exact : nombre de vendeurs, montant, anciennete, puis minimum de transferts entre livreurs. "
            + "Chaque vendeur est solde par un seul livreur, apres regroupement de la caisse necessaire. "
            + "Bornes : 100 000 etats de selection, 2 000 000 transitions, 200 000 etats d'allocation ; sinon preparation manuelle requise.";
    private static final int MAX_SELECTION_STATES = 100_000;
    private static final int MAX_ALLOCATION_NODES = 200_000;
    private static final Comparator<Seller> SELLER_ORDER = Comparator.comparing(Seller::oldestDate)
            .thenComparing(s -> s.id().toString());
    private static final Comparator<Courier> COURIER_ORDER = Comparator.comparing(c -> c.id().toString());
    private final int maxSelectionStates;
    private final int maxSelectionTransitions;
    private final int maxAllocationNodes;

    public CashPayoutOptimizer() { this(MAX_SELECTION_STATES, MAX_ALLOCATION_NODES); }

    CashPayoutOptimizer(int maxSelectionStates, int maxAllocationNodes) {
        if (maxSelectionStates < 1 || maxAllocationNodes < 1)
            throw new IllegalArgumentException("Optimization bounds must be positive.");
        this.maxSelectionStates = maxSelectionStates;
        this.maxSelectionTransitions = (int) Math.min(Integer.MAX_VALUE, 20L * maxSelectionStates);
        this.maxAllocationNodes = maxAllocationNodes;
    }

    public record Seller(UUID id, String name, String payoutNumber, long debt, LocalDate oldestDate) {}
    public record Courier(UUID id, String name, long available) {}
    public record Allocation(Seller seller, Courier courier, long amount) {}
    public record CashTransfer(Courier from, Courier to, long amount) {}
    public record Plan(List<Allocation> payouts, List<CashTransfer> courierTransfers) {
        public Plan {
            payouts = List.copyOf(payouts);
            courierTransfers = List.copyOf(courierTransfers);
        }
    }

    /**
     * A connected allocation component with r couriers and s sellers has at least r+s-1 edges.
     * Funding one payer needs at most r-1 courier transfers and s whole seller payouts.
     * Conversely, each courier-transfer component admits a transportation allocation with no
     * more edges than its transfers plus payouts. The two minimum operation counts coincide,
     * so consolidating the existing exact allocation also minimizes internal transfers.
     */
    public Plan optimizePlan(List<Seller> sellers, List<Courier> couriers) {
        List<Allocation> seed = optimize(sellers, couriers);
        Map<UUID, UUID> parents = new HashMap<>();
        Map<UUID, UUID> sellerCouriers = new HashMap<>();
        for (Allocation allocation : seed) {
            UUID courierId = allocation.courier().id();
            parents.putIfAbsent(courierId, courierId);
            UUID previous = sellerCouriers.putIfAbsent(allocation.seller().id(), courierId);
            if (previous != null) {
                UUID a = root(parents, previous), b = root(parents, courierId);
                if (a.toString().compareTo(b.toString()) < 0) parents.put(b, a);
                else parents.put(a, b);
            }
        }
        Map<UUID, Component> components = new HashMap<>();
        for (Allocation allocation : seed) {
            Component component = components.computeIfAbsent(root(parents, allocation.courier().id()),
                    ignored -> new Component());
            component.sellers.put(allocation.seller().id(), allocation.seller());
            component.couriers.put(allocation.courier().id(), allocation.courier());
        }
        List<Allocation> payouts = new ArrayList<>();
        List<CashTransfer> transfers = new ArrayList<>();
        for (UUID componentId : components.keySet().stream().sorted(Comparator.comparing(UUID::toString)).toList()) {
            Component component = components.get(componentId);
            List<Courier> members = component.couriers.values().stream()
                    .sorted(Comparator.comparingLong(Courier::available).reversed().thenComparing(COURIER_ORDER))
                    .toList();
            Courier payer = members.getFirst();
            long total = component.sellers.values().stream().mapToLong(Seller::debt)
                    .reduce(0, CashPayoutOptimizer::safeTotal);
            long shortfall = Math.max(0L, total - payer.available());
            for (Courier donor : members) {
                if (shortfall == 0) break;
                if (donor.id().equals(payer.id())) continue;
                long amount = Math.min(shortfall, donor.available());
                if (amount > 0) {
                    transfers.add(new CashTransfer(donor, payer, amount));
                    shortfall -= amount;
                }
            }
            if (shortfall != 0) insufficientCash();
            component.sellers.values().stream().sorted(SELLER_ORDER)
                    .forEach(seller -> payouts.add(new Allocation(seller, payer, seller.debt())));
        }
        payouts.sort(Comparator.comparing(Allocation::seller, SELLER_ORDER));
        return new Plan(payouts, transfers);
    }

    /** Compatibility/internal transportation seed; its rows may split a seller across couriers. */

    public List<Allocation> optimize(List<Seller> input, List<Courier> couriers) {
        long budget = couriers.stream().mapToLong(Courier::available).filter(value -> value > 0)
                .reduce(0, CashPayoutOptimizer::safeTotal);
        List<Seller> sellers = input.stream().filter(s -> s.debt() > 0 && s.debt() <= budget)
                .sorted(SELLER_ORDER).toList();
        long cheapest = 0;
        int count = 0;
        for (long amount : sellers.stream().mapToLong(Seller::debt).sorted().toArray()) {
            if (amount > budget - cheapest) break;
            cheapest += amount;
            count++;
        }
        if (count == 0) return List.of();
        if (count + 1L > maxSelectionStates) tooComplex();
        // For the same count and sum, the oldest lexicographic seller list dominates every newer list.
        List<Map<Long, List<Seller>>> states = new ArrayList<>();
        for (int i = 0; i <= count; i++) states.add(new HashMap<>());
        states.get(0).put(0L, List.of());
        int stateCount = 1;
        long transitions = 0;
        int processed = 0;
        for (Seller seller : sellers) {
            processed++;
            for (int n = Math.min(count, processed); n > 0; n--) {
                for (var entry : new ArrayList<>(states.get(n - 1).entrySet())) {
                    if (++transitions > maxSelectionTransitions) tooComplex();
                    if (seller.debt() > budget - entry.getKey()) continue;
                    long sum = entry.getKey() + seller.debt();
                    var selected = new ArrayList<>(entry.getValue());
                    selected.add(seller);
                    List<Seller> previous = states.get(n).get(sum);
                    if (previous == null || older(selected, previous)) {
                        if (previous == null && ++stateCount > maxSelectionStates) tooComplex();
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
                .sorted(COURIER_ORDER).toList();
        List<Seller> orderedSellers = sellers.stream().filter(s -> s.debt() > 0).sorted(SELLER_ORDER).toList();
        if (orderedSellers.isEmpty()) return List.of();
        long debt = orderedSellers.stream().mapToLong(Seller::debt).reduce(0, CashPayoutOptimizer::safeTotal);
        long available = couriers.stream().mapToLong(Courier::available).reduce(0, CashPayoutOptimizer::safeTotal);
        if (debt > available) insufficientCash();
        Search search = new Search(orderedSellers, couriers, maxAllocationNodes);
        search.visit(orderedSellers.stream().mapToLong(Seller::debt).toArray(),
                couriers.stream().mapToLong(Courier::available).toArray(), new ArrayList<>());
        return search.best;
    }

    private static UUID root(Map<UUID, UUID> parents, UUID id) {
        UUID root = id;
        while (!root.equals(parents.get(root))) root = parents.get(root);
        while (!id.equals(root)) {
            UUID next = parents.put(id, root);
            id = next;
        }
        return root;
    }

    private static final class Component {
        private final Map<UUID, Seller> sellers = new HashMap<>();
        private final Map<UUID, Courier> couriers = new HashMap<>();
    }

    private static long safeTotal(long a, long b) {
        try { return Math.addExact(a, b); }
        catch (ArithmeticException exception) {
            throw new FinanceConflictException("Le total des caisses ou des versements depasse la limite autorisee.");
        }
    }

    private static void insufficientCash() {
        throw new FinanceConflictException("Les caisses confirmees ne couvrent plus les vendeurs reserves.");
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
        private long nodes;
        private final int globalLowerBound;
        private final int maxNodes;

        Search(List<Seller> sellers, List<Courier> couriers, int maxNodes) {
            this.sellers = sellers;
            this.couriers = couriers;
            this.globalLowerBound = sellers.size();
            this.maxNodes = maxNodes;
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
            if (++nodes > maxNodes) tooComplex();
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
