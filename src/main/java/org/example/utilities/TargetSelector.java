package org.example.utilities;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.*;

/**
 * Utility class:
 * Select method with maximal AFeature in latest release → AFMethod.
 * <p>
 * Legge: output/csv/<PROJECT_UPPERCASE>/dataset.csv
 * (file prodotto da CsvExporter.exportAll(...), header come nel dataset export).
 */
public final class TargetSelector {

    /** Output file (txt) with the chosen method details. */
    private static final String TARGET_INFO_FILE = "target_method.txt";

    private TargetSelector() {
        // utility class
    }

    /**
     * AFeature selezionabile: deve corrispondere a un header presente in dataset.csv.
     * Header attesi (subset): LOC, NumParameters, NumBranches, NestingDepth, NumCodeSmells, NumLocalVariables.
     */
    public enum AFeature {
        LOC("LOC"),
        NUM_PARAMETERS("NumParameters"),
        NUM_BRANCHES("NumBranches"),
        NESTING_DEPTH("NestingDepth"),
        NUM_CODE_SMELLS("NumCodeSmells"),
        NUM_LOCAL_VARIABLES("NumLocalVariables");

        public final String csvHeader;

        AFeature(String csvHeader) {
            this.csvHeader = csvHeader;
        }

        public static AFeature fromString(String s) {
            if (s == null) throw new IllegalArgumentException("AFeature null");
            String x = s.trim();
            for (AFeature f : values()) {
                if (f.name().equalsIgnoreCase(x) || f.csvHeader.equalsIgnoreCase(x)) {
                    return f;
                }
            }
            throw new IllegalArgumentException("Unknown AFeature: " + s);
        }
    }

    /**
     * Output della selezione.
     * row: snapshot (colonna->valore) della riga selezionata, utile per debug/report.
     */
    public record AFMethod(
            String project,
            int latestVersionIndex,
            String versionName,
            AFeature aFeature,
            double aFeatureValue,
            String methodFqn,
            int loc,
            Map<String, String> row
    ) {}

    /* ===================== Helper: Parsed Row ===================== */

    /** Parsed values di una riga del CSV. */
    private record ParsedRow(int versionIndex, double featureValue, int loc, String fqn, String versionName,
                             Map<String, String> rowMap) {

        boolean isValid() {
            return versionIndex != Integer.MIN_VALUE;
        }
    }

    /* ===================== Helper: Best Candidate Tracker ===================== */

    /** Tiene traccia del candidato migliore */
    private static final class BestCandidate {
        int latestVersion = Integer.MIN_VALUE;
        double featVal = Double.NEGATIVE_INFINITY;
        int loc = Integer.MIN_VALUE;
        String fqn = null;
        String versionName = "";
        Map<String, String> rowMap = null;

        boolean isFound() {
            return rowMap != null && latestVersion != Integer.MIN_VALUE;
        }

        boolean hasFqn() {
            return fqn != null && !fqn.isBlank();
        }

        /**
         * Aggiorna il candidato se la riga è migliore
         * Tie-break: 1) max version, 2) max feature, 3) max LOC, 4) lexicographic FQN.
         */
        void updateIfBetter(ParsedRow row) {
            if (row.isValid() && isBetterThan(row)) {
                acceptRow(row);
            }
        }

        private boolean isBetterThan(ParsedRow row) {
            if (row.versionIndex() != latestVersion) {
                return row.versionIndex() > latestVersion;
            }
            int cmpFeat = Double.compare(row.featureValue(), featVal);
            if (cmpFeat != 0) {
                return cmpFeat > 0;
            }
            if (row.loc() != loc) {
                return row.loc() > loc;
            }
            return compareFqn(row.fqn(), fqn) < 0;
        }

        private void acceptRow(ParsedRow row) {
            latestVersion = row.versionIndex();
            featVal = row.featureValue();
            loc = row.loc();
            fqn = row.fqn();
            versionName = row.versionName();
            rowMap = row.rowMap();
        }

        private static int compareFqn(String a, String b) {
            String sa = (a == null) ? "" : a;
            String sb = (b == null) ? "" : b;
            return sa.compareTo(sb);
        }
    }

    /* ===================== Helper: Column Indices ===================== */

    /** Contiene indici richiesti per il parsing del CSV */
    private record ColumnIndices(int version, int versionName, int fqn, int loc, int feature) {

        static ColumnIndices from(Map<String, Integer> idx, AFeature aFeature) throws IOException {
            return new ColumnIndices(
                    requireIdx(idx, "VersionIndex"),
                    requireIdx(idx, "VersionName"),
                    requireIdx(idx, "MethodFQN"),
                    requireIdx(idx, "LOC"),
                    requireIdx(idx, aFeature.csvHeader)
            );
        }
    }

    /* ===================== Main Method ===================== */

    /**
     * Seleziona il metodo con valore massimo della AFeature nella release più recente (max VersionIndex).
     * <p>
     * Tie-break deterministico:
     *  1) max AFeature
     *  2) max LOC
     *  3) MethodFQN lessicografico
     */
    public static String selectAFMethod(String projectName, String aFeatureName) throws IOException {
        validateProjectName(projectName);
        AFeature aFeature = AFeature.fromString(aFeatureName);
        Objects.requireNonNull(aFeature, "aFeature");

        String proj = projectName.toUpperCase(Locale.ROOT);
        Path csv = Paths.get("output", "csv", proj, "dataset.csv");
        validateCsvExists(csv);

        AFMethod result = processDataset(csv, proj, aFeature);
        writeSelectionInfo(result);
        return methodNameFromMethodFqn(result.methodFqn());
    }

    private static void validateProjectName(String projectName) {
        if (projectName == null || projectName.isBlank()) {
            throw new IllegalArgumentException("projectName null/blank");
        }
    }

    private static void validateCsvExists(Path csv) throws IOException {
        if (!Files.exists(csv)) {
            throw new IOException("Missing dataset CSV: " + csv);
        }
    }

    private static AFMethod processDataset(Path csv, String proj, AFeature aFeature) throws IOException {
        try (BufferedReader br = Files.newBufferedReader(csv, StandardCharsets.UTF_8)) {
            List<String> header = readAndValidateHeader(br, csv);
            ColumnIndices cols = ColumnIndices.from(headerIndex(header), aFeature);
            BestCandidate best = findBestCandidate(br, header, cols);

            validateResult(best, csv);
            return buildResult(proj, aFeature, best);
        }
    }

    private static List<String> readAndValidateHeader(BufferedReader br, Path csv) throws IOException {
        String headerLine = br.readLine();
        if (headerLine == null || headerLine.isBlank()) {
            throw new IOException("Empty dataset CSV (missing header): " + csv);
        }
        return CsvExporter.parseCsvLine(headerLine);
    }

    private static BestCandidate findBestCandidate(BufferedReader br, List<String> header, ColumnIndices cols) throws IOException {
        BestCandidate best = new BestCandidate();
        String line;
        while ((line = br.readLine()) != null) {
            if (!line.isBlank()) {
                ParsedRow row = parseRow(line, header, cols);
                best.updateIfBetter(row);
            }
        }
        return best;
    }

    private static ParsedRow parseRow(String line, List<String> header, ColumnIndices cols) {
        List<String> row = CsvExporter.parseCsvLine(line);
        if (row.size() < header.size()) {
            row = pad(row, header.size());
        }
        return new ParsedRow(
                parseIntSafe(get(row, cols.version()), Integer.MIN_VALUE),
                parseDoubleSafe(get(row, cols.feature()), Double.NEGATIVE_INFINITY),
                parseIntSafe(get(row, cols.loc()), 0),
                safe(get(row, cols.fqn())),
                safe(get(row, cols.versionName())),
                toRowMap(header, row)
        );
    }

    private static void validateResult(BestCandidate best, Path csv) {
        if (!best.isFound()) {
            throw new IllegalStateException("No valid rows found in: " + csv);
        }
        if (!best.hasFqn()) {
            throw new IllegalStateException("Best row has empty MethodFQN in: " + csv);
        }
    }

    private static AFMethod buildResult(String proj, AFeature aFeature, BestCandidate best) {
        return new AFMethod(
                proj,
                best.latestVersion,
                best.versionName,
                aFeature,
                best.featVal,
                best.fqn,
                best.loc,
                Collections.unmodifiableMap(best.rowMap)
        );
    }

    /* ===================== Helpers ===================== */

    private static Map<String, Integer> headerIndex(List<String> header) {
        Map<String, Integer> m = new HashMap<>();
        for (int i = 0; i < header.size(); i++) {
            String h = header.get(i);
            if (h != null) m.put(h.trim(), i);
        }
        return m;
    }

    private static int requireIdx(Map<String, Integer> idx, String col) throws IOException {
        Integer i = idx.get(col);
        if (i == null) {
            throw new IOException("Missing column '" + col + "' in dataset.csv header");
        }
        return i;
    }

    private static String get(List<String> row, int i) {
        if (i < 0 || i >= row.size()) return "";
        return row.get(i);
    }

    private static String safe(String s) {
        return (s == null) ? "" : s;
    }

    private static int parseIntSafe(String s, int def) {
        try {
            if (s == null) return def;
            String t = s.trim();
            if (t.isEmpty()) return def;
            return Integer.parseInt(t);
        } catch (Exception _) {
            return def;
        }
    }

    private static double parseDoubleSafe(String s, double def) {
        try {
            if (s == null) return def;
            String t = s.trim();
            if (t.isEmpty()) return def;
            return Double.parseDouble(t);
        } catch (Exception _) {
            return def;
        }
    }

    private static Map<String, String> toRowMap(List<String> header, List<String> row) {
        Map<String, String> m = new LinkedHashMap<>();
        int n = Math.min(header.size(), row.size());
        for (int i = 0; i < n; i++) {
            m.put(header.get(i), row.get(i));
        }
        return m;
    }

    private static void writeSelectionInfo(AFMethod m) throws IOException {
        if (m == null) return;

        Path outDir = Paths.get("output", "refactor", m.project().toUpperCase(Locale.ROOT));
        Files.createDirectories(outDir);
        Path outFile = outDir.resolve(TARGET_INFO_FILE);

        StringBuilder sb = new StringBuilder();
        sb.append("=== TARGET SELECTOR OUTPUT ===\n");
        sb.append("Project: ").append(m.project()).append("\n");
        sb.append("LatestVersionIndex: ").append(m.latestVersionIndex()).append("\n");
        sb.append("LatestVersionName: ").append(m.versionName()).append("\n");
        sb.append("AFeature: ").append(m.aFeature()).append(" (csv=")
                .append(m.aFeature().csvHeader).append(")\n");
        sb.append("AFeatureValue: ").append(m.aFeatureValue()).append("\n");
        sb.append("MethodFQN: ").append(m.methodFqn()).append("\n");
        sb.append("LOC: ").append(m.loc()).append("\n\n");

        sb.append("Row snapshot (dataset.csv):\n");
        for (Map.Entry<String, String> e : m.row().entrySet()) {
            sb.append(e.getKey()).append(": ").append(e.getValue()).append("\n");
        }

        Files.writeString(outFile, sb.toString(), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
    }

    private static List<String> pad(List<String> row, int size) {
        ArrayList<String> out = new ArrayList<>(row);
        while (out.size() < size) out.add("");
        return out;
    }

    // helper per prendere il nome del metodo
    private static String methodNameFromMethodFqn(String methodFqn) {
        if (methodFqn == null) return "";

        String s = methodFqn.trim();
        if (s.isEmpty()) return "";

        // preferisci il segmento dopo l'ultimo '#'
        int hash = s.lastIndexOf('#');
        int start = (hash >= 0) ? (hash + 1) : Math.max(s.lastIndexOf('/') + 1, 0);

        int paren = s.indexOf('(', start);
        if (paren < 0) {
            // niente parametri: fino alla fine
            return s.substring(start);
        }
        if (paren <= start) return "";
        return s.substring(start, paren);
    }

}