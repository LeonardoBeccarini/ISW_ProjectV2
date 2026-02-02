package org.example.utilities;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;

import static org.example.utilities.CsvExporter.*;

/**
 * Calcola la correlazione di Spearman tra feature "actionable" e la colonna target "Buggy".
 * <p></p>
 * Responsabilità UNICA: calcoli statistici (Spearman, Pearson, Rank).
 * Delega TUTTO l'I/O CSV a CsvExporter.
 */
public final class SpearmanDatasetAnalyzer {

    private static final Logger LOGGER = Logger.getLogger(SpearmanDatasetAnalyzer.class.getName());

    private SpearmanDatasetAnalyzer() { }

    private static final List<String> ACTIONABLE_FEATURES = List.of(
            "LOC",
            "NumParameters",
            "NumBranches",
            "NestingDepth",
            "NumCodeSmells",
            "NumLocalVariables"
    );

    private static final String TARGET_COL = "Buggy";

    // ================================================================
    //                    ENTRY POINT
    // ================================================================

    public static void computeCorrelation(String projectName) throws IOException {
        String proj = (projectName == null) ? "PROJECT" : projectName.trim().toUpperCase(Locale.ROOT);
        Path datasetPath = Paths.get("output", "csv", proj, "dataset.csv");

        // Delega lettura CSV a CsvExporter
        CsvData csvData = readCsvFile(datasetPath);
        validateRequiredColumns(csvData, datasetPath);

        // Estrae i dati per il calcolo
        CorrelationData data = extractCorrelationData(csvData);

        if (data.targetValues().size() < 2) {
            throw new IOException("Pochi dati validi per calcolare Spearman (righe valide=" +
                    data.targetValues().size() + "). File: " + datasetPath);
        }

        // Calcola e logga le correlazioni
        computeAndLogCorrelations(data, datasetPath);
    }

    // ================================================================
    //                    VALIDAZIONE
    // ================================================================

    private static void validateRequiredColumns(CsvData csvData, Path datasetPath) throws IOException {
        List<String> allRequired = new ArrayList<>(ACTIONABLE_FEATURES);
        allRequired.add(TARGET_COL);
        validateColumns(csvData, datasetPath, allRequired.toArray(new String[0]));
    }

    // ================================================================
    //                    ESTRAZIONE DATI
    // ================================================================

    private record CorrelationData(
            List<Double> targetValues,
            Map<String, List<Double>> featureValues
    ) {}

    private static CorrelationData extractCorrelationData(CsvData csvData) {
        List<Double> targetValues = new ArrayList<>();
        Map<String, List<Double>> featureValues = new LinkedHashMap<>();

        for (String feature : ACTIONABLE_FEATURES) {
            featureValues.put(feature, new ArrayList<>());
        }

        int targetIdx = csvData.columnIndex().get(TARGET_COL);

        for (List<String> row : csvData.rows()) {
            Double target = parseBuggy(row, targetIdx);
            Map<String, Double> rowFeatures = (target != null) ? extractRowFeatures(row, csvData.columnIndex()) : null;

            if (target != null && rowFeatures != null) {
                targetValues.add(target);
                for (String feature : ACTIONABLE_FEATURES) {
                    featureValues.get(feature).add(rowFeatures.get(feature));
                }
            }
        }


        return new CorrelationData(targetValues, featureValues);
    }

    private static Map<String, Double> extractRowFeatures(List<String> row, Map<String, Integer> columnIndex) {
        Map<String, Double> features = new HashMap<>();

        for (String feature : ACTIONABLE_FEATURES) {
            int idx = columnIndex.get(feature);
            Double value = parseDoubleSafe(row, idx);
            if (value == null || !Double.isFinite(value)) {
                return new HashMap<>(); // Riga non valida
            }
            features.put(feature, value);
        }

        return features;
    }

    // ================================================================
    //                    CALCOLO CORRELAZIONI
    // ================================================================

    private static void computeAndLogCorrelations(CorrelationData data, Path datasetPath) {
        String format = "% .6f";
        String bestFeature = null;
        double bestRho = Double.NaN;

        if (LOGGER.isLoggable(Level.INFO)) {
            LOGGER.log(Level.INFO, "Spearman (rho) vs target ''{0}'' su file: {1}",
                    new Object[]{TARGET_COL, datasetPath});
        }

        for (String feature : ACTIONABLE_FEATURES) {
            double rho = spearman(data.featureValues().get(feature), data.targetValues());

            if (LOGGER.isLoggable(Level.INFO)) {
                LOGGER.log(Level.INFO, "  {0} rho={1} |rho|={2}",
                        new Object[]{
                                String.format("%-16s", feature),
                                String.format(Locale.ROOT, format, rho),
                                String.format(Locale.ROOT, format, Math.abs(rho))
                        });
            }

            if (!Double.isNaN(rho) && (bestFeature == null || Math.abs(rho) > Math.abs(bestRho))) {
                bestFeature = feature;
                bestRho = rho;
            }
        }

        logBestFeature(bestFeature, bestRho);
    }

    private static void logBestFeature(String bestFeature, double bestRho) {
        if (!LOGGER.isLoggable(Level.INFO)) return;

        String format = "% .6f";
        if (bestFeature == null) {
            LOGGER.log(Level.INFO, "Nessuna correlazione valida (tutte NaN).");
        } else {
            LOGGER.log(Level.INFO, "FEATURE PIU'' CORRELATA (per |rho|): {0}  rho={1}  |rho|={2}",
                    new Object[]{
                            bestFeature,
                            String.format(Locale.ROOT, format, bestRho),
                            String.format(Locale.ROOT, format, Math.abs(bestRho))
                    });
        }
    }

    // ================================================================
    //               ALGORITMI STATISTICI (CORE)
    // ================================================================

    /**
     * Calcola il coefficiente di correlazione di Spearman tra due liste.
     */
    public static double spearman(List<Double> x, List<Double> y) {
        if (x == null || y == null || x.size() != y.size() || x.size() < 2) {
            return Double.NaN;
        }

        // Filtra coppie con valori non finiti
        List<Double> xFiltered = new ArrayList<>();
        List<Double> yFiltered = new ArrayList<>();
        filterFinitePairs(x, y, xFiltered, yFiltered);

        if (xFiltered.size() < 2) {
            return Double.NaN;
        }

        // Calcola i rank e applica Pearson
        List<Double> rankX = computeRanks(xFiltered);
        List<Double> rankY = computeRanks(yFiltered);

        return pearson(rankX, rankY);
    }

    /**
     * Calcola il coefficiente di correlazione di Pearson tra due liste.
     */
    public static double pearson(List<Double> a, List<Double> b) {
        int n = a.size();
        if (n < 2) return Double.NaN;

        // Calcola le medie
        double meanA = 0.0;
        double meanB = 0.0;
        for (int i = 0; i < n; i++) {
            meanA += a.get(i);
            meanB += b.get(i);
        }
        meanA /= n;
        meanB /= n;

        // Calcola covarianza e deviazioni standard
        double covariance = 0.0;
        double varA = 0.0;
        double varB = 0.0;

        for (int i = 0; i < n; i++) {
            double devA = a.get(i) - meanA;
            double devB = b.get(i) - meanB;
            covariance += devA * devB;
            varA += devA * devA;
            varB += devB * devB;
        }

        if (varA == 0.0 || varB == 0.0) {
            return Double.NaN;
        }

        return covariance / Math.sqrt(varA * varB);
    }

    /**
     * Calcola i rank (con gestione dei tie tramite media).
     */
    public static List<Double> computeRanks(List<Double> values) {
        int n = values.size();

        // Crea array di indici e ordina per valore
        Integer[] sortedIndices = new Integer[n];
        for (int i = 0; i < n; i++) {
            sortedIndices[i] = i;
        }
        Arrays.sort(sortedIndices, Comparator.comparingDouble(values::get));

        // Assegna i rank gestendo i tie (valori uguali)
        double[] ranks = new double[n];
        List<int[]> tieGroups = identifyTieGroups(values, sortedIndices);

        for (int[] group : tieGroups) {
            int start = group[0];
            int end = group[1];
            double avgRank = (start + 1 + end) / 2.0;

            for (int k = start; k < end; k++) {
                ranks[sortedIndices[k]] = avgRank;
            }
        }

        // Converti in List
        List<Double> result = new ArrayList<>(n);
        for (double rank : ranks) {
            result.add(rank);
        }
        return result;
    }

    // ================================================================
    //                    HELPER STATISTICI
    // ================================================================

    private static void filterFinitePairs(List<Double> x, List<Double> y,
                                          List<Double> xOut, List<Double> yOut) {
        for (int i = 0; i < x.size(); i++) {
            double a = x.get(i);
            double b = y.get(i);
            if (Double.isFinite(a) && Double.isFinite(b)) {
                xOut.add(a);
                yOut.add(b);
            }
        }
    }

    private static List<int[]> identifyTieGroups(List<Double> values, Integer[] sortedIndices) {
        int n = sortedIndices.length;
        List<int[]> groups = new ArrayList<>();
        int groupStart = 0;

        for (int i = 1; i <= n; i++) {
            boolean isEndOfGroup = (i == n) ||
                    Double.compare(values.get(sortedIndices[i]), values.get(sortedIndices[groupStart])) != 0;

            if (isEndOfGroup) {
                groups.add(new int[]{groupStart, i});
                groupStart = i;
            }
        }

        return groups;
    }
}