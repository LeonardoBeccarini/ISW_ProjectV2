package org.example.utilities;

import org.example.model.Method;
import org.example.model.Metrics;
import org.example.model.Ticket;
import org.example.model.Version;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;

public class DatasetDiagnostics {

    public static void analyzeLabelingQuality(String projectName,
                                              List<Method> methods,
                                              List<Ticket> tickets,
                                              List<Version> versions) throws IOException {

        Path outputPath = Paths.get("output/diagnostics", projectName + "_diagnostics.txt");
        Files.createDirectories(outputPath.getParent());

        try (BufferedWriter writer = Files.newBufferedWriter(outputPath, StandardCharsets.UTF_8)) {

            writer.write("=== DATASET DIAGNOSTICS FOR " + projectName + " ===\n\n");

            // 1. CLASS IMBALANCE ANALYSIS
            analyzeClassImbalance(methods, writer);

            // 2. LABELING COVERAGE
            analyzeLabelingCoverage(methods, tickets, writer);

            // 3. TEMPORAL DISTRIBUTION
            analyzeTemporalDistribution(methods, versions, writer);

            // 4. FEATURE QUALITY
            analyzeFeatureQuality(methods, writer);

            // 5. TICKET-METHOD MAPPING
            analyzeTicketMethodMapping(methods, tickets, writer);

            // 6. PROPORTION ACCURACY
            analyzeProportionAccuracy(tickets, writer);

            System.out.println("Diagnostics saved to: " + outputPath);
        }
    }

    private static void analyzeClassImbalance(List<Method> methods, BufferedWriter writer)
            throws IOException {
        writer.write("1. CLASS IMBALANCE ANALYSIS\n");
        writer.write("----------------------------\n");

        long totalMethods = methods.size();
        long buggyMethods = methods.stream().filter(Method::isBuggy).count();
        long cleanMethods = totalMethods - buggyMethods;

        double buggyPercentage = (buggyMethods * 100.0) / totalMethods;
        double imbalanceRatio = (double) cleanMethods / Math.max(1, buggyMethods);

        writer.write(String.format("Total methods: %d\n", totalMethods));
        writer.write(String.format("Buggy methods: %d (%.2f%%)\n", buggyMethods, buggyPercentage));
        writer.write(String.format("Clean methods: %d (%.2f%%)\n", cleanMethods, 100 - buggyPercentage));
        writer.write(String.format("Imbalance ratio (clean:buggy): %.2f:1\n", imbalanceRatio));

        // CRITICAL THRESHOLD
        if (buggyPercentage < 5.0) {
            writer.write("\nCRITICAL: Buggy class < 5% - Extreme imbalance!\n");
        } else if (buggyPercentage < 10.0) {
            writer.write("\n WARNING: Buggy class < 10% - High imbalance\n");
        }

        writer.write("\n");
    }

    private static void analyzeLabelingCoverage(List<Method> methods, List<Ticket> tickets,
                                                BufferedWriter writer) throws IOException {
        writer.write("2. LABELING COVERAGE ANALYSIS\n");
        writer.write("------------------------------\n");

        // Unique methods (by FQN)
        Set<String> uniqueMethods = methods.stream()
                .map(Method::getFullyQualifiedName)
                .collect(Collectors.toSet());

        // Methods that are buggy in at least one version
        Set<String> methodsEverBuggy = methods.stream()
                .filter(Method::isBuggy)
                .map(Method::getFullyQualifiedName)
                .collect(Collectors.toSet());

        // Methods never labeled as buggy
        Set<String> neverBuggyMethods = new HashSet<>(uniqueMethods);
        neverBuggyMethods.removeAll(methodsEverBuggy);

        double coveragePercentage = (methodsEverBuggy.size() * 100.0) / uniqueMethods.size();

        writer.write(String.format("Unique methods in dataset: %d\n", uniqueMethods.size()));
        writer.write(String.format("Methods labeled buggy (at least once): %d (%.2f%%)\n",
                methodsEverBuggy.size(), coveragePercentage));
        writer.write(String.format("Methods NEVER labeled buggy: %d (%.2f%%)\n",
                neverBuggyMethods.size(), 100 - coveragePercentage));

        // Tickets with associated commits
        long ticketsWithCommits = tickets.stream()
                .filter(t -> t.getAssociatedCommits() != null && !t.getAssociatedCommits().isEmpty())
                .count();

        writer.write(String.format("\nTotal tickets: %d\n", tickets.size()));
        writer.write(String.format("Tickets with associated commits: %d (%.2f%%)\n",
                ticketsWithCommits,
                (ticketsWithCommits * 100.0) / Math.max(1, tickets.size())));

        if (coveragePercentage < 10.0) {
            writer.write("\nCRITICAL: < 10% of methods ever labeled buggy!\n");
            writer.write("    This suggests:\n");
            writer.write("    - Very few fix commits found\n");
            writer.write("    - Poor commit-ticket linking\n");
            writer.write("    - Issues with SZZ/Proportion labeling\n");
        }

        writer.write("\n");
    }

    private static void analyzeTemporalDistribution(List<Method> methods, List<Version> versions,
                                                    BufferedWriter writer) throws IOException {
        writer.write("3. TEMPORAL DISTRIBUTION OF BUGS\n");
        writer.write("---------------------------------\n");

        Map<Integer, Long> buggyByVersion = methods.stream()
                .filter(Method::isBuggy)
                .collect(Collectors.groupingBy(
                        m -> m.getVersion().getIndex(),
                        Collectors.counting()
                ));

        Map<Integer, Long> totalByVersion = methods.stream()
                .collect(Collectors.groupingBy(
                        m -> m.getVersion().getIndex(),
                        Collectors.counting()
                ));

        writer.write("Version | Commits | Total Methods | Buggy Methods | Buggy %\n");
        writer.write("--------|---------|---------------|---------------|--------\n");

        for (Version v : versions) {
            int idx = v.getIndex();
            int numCommits = (v.getCommitList() != null) ? v.getCommitList().size() : 0;
            long total = totalByVersion.getOrDefault(idx, 0L);
            long buggy = buggyByVersion.getOrDefault(idx, 0L);
            double percentage = total > 0 ? (buggy * 100.0 / total) : 0.0;

            writer.write(String.format("%-7d | %-7d | %-13d | %-13d | %.2f%%\n",
                    idx, numCommits, total, buggy, percentage));
        }

        // Check for versions with NO commits
        long versionsWithNoCommits = versions.stream()
                .filter(v -> v.getCommitList() == null || v.getCommitList().isEmpty())
                .count();

        if (versionsWithNoCommits > 0) {
            writer.write(String.format("\n[INFO] %d/%d versions have ZERO commits (no Git history)\n",
                    versionsWithNoCommits, versions.size()));
        }

        // Check for versions with NO buggy methods
        long versionsWithNoBugs = versions.stream()
                .filter(v -> buggyByVersion.getOrDefault(v.getIndex(), 0L) == 0)
                .count();

        if (versionsWithNoBugs > versions.size() / 2) {
            writer.write(String.format("\nWARNING: %d/%d versions have ZERO buggy methods\n",
                    versionsWithNoBugs, versions.size()));
        }

        writer.write("\n");
    }

    private static void analyzeFeatureQuality(List<Method> methods, BufferedWriter writer)
            throws IOException {
        writer.write("4. FEATURE QUALITY ANALYSIS\n");
        writer.write("---------------------------\n");

        // Separate buggy and clean
        List<Method> buggyMethods = methods.stream()
                .filter(Method::isBuggy)
                .collect(Collectors.toList());

        List<Method> cleanMethods = methods.stream()
                .filter(m -> !m.isBuggy())
                .collect(Collectors.toList());

        if (buggyMethods.isEmpty()) {
            writer.write(" CRITICAL: NO buggy methods to analyze!\n\n");
            return;
        }

        writer.write("Feature          | Buggy Avg | Clean Avg | Ratio | Discriminative?\n");
        writer.write("-----------------|-----------|-----------|-------|----------------\n");

        // Analyze key features
        analyzeFeature("LOC", buggyMethods, cleanMethods, m -> (double) m.getMetrics().getLoc(), writer);
        analyzeFeature("Complexity", buggyMethods, cleanMethods,
                m -> (double) m.getMetrics().getCyclomaticComplexity(), writer);
        analyzeFeature("NumRevisions", buggyMethods, cleanMethods,
                m -> (double) m.getMetrics().getNumRevisions(), writer);
        analyzeFeature("NumAuthors", buggyMethods, cleanMethods,
                m -> (double) m.getMetrics().getNumAuthors(), writer);
        analyzeFeature("MaxChurn", buggyMethods, cleanMethods,
                m -> (double) m.getMetrics().getMaxChurn(), writer);
        analyzeFeature("AvgChurn", buggyMethods, cleanMethods,
                m -> m.getMetrics().getAvgChurn(), writer);
        analyzeFeature("HasFixHistory", buggyMethods, cleanMethods,
                m -> (double) m.getMetrics().getHasFixHistory(), writer);

        writer.write("\n");
    }

    private static void analyzeFeature(String name,
                                       List<Method> buggy,
                                       List<Method> clean,
                                       java.util.function.Function<Method, Double> extractor,
                                       BufferedWriter writer) throws IOException {
        double buggyAvg = buggy.stream()
                .mapToDouble(m -> extractor.apply(m))
                .average()
                .orElse(0.0);

        double cleanAvg = clean.stream()
                .mapToDouble(m -> extractor.apply(m))
                .average()
                .orElse(0.0);

        double ratio = cleanAvg > 0 ? buggyAvg / cleanAvg : 0.0;
        String discriminative = (ratio > 1.5 || ratio < 0.67) ? "YES âœ“" : "NO âœ—";

        writer.write(String.format("%-16s | %9.2f | %9.2f | %5.2f | %s\n",
                name, buggyAvg, cleanAvg, ratio, discriminative));
    }

    private static void analyzeTicketMethodMapping(List<Method> methods, List<Ticket> tickets,
                                                   BufferedWriter writer) throws IOException {
        writer.write("5. TICKET-METHOD MAPPING ANALYSIS\n");
        writer.write("----------------------------------\n");

        // Count how many distinct methods were touched by fix commits
        Set<String> methodsTouchedByFixes = new HashSet<>();

        for (Ticket ticket : tickets) {
            if (ticket.getAssociatedCommits() == null) continue;

            // This is a simplification - in reality we'd need to parse commits
            // For now, just count tickets
        }

        // Methods with process metrics > 0 (meaning they were touched by commits)
        long methodsWithHistory = methods.stream()
                .filter(m -> m.getMetrics().getNumRevisions() > 0)
                .count();

        long methodsWithFixHistory = methods.stream()
                .filter(m -> m.getMetrics().getHasFixHistory() > 0)
                .count();

        writer.write(String.format("Methods with revision history: %d (%.2f%%)\n",
                methodsWithHistory,
                (methodsWithHistory * 100.0) / methods.size()));

        writer.write(String.format("Methods with fix history: %d (%.2f%%)\n",
                methodsWithFixHistory,
                (methodsWithFixHistory * 100.0) / methods.size()));

        if (methodsWithHistory < methods.size() * 0.3) {
            writer.write("\n WARNING: < 30% of methods have revision history\n");
            writer.write("    Process metrics will be mostly zeros!\n");
        }

        writer.write("\n");
    }

    private static void analyzeProportionAccuracy(List<Ticket> tickets, BufferedWriter writer)
            throws IOException {
        writer.write("6. PROPORTION ACCURACY INDICATORS\n");
        writer.write("----------------------------------\n");

        List<Double> proportions = new ArrayList<>();

        for (Ticket t : tickets) {
            if (t.getInjectedVersion() == null ||
                    t.getOpeningVersion() == null ||
                    t.getFixedVersion() == null) {
                continue;
            }

            int iv = t.getInjectedVersion().getIndex();
            int ov = t.getOpeningVersion().getIndex();
            int fv = t.getFixedVersion().getIndex();

            if (fv > ov) {
                double p = (double) (fv - iv) / (double) (fv - ov);
                if (p >= 0 && p <= 10) {  // Reasonable range
                    proportions.add(p);
                }
            }
        }

        if (proportions.isEmpty()) {
            writer.write("NO valid proportions calculated!\n\n");
            return;
        }

        double avgP = proportions.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
        double stdP = calculateStdDev(proportions, avgP);

        Collections.sort(proportions);
        double medianP = proportions.get(proportions.size() / 2);

        writer.write(String.format("Number of valid proportions: %d\n", proportions.size()));
        writer.write(String.format("Average proportion (P): %.3f\n", avgP));
        writer.write(String.format("Median proportion: %.3f\n", medianP));
        writer.write(String.format("Std deviation: %.3f\n", stdP));

        if (stdP > 2.0) {
            writer.write("\nWARNING: High variance in proportion values\n");
            writer.write("    This suggests inconsistent bug lifecycle\n");
        }

        if (avgP > 3.0) {
            writer.write("\nWARNING: Average proportion > 3.0\n");
            writer.write("    IVs might be pushed too far back\n");
            writer.write("     Too many methods labeled as buggy\n");
            writer.write("     Many false positives expected\n");
        }

        writer.write("\n");
    }

    private static double calculateStdDev(List<Double> values, double mean) {
        double sumSquaredDiff = values.stream()
                .mapToDouble(v -> Math.pow(v - mean, 2))
                .sum();
        return Math.sqrt(sumSquaredDiff / values.size());
    }
}