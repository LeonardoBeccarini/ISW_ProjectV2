package org.example.controller;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.ConditionalExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.stmt.*;
import net.sourceforge.pmd.PMDConfiguration;
import net.sourceforge.pmd.PmdAnalysis;
import net.sourceforge.pmd.Report;
import net.sourceforge.pmd.RuleViolation;
import net.sourceforge.pmd.lang.LanguageRegistry;
import net.sourceforge.pmd.lang.LanguageVersion;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.diff.RawTextComparator;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectLoader;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.util.io.DisabledOutputStream;
import org.example.model.Method;
import org.example.model.Metrics;
import org.example.model.Ticket;
import org.example.model.Version;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * Calcolo metriche statiche e di processo.
 */
public class MetricsCalc {

    private static final Logger LOGGER = Logger.getLogger(MetricsCalc.class.getName());

    private static final String JAVA_EXTENSION = ".java";
    private static final String TEST_DIR_FRAGMENT = "/test/";

    private final Repository repository;
    private final Map<String, Version> commitToVersion;
    private final List<Ticket> ticketList;

    private final JavaParser javaParser;

    private static final String PMD_RULESET_PATH = "rulesets/java/quickstart.xml";
    private final LanguageVersion pmdJavaLv;

    public MetricsCalc(Repository repository,
                       Map<String, Version> commitToVersion,
                       List<Ticket> ticketList) {
        this.repository = repository;
        this.commitToVersion = commitToVersion;
        this.ticketList = (ticketList != null) ? ticketList : new ArrayList<>();

        ParserConfiguration parserConfig = new ParserConfiguration();
        parserConfig.setLanguageLevel(ParserConfiguration.LanguageLevel.BLEEDING_EDGE);
        this.javaParser = new JavaParser(parserConfig);

        this.pmdJavaLv = LanguageRegistry.findLanguageByTerseName("java").getVersion("11");
    }

    /* =========================================================
       =             FIRME (metodi + costruttori)               =
       ========================================================= */

    public String buildMethodSignature(MethodDeclaration md) {
        String owner = buildEnclosingTypeChain(md);
        String params = md.getParameters().stream()
                .map(p -> eraseGenericType(p.getType().asString()))
                .collect(Collectors.joining(","));
        return owner + "#" + md.getNameAsString() + "(" + params + ")";
    }

    public String buildConstructorSignature(ConstructorDeclaration cd) {
        String owner = buildEnclosingTypeChain(cd);
        String params = cd.getParameters().stream()
                .map(p -> eraseGenericType(p.getType().asString()))
                .collect(Collectors.joining(","));
        return owner + "#<init>(" + params + ")";
    }

    /* =========================================================
       =             METRICHE STATICHE (metodi)                 =
       ========================================================= */

    public void computeStaticMetricsForMethod(Method method,
                                              MethodDeclaration md,
                                              Map<Integer, Integer> codeSmellsByLine) {
        computeStaticMetricsForCallable(method, md, codeSmellsByLine);
    }

    public void computeStaticMetricsForConstructor(Method method,
                                                   ConstructorDeclaration cd,
                                                   Map<Integer, Integer> codeSmellsByLine) {
        computeStaticMetricsForCallable(method, cd, codeSmellsByLine);
    }

    private void computeStaticMetricsForCallable(Method method,
                                                 CallableDeclaration<?> cd,
                                                 Map<Integer, Integer> codeSmellsByLine) {
        Metrics metrics = method.getMetrics();

        int loc = calculateLOC(cd);
        int params = cd.getParameters().size();
        int branches = calculateNumBranches(cd);
        int cc = branches + 1;
        int nesting = calculateNestingDepth(cd);
        int localVars = calculateNumLocalVariables(cd);
        int codeSmells = estimateCodeSmellsForCallable(cd, codeSmellsByLine);

        metrics.setLoc(loc);
        metrics.setParameterCount(params);
        metrics.setNumBranches(branches);
        metrics.setCyclomaticComplexity(cc);
        metrics.setNestingDepth(nesting);
        metrics.setNumLocalVariables(localVars);
        metrics.setNumCodeSmells(codeSmells);

        method.setBodyHash(calculateBodyHash(cd));
    }

    private Optional<BlockStmt> getBody(CallableDeclaration<?> cd) {
        if (cd instanceof MethodDeclaration md) {
            return md.getBody();
        }
        if (cd instanceof ConstructorDeclaration c) {
            return Optional.ofNullable(c.getBody());
        }
        return Optional.empty();
    }

    private int calculateNumLocalVariables(CallableDeclaration<?> cd) {
        return getBody(cd)
                .map(body -> body.findAll(com.github.javaparser.ast.body.VariableDeclarator.class).size())
                .orElse(0);
    }

    /* =========================================================
       =                METRICHE DI PROCESSO                    =
       ========================================================= */

    public void addProcessMetrics(List<Method> allMethods,
                                  Map<String, List<Method>> methodsByFqn,
                                  List<RevCommit> sortedCommits) throws IOException {

        if (sortedCommits == null || sortedCommits.isEmpty()) {
            return;
        }

        for (RevCommit commit : sortedCommits) {
            if (commit.getParentCount() == 0) {
                continue;
            }

            RevCommit parent = commit.getParent(0);
            List<DiffEntry> diffs = getDiffEntries(parent, commit);

            Map<String, String> oldFileContents = getFileContents(diffs, true);
            Map<String, String> newFileContents = getFileContents(diffs, false);

            for (DiffEntry diff : diffs) {
                processDiffEntryForMetrics(diff, commit, methodsByFqn, oldFileContents, newFileContents);
            }
        }

        finalizeProcessMetrics(allMethods);
    }

    private void finalizeProcessMetrics(List<Method> allMethods) {
        for (Method m : allMethods) {
            Metrics metrics = m.getMetrics();
            computeNumAuthors(m, metrics);
            computeAvgChurn(metrics);
        }
    }

    private void computeNumAuthors(Method m, Metrics metrics) {
        if (m.getCommits().isEmpty()) {
            metrics.setNumAuthors(0);
            return;
        }
        Set<String> authors = m.getCommits().stream()
                .map(c -> c.getAuthorIdent().getName())
                .collect(Collectors.toSet());
        metrics.setNumAuthors(authors.size());
    }

    private void computeAvgChurn(Metrics metrics) {
        if (metrics.getNumRevisions() > 0) {
            double avg = (double) (metrics.getTotalStmtAdded() + metrics.getTotalStmtDeleted())
                    / (double) metrics.getNumRevisions();
            metrics.setAvgChurn(avg);
        } else {
            metrics.setAvgChurn(0.0);
        }
    }

    private void processDiffEntryForMetrics(DiffEntry diff,
                                            RevCommit commit,
                                            Map<String, List<Method>> methodsByFqn,
                                            Map<String, String> oldFileContents,
                                            Map<String, String> newFileContents) {

        String filePath = diff.getChangeType() == DiffEntry.ChangeType.DELETE
                ? diff.getOldPath()
                : diff.getNewPath();

        if (!isValidJavaFile(filePath)) {
            return;
        }

        Map<String, CallableDeclaration<?>> oldCallables = parseCallables(oldFileContents.getOrDefault(diff.getOldPath(), ""));
        Map<String, CallableDeclaration<?>> newCallables = parseCallables(newFileContents.getOrDefault(diff.getNewPath(), ""));

        for (Map.Entry<String, CallableDeclaration<?>> entry : newCallables.entrySet()) {
            String signature = entry.getKey();
            CallableDeclaration<?> newCd = entry.getValue();
            CallableDeclaration<?> oldCd = oldCallables.get(signature);

            String newBodyHash = calculateBodyHash(newCd);
            String oldBodyHash = (oldCd != null) ? calculateBodyHash(oldCd) : null;

            if (oldCd == null || !newBodyHash.equals(oldBodyHash)) {
                String fqn = filePath + "/" + signature;
                List<Method> methodsToUpdate = methodsByFqn.get(fqn);
                if (methodsToUpdate != null && !methodsToUpdate.isEmpty()) {
                    updateMethodMetricsForCommit(methodsToUpdate, commit, newCd, oldCd, newBodyHash);
                }
            }
        }
    }

    private boolean isValidJavaFile(String filePath) {
        return filePath != null
                && filePath.endsWith(JAVA_EXTENSION)
                && !filePath.contains(TEST_DIR_FRAGMENT);
    }

    private void updateMethodMetricsForCommit(List<Method> methodsToUpdate,
                                              RevCommit commit,
                                              CallableDeclaration<?> currentAst,
                                              CallableDeclaration<?> oldAst,
                                              String newBodyHash) {

        Version commitVersion = commitToVersion.get(commit.getName());
        if (commitVersion == null) {
            return;
        }
        int commitVersionIndex = commitVersion.getIndex();

        for (Method method : methodsToUpdate) {
            if (method.getVersion().getIndex() >= commitVersionIndex) {
                updateSingleMethodMetrics(method, commit, currentAst, oldAst, newBodyHash);
            }
        }
    }

    private void updateSingleMethodMetrics(Method method,
                                           RevCommit commit,
                                           CallableDeclaration<?> currentAst,
                                           CallableDeclaration<?> oldAst,
                                           String newBodyHash) {
        Metrics metrics = method.getMetrics();

        if (!method.getCommits().contains(commit)) {
            method.getCommits().add(commit);
            metrics.incrementNumRevisions();
        }

        method.setBodyHash(newBodyHash);

        int locNew = calculateLOC(currentAst);
        int locOld = (oldAst != null) ? calculateLOC(oldAst) : 0;

        ChurnResult churnResult = computeChurn(locNew, locOld, oldAst == null);
        metrics.addTotalStmtAdded(churnResult.added);
        metrics.addTotalStmtDeleted(churnResult.deleted);

        int churn = churnResult.added + churnResult.deleted;
        if (churn > metrics.getMaxChurn()) {
            metrics.setMaxChurn(churn);
        }
    }

    private record ChurnResult(int added, int deleted) {}

    private ChurnResult computeChurn(int locNew, int locOld, boolean isNewMethod) {
        if (isNewMethod) {
            return new ChurnResult(locNew, 0);
        }
        if (locNew > locOld) {
            return new ChurnResult(locNew - locOld, 0);
        }
        if (locOld > locNew) {
            return new ChurnResult(0, locOld - locNew);
        }
        return new ChurnResult(1, 1);
    }

    /* =========================================================
       =                  HAS FIX HISTORY                       =
       ========================================================= */

    public void calculateHasFixHistory(List<Method> allMethods) {
        if (ticketList == null || ticketList.isEmpty()) {
            return;
        }

        Map<String, Ticket> commitNameToTicket = buildCommitToTicketMap();

        for (Method method : allMethods) {
            checkMethodFixHistory(method, commitNameToTicket);
        }
    }

    private Map<String, Ticket> buildCommitToTicketMap() {
        Map<String, Ticket> commitNameToTicket = new HashMap<>();
        for (Ticket ticket : ticketList) {
            if (ticket.getAssociatedCommits() == null) {
                continue;
            }
            for (RevCommit commit : ticket.getAssociatedCommits()) {
                commitNameToTicket.put(commit.getName(), ticket);
            }
        }
        return commitNameToTicket;
    }

    private void checkMethodFixHistory(Method method, Map<String, Ticket> commitNameToTicket) {
        Metrics metrics = method.getMetrics();
        Version methodVersion = method.getVersion();

        for (RevCommit commit : method.getCommits()) {
            Ticket t = commitNameToTicket.get(commit.getName());
            if (t == null) {
                continue;
            }

            Version commitVersion = commitToVersion.get(commit.getName());
            if (commitVersion != null && commitVersion.getIndex() < methodVersion.getIndex()) {
                metrics.setHasFixHistory(1);
                return;
            }
        }
    }

    /* =========================================================
       =                     LABELING BUGGY                     =
       ========================================================= */

    public void setMethodBuggyness(List<Method> allProjectMethods) {
        if (ticketList == null || ticketList.isEmpty()) {
            return;
        }
        if (allProjectMethods == null || allProjectMethods.isEmpty()) {
            return;
        }

        for (Ticket ticket : ticketList) {
            processTicketForBuggyness(ticket, allProjectMethods);
        }
    }

    private void processTicketForBuggyness(Ticket ticket, List<Method> allProjectMethods) {
        Version injectedVersion = ticket.getInjectedVersion();
        if (injectedVersion == null) {
            return;
        }

        List<RevCommit> fixCommits = ticket.getAssociatedCommits();
        if (fixCommits == null || fixCommits.isEmpty()) {
            return;
        }

        for (RevCommit fixCommit : fixCommits) {
            processSingleFixCommit(fixCommit, injectedVersion, allProjectMethods);
        }
    }

    private void processSingleFixCommit(RevCommit fixCommit,
                                        Version injectedVersion,
                                        List<Method> allProjectMethods) {
        Version fixedVersion = commitToVersion.get(fixCommit.getName());
        if (fixedVersion == null) {
            return;
        }

        try {
            if (fixCommit.getParentCount() == 0) {
                return;
            }
            RevCommit parentOfFix = fixCommit.getParent(0);

            List<DiffEntry> diffs = getDiffEntries(parentOfFix, fixCommit);

            Map<String, String> newFileContentsInFix = getFileContents(diffs, false);
            Map<String, String> oldFileContentsInFix = getFileContents(diffs, true);

            for (DiffEntry diff : diffs) {
                processDiffForBuggyness(
                        diff,
                        newFileContentsInFix,
                        oldFileContentsInFix,
                        injectedVersion,
                        fixedVersion,
                        allProjectMethods
                );
            }
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Error parsing fix commit {0}: {1}",
                    new Object[]{fixCommit.getName(), e.getMessage()});
        }
    }

    private void processDiffForBuggyness(DiffEntry diff,
                                         Map<String, String> newFileContents,
                                         Map<String, String> oldFileContents,
                                         Version injectedVersion,
                                         Version fixedVersion,
                                         List<Method> allProjectMethods) {

        String filePath = diff.getNewPath();
        if (filePath == null || DiffEntry.DEV_NULL.equals(filePath)) {
            return;
        }

        if (!isValidJavaFile(filePath)) {
            return;
        }

        String newContent = newFileContents.getOrDefault(filePath, "");
        Map<String, MethodDeclaration> newMethodsInFix = parseMethodsForBuggyness(newContent);

        String oldContent = oldFileContents.getOrDefault(diff.getOldPath(), "");
        Map<String, MethodDeclaration> oldMethodsInFix = parseMethodsForBuggyness(oldContent);

        for (Map.Entry<String, MethodDeclaration> newMethodEntry : newMethodsInFix.entrySet()) {
            String signature = newMethodEntry.getKey();
            MethodDeclaration newMd = newMethodEntry.getValue();
            MethodDeclaration oldMd = oldMethodsInFix.get(signature);

            String newHash = calculateBodyHash(newMd);
            String oldHash = calculateBodyHash(oldMd);

            if (oldMd == null || !newHash.equals(oldHash)) {
                String fqn = filePath + "/" + signature;
                labelBuggyMethods(fqn, injectedVersion, fixedVersion, allProjectMethods);
            }
        }
    }

    private void labelBuggyMethods(String fixedMethodFQN,
                                   Version injectedVersion,
                                   Version fixedVersion,
                                   List<Method> allProjectMethods) {

        int iv = injectedVersion.getIndex();
        int fv = fixedVersion.getIndex();

        for (Method projectMethod : allProjectMethods) {
            if (projectMethod.getFullyQualifiedName().equals(fixedMethodFQN)) {
                int mid = projectMethod.getVersion().getIndex();
                if (mid >= iv && mid < fv) {
                    projectMethod.setBuggy(true);
                }
            }
        }
    }

    private Map<String, MethodDeclaration> parseMethodsForBuggyness(String content) {
        Map<String, MethodDeclaration> methods = new HashMap<>();
        if (content == null || content.isEmpty()) {
            return methods;
        }

        content = sanitizeFileContent(content);

        try {
            ParseResult<CompilationUnit> pr = javaParser.parse(content);
            pr.getResult().ifPresent(cu -> {
                for (MethodDeclaration md : cu.findAll(MethodDeclaration.class)) {
                    methods.put(buildMethodSignature(md), md);
                }
            });
        } catch (Exception _) {
            // reference-like: ignora errori di parsing
        }
        return methods;
    }

    /* =========================================================
       =             UTILITY: LETTURA DIFF/FILE                 =
       ========================================================= */

    private List<DiffEntry> getDiffEntries(RevCommit parent, RevCommit commit) throws IOException {
        try (DiffFormatter df = new DiffFormatter(DisabledOutputStream.INSTANCE)) {
            df.setRepository(repository);
            df.setDiffComparator(RawTextComparator.DEFAULT);
            df.setContext(0);
            df.setDetectRenames(true);
            return df.scan(parent.getTree(), commit.getTree());
        }
    }

    private Map<String, String> getFileContents(List<DiffEntry> diffs, boolean useOldPath) {
        Map<String, String> contents = new HashMap<>();
        try (ObjectReader reader = repository.newObjectReader()) {
            for (DiffEntry diff : diffs) {
                loadFileContent(diff, useOldPath, reader, contents);
            }
        }
        return contents;
    }

    private void loadFileContent(DiffEntry diff,
                                 boolean useOldPath,
                                 ObjectReader reader,
                                 Map<String, String> contents) {
        String path = useOldPath ? diff.getOldPath() : diff.getNewPath();
        ObjectId id = useOldPath ? diff.getOldId().toObjectId() : diff.getNewId().toObjectId();

        if (DiffEntry.DEV_NULL.equals(path)) {
            return;
        }

        try {
            ObjectLoader loader = reader.open(id);
            String txt = new String(loader.getBytes(), StandardCharsets.UTF_8);
            contents.put(path, sanitizeFileContent(txt));
        } catch (IOException _) {
            // oggetto mancante o errore I/O: ignora file
        }
    }

    private Map<String, CallableDeclaration<?>> parseCallables(String content) {
        Map<String, CallableDeclaration<?>> callables = new HashMap<>();
        if (content == null || content.isEmpty()) {
            return callables;
        }

        content = sanitizeFileContent(content);

        try {
            ParseResult<CompilationUnit> pr = javaParser.parse(content);
            pr.getResult().ifPresent(cu -> {
                for (MethodDeclaration md : cu.findAll(MethodDeclaration.class)) {
                    callables.put(buildMethodSignature(md), md);
                }
                for (ConstructorDeclaration cd : cu.findAll(ConstructorDeclaration.class)) {
                    callables.put(buildConstructorSignature(cd), cd);
                }
            });
        } catch (Exception _) {
            // parsing fallito: ignora file
        }
        return callables;
    }

    private String sanitizeFileContent(String s) {
        if (s == null) {
            return "";
        }
        if (!s.isEmpty() && s.charAt(0) == '\uFEFF') {
            s = s.substring(1);
        }
        return s.replace("\u0000", "");
    }

    /* =========================================================
       =             UTILITY: METRICHE STATICHE                 =
       ========================================================= */

    private int calculateLOC(CallableDeclaration<?> cd) {
        return getBody(cd)
                .map(this::countLinesOfCode)
                .orElse(0);
    }

    private int countLinesOfCode(BlockStmt body) {
        String[] lines = body.toString().split("\\r?\\n");
        boolean inMultiLineComment = false;
        int loc = 0;

        for (String line : lines) {
            String trimmed = line.trim();

            if (inMultiLineComment) {
                inMultiLineComment = !trimmed.contains("*/");
            } else if (trimmed.startsWith("/*")) {
                inMultiLineComment = !trimmed.contains("*/");
            } else if (!trimmed.isEmpty() && !trimmed.startsWith("//")) {
                loc++;
            }
        }
        return loc;
    }

    private int calculateNumBranches(CallableDeclaration<?> cd) {
        return getBody(cd)
                .map(this::countBranches)
                .orElse(0);
    }

    private int countBranches(BlockStmt body) {
        int count = 0;
        count += body.findAll(IfStmt.class).size();
        count += body.findAll(ForStmt.class).size();
        count += body.findAll(ForEachStmt.class).size();
        count += body.findAll(WhileStmt.class).size();
        count += body.findAll(DoStmt.class).size();
        count += body.findAll(SwitchEntry.class).size();
        count += body.findAll(CatchClause.class).size();
        count += body.findAll(ConditionalExpr.class).size();
        return count;
    }

    private int calculateNestingDepth(CallableDeclaration<?> cd) {
        return getBody(cd)
                .map(body -> calculateNestingDepth(body, 0))
                .orElse(0);
    }

    private int calculateNestingDepth(Node node, int currentDepth) {
        int maxDepth = currentDepth;
        for (Node child : node.getChildNodes()) {
            int nextDepth = isNestingNode(child) ? currentDepth + 1 : currentDepth;
            int childDepth = calculateNestingDepth(child, nextDepth);
            maxDepth = Math.max(maxDepth, childDepth);
        }
        return maxDepth;
    }

    private boolean isNestingNode(Node node) {
        return node instanceof IfStmt
                || node instanceof ForStmt
                || node instanceof ForEachStmt
                || node instanceof WhileStmt
                || node instanceof DoStmt
                || node instanceof SwitchStmt
                || node instanceof TryStmt;
    }

    private String calculateBodyHash(CallableDeclaration<?> cd) {
        if (cd == null) {
            return "NULL_METHOD_HASH";
        }

        return getBody(cd)
                .map(this::computeHashFromBody)
                .orElse("NULL_METHOD_HASH");
    }

    private String computeHashFromBody(BlockStmt body) {
        String normalizedBody = normalizeBodyForHash(body);
        if (normalizedBody.isEmpty()) {
            return "EMPTY_BODY_HASH";
        }
        return computeSha256Hash(normalizedBody);
    }

    private String normalizeBodyForHash(BlockStmt body) {
        String bodyStr = body.toString();
        bodyStr = bodyStr.replaceAll("//.*|/\\*[\\s\\S]*?\\*/", "");
        return bodyStr.replaceAll("\\s+", " ").trim();
    }

    private String computeSha256Hash(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] encoded = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(2 * encoded.length);
            for (byte b : encoded) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) {
                    sb.append('0');
                }
                sb.append(hex);
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 non disponibile", e);
        }
    }

    /* =========================================================
       =             PMD CODE SMELLS (PMD 6.55)                 =
       ========================================================= */

    public Map<Integer, Integer> calculateCodeSmellsByLine(String fileContent) {
        Map<Integer, Integer> result = new HashMap<>();
        if (fileContent == null || fileContent.isBlank()) {
            return result;
        }

        PMDConfiguration cfg = new PMDConfiguration();
        cfg.setDefaultLanguageVersion(pmdJavaLv);
        cfg.setFailOnViolation(false);
        cfg.setThreads(1);
        cfg.addRuleSet(PMD_RULESET_PATH);

        try (PmdAnalysis pmd = PmdAnalysis.create(cfg)) {
            pmd.files().addSourceFile(fileContent, "Analysis.java");

            Report report = pmd.performAnalysisAndCollectReport();
            for (RuleViolation rv : report.getViolations()) {
                result.merge(rv.getBeginLine(), 1, Integer::sum);
            }
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Errore PMD: {0}", e.getMessage());
        }

        return result;
    }

    private int estimateCodeSmellsForCallable(CallableDeclaration<?> cd,
                                              Map<Integer, Integer> codeSmellsByLine) {
        if (codeSmellsByLine.isEmpty()) {
            return 0;
        }
        int begin = cd.getBegin().map(p -> p.line).orElse(-1);
        int end = cd.getEnd().map(p -> p.line).orElse(-1);
        if (begin < 0 || end < begin) {
            return 0;
        }

        int count = 0;
        for (Map.Entry<Integer, Integer> e : codeSmellsByLine.entrySet()) {
            int line = e.getKey();
            if (line >= begin && line <= end) {
                count += e.getValue();
            }
        }
        return count;
    }

    /* =========================================================
       =             UTILITY PER LA FIRMA UNIVOCA               =
       ========================================================= */

    private String buildEnclosingTypeChain(Node start) {
        List<String> parts = new ArrayList<>();

        Node n = start;
        while (n != null) {
            extractTypeName(n, parts);
            n = n.getParentNode().orElse(null);
        }

        Collections.reverse(parts);

        if (parts.isEmpty()) {
            return "<unknownType>";
        }
        return String.join("$", parts);
    }

    private void extractTypeName(Node n, List<String> parts) {
        if (n instanceof com.github.javaparser.ast.body.ClassOrInterfaceDeclaration c) {
            parts.add(c.getNameAsString());
        } else if (n instanceof com.github.javaparser.ast.body.EnumDeclaration e) {
            parts.add(e.getNameAsString());
        } else if (n instanceof com.github.javaparser.ast.body.RecordDeclaration r) {
            parts.add(r.getNameAsString());
        } else if (n instanceof com.github.javaparser.ast.body.AnnotationDeclaration a) {
            parts.add(a.getNameAsString());
        } else if (n instanceof ObjectCreationExpr oce && oce.getAnonymousClassBody().isPresent()) {
            parts.add(buildAnonymousQualifier(oce));
        }
    }

    private String buildAnonymousQualifier(ObjectCreationExpr oce) {
        String typeName = eraseGenericType(oce.getType().asString());
        String ctx = resolveAnonymousContext(oce);
        return "<anon:" + typeName + "@" + ctx + ">";
    }

    private String resolveAnonymousContext(ObjectCreationExpr oce) {
        Node parent = oce.getParentNode().orElse(null);
        String ctx = extractContextFromParent(parent, oce);

        ctx = ctx.replaceAll("[^a-zA-Z0-9_.$-]", "_");
        if (ctx.length() > 40) {
            ctx = ctx.substring(0, 40);
        }
        return ctx;
    }

    private String extractContextFromParent(Node parent, ObjectCreationExpr oce) {
        return switch (parent) {
            case com.github.javaparser.ast.body.VariableDeclarator vd -> vd.getNameAsString();
            case com.github.javaparser.ast.expr.AssignExpr ae -> ae.getTarget().toString();
            case com.github.javaparser.ast.body.FieldDeclaration fd when !fd.getVariables().isEmpty() ->
                    fd.getVariable(0).getNameAsString();
            case com.github.javaparser.ast.expr.MethodCallExpr mce -> "arg" + mce.getArguments().indexOf(oce);
            case ObjectCreationExpr oce2 -> "arg" + oce2.getArguments().indexOf(oce);
            case null, default -> buildPositionContext(oce);
        };
    }

    private String buildPositionContext(ObjectCreationExpr oce) {
        int line = oce.getBegin().map(p -> p.line).orElse(-1);
        int col = oce.getBegin().map(p -> p.column).orElse(-1);
        return "pos" + line + "_" + col;
    }

    private String eraseGenericType(String typeStr) {
        if (typeStr == null) {
            return "";
        }

        String s = typeStr.replaceAll("\\s+", "");
        StringBuilder out = new StringBuilder(s.length());

        int depth = 0;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == '<') {
                depth++;
            } else if (ch == '>') {
                depth = Math.max(0, depth - 1);
            } else if (depth == 0) {
                out.append(ch);
            }
        }

        return out.toString().replace("...", "[]");
    }
}