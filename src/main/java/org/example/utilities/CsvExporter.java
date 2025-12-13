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

        exportVersions(baseDir.resolve("versions.csv"), versions);
        exportCommits(baseDir.resolve("commits.csv"), versions, tickets);
        exportTickets(baseDir.resolve("tickets.csv"), tickets);
        exportDataset(baseDir.resolve("dataset.csv"), methods);
    }

    // ================================
    //            VERSIONS
    // ================================
    private static void exportVersions(Path file,
                                       List<Version> versions) throws IOException {

        try (BufferedWriter writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            writer.write("Index,Id,Name,Date,NumCommits");
            writer.newLine();

            if (versions == null) {
                return;
            }

            for (Version v : versions) {
                if (v == null) continue;

                int index = v.getIndex();
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
                                      List<Ticket> tickets) throws IOException {

        try (BufferedWriter writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            writer.write("Hash,AuthorName,AuthorEmail,Date,VersionIndex,VersionName,TicketKeys,ShortMessage");
            writer.newLine();

            if (versions == null) {
                return;
            }

            // Per evitare duplicati nel caso un commit compaia in più liste
            Set<String> seenHashes = new HashSet<>();

            for (Version v : versions) {
                if (v == null || v.getCommitList() == null) {
                    continue;
                }

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
                            v.getIndex() + "," +
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
                                      List<Ticket> tickets) throws IOException {

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

                int ovIndex = (ov != null) ? ov.getIndex() : -1;
                String ovName = (ov != null) ? nullSafe(ov.getName()) : "";

                int fvIndex = (fv != null) ? fv.getIndex() : -1;
                String fvName = (fv != null) ? nullSafe(fv.getName()) : "";

                int ivIndex = (iv != null) ? iv.getIndex() : -1;
                String ivName = (iv != null) ? nullSafe(iv.getName()) : "";

                List<Version> affected = t.getAffectedVersions();
                String affectedIndices = "";
                String affectedNames = "";
                if (affected != null && !affected.isEmpty()) {
                    affectedIndices = affected.stream()
                            .filter(Objects::nonNull)
                            .map(v -> Integer.toString(v.getIndex()))
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
                                      List<Method> methods) throws IOException {

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

            // Ordine deterministico: versione -> FQN
            List<Method> sorted = new ArrayList<>(methods);
            sorted.sort(Comparator
                    .comparingInt((Method m) -> (m.getVersion() != null) ? m.getVersion().getIndex() : Integer.MAX_VALUE)
                    .thenComparing(m -> nullSafe(m.getFullyQualifiedName())));

            for (Method m : sorted) {
                if (m == null) continue;

                Version v = m.getVersion();
                int versionIndex = (v != null) ? v.getIndex() : -1;
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
}
