package org.example.controller;

import org.example.model.ClassifierEvaluation;
import weka.classifiers.Classifier;
import weka.classifiers.rules.ZeroR;
import weka.core.*;
import weka.core.converters.CSVLoader;
import weka.core.converters.CSVSaver;
import weka.filters.Filter;
import weka.filters.unsupervised.attribute.Normalize;
import weka.filters.unsupervised.attribute.Remove;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * What-If analysis (method-level) allineata al report:
 * - ExpectedDefectsSum(X) = Σ P(buggy="yes") su X
 * - Riduzione attesa = ExpectedDefectsSum(B+) - ExpectedDefectsSum(B)
 * <p>
 * Patch principali:
 * 1) Parser CSV eval robusto (MODEL/BALANCING... + legacy Classifier/Sampling...)
 * 2) Niente clamp a 1.0 sul refactor factor (AFTER/BEFORE)
 * 3) Aggiunta metrica paper-like: EstimatedBuggy_Classify (count via classifyInstance)
 * 4) Fallback per B+ degenerato (vuoto o =A_latest): top-k per AFeature
 */
public class WhatIfAnalysis {

    private static final String COL_VERSION_INDEX = "VersionIndex";
    private static final String COL_VERSION_NAME = "VersionName";
    private static final String COL_METHOD_FQN = "MethodFQN";
    private static final String COL_BODY_HASH = "BodyHash";
    private static final String COL_BUGGY = "Buggy";

    private static final double DEFAULT_TOP_QUANTILE_FOR_BPLUS = 0.75; // top 25%
    private static final double EPS = 1e-9;

    private final String projectName;          // expected UPPERCASE in your project
    private final String actionableFeature;    // AFeature
    private final String baseMethodName;       // AFMethod base name (used by RefactorAnalyzer for refactor_metrics_<M>.csv)

    public WhatIfAnalysis(String projectNameUpperCase, String actionableFeature, String baseMethodName) {
        this.projectName = Objects.requireNonNull(projectNameUpperCase, "projectNameUpperCase").trim().toUpperCase();
        this.actionableFeature = Objects.requireNonNull(actionableFeature, "actionableFeature").trim();
        this.baseMethodName = Objects.requireNonNull(baseMethodName, "baseMethodName").trim();
        if (this.actionableFeature.isBlank()) throw new IllegalArgumentException("actionableFeature is blank");
        if (this.baseMethodName.isBlank()) throw new IllegalArgumentException("baseMethodName is blank");
    }

    public void execute() throws Exception {
        Path projectCsvDir = Paths.get("output", "csv", projectName);
        Path datasetPath = projectCsvDir.resolve("dataset.csv");
        Path evalPath = projectCsvDir.resolve("weka_walkforward.csv");
        Path refPath = projectCsvDir.resolve("refactor_metrics_" + baseMethodName + ".csv");

        if (!Files.exists(datasetPath)) throw new FileNotFoundException("Missing dataset: " + datasetPath);
        if (!Files.exists(evalPath)) throw new FileNotFoundException("Missing evaluations: " + evalPath);
        if (!Files.exists(refPath)) throw new FileNotFoundException("Missing refactor metrics: " + refPath);

        // --- Load raw dataset (keeps VersionIndex for temporal split)
        Instances raw = loadCsvAsInstances(datasetPath);
        if (raw.isEmpty()) throw new IllegalStateException("Dataset is empty: " + datasetPath);

        Attribute versionAttr = raw.attribute(COL_VERSION_INDEX);
        Attribute buggyAttr = raw.attribute(COL_BUGGY);
        if (versionAttr == null) throw new IllegalArgumentException("Dataset missing column: " + COL_VERSION_INDEX);
        if (buggyAttr == null) throw new IllegalArgumentException("Dataset missing column: " + COL_BUGGY);

        int latestVersion = findMaxInt(raw, versionAttr);
        if (latestVersion < 2)
            throw new IllegalStateException("Not enough releases for what-if (latestVersion=" + latestVersion + ")");

        // Temporal split: train = all releases < latest, test(A_latest) = latest
        Instances trainRaw = new Instances(raw, 0);
        Instances testRaw = new Instances(raw, 0);
        for (int i = 0; i < raw.numInstances(); i++) {
            Instance inst = raw.instance(i);
            int v = (int) Math.round(inst.value(versionAttr));
            if (v < latestVersion) trainRaw.add(inst);
            else if (v == latestVersion) testRaw.add(inst);
        }
        if (trainRaw.isEmpty() || testRaw.isEmpty()) {
            throw new IllegalStateException("Temporal split produced empty train or test: train=" + trainRaw.size() + ", test=" + testRaw.size());
        }

        // Remove meta columns (no leakage / no identifiers)
        Remove rm = buildRemoveMetaFilter(trainRaw);
        Instances train = Filter.useFilter(trainRaw, rm);

        Remove rmCopy = (Remove) Filter.makeCopy(rm);
        rmCopy.setInputFormat(testRaw);
        Instances test = Filter.useFilter(testRaw, rmCopy);

        // Ensure class is last and set classIndex
        ensureClassIsLastAndSet(train, COL_BUGGY);
        ensureClassIsLastAndSet(test, COL_BUGGY);

        // Read refactor delta (BEFORE vs AFTER) for actionableFeature
        RefactorDelta delta = readRefactorDelta(refPath, actionableFeature);

        // Build B+, B, C from TEST (latest release)
        Attribute aFeatAttr = test.attribute(actionableFeature);
        if (aFeatAttr == null)
            throw new IllegalArgumentException("Actionable feature not found in dataset: " + actionableFeature);
        if (!aFeatAttr.isNumeric())
            throw new IllegalArgumentException("Actionable feature is not numeric: " + actionableFeature);

        int aFeatIndex = aFeatAttr.index();

        // Primary rule: threshold-based (quantile or NumCodeSmells>0 special case)
        double threshold = chooseBPlusThreshold(test, actionableFeature, DEFAULT_TOP_QUANTILE_FOR_BPLUS);

        Instances bPlus = new Instances(test, 0);
        Instances c = new Instances(test, 0);

        for (int i = 0; i < test.numInstances(); i++) {
            Instance src = test.instance(i);
            double v = src.value(aFeatIndex);
            boolean inBPlus = isInBPlus(actionableFeature, v, threshold);
            if (inBPlus) bPlus.add((Instance) src.copy());
            else c.add((Instance) src.copy());
        }

        // Fallback: avoid degenerate B+ (empty or equals all)
        if (bPlus.isEmpty() || bPlus.size() == test.size()) {
            SplitBC split = splitByTopK(test, aFeatIndex, DEFAULT_TOP_QUANTILE_FOR_BPLUS);
            bPlus = split.bPlus;
            c = split.c;
            threshold = split.effectiveThreshold;
        }

        // B = copy of B+ with AFeature transformed using observed factor
        Instances b = new Instances(bPlus);
        applyWhatIfTransformation(b, aFeatIndex, delta);

        // Normalize (fit on train, apply to others) - same pattern as WekaProcessor (no leakage)
        Normalize norm = new Normalize();
        norm.setInputFormat(train);

        Instances trainN = applyTrainedFilter(norm, train);

        Filter normCopyA = Filter.makeCopy(norm);
        Instances testN = applyTrainedFilter(normCopyA, test);

        Filter normCopyBPlus = Filter.makeCopy(norm);
        Instances bPlusN = applyTrainedFilter(normCopyBPlus, bPlus);

        Filter normCopyB = Filter.makeCopy(norm);
        Instances bN = applyTrainedFilter(normCopyB, b);

        Filter normCopyC = Filter.makeCopy(norm);
        Instances cN = applyTrainedFilter(normCopyC, c);

        // Load evaluations and pick best spec (BClassifier)
        List<ClassifierEvaluation> evals = readEvaluationsCsvProjectFormat(projectName, evalPath);
        WekaProcessor.ModelSpec best = WekaProcessor.pickBestSpec(evals);

        WekaProcessor wp = new WekaProcessor(projectName, Collections.emptyList());
        Classifier model = buildSafeClassifier(wp, trainN, best);

        int posIndex = positiveClassIndex(trainN, "yes");

        // Evaluate datasets
        Result rA = evaluateDataset("A_latest", testN, model, posIndex);
        Result rBPlus = evaluateDataset("B_plus", bPlusN, model, posIndex);
        Result rB = evaluateDataset("B", bN, model, posIndex);
        Result rC = evaluateDataset("C", cN, model, posIndex);

        // Probabilistic reduction (report-style)
        double deltaExpectedProb = rBPlus.expectedDefectsSum - rB.expectedDefectsSum;
        double relOnBPlusProb = (rBPlus.expectedDefectsSum > EPS) ? (deltaExpectedProb / rBPlus.expectedDefectsSum) : 0.0;
        double relOnAProb = (rA.expectedDefectsSum > EPS) ? (deltaExpectedProb / rA.expectedDefectsSum) : 0.0;

        // Paper-like reduction (count predicted buggy via classifyInstance)
        int deltaEstimatedClassify = rBPlus.estimatedBuggyClassify - rB.estimatedBuggyClassify;

        // Output dir
        Path outDir = projectCsvDir.resolve(Paths.get("whatif", sanitize(actionableFeature) + "_" + sanitize(baseMethodName)));
        Files.createDirectories(outDir);

        // Save intermediate datasets (for inspection)
        saveInstancesAsCsv(testN, outDir.resolve("A_latest.csv"));
        saveInstancesAsCsv(bPlusN, outDir.resolve("B_plus.csv"));
        saveInstancesAsCsv(bN, outDir.resolve("B.csv"));
        saveInstancesAsCsv(cN, outDir.resolve("C.csv"));

        // Save results
        Path resCsv = outDir.resolve("whatif_results.csv");
        writeResultsCsv(resCsv, projectName, actionableFeature, baseMethodName, best, delta, threshold,
                rA, rBPlus, rB, rC,
                deltaExpectedProb, relOnBPlusProb, relOnAProb,
                deltaEstimatedClassify);

        // Console summary
        System.out.println("\n=== WHAT-IF ANALYSIS (latest release) ===");
        System.out.println("Project: " + projectName);
        System.out.println("AFeature: " + actionableFeature);
        System.out.println("AFMethod: " + baseMethodName);
        System.out.println("Latest VersionIndex: " + latestVersion);
        System.out.println("BClassifier(best spec): " + best);

        System.out.printf(Locale.US, "Refactor delta on AFeature: BEFORE=%.3f AFTER=%.3f factor=%.3f%n",
                delta.before, delta.after, delta.factor);

        System.out.printf(Locale.US, "B+ threshold: %.3f (rule: %s)%n", threshold, ruleDescription(actionableFeature));

        System.out.println("\nReport-style (probabilities): ExpectedDefectsSum = Σ P(yes)");
        System.out.printf(Locale.US, "A_latest: %.3f (n=%d)%n", rA.expectedDefectsSum, rA.n);
        System.out.printf(Locale.US, "B_plus:   %.3f (n=%d)%n", rBPlus.expectedDefectsSum, rBPlus.n);
        System.out.printf(Locale.US, "B:        %.3f (n=%d)%n", rB.expectedDefectsSum, rB.n);
        System.out.printf(Locale.US, "C:        %.3f (n=%d)%n", rC.expectedDefectsSum, rC.n);

        System.out.println("\nPaper-like (counts): EstimatedBuggy_Classify = count(classifyInstance==yes)");
        System.out.printf(Locale.US, "A_latest: %d%n", rA.estimatedBuggyClassify);
        System.out.printf(Locale.US, "B_plus:   %d%n", rBPlus.estimatedBuggyClassify);
        System.out.printf(Locale.US, "B:        %d%n", rB.estimatedBuggyClassify);
        System.out.printf(Locale.US, "C:        %d%n", rC.estimatedBuggyClassify);

        System.out.println("\nReductions:");
        System.out.printf(Locale.US, "Δprob(B+->B): %.3f (rel on B+: %.2f%%, rel on A_latest: %.2f%%)%n",
                deltaExpectedProb, relOnBPlusProb * 100.0, relOnAProb * 100.0);
        System.out.printf(Locale.US, "Δcount(B+->B) via classifyInstance: %d%n", deltaEstimatedClassify);

        System.out.println("\nSaved: " + resCsv);
        System.out.println("Saved datasets in: " + outDir);
    }

    /* =========================================================
       =                   Core helpers                        =
       ========================================================= */

    private static Instances loadCsvAsInstances(Path csvPath) throws IOException {
        CSVLoader loader = new CSVLoader();
        loader.setSource(csvPath.toFile());
        return loader.getDataSet();
    }

    private static int findMaxInt(Instances data, Attribute attr) {
        int max = Integer.MIN_VALUE;
        for (int i = 0; i < data.numInstances(); i++) {
            int v = (int) Math.round(data.instance(i).value(attr));
            if (v > max) max = v;
        }
        return max;
    }

    private static Remove buildRemoveMetaFilter(Instances trainRaw) throws Exception {
        List<String> metaNames = List.of(COL_VERSION_INDEX, COL_VERSION_NAME, COL_METHOD_FQN, COL_BODY_HASH);

        List<Integer> idx1Based = new ArrayList<>();
        for (String name : metaNames) {
            Attribute a = trainRaw.attribute(name);
            if (a != null) idx1Based.add(a.index() + 1); // Remove uses 1-based indices
        }

        Remove rm = new Remove();
        if (!idx1Based.isEmpty()) {
            idx1Based.sort(Integer::compareTo);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < idx1Based.size(); i++) {
                if (i > 0) sb.append(",");
                sb.append(idx1Based.get(i));
            }
            rm.setAttributeIndices(sb.toString());
            rm.setInvertSelection(false);
        } else {
            // keep all attributes (no-op)
            rm.setAttributeIndices("1");
            rm.setInvertSelection(true);
        }
        rm.setInputFormat(trainRaw);
        return rm;
    }

    private static void ensureClassIsLastAndSet(Instances data, String className) {
        Attribute cls = data.attribute(className);
        if (cls == null) throw new IllegalArgumentException("Missing class attribute: " + className);

        int clsIndex = cls.index();
        int last = data.numAttributes() - 1;

        if (clsIndex != last) {
            // rebuild with class last (safe using DenseInstance)
            ArrayList<Attribute> attrs = new ArrayList<>();
            for (int i = 0; i < data.numAttributes(); i++) {
                if (i != clsIndex) attrs.add((Attribute) data.attribute(i).copy());
            }
            attrs.add((Attribute) cls.copy());

            Instances rebuilt = new Instances(data.relationName(), attrs, data.numInstances());

            for (int i = 0; i < data.numInstances(); i++) {
                Instance old = data.instance(i);
                double[] vals = new double[rebuilt.numAttributes()];

                int k = 0;
                for (int j = 0; j < data.numAttributes(); j++) {
                    if (j == clsIndex) continue;
                    vals[k++] = old.value(j);
                }
                vals[vals.length - 1] = old.value(clsIndex);

                DenseInstance ni = new DenseInstance(old.weight(), vals);
                ni.setDataset(rebuilt);
                rebuilt.add(ni);
            }

            // replace in-place
            while (data.numInstances() > 0) data.delete(0);
            for (int i = 0; i < rebuilt.numInstances(); i++) data.add(rebuilt.instance(i));
            for (int i = 0; i < rebuilt.numAttributes(); i++) data.renameAttribute(i, rebuilt.attribute(i).name());
        }

        data.setClassIndex(data.numAttributes() - 1);
    }

    private static int positiveClassIndex(Instances data, String positiveLabel) {
        int pos = data.classAttribute().indexOfValue(positiveLabel);
        if (pos >= 0) return pos;
        return Math.min(1, data.classAttribute().numValues() - 1);
    }

    private static Instances applyTrainedFilter(Filter trained, Instances data) throws Exception {
        Instances out = new Instances(trained.getOutputFormat(), 0);

        for (int i = 0; i < data.numInstances(); i++) {
            trained.input(data.instance(i));
            Instance processed;
            while ((processed = trained.output()) != null) {
                out.add(processed);
            }
        }
        trained.batchFinished();

        Instance processed;
        while ((processed = trained.output()) != null) {
            out.add(processed);
        }

        out.setClassIndex(out.numAttributes() - 1);
        return out;
    }

    private static Classifier buildSafeClassifier(WekaProcessor wp, Instances train, WekaProcessor.ModelSpec spec) throws Exception {
        AttributeStats stats = train.attributeStats(train.classIndex());
        int[] counts = (stats != null) ? stats.nominalCounts : null;
        if (counts == null || counts.length < 2 || counts[0] == 0 || counts[1] == 0) {
            ZeroR zr = new ZeroR();
            zr.buildClassifier(train);
            return zr;
        }

        Classifier c = wp.buildClassifier(train, spec);
        c.buildClassifier(train);
        return c;
    }

    /* =========================================================
       =            B+, B, C construction rules                =
       ========================================================= */

    private static String ruleDescription(String aFeature) {
        if ("NumCodeSmells".equalsIgnoreCase(aFeature)) return "NumCodeSmells > 0";
        return "AFeature >= quantile(0.75) on latest release (fallback: top-k if ties/degenerate)";
    }

    private static boolean isInBPlus(String aFeature, double value, double threshold) {
        if ("NumCodeSmells".equalsIgnoreCase(aFeature)) return value > 0.0;
        return value >= threshold;
    }

    private static double chooseBPlusThreshold(Instances test, String aFeature, double topQuantile) {
        Attribute a = test.attribute(aFeature);
        if (a == null) throw new IllegalArgumentException("Missing attribute: " + aFeature);
        if ("NumCodeSmells".equalsIgnoreCase(aFeature)) return 0.0;
        return quantile(test, a, topQuantile);
    }

    private static double quantile(Instances data, Attribute attr, double q) {
        double[] vals = new double[data.numInstances()];
        for (int i = 0; i < data.numInstances(); i++) vals[i] = data.instance(i).value(attr);
        Arrays.sort(vals);
        if (vals.length == 0) return 0.0;

        double qq = Math.max(0.0, Math.min(1.0, q));
        int idx = (int) Math.floor(qq * (vals.length - 1));
        idx = Math.max(0, Math.min(idx, vals.length - 1));
        return vals[idx];
    }

    private static final class SplitBC {
        Instances bPlus;
        Instances c;
        double effectiveThreshold;
    }

    private static SplitBC splitByTopK(Instances test, int aFeatIndex, double topQuantile) {
        int n = test.numInstances();
        int k = Math.max(1, (int) Math.ceil((1.0 - topQuantile) * n)); // top 25% if q=0.75

        List<Integer> idx = new ArrayList<>(n);
        for (int i = 0; i < n; i++) idx.add(i);

        idx.sort((i1, i2) -> Double.compare(test.instance(i2).value(aFeatIndex), test.instance(i1).value(aFeatIndex)));

        Set<Integer> top = new HashSet<>(idx.subList(0, Math.min(k, n)));

        Instances bPlus = new Instances(test, 0);
        Instances c = new Instances(test, 0);

        double thr = Double.POSITIVE_INFINITY;
        for (int i = 0; i < n; i++) {
            Instance src = test.instance(i);
            if (top.contains(i)) {
                bPlus.add((Instance) src.copy());
                thr = Math.min(thr, src.value(aFeatIndex));
            } else {
                c.add((Instance) src.copy());
            }
        }

        SplitBC out = new SplitBC();
        out.bPlus = bPlus;
        out.c = c;
        out.effectiveThreshold = (thr == Double.POSITIVE_INFINITY) ? 0.0 : thr;
        return out;
    }

    private static void applyWhatIfTransformation(Instances b, int aFeatIndex, RefactorDelta d) {
        for (int i = 0; i < b.numInstances(); i++) {
            Instance inst = b.instance(i);
            double oldV = inst.value(aFeatIndex);
            double newV = oldV * d.factor;
            if (Double.isNaN(newV) || Double.isInfinite(newV)) newV = oldV;
            if (newV < 0.0) newV = 0.0;
            inst.setValue(aFeatIndex, newV);
        }
    }

    /* =========================================================
       =                Estimation metrics                      =
       ========================================================= */

    private static Result evaluateDataset(String name, Instances data, Classifier model, int posIndex) throws Exception {
        Result r = new Result();
        r.name = name;
        r.n = data.numInstances();
        r.actualBuggy = countActualBuggy(data, posIndex);

        double sumProb = 0.0;
        int hardPredThreshold = 0;
        int hardPredClassify = 0;

        for (int i = 0; i < data.numInstances(); i++) {
            Instance inst = data.instance(i);

            // report-like: sum of probabilities
            double[] dist = model.distributionForInstance(inst);
            double pYes = (dist != null && dist.length > posIndex) ? dist[posIndex] : 0.0;
            if (Double.isNaN(pYes) || Double.isInfinite(pYes)) pYes = 0.0;

            sumProb += pYes;
            if (pYes >= 0.5) hardPredThreshold++;

            // paper-like: classifyInstance (argmax)
            double cls = model.classifyInstance(inst);
            if ((int) Math.round(cls) == posIndex) hardPredClassify++;
        }

        r.expectedDefectsSum = sumProb;
        r.estimatedBuggyThreshold05 = hardPredThreshold;
        r.estimatedBuggyClassify = hardPredClassify;
        return r;
    }

    private static int countActualBuggy(Instances data, int posIndex) {
        int c = 0;
        for (int i = 0; i < data.numInstances(); i++) {
            if ((int) Math.round(data.instance(i).classValue()) == posIndex) c++;
        }
        return c;
    }

    /* =========================================================
       =                 I/O: eval + refactor CSV               =
       ========================================================= */

    /**
     * Parser SOLO per il CSV generato dalla tua pipeline:
     * header atteso (case-sensitive):
     * PROJ,WF_ITER,MODEL,FEATURE_SELECTION,BALANCING,COST_SENSITIVE,...,MCC,...,AUC,...
     */
    private static List<ClassifierEvaluation> readEvaluationsCsvProjectFormat(String projectName, Path evalCsv) throws IOException {
        List<ClassifierEvaluation> out = new ArrayList<>();
        List<String> lines = Files.readAllLines(evalCsv, StandardCharsets.UTF_8);
        if (lines.isEmpty()) return out;

        String[] header = splitCsvLine(lines.get(0));
        Map<String, Integer> idx = new HashMap<>();
        for (int i = 0; i < header.length; i++) {
            idx.put(header[i].trim(), i);
        }

        // Required columns (your exact format)
        int iIter = requireIdx(idx, "WF_ITER");
        int iModel = requireIdx(idx, "MODEL");
        int iFs = requireIdx(idx, "FEATURE_SELECTION");
        int iBal = requireIdx(idx, "BALANCING");
        int iCost = requireIdx(idx, "COST_SENSITIVE");
        int iAuc = requireIdx(idx, "AUC");
        int iMcc = requireIdx(idx, "MCC");

        // Optional (not needed by pickBestSpec, but harmless if present)
        int iProj = idx.getOrDefault("PROJ", -1);

        for (int r = 1; r < lines.size(); r++) {
            String line = lines.get(r).trim();
            if (line.isEmpty()) continue;

            String[] p = splitCsvLine(line);
            if (p.length < header.length) continue;

            int iter = safeInt(p, iIter, -1);
            if (iter < 0) continue;

            // (Optional) skip rows not matching project, if PROJ column exists
            if (iProj >= 0) {
                String projInRow = safeStr(p, iProj).trim();
                if (!projInRow.isEmpty() && !projInRow.equalsIgnoreCase(projectName)) {
                    continue;
                }
            }

            ClassifierEvaluation ce = new ClassifierEvaluation(projectName, iter);

            ce.setModel(safeStr(p, iModel));
            ce.setFeatureSelection(safeStr(p, iFs));
            ce.setBalancing(safeStr(p, iBal));     // maps to sampling
            ce.setCostSensitive(safeStr(p, iCost));

            ce.setAuc(safeDouble(p, iAuc));
            ce.setMcc(safeDouble(p, iMcc));

            out.add(ce);
        }

        return out;
    }

    private static int requireIdx(Map<String, Integer> idx, String colName) {
        Integer v = idx.get(colName);
        if (v == null) {
            throw new IllegalArgumentException("weka_walkforward.csv missing required column: " + colName);
        }
        return v;
    }


    private static int firstIdx(Map<String, Integer> idx, String... keysCanon) {
        for (String k : keysCanon) {
            Integer v = idx.get(canon(k));
            if (v != null) return v;
        }
        return -1;
    }

    private static String canon(String s) {
        if (s == null) return "";
        String t = s.trim().toLowerCase(Locale.ROOT);
        // remove all non-alphanumeric to unify "COST_SENSITIVE" / "CostSensitive" / "F1-Score" etc.
        return t.replaceAll("[^a-z0-9]+", "");
    }

    private static String safeStr(String[] arr, int i) {
        if (i < 0 || i >= arr.length) return "";
        return arr[i] == null ? "" : arr[i].trim();
    }

    private static int safeInt(String[] arr, int i, int def) {
        try {
            String s = safeStr(arr, i);
            if (s.isEmpty()) return def;
            return (int) Math.round(Double.parseDouble(s));
        } catch (Exception e) {
            return def;
        }
    }

    private static double safeDouble(String[] arr, int i) {
        try {
            String s = safeStr(arr, i);
            if (s.isEmpty()) return Double.NaN;
            return Double.parseDouble(s);
        } catch (Exception e) {
            return Double.NaN;
        }
    }

    private static RefactorDelta readRefactorDelta(Path refactorCsv, String aFeature) throws IOException {
        List<String> lines = Files.readAllLines(refactorCsv, StandardCharsets.UTF_8);
        if (lines.size() < 2) throw new IllegalArgumentException("Refactor metrics CSV too short: " + refactorCsv);

        String[] header = splitCsvLine(lines.get(0));
        Map<String, Integer> idx = new HashMap<>();
        for (int i = 0; i < header.length; i++) idx.put(header[i].trim(), i);

        Integer iTag = idx.get("Tag");
        Integer iFeat = idx.get(aFeature);
        if (iTag == null) throw new IllegalArgumentException("Refactor CSV missing column: Tag");
        if (iFeat == null) throw new IllegalArgumentException("Refactor CSV missing column for AFeature: " + aFeature);

        Double before = null, after = null;

        for (int r = 1; r < lines.size(); r++) {
            String line = lines.get(r).trim();
            if (line.isEmpty()) continue;

            String[] p = splitCsvLine(line);
            if (p.length < header.length) continue;

            String tag = p[iTag].trim();
            double v = parseDoubleOrNaN(p[iFeat]);

            if ("BEFORE".equalsIgnoreCase(tag)) before = v;
            if ("AFTER".equalsIgnoreCase(tag)) after = v;
        }

        if (before == null || after == null || Double.isNaN(before) || Double.isNaN(after)) {
            throw new IllegalStateException("Could not read BEFORE/AFTER values for " + aFeature + " from " + refactorCsv);
        }

        RefactorDelta d = new RefactorDelta();
        d.before = before;
        d.after = after;

        // NO clamp to <= 1.0: respect actual delta
        if (Math.abs(before) > EPS) d.factor = after / before;
        else d.factor = 0.0;

        if (Double.isNaN(d.factor) || Double.isInfinite(d.factor)) d.factor = 1.0;
        if (d.factor < 0.0) d.factor = 0.0;
        return d;
    }

    private static double parseDoubleOrNaN(String s) {
        try {
            return Double.parseDouble(s.trim());
        } catch (Exception e) {
            return Double.NaN;
        }
    }

    private static String[] splitCsvLine(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inQ = false;

        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);

            if (ch == '"') {
                if (inQ && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    cur.append('"');
                    i++;
                } else {
                    inQ = !inQ;
                }
            } else if (ch == ',' && !inQ) {
                out.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(ch);
            }
        }
        out.add(cur.toString());
        return out.toArray(new String[0]);
    }

    /* =========================================================
       =                     Output CSVs                       =
       ========================================================= */

    private static void saveInstancesAsCsv(Instances data, Path outPath) throws Exception {
        CSVSaver saver = new CSVSaver();
        saver.setInstances(data);
        saver.setFile(outPath.toFile());
        saver.writeBatch();
    }

    private static void writeResultsCsv(Path out,
                                        String project,
                                        String aFeature,
                                        String method,
                                        WekaProcessor.ModelSpec spec,
                                        RefactorDelta delta,
                                        double bPlusThreshold,
                                        Result rA, Result rBPlus, Result rB, Result rC,
                                        double deltaExpectedProb,
                                        double relOnBPlusProb,
                                        double relOnAProb,
                                        int deltaEstimatedClassify) throws IOException {

        Files.createDirectories(out.getParent());
        try (PrintWriter pw = new PrintWriter(new OutputStreamWriter(new FileOutputStream(out.toFile()), StandardCharsets.UTF_8))) {

            pw.println(String.join(",",
                    "Project", "AFeature", "AFMethod", "BClassifier",
                    "RefBefore", "RefAfter", "RefFactor",
                    "BPlusThreshold", "Dataset",
                    "N", "ActualBuggy",
                    "ExpectedDefectsSum_Prob",
                    "EstimatedBuggy_Threshold05",
                    "EstimatedBuggy_Classify",
                    "DeltaExpectedProb_BPlus_to_B",
                    "RelDropProbOnBPlus",
                    "RelDropProbOnA_latest",
                    "DeltaEstimatedClassify_BPlus_to_B"
            ));

            writeRow(pw, project, aFeature, method, spec, delta, bPlusThreshold, rA,
                    deltaExpectedProb, relOnBPlusProb, relOnAProb, deltaEstimatedClassify);
            writeRow(pw, project, aFeature, method, spec, delta, bPlusThreshold, rBPlus,
                    deltaExpectedProb, relOnBPlusProb, relOnAProb, deltaEstimatedClassify);
            writeRow(pw, project, aFeature, method, spec, delta, bPlusThreshold, rB,
                    deltaExpectedProb, relOnBPlusProb, relOnAProb, deltaEstimatedClassify);
            writeRow(pw, project, aFeature, method, spec, delta, bPlusThreshold, rC,
                    deltaExpectedProb, relOnBPlusProb, relOnAProb, deltaEstimatedClassify);
        }
    }

    private static void writeRow(PrintWriter pw,
                                 String project,
                                 String aFeature,
                                 String method,
                                 WekaProcessor.ModelSpec spec,
                                 RefactorDelta delta,
                                 double bPlusThreshold,
                                 Result r,
                                 double deltaExpectedProb,
                                 double relOnBPlusProb,
                                 double relOnAProb,
                                 int deltaEstimatedClassify) {

        pw.printf(Locale.US,
                "%s,%s,%s,\"%s\",%.6f,%.6f,%.6f,%.6f,%s,%d,%d,%.6f,%d,%d,%.6f,%.6f,%.6f,%d%n",
                esc(project),
                esc(aFeature),
                esc(method),
                spec,
                delta.before,
                delta.after,
                delta.factor,
                bPlusThreshold,
                esc(r.name),
                r.n,
                r.actualBuggy,
                r.expectedDefectsSum,
                r.estimatedBuggyThreshold05,
                r.estimatedBuggyClassify,
                deltaExpectedProb,
                relOnBPlusProb,
                relOnAProb,
                deltaEstimatedClassify
        );
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace(",", " ");
    }

    private static String sanitize(String s) {
        if (s == null) return "x";
        return s.replaceAll("[^A-Za-z0-9_\\-.]", "_");
    }

    /* =========================================================
       =                      Data types                       =
       ========================================================= */

    private static final class RefactorDelta {
        double before;
        double after;
        double factor;
    }

    private static final class Result {
        String name;
        int n;
        int actualBuggy;

        // report-like
        double expectedDefectsSum;

        // auxiliary
        int estimatedBuggyThreshold05;
        int estimatedBuggyClassify;
    }
}
