package org.example.utilities;

import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.revwalk.RevCommit;
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
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Utility per esportare in CSV:
 *  - le versioni
 *  - i commit
 *  - i ticket
 *  - il dataset completo dei metodi (metriche + label buggy)
 * I file vengono scritti nella cartella:
 *   output/csv/<PROJECT_NAME>/
 */
public final class CsvExporter {

    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ISO_LOCAL_DATE;

    private CsvExporter() {
        // utility class
    }

    /**
     * Esporta tutti i CSV:
     *  - versions.csv
     *  - commits.csv
     *  - tickets.csv
     *  - dataset.csv
     */
    public static void exportAll(String projectName,
                                 List<Version> versions,
                                 List<Ticket> tickets,
                                 List<Method> methods) throws IOException {

        if (projectName == null || projectName.isBlank()) {
            projectName = "PROJECT";
        }

        Path baseDir = Paths.get("output", "csv", projectName.toUpperCase());
        Files.createDirectories(baseDir);

        // NEW: indici contigui di export (1..N) per la lista di versioni passata
        Map<Version, Integer> exportIndexMap = buildExportIndexMap(versions);

        exportVersions(baseDir.resolve("versions.csv"), versions, exportIndexMap);
        exportCommits(baseDir.resolve("commits.csv"), versions, tickets, exportIndexMap);
        exportTickets(baseDir.resolve("tickets.csv"), tickets, exportIndexMap);
        exportDataset(baseDir.resolve("dataset.csv"), methods, exportIndexMap);
    }

    // ================================
    //            VERSIONS
    // ================================
    private static void exportVersions(Path file,
                                       List<Version> versions,
                                       Map<Version, Integer> exportIndexMap) throws IOException {

        try (BufferedWriter writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            writer.write("Index,Id,Name,Date,NumCommits");
            writer.newLine();

            if (versions == null) {
                return;
            }

            // Scriviamo in ordine di exportIndex (contiguo)
            List<Version> sorted = new ArrayList<>();
            for (Version v : versions) {
                if (v != null) sorted.add(v);
            }
            sorted.sort(Comparator
                    .comparing(Version::getDate, Comparator.nullsLast(Comparator.naturalOrder()))
                    .thenComparing(v -> nullSafe(v.getName()))
                    .thenComparing(v -> nullSafe(v.getId())));

            for (Version v : sorted) {
                int index = exportIndexOf(v, exportIndexMap);

                String id = nullSafe(v.getId());
                String name = nullSafe(v.getName());
                String date = formatDate(v.getDate());
                int numCommits = (v.getCommitList() == null) ? 0 : v.getCommitList().size();

                writer.write(index + "," +
                        escapeCsv(id) + "," +
                        escapeCsv(name) + "," +
                        escapeCsv(date) + "," +
                        numCommits);
                writer.newLine();
            }
        }
    }

    // ================================
    //            COMMITS
    // ================================
    private static void exportCommits(Path file,
                                      List<Version> versions,
                                      List<Ticket> tickets,
                                      Map<Version, Integer> exportIndexMap) throws IOException {

        try (BufferedWriter writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            writer.write("Hash,AuthorName,AuthorEmail,Date,VersionIndex,VersionName,TicketKeys,ShortMessage");
            writer.newLine();

            if (versions == null) {
                return;
            }

            // Per evitare duplicati nel caso un commit compaia in più liste
            Set<String> seenHashes = new HashSet<>();

            // Ordine per data versione (coerente con versions.csv)
            List<Version> sorted = new ArrayList<>();
            for (Version v : versions) {
                if (v != null) sorted.add(v);
            }
            sorted.sort(Comparator
                    .comparing(Version::getDate, Comparator.nullsLast(Comparator.naturalOrder()))
                    .thenComparing(v -> nullSafe(v.getName()))
                    .thenComparing(v -> nullSafe(v.getId())));

            for (Version v : sorted) {
                if (v.getCommitList() == null) continue;

                int vIdx = exportIndexOf(v, exportIndexMap);

                for (RevCommit c : v.getCommitList()) {
                    if (c == null) continue;
                    String hash = c.getName();
                    if (!seenHashes.add(hash)) {
                        continue;
                    }

                    PersonIdent author = c.getAuthorIdent();
                    String authorName = (author != null) ? nullSafe(author.getName()) : "";
                    String authorEmail = (author != null) ? nullSafe(author.getEmailAddress()) : "";

                    LocalDate commitDate = Instant.ofEpochSecond(c.getCommitTime())
                            .atZone(ZoneOffset.UTC)
                            .toLocalDate();

                    String date = formatDate(commitDate);
                    String ticketKeys = buildTicketKeysForCommit(c, tickets);
                    String shortMsg = nullSafe(c.getShortMessage());

                    writer.write(escapeCsv(hash) + "," +
                            escapeCsv(authorName) + "," +
                            escapeCsv(authorEmail) + "," +
                            escapeCsv(date) + "," +
                            vIdx + "," +
                            escapeCsv(nullSafe(v.getName())) + "," +
                            escapeCsv(ticketKeys) + "," +
                            escapeCsv(shortMsg));
                    writer.newLine();
                }
            }
        }
    }

    /**
     * Restituisce la lista di ticket key associati ad un commit,
     * unita con "|", es: "BOOKKEEPER-12|BOOKKEEPER-34".
     */
    private static String buildTicketKeysForCommit(RevCommit commit, List<Ticket> tickets) {
        if (commit == null || tickets == null) {
            return "";
        }

        List<String> keys = new ArrayList<>();
        for (Ticket t : tickets) {
            if (t == null || t.getAssociatedCommits() == null) continue;
            if (t.getAssociatedCommits().contains(commit)) {
                keys.add(nullSafe(t.getKey()));
            }
        }
        return keys.isEmpty() ? "" : String.join("|", keys);
    }

    // ================================
    //             TICKETS
    // ================================
    private static void exportTickets(Path file,
                                      List<Ticket> tickets,
                                      Map<Version, Integer> exportIndexMap) throws IOException {

        try (BufferedWriter writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            writer.write("Key,CreationDate,ResolutionDate," +
                    "OpeningVersionIndex,OpeningVersionName," +
                    "FixedVersionIndex,FixedVersionName," +
                    "InjectedVersionIndex,InjectedVersionName," +
                    "AffectedVersionIndices,AffectedVersionNames," +
                    "NumAssociatedCommits");
            writer.newLine();

            if (tickets == null) {
                return;
            }

            for (Ticket t : tickets) {
                if (t == null) continue;

                String key = nullSafe(t.getKey());
                String creationDate = formatDate(t.getCreationDate());
                String resolutionDate = formatDate(t.getResolutionDate());

                Version ov = t.getOpeningVersion();
                Version fv = t.getFixedVersion();
                Version iv = t.getInjectedVersion();

                int ovIndex = exportIndexOf(ov, exportIndexMap);
                String ovName = (ov != null) ? nullSafe(ov.getName()) : "";

                int fvIndex = exportIndexOf(fv, exportIndexMap);
                String fvName = (fv != null) ? nullSafe(fv.getName()) : "";

                int ivIndex = exportIndexOf(iv, exportIndexMap);
                String ivName = (iv != null) ? nullSafe(iv.getName()) : "";

                List<Version> affected = t.getAffectedVersions();
                String affectedIndices = "";
                String affectedNames = "";
                if (affected != null && !affected.isEmpty()) {
                    affectedIndices = affected.stream()
                            .filter(Objects::nonNull)
                            .map(v -> Integer.toString(exportIndexOf(v, exportIndexMap)))
                            .collect(Collectors.joining("|"));
                    affectedNames = affected.stream()
                            .filter(Objects::nonNull)
                            .map(v -> nullSafe(v.getName()))
                            .collect(Collectors.joining("|"));
                }

                int numAssociatedCommits = (t.getAssociatedCommits() == null)
                        ? 0
                        : t.getAssociatedCommits().size();

                writer.write(escapeCsv(key) + "," +
                        escapeCsv(creationDate) + "," +
                        escapeCsv(resolutionDate) + "," +
                        ovIndex + "," +
                        escapeCsv(ovName) + "," +
                        fvIndex + "," +
                        escapeCsv(fvName) + "," +
                        ivIndex + "," +
                        escapeCsv(ivName) + "," +
                        escapeCsv(affectedIndices) + "," +
                        escapeCsv(affectedNames) + "," +
                        numAssociatedCommits);
                writer.newLine();
            }
        }
    }

    // ================================
    //            DATASET
    // ================================
    /**
     * Manteniamo VersionIndex/VersionName solo come metadati CSV (non usati in WEKA).
     * Header (ordine reference):
     * VersionIndex,VersionName,MethodFQN,LOC,NumParameters,NumBranches,NestingDepth,NumCodeSmells,
     * NumLocalVariables,NumRevisions,NumAuthors,TotalStmtAdded,TotalStmtDeleted,MaxChurn,AvgChurn,
     * HasFixHistory,Buggy,BodyHash
     */
    private static void exportDataset(Path file,
                                      List<Method> methods,
                                      Map<Version, Integer> exportIndexMap) throws IOException {

        try (BufferedWriter writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {

            writer.write(String.join(",",
                    "VersionIndex",
                    "VersionName",
                    "MethodFQN",
                    "LOC",
                    "NumParameters",
                    "NumBranches",
                    "NestingDepth",
                    "NumCodeSmells",
                    "NumLocalVariables",
                    "NumRevisions",
                    "NumAuthors",
                    "TotalStmtAdded",
                    "TotalStmtDeleted",
                    "MaxChurn",
                    "AvgChurn",
                    "HasFixHistory",
                    "Buggy",
                    "BodyHash"
            ));
            writer.newLine();

            if (methods == null) {
                return;
            }

            // Ordine deterministico: exportIndex versione -> FQN
            List<Method> sorted = new ArrayList<>(methods);
            sorted.sort(Comparator
                    .comparingInt((Method m) -> {
                        int idx = exportIndexOf(m.getVersion(), exportIndexMap);
                        return (idx >= 0) ? idx : Integer.MAX_VALUE;
                    })
                    .thenComparing(m -> nullSafe(m.getFullyQualifiedName())));

            for (Method m : sorted) {
                if (m == null) continue;

                Version v = m.getVersion();
                int versionIndex = exportIndexOf(v, exportIndexMap);
                String versionName = (v != null) ? nullSafe(v.getName()) : "";

                String methodFqn = nullSafe(m.getFullyQualifiedName());
                String bodyHash = nullSafe(m.getBodyHash());

                Metrics metrics = m.getMetrics();

                int loc = 0;
                int numParams = 0;
                int numBranches = 0;
                int nesting = 0;
                int smells = 0;
                int numLocalVars = 0;

                int numRevisions = 0;
                int numAuthors = 0;
                int totalAdded = 0;
                int totalDeleted = 0;
                int maxChurn = 0;
                double avgChurn = 0.0;
                int hasFixHistory = 0;

                if (metrics != null) {
                    loc = metrics.getLoc();
                    numParams = metrics.getParameterCount();
                    numBranches = metrics.getNumBranches();
                    nesting = metrics.getNestingDepth();
                    smells = metrics.getNumCodeSmells();
                    numLocalVars = metrics.getNumLocalVariables();

                    numRevisions = metrics.getNumRevisions();
                    numAuthors = metrics.getNumAuthors();
                    totalAdded = metrics.getTotalStmtAdded();
                    totalDeleted = metrics.getTotalStmtDeleted();
                    maxChurn = metrics.getMaxChurn();
                    avgChurn = metrics.getAvgChurn();
                    hasFixHistory = metrics.getHasFixHistory();
                }

                String buggy = m.isBuggy() ? "yes" : "no";

                writer.write(
                        versionIndex + "," +
                                escapeCsv(versionName) + "," +
                                escapeCsv(methodFqn) + "," +
                                loc + "," +
                                numParams + "," +
                                numBranches + "," +
                                nesting + "," +
                                smells + "," +
                                numLocalVars + "," +
                                numRevisions + "," +
                                numAuthors + "," +
                                totalAdded + "," +
                                totalDeleted + "," +
                                maxChurn + "," +
                                avgChurn + "," +
                                hasFixHistory + "," +
                                buggy + "," +
                                escapeCsv(bodyHash)
                );
                writer.newLine();
            }
        }
    }

    // ================================
    //        HELPER / UTILITIES
    // ================================
    private static Map<Version, Integer> buildExportIndexMap(List<Version> versions) {
        IdentityHashMap<Version, Integer> map = new IdentityHashMap<>();
        if (versions == null) return map;

        List<Version> sorted = new ArrayList<>();
        for (Version v : versions) {
            if (v != null) sorted.add(v);
        }

        sorted.sort(Comparator
                .comparing(Version::getDate, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(v -> nullSafe(v.getName()))
                .thenComparing(v -> nullSafe(v.getId())));

        int idx = 1;
        for (Version v : sorted) {
            map.put(v, idx++);
        }
        return map;
    }

    /**
     * Ritorna l'indice contiguo (export) se la Version è presente nella lista esportata,
     * altrimenti -1 (così non produciamo riferimenti a indici inesistenti in versions.csv).
     */
    private static int exportIndexOf(Version v, Map<Version, Integer> exportIndexMap) {
        if (v == null || exportIndexMap == null) return -1;
        Integer mapped = exportIndexMap.get(v);
        return (mapped != null) ? mapped : -1;
    }

    private static String nullSafe(String s) {
        return (s == null) ? "" : s;
    }

    private static String formatDate(LocalDate date) {
        return (date == null) ? "" : DATE_FORMAT.format(date);
    }

    /**
     * Escape minimale per CSV:
     * - raddoppia i doppi apici
     * - racchiude tra doppi apici se contiene virgola, apici o newline
     */
    private static String escapeCsv(String value) {
        if (value == null) {
            return "";
        }
        boolean hasSpecial = value.contains("\"") ||
                value.contains(",") ||
                value.contains("\n") ||
                value.contains("\r");
        String escaped = value.replace("\"", "\"\"");
        if (hasSpecial) {
            return "\"" + escaped + "\"";
        }
        return escaped;
    }
    public static List<String> parseCsvLine(String line) {
        List<String> out = new ArrayList<>();
        if (line == null) return out;

        StringBuilder cur = new StringBuilder();
        boolean inQuotes = false;

        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);

            if (inQuotes) {
                if (ch == '"') {
                    // doppia virgoletta -> una virgoletta
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                        cur.append('"');
                        i++;
                    } else {
                        inQuotes = false;
                    }
                } else {
                    cur.append(ch);
                }
            } else {
                if (ch == '"') {
                    inQuotes = true;
                } else if (ch == ',') {
                    out.add(cur.toString());
                    cur.setLength(0);
                } else {
                    cur.append(ch);
                }
            }
        }

        out.add(cur.toString());
        return out;
    }
}
