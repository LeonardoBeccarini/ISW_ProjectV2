package org.example.controller;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseProblemException;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.ObjectLoader;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.example.model.Method;
import org.example.model.Ticket;
import org.example.model.Version;
import org.example.utilities.JiraUtils;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Recupera informazioni dal repository Git e calcola le metriche richieste
 * per ogni metodo Java in ogni Version.
 */
public class GitRetriever {

    private static final String JAVA_EXTENSION = ".java";
    private static final String TEST_DIR_FRAGMENT = "/test/";
    private static final double ANALYSIS_FRACTION = 0.5; // Prima porzione di release da usare per l'analisi

    // Regole di esclusione versioni (warning + skip)
    // - Se la versione non ha commit associati → warning + esclusione dall'analisi
    // - Se la versione produce un numero di metodi drasticamente inferiore alla baseline → warning + esclusione dall'analisi
    private static final int MIN_METHODS_ABS = 200;
    private static final int MIN_BASELINE_FOR_RATIO = 1000;
    private static final int BASELINE_WINDOW = 5;
    private static final double LOW_METHOD_RATIO = 0.20;

    private final String projectName;
    private final List<Version> versionList; // lista completa delle versioni
    private final List<Version> analysisVersionList = new ArrayList<>(); // sottoinsieme usato per l'analisi
    private final List<Ticket> ticketList;

    private final Repository repository;
    private final Git git;

    /** Parser tollerante (usa ParseResult invece di eccezioni). */
    private final JavaParser javaParser;

    /** Tutti i commit del repository, ordinati cronologicamente. */
    private final List<RevCommit> allCommits = new ArrayList<>();

    /** Mapping commitId -> Version (calcolato in associateCommitToVersion). */
    private final Map<String, Version> commitToVersion = new HashMap<>();

    /** Componente dedicata al calcolo delle metriche. */
    private final MetricsCalc metricsCalc;

    public GitRetriever(String projectName,
                        String repoUrl,
                        List<Version> versionList,
                        List<Ticket> ticketList) throws IOException, GitAPIException {

        this.projectName = projectName;
        this.versionList = (versionList != null) ? versionList : new ArrayList<>();
        this.ticketList = (ticketList != null) ? ticketList : new ArrayList<>();

        // Parser config piÃ¹ permissiva (riduce i casi di file scartati)
        ParserConfiguration parserConfiguration = new ParserConfiguration();
        parserConfiguration.setLanguageLevel(ParserConfiguration.LanguageLevel.BLEEDING_EDGE);

        StaticJavaParser.setConfiguration(parserConfiguration);
        this.javaParser = new JavaParser(parserConfiguration);

        String pathName = "repos/" + projectName.toLowerCase() + "Clone";
        File dir = new File(pathName);
        if (dir.exists()) {
            this.repository = new FileRepositoryBuilder()
                    .setGitDir(new File(dir, ".git"))
                    .build();
            this.git = new Git(repository);
        } else {
            this.git = Git.cloneRepository()
                    .setURI(repoUrl)
                    .setDirectory(dir)
                    .call();
            this.repository = git.getRepository();
        }

        // Inizializza il calcolatore di metriche con i riferimenti necessari
        this.metricsCalc = new MetricsCalc(this.repository, this.commitToVersion, this.ticketList);
    }

    /* =========================================================
       =                 COMMIT â†’ VERSION                       =
       ========================================================= */

    public void associateCommitToVersion() throws GitAPIException, IOException {
        if (versionList == null || versionList.isEmpty()) {
            return;
        }

        loadAllCommits();

        for (Version v : versionList) {
            if (v.getCommitList() != null) {
                v.getCommitList().clear();
            }
        }
        commitToVersion.clear();

        for (RevCommit c : allCommits) {
            LocalDate d = Instant.ofEpochSecond(c.getCommitTime())
                    .atZone(ZoneOffset.UTC)
                    .toLocalDate();
            Version v = JiraUtils.getReleaseAfterOrEqualDate(d, versionList);
            if (v != null) {
                if (v.getCommitList() != null) {
                    v.getCommitList().add(c);
                }
                commitToVersion.put(c.getName(), v);
            }
        }

        for (Version v : versionList) {
            if (v.getCommitList() != null) {
                v.getCommitList().sort(Comparator.comparingInt(RevCommit::getCommitTime));
            }
        }
    }

    private void prepareAnalysisVersionList() {
        analysisVersionList.clear();
        if (versionList == null || versionList.isEmpty()) return;

        // Trova l'indice massimo della fixed version tra i ticket (se presente).
        int maxFixedVersionIndex = ticketList.stream()
                .filter(Objects::nonNull)
                .map(Ticket::getFixedVersion)
                .filter(Objects::nonNull)
                .mapToInt(Version::getIndex)
                .max()
                .orElse(0);

        // Considera le versioni fino alla max fixed version (se definita), altrimenti tutte.
        List<Version> ordered = versionList.stream()
                .filter(Objects::nonNull)
                .filter(v -> maxFixedVersionIndex == 0 || v.getIndex() <= maxFixedVersionIndex)
                .sorted(Comparator.comparingInt(Version::getIndex))
                .collect(Collectors.toList());

        if (ordered.isEmpty()) {
            ordered = versionList.stream()
                    .filter(Objects::nonNull)
                    .sorted(Comparator.comparingInt(Version::getIndex))
                    .collect(Collectors.toList());
        }

        // Costruisce la lista dei candidati con commit, ma stampa warning per le versioni senza commit.
        List<Version> candidatesWithCommits = new ArrayList<>();
        for (Version v : ordered) {
            List<RevCommit> commits = (v != null) ? v.getCommitList() : null;
            if (commits == null || commits.isEmpty()) {
                warnSkipVersion(v, "0 commit associati alla release (baseline/milestone o bucket temporale vuoto)");
                continue;
            }
            candidatesWithCommits.add(v);
        }

        if (candidatesWithCommits.isEmpty()) {
            // Fallback: prova a prendere tutte le versioni con commit senza vincolo su maxFixedVersionIndex
            for (Version v : versionList.stream()
                    .filter(Objects::nonNull)
                    .sorted(Comparator.comparingInt(Version::getIndex))
                    .collect(Collectors.toList())) {

                List<RevCommit> commits = v.getCommitList();
                if (commits == null || commits.isEmpty()) {
                    warnSkipVersion(v, "0 commit associati alla release (fallback)");
                    continue;
                }
                candidatesWithCommits.add(v);
            }
        }

        int numToConsider = (int) Math.ceil(candidatesWithCommits.size() * GitRetriever.ANALYSIS_FRACTION);
        if (numToConsider == 0 && !candidatesWithCommits.isEmpty()) numToConsider = 1;

        analysisVersionList.addAll(candidatesWithCommits.subList(0, numToConsider));
    }

    /* =========================================================
       =                 COMMIT â†’ TICKET                        =
       ========================================================= */

    void associateCommitToTicket(List<Ticket> tickets) throws GitAPIException, IOException {
        if (tickets == null || tickets.isEmpty()) {
            return;
        }

        loadAllCommits();

        Map<String, Ticket> byKey = new LinkedHashMap<>();
        LocalDate minC = null, maxR = null;

        final int DATE_SLACK_DAYS = 3;

        for (Ticket t : tickets) {
            if (t == null) continue;
            String key = safeTicketKey(t);
            if (key == null || key.isBlank()) continue;

            byKey.put(key.toUpperCase(), t);

            if (t.getCreationDate() != null) {
                minC = (minC == null || t.getCreationDate().isBefore(minC))
                        ? t.getCreationDate()
                        : minC;
            }
            if (t.getResolutionDate() != null) {
                maxR = (maxR == null || t.getResolutionDate().isAfter(maxR))
                        ? t.getResolutionDate()
                        : maxR;
            }

            t.setAssociatedCommits(new ArrayList<>());
        }

        if (byKey.isEmpty()) {
            return;
        }

        Pattern pattern = Pattern.compile(projectName + "-\\d+", Pattern.CASE_INSENSITIVE);

        Iterable<RevCommit> log = git.log().add(repository.resolve("HEAD")).call();
        for (RevCommit c : log) {
            if (c.getParentCount() == 0) continue;

            String msg = Optional.ofNullable(c.getFullMessage()).orElse("");

            LocalDate d = Instant.ofEpochSecond(c.getCommitTime())
                    .atZone(ZoneOffset.UTC)
                    .toLocalDate();

            if (minC != null && d.isBefore(minC.minusDays(DATE_SLACK_DAYS))) continue;
            if (maxR != null && d.isAfter(maxR.plusDays(DATE_SLACK_DAYS))) continue;

            Matcher m = pattern.matcher(msg);
            while (m.find()) {
                String key = m.group().toUpperCase();
                Ticket t = byKey.get(key);
                if (t == null) continue;

                LocalDate cmin = t.getCreationDate();
                LocalDate cmax = t.getResolutionDate();

                if (cmin != null && d.isBefore(cmin.minusDays(DATE_SLACK_DAYS))) continue;
                if (cmax != null && d.isAfter(cmax.plusDays(DATE_SLACK_DAYS))) continue;

                t.getAssociatedCommits().add(c);
            }
        }
    }

    private String safeTicketKey(Ticket t) {
        return (t.getKey() != null) ? t.getKey().trim() : null;
    }

    /* =========================================================
       =              PIPELINE PRINCIPALE                       =
       ========================================================= */

    public List<Method> extractMethodsAndMetrics() throws IOException, GitAPIException {
        associateCommitToVersion();
        prepareAnalysisVersionList();
        associateCommitToTicket(this.ticketList);

        List<Method> allMethods = new ArrayList<>();
        Map<String, List<Method>> methodsByFqn = new HashMap<>();

        // Baseline dinamica: usiamo la mediana degli ultimi BASELINE_WINDOW conteggi accettati
        // per identificare versioni con un numero di metodi anomalo (troppo basso).
        List<Integer> acceptedMethodCounts = new ArrayList<>();

        for (Version version : analysisVersionList) {
            List<RevCommit> versionCommits = version.getCommitList();
            if (versionCommits == null || versionCommits.isEmpty()) {
                // In teoria già filtrato in prepareAnalysisVersionList(), ma manteniamo robustezza.
                warnSkipVersion(version, "0 commit associati alla release");
                continue;
            }

            // In associateCommitToVersion() la lista è già ordinata per commitTime.
            RevCommit snapshotCommit = versionCommits.get(versionCommits.size() - 1);

            // Estrazione in strutture temporanee: se la versione è outlier, non facciamo merge.
            List<Method> tmpMethods = new ArrayList<>();
            Map<String, List<Method>> tmpByFqn = new HashMap<>();

            try (TreeWalk treeWalk = new TreeWalk(repository)) {
                treeWalk.addTree(snapshotCommit.getTree());
                treeWalk.setRecursive(true);

                while (treeWalk.next()) {
                    String path = treeWalk.getPathString();
                    if (!path.endsWith(JAVA_EXTENSION) || path.contains(TEST_DIR_FRAGMENT)) {
                        continue;
                    }
                    processJavaFile(treeWalk, version, path, tmpMethods, tmpByFqn);
                }
            }

            int extractedCount = tmpMethods.size();
            if (shouldSkipByMethodCount(extractedCount, acceptedMethodCounts)) {
                String baselineStr = acceptedMethodCounts.isEmpty()
                        ? "n/a"
                        : String.valueOf(medianOfLast(acceptedMethodCounts, BASELINE_WINDOW));

                warnSkipVersion(
                        version,
                        String.format(
                                "metodi estratti=%d (baseline mediana=%s). Versione esclusa dall'analisi.",
                                extractedCount,
                                baselineStr
                        )
                );
                continue;
            }

            // Versione accettata: merge in strutture globali
            allMethods.addAll(tmpMethods);
            for (Map.Entry<String, List<Method>> e : tmpByFqn.entrySet()) {
                methodsByFqn
                        .computeIfAbsent(e.getKey(), k -> new ArrayList<>(e.getValue().size()))
                        .addAll(e.getValue());
            }

            acceptedMethodCounts.add(extractedCount);
        }

        // allCommits è già ordinata cronologicamente da loadAllCommits()
        metricsCalc.addProcessMetrics(allMethods, methodsByFqn, allCommits);

        metricsCalc.calculateHasFixHistory(allMethods);
        metricsCalc.setMethodBuggyness(allMethods);

        return allMethods;
    }

    /* =========================================================
       =        ESTRAZIONE METRICHE STATICHE PER FILE           =
       ========================================================= */

    private void processJavaFile(TreeWalk treeWalk,
                                 Version version,
                                 String filePath,
                                 List<Method> allMethods,
                                 Map<String, List<Method>> methodsByFqn) throws IOException {

        ObjectLoader loader = repository.open(treeWalk.getObjectId(0));
        String fileContent;
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            loader.copyTo(out);
            fileContent = out.toString(StandardCharsets.UTF_8);
        }

        fileContent = sanitizeFileContent(fileContent);

        Map<Integer, Integer> codeSmellsByLine = metricsCalc.calculateCodeSmellsByLine(fileContent);

        // Parsing tollerante: se c'Ã¨ un AST parziale lo usiamo comunque
        try {
            ParseResult<CompilationUnit> pr = javaParser.parse(fileContent);
            if (pr.getResult().isEmpty()) return;

            CompilationUnit cu = pr.getResult().get();

            // 1) Metodi
            for (MethodDeclaration md : cu.findAll(MethodDeclaration.class)) {
                String signature = metricsCalc.buildMethodSignature(md);
                String fqn = filePath + "/" + signature;

                Method method = new Method(fqn, version);
                metricsCalc.computeStaticMetricsForMethod(method, md, codeSmellsByLine);

                allMethods.add(method);
                methodsByFqn.computeIfAbsent(fqn, k -> new ArrayList<>()).add(method);
            }

            // 2) Costruttori (nuovo: prima erano persi)
            for (ConstructorDeclaration cd : cu.findAll(ConstructorDeclaration.class)) {
                String signature = metricsCalc.buildConstructorSignature(cd);
                String fqn = filePath + "/" + signature;

                Method ctor = new Method(fqn, version);
                metricsCalc.computeStaticMetricsForConstructor(ctor, cd, codeSmellsByLine);

                allMethods.add(ctor);
                methodsByFqn.computeIfAbsent(fqn, k -> new ArrayList<>()).add(ctor);
            }

        } catch (ParseProblemException | StackOverflowError e) {
            // Se proprio fallisce tutto, ignora il file (ma ora succede molto meno)
        }
    }

    private String sanitizeFileContent(String s) {
        if (s == null) return "";
        // BOM UTF-8
        if (!s.isEmpty() && s.charAt(0) == '\uFEFF') {
            s = s.substring(1);
        }
        // caratteri NUL (a volte presenti in file â€œsporchiâ€)
        s = s.replace("\u0000", "");
        return s;
    }

    /* =========================================================
       =   FILTRI ANALISI: WARNING + ESCLUSIONE VERSIONI        =
       ========================================================= */

    private void warnSkipVersion(Version v, String reason) {
        String name = (v != null && v.getName() != null) ? v.getName() : "<unknown>";
        int idx = (v != null) ? v.getIndex() : -1;
        System.err.printf("[WARN] [%s] Versione %d (%s) esclusa dall'analisi: %s%n",
                projectName, idx, name, reason);
    }

    private boolean shouldSkipByMethodCount(int extractedCount, List<Integer> acceptedCounts) {
        // Se non estraiamo nulla, è sempre un problema (versione vuota o snapshot non valido).
        if (extractedCount <= 0) return true;

        // Se non abbiamo ancora una baseline, evitiamo di scartare: non sappiamo "quanto" dovrebbe essere grande.
        if (acceptedCounts == null || acceptedCounts.isEmpty()) return false;

        int baseline = medianOfLast(acceptedCounts, BASELINE_WINDOW);

        // Applichiamo la regola relativa solo quando la baseline è sufficientemente grande.
        if (baseline < MIN_BASELINE_FOR_RATIO) return false;

        int threshold = Math.max(MIN_METHODS_ABS, (int) Math.floor(baseline * LOW_METHOD_RATIO));
        return extractedCount < threshold;
    }

    private int medianOfLast(List<Integer> values, int lastN) {
        if (values == null || values.isEmpty() || lastN <= 0) return 0;

        int n = Math.min(lastN, values.size());
        int start = values.size() - n;

        int[] buf = new int[n];
        for (int i = 0; i < n; i++) {
            buf[i] = values.get(start + i);
        }
        Arrays.sort(buf);

        if ((n & 1) == 1) {
            return buf[n / 2];
        }
        // n pari: media dei due centrali (int division ok)
        return (buf[(n / 2) - 1] + buf[n / 2]) / 2;
    }

    /* =========================================================
       =             UTILITY: LETTURA COMMIT                    =
       ========================================================= */

    private void loadAllCommits() throws GitAPIException, IOException {
        if (!allCommits.isEmpty()) {
            return;
        }
        Iterable<RevCommit> log = git.log().add(repository.resolve("HEAD")).call();
        for (RevCommit c : log) {
            allCommits.add(c);
        }
        allCommits.sort(Comparator.comparingInt(RevCommit::getCommitTime));
    }
}
