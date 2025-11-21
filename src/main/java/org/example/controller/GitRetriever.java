package org.example.controller;

import com.github.javaparser.ParseProblemException;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
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
 *
 * Metriche calcolate:
 *  - LOC
 *  - Cyclomatic complexity (decision points + 1)
 *  - Nesting depth
 *  - Numero di code smells (via PMD)
 *  - Parameter count
 *  - Authors
 *  - maxChurn
 *  - AvgChurn
 *  - totalStmtAdded / totalStmtDeleted
 *  - hasFixHistory
 *  - buggy (labeling basato su IV/FV e commit di fix)
 */
public class GitRetriever {

    private static final String JAVA_EXTENSION = ".java";
    private static final String TEST_DIR_FRAGMENT = "/test/";
    private static final double ANALYSIS_FRACTION = 0.5; // Prima porzione di release da usare per l'analisi

    private final String projectName;
    private final List<Version> versionList; // lista completa delle versioni
    private final List<Version> analysisVersionList = new ArrayList<>(); // sottoinsieme usato per l'analisi
    private final List<Ticket> ticketList;

    private final Repository repository;
    private final Git git;

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

        ParserConfiguration parserConfiguration = new ParserConfiguration();
        StaticJavaParser.setConfiguration(parserConfiguration);

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
       =                 COMMIT → VERSION                       =
       ========================================================= */

    /**
     * Popola per ogni Version la commitList e la mappa commitToVersion.
     */
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

    /**
     * Popola {@code analysisVersionList} selezionando solo la prima porzione
     * (in termini di indice/versione) delle release che hanno almeno un commit.
     * L'idea è la stessa di {@code setReleaseListForAnalysis} in {@code GitDataExtractor},
     * ma applicata al modello {@link Version} di questo progetto.
     */
    private void prepareAnalysisVersionList(double fraction) {
        analysisVersionList.clear();
        if (versionList == null || versionList.isEmpty()) {
            return;
        }

        // Consideriamo solo le versioni che hanno effettivamente dei commit associati
        List<Version> candidates = versionList.stream()
                .filter(v -> v.getCommitList() != null && !v.getCommitList().isEmpty())
                .sorted(Comparator.comparingInt(Version::getIndex))
                .collect(Collectors.toList());

        if (candidates.isEmpty()) {
            return;
        }

        int numToConsider = (int) Math.ceil(candidates.size() * fraction);
        if (numToConsider == 0 && !candidates.isEmpty()) {
            numToConsider = 1;
        }

        analysisVersionList.addAll(candidates.subList(0, numToConsider));
    }

    /* =========================================================
       =                 COMMIT → TICKET                        =
       ========================================================= */

    /**
     * Associa i commit ai ticket cercando pattern PROJECT-XXX
     * nei messaggi di commit e rispettando le date del ticket.
     */
    public void associateCommitToTicket(List<Ticket> tickets) throws GitAPIException, IOException {
        if (tickets == null || tickets.isEmpty()) {
            return;
        }

        loadAllCommits();

        Map<String, Ticket> byKey = new LinkedHashMap<>();
        LocalDate minC = null, maxR = null;

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
            // resetta la lista dei commit associati
            t.setAssociatedCommits(new ArrayList<>());
        }

        if (byKey.isEmpty()) {
            return;
        }

        Pattern pattern = Pattern.compile(projectName + "-\\d+", Pattern.CASE_INSENSITIVE);

        Iterable<RevCommit> log = git.log().add(repository.resolve("HEAD")).call();
        for (RevCommit c : log) {
            if (c.getParentCount() == 0) continue;          // root
            String msg = Optional.ofNullable(c.getFullMessage()).orElse("");
            if (msg.startsWith("Merge")) continue;          // salta i merge

            LocalDate d = Instant.ofEpochSecond(c.getCommitTime())
                    .atZone(ZoneOffset.UTC)
                    .toLocalDate();
            if (minC != null && d.isBefore(minC)) continue;
            if (maxR != null && d.isAfter(maxR)) continue;

            Matcher m = pattern.matcher(msg);
            while (m.find()) {
                String key = m.group().toUpperCase();
                Ticket t = byKey.get(key);
                if (t == null) continue;

                LocalDate cmin = t.getCreationDate();
                LocalDate cmax = t.getResolutionDate();
                if (cmin != null && d.isBefore(cmin)) continue;
                if (cmax != null && d.isAfter(cmax)) continue;

                t.getAssociatedCommits().add(c);
            }
        }
        //elimino ticket senza commit
        tickets.removeIf(t ->
                t.getAssociatedCommits() == null ||
                        t.getAssociatedCommits().isEmpty()
        );
    }

    private String safeTicketKey(Ticket t) {
        return (t.getKey() != null) ? t.getKey().trim() : null;
    }

    /* =========================================================
       =              PIPELINE PRINCIPALE                       =
       ========================================================= */

    /**
     * Esegue l'intera pipeline:
     *  - associa commit → version e commit → ticket
     *  - estrae tutti i metodi Java dalle versioni
     *  - calcola metriche statiche
     *  - calcola metriche di processo (churn, autori, revisioni)
     *  - calcola hasFixHistory
     *  - esegue il labeling buggy
     *
     * @return lista dei Method con Metrics e label popolati
     */
    public List<Method> extractMethodsAndMetrics() throws IOException, GitAPIException {
        // Associa commit -> versioni (su tutte le release disponibili)
        associateCommitToVersion();

        // Seleziona solo la prima porzione di release per l'analisi (es. 50%)
        prepareAnalysisVersionList(ANALYSIS_FRACTION);

        // Associa i commit ai ticket (usa l'intera storia del repository)
        associateCommitToTicket(this.ticketList);

        List<Method> allMethods = new ArrayList<>();
        Map<String, List<Method>> methodsByFqn = new HashMap<>();

        // Estrazione metodi e metriche statiche solo per le versioni selezionate
        for (Version version : analysisVersionList) {
            List<RevCommit> versionCommits = version.getCommitList();
            if (versionCommits == null || versionCommits.isEmpty()) {
                continue;
            }

            versionCommits.sort(Comparator.comparingInt(RevCommit::getCommitTime));
            RevCommit lastCommit = versionCommits.get(versionCommits.size() - 1);

            try (TreeWalk treeWalk = new TreeWalk(repository)) {
                treeWalk.addTree(lastCommit.getTree());
                treeWalk.setRecursive(true);

                while (treeWalk.next()) {
                    String path = treeWalk.getPathString();
                    if (path.endsWith(JAVA_EXTENSION) && !path.contains(TEST_DIR_FRAGMENT)) {
                        processJavaFile(treeWalk, version, path, allMethods, methodsByFqn);
                    }
                }
            }
        }

        // Metriche di processo (churn, autori, ecc.) sui commit già caricati
        List<RevCommit> sortedCommits = new ArrayList<>(allCommits);
        sortedCommits.sort(Comparator.comparingInt(RevCommit::getCommitTime));
        metricsCalc.addProcessMetrics(allMethods, methodsByFqn, sortedCommits);

        // hasFixHistory
        metricsCalc.calculateHasFixHistory(allMethods);

        // labeling buggy
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

        Map<Integer, Integer> codeSmellsByLine = metricsCalc.calculateCodeSmellsByLine(fileContent);

        try {
            CompilationUnit cu = StaticJavaParser.parse(fileContent);
            for (MethodDeclaration md : cu.findAll(MethodDeclaration.class)) {
                String signature = metricsCalc.buildMethodSignature(md);
                String fqn = filePath + "/" + signature;

                Method method = new Method(fqn, version);
                metricsCalc.computeStaticMetricsForMethod(method, md, codeSmellsByLine);

                allMethods.add(method);
                methodsByFqn.computeIfAbsent(fqn, k -> new ArrayList<>()).add(method);
            }
        } catch (ParseProblemException | StackOverflowError e) {
            // file non parsabile, lo ignoriamo
        }
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
