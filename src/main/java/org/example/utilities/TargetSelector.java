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

    /**
     * Seleziona il metodo con valore massimo della AFeature nella release più recente (max VersionIndex).
     * <p>
     * Tie-break deterministico:
     *  1) max AFeature
     *  2) max LOC
     *  3) MethodFQN lessicografico
     */
    public static String selectAFMethod(String projectName, String aFeatureName) throws IOException {
        if (projectName == null || projectName.isBlank()) {
            throw new IllegalArgumentException("projectName null/blank");
        }
        AFeature aFeature = AFeature.fromString(aFeatureName);
        Objects.requireNonNull(aFeature, "aFeature");

        String proj = projectName.toUpperCase(Locale.ROOT);
        Path csv = Paths.get("output", "csv", proj, "dataset.csv");
        if (!Files.exists(csv)) {
            throw new IOException("Missing dataset CSV: " + csv);
        }


        try (BufferedReader br = Files.newBufferedReader(csv, StandardCharsets.UTF_8)) {
            String headerLine = br.readLine();
            if (headerLine == null || headerLine.isBlank()) {
                throw new IOException("Empty dataset CSV (missing header): " + csv);
            }

            List<String> header = CsvExporter.parseCsvLine(headerLine);
            Map<String, Integer> idx = headerIndex(header);

            int iVersion = requireIdx(idx, "VersionIndex");
            int iVName   = requireIdx(idx, "VersionName");
            int iFqn     = requireIdx(idx, "MethodFQN");
            int iLoc     = requireIdx(idx, "LOC");
            int iFeat    = requireIdx(idx, aFeature.csvHeader);

            int bestLatestVersion = Integer.MIN_VALUE;
            double bestFeatVal = Double.NEGATIVE_INFINITY;
            int bestLoc = Integer.MIN_VALUE;
            String bestFqn = null;
            String bestVName = "";
            Map<String, String> bestRow = null;

            String line;
            while ((line = br.readLine()) != null) {
                if (line.isBlank()) continue;

                List<String> row = CsvExporter.parseCsvLine(line);
                if (row.size() < header.size()) {
                    row = pad(row, header.size());
                }

                int vIdx = parseIntSafe(get(row, iVersion), Integer.MIN_VALUE);
                if (vIdx == Integer.MIN_VALUE) continue;

                double featVal = parseDoubleSafe(get(row, iFeat), Double.NEGATIVE_INFINITY);
                int locVal = parseIntSafe(get(row, iLoc), 0);
                String fqn = safe(get(row, iFqn));
                String vName = safe(get(row, iVName));

                if (vIdx > bestLatestVersion) {
                    bestLatestVersion = vIdx;
                    bestFeatVal = featVal;
                    bestLoc = locVal;
                    bestFqn = fqn;
                    bestVName = vName;
                    bestRow = toRowMap(header, row);
                    continue;
                }

                if (vIdx != bestLatestVersion) {
                    continue;
                }

                if (featVal > bestFeatVal) {
                    bestFeatVal = featVal;
                    bestLoc = locVal;
                    bestFqn = fqn;
                    bestVName = vName;
                    bestRow = toRowMap(header, row);
                } else if (Double.compare(featVal, bestFeatVal) == 0) {
                    if (locVal > bestLoc) {
                        bestLoc = locVal;
                        bestFqn = fqn;
                        bestVName = vName;
                        bestRow = toRowMap(header, row);
                    } else if (locVal == bestLoc) {
                        String curFqn = (fqn == null) ? "" : fqn;
                        String oldFqn = (bestFqn == null) ? "" : bestFqn;
                        if (curFqn.compareTo(oldFqn) < 0) {
                            bestFqn = fqn;
                            bestVName = vName;
                            bestRow = toRowMap(header, row);
                        }
                    }
                }
            }

            if (bestRow == null || bestLatestVersion == Integer.MIN_VALUE) {
                throw new IllegalStateException("No valid rows found in: " + csv);
            }
            if (bestFqn == null || bestFqn.isBlank()) {
                throw new IllegalStateException("Best row has empty MethodFQN in: " + csv);
            }

            AFMethod out = new AFMethod(
                    proj,
                    bestLatestVersion,
                    bestVName,
                    aFeature,
                    bestFeatVal,
                    bestFqn,
                    bestLoc,
                    Collections.unmodifiableMap(bestRow)
            );

            writeSelectionInfo(out);
            return methodNameFromMethodFqn(out.methodFqn);
        }

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
        } catch (Exception ex) {
            return def;
        }
    }

    private static double parseDoubleSafe(String s, double def) {
        try {
            if (s == null) return def;
            String t = s.trim();
            if (t.isEmpty()) return def;
            return Double.parseDouble(t);
        } catch (Exception ex) {
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
