package org.example.controller;

import org.example.model.ClassifierEvaluation;
import org.example.model.EvaluationFile;
import org.example.model.Method;
import org.example.utilities.ArffExporter;
import weka.attributeSelection.BestFirst;
import weka.attributeSelection.CfsSubsetEval;
import weka.classifiers.Classifier;
import weka.classifiers.Evaluation;
import weka.classifiers.bayes.NaiveBayes;
import weka.classifiers.lazy.IBk;
import weka.classifiers.meta.CostSensitiveClassifier;
import weka.classifiers.meta.FilteredClassifier;
import weka.classifiers.trees.RandomForest;
import weka.core.AttributeStats;
import weka.core.Instance;
import weka.core.Instances;
import weka.classifiers.CostMatrix;
import weka.filters.Filter;
import weka.filters.MultiFilter;
import weka.filters.supervised.attribute.AttributeSelection;
import weka.filters.supervised.instance.Resample;
import weka.filters.supervised.instance.SMOTE;
import weka.filters.unsupervised.attribute.Normalize;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * Motore di classificazione WEKA (walk-forward temporale)
 */
public class WekaProcessor {

    private static final Logger LOGGER = Logger.getLogger(WekaProcessor.class.getName());

    private static final String FS_NONE = "none";
    private static final String FS_BEST_FIRST = "BestFirst (backward)";
    private static final String SAMPLING_NONE = "none";
    private static final String SAMPLING_SMOTE = "SMOTE";
    private static final String COST_NONE = "none";
    private static final String COST_SENSITIVE = "CostSensitive";

    private final String projectName;
    private final List<Method> allMethods;

    public WekaProcessor(String projectName, List<Method> allMethods) {
        this.projectName = (projectName == null || projectName.isBlank()) ? "PROJECT" : projectName;
        this.allMethods = (allMethods == null) ? new ArrayList<>() : new ArrayList<>(allMethods);
    }

    /**
     * Esegue la pipeline di predizione temporale (walk-forward) e salva:
     * - output/arff/<PROJ>/temporal/iteration_i/{training,testing}.arff
     * - output/csv/<PROJ>/weka_walkforward_details.csv
     * - output/csv/<PROJ>/weka_walkforward.csv
     */
    public List<ClassifierEvaluation> runPredictionPipeline() {
        List<ClassifierEvaluation> evaluations = new ArrayList<>();
        if (allMethods.isEmpty()) {
            return evaluations;
        }

        // Ordine deterministico: versione, poi nome metodo
        allMethods.sort(Comparator
                .comparingInt((Method m) -> (m.getVersion() != null) ? m.getVersion().getIndex() : Integer.MAX_VALUE)
                .thenComparing(Method::getFullyQualifiedName, Comparator.nullsLast(String::compareTo)));

        // Raggruppa per release effettivamente presenti
        java.util.Map<Integer, List<Method>> byRelease = allMethods.stream()
                .filter(m -> m.getVersion() != null)
                .collect(Collectors.groupingBy(m -> m.getVersion().getIndex()));

        List<Integer> releaseIds = new ArrayList<>(byRelease.keySet());
        releaseIds.sort(Integer::compareTo);

        if (releaseIds.size() < 2) {
            return evaluations;
        }

        boolean isStorm = "STORM".equalsIgnoreCase(projectName);
        final int windowSize = 5;

        // STORM: parto solo quando posso avere 5 release in training + 1 in test
        int startPos = isStorm ? (windowSize - 1) : 0;

        // Contatore sequenziale per le iterazioni (1, 2, 3, ...)
        int iterationCounter = 1;

        LOGGER.info(String.format("Walk-forward: %d releases disponibili, startPos=%d, isStorm=%b",
                releaseIds.size(), startPos, isStorm));

        for (int pos = startPos; pos < releaseIds.size() - 1; pos++) {
            int nextReleaseId = releaseIds.get(pos + 1);

            // Training: cumulativo (da 0 a pos) per tutti; per STORM si parte solo da pos=4
            List<Integer> trainReleaseIds = releaseIds.subList(0, pos + 1);

            List<Method> trainingMethods = trainReleaseIds.stream()
                    .flatMap(rid -> byRelease.getOrDefault(rid, List.of()).stream())
                    .collect(Collectors.toList());

            List<Method> testingMethods = byRelease.getOrDefault(nextReleaseId, List.of());

            LOGGER.info(String.format("Iteration %d: train releases %s (%d methods), test release %d (%d methods)",
                    iterationCounter, trainReleaseIds, trainingMethods.size(), nextReleaseId, testingMethods.size()));

            if (trainingMethods.isEmpty() || testingMethods.isEmpty()) {
                LOGGER.warning(String.format("Skipping iteration %d: empty train/test set", iterationCounter));
                iterationCounter++; // Conta comunque per mantenere coerenza con pos
                continue;
            }

            try {
                // Usa iterationCounter per la directory (sequenziale)
                String iterDir = String.format("output/arff/%s/temporal/iteration_%d",
                        projectName.toUpperCase(), iterationCounter);
                Path iterPath = Paths.get(iterDir);
                Files.createDirectories(iterPath);

                Instances trainingSet = ArffExporter.methodsToInstances(trainingMethods, "training");
                Instances testingSet = ArffExporter.methodsToInstances(testingMethods, "testing");

                // Salva sempre per inspection
                ArffExporter.saveInstancesAsArff(trainingSet, iterPath.resolve("training.arff").toString());
                ArffExporter.saveInstancesAsArff(testingSet, iterPath.resolve("testing.arff").toString());

                if (testingSet.isEmpty()) {
                    LOGGER.warning(String.format("Skipping iteration %d: testingSet empty after conversion", iterationCounter));
                    iterationCounter++; // Conta comunque l'iterazione saltata per coerenza
                    continue;
                }

                // Usa iterationCounter (sequenziale) invece di currentReleaseId
                runAllClassifiersForIteration(iterationCounter, trainingSet, testingSet, evaluations);

                iterationCounter++;

            } catch (Exception e) {
                LOGGER.log(Level.SEVERE,
                        String.format("Failed temporal iteration %d (train releases=%s, test=%d)",
                                iterationCounter, trainReleaseIds, nextReleaseId), e);
                iterationCounter++; // Incrementa anche in caso di errore per mantenere la sequenza
            }
        }

        saveEvaluationCSVs(evaluations);
        return evaluations;
    }


    /* =========================================================
       =                 CLASSIFIER GRID                        =
       ========================================================= */

    private void runAllClassifiersForIteration(int iteration,
                                               Instances trainingSet,
                                               Instances testingSet,
                                               List<ClassifierEvaluation> out) {

        // Baseline
        evaluateAndAppend(iteration, trainingSet, testingSet,
                createRandomForest(), "RandomForest", FS_NONE, SAMPLING_NONE, COST_NONE, out);
        evaluateAndAppend(iteration, trainingSet, testingSet,
                createNaiveBayes(), "NaiveBayes", FS_NONE, SAMPLING_NONE, COST_NONE, out);
        evaluateAndAppend(iteration, trainingSet, testingSet,
                createIBk(), "IBk", FS_NONE, SAMPLING_NONE, COST_NONE, out);

        // Feature Selection (CFS + BestFirst backward)
        evaluateAndAppend(iteration, trainingSet, testingSet,
                wrapWithFeatureSelection(createRandomForest()), "RandomForest", FS_BEST_FIRST, SAMPLING_NONE, COST_NONE, out);
        evaluateAndAppend(iteration, trainingSet, testingSet,
                wrapWithFeatureSelection(createNaiveBayes()), "NaiveBayes", FS_BEST_FIRST, SAMPLING_NONE, COST_NONE, out);
        evaluateAndAppend(iteration, trainingSet, testingSet,
                wrapWithFeatureSelection(createIBk()), "IBk", FS_BEST_FIRST, SAMPLING_NONE, COST_NONE, out);

        // SMOTE
        evaluateAndAppend(iteration, trainingSet, testingSet,
                wrapWithSmote(trainingSet, createRandomForest()), "RandomForest", FS_NONE, SAMPLING_SMOTE, COST_NONE, out);
        evaluateAndAppend(iteration, trainingSet, testingSet,
                wrapWithSmote(trainingSet, createNaiveBayes()), "NaiveBayes", FS_NONE, SAMPLING_SMOTE, COST_NONE, out);
        evaluateAndAppend(iteration, trainingSet, testingSet,
                wrapWithSmote(trainingSet, createIBk()), "IBk", FS_NONE, SAMPLING_SMOTE, COST_NONE, out);

        // Feature Selection + SMOTE
        evaluateAndAppend(iteration, trainingSet, testingSet,
                wrapWithFeatureSelectionAndSmote(trainingSet, createRandomForest()),
                "RandomForest", FS_BEST_FIRST, SAMPLING_SMOTE, COST_NONE, out);

        evaluateAndAppend(iteration, trainingSet, testingSet,
                wrapWithFeatureSelectionAndSmote(trainingSet, createNaiveBayes()),
                "NaiveBayes", FS_BEST_FIRST, SAMPLING_SMOTE, COST_NONE, out);

        evaluateAndAppend(iteration, trainingSet, testingSet,
                wrapWithFeatureSelectionAndSmote(trainingSet, createIBk()),
                "IBk", FS_BEST_FIRST, SAMPLING_SMOTE, COST_NONE, out);

        // Cost Sensitive
        evaluateAndAppend(iteration, trainingSet, testingSet,
                wrapWithCostSensitive(createRandomForest()), "RandomForest", FS_NONE, SAMPLING_NONE, COST_SENSITIVE, out);
        evaluateAndAppend(iteration, trainingSet, testingSet,
                wrapWithCostSensitive(createNaiveBayes()), "NaiveBayes", FS_NONE, SAMPLING_NONE, COST_SENSITIVE, out);
        evaluateAndAppend(iteration, trainingSet, testingSet,
                wrapWithCostSensitive(createIBk()), "IBk", FS_NONE, SAMPLING_NONE, COST_SENSITIVE, out);
    }

    private void evaluateAndAppend(int iteration,
                                   Instances trainingSet,
                                   Instances testingSet,
                                   Classifier classifier,
                                   String modelName,
                                   String featureSelection,
                                   String sampling,
                                   String costSensitive,
                                   List<ClassifierEvaluation> out) {
        try {
            ClassifierEvaluation e = evaluateClassifier(iteration, trainingSet, testingSet, classifier, modelName, featureSelection, sampling, costSensitive);
            if (e != null) {
                out.add(e);
            }
        } catch (Exception ex) {
            LOGGER.log(Level.WARNING, "Could not evaluate classifier {0} at iteration {1}", new Object[]{modelName, iteration});
        }
    }

    private ClassifierEvaluation evaluateClassifier(int iteration,
                                                    Instances trainingSet,
                                                    Instances testingSet,
                                                    Classifier classifier,
                                                    String modelName,
                                                    String featureSelection,
                                                    String sampling,
                                                    String costSensitive) throws Exception {
        if (trainingSet == null || testingSet == null || trainingSet.isEmpty() || testingSet.isEmpty()) {
            return null;
        }

        trainingSet.setClassIndex(trainingSet.numAttributes() - 1);
        testingSet.setClassIndex(testingSet.numAttributes() - 1);

        int posIndex = trainingSet.classAttribute().indexOfValue("yes");
        if (posIndex < 0) posIndex = 1; // fallback
        int negIndex = (posIndex == 0) ? 1 : 0;

        //normalizzo le feature
        // PREPROCESSING: Normalize (fit su training, apply su testing)
        Instances[] norm = normalizeTrainTestNoLeakage(trainingSet, testingSet);
        trainingSet = norm[0];
        testingSet  = norm[1];

        classifier.buildClassifier(trainingSet);
        Evaluation eval = new Evaluation(trainingSet);
        eval.evaluateModel(classifier, testingSet);

        double[][] cm = eval.confusionMatrix();
        int tp = safeInt(cm, posIndex, posIndex);
        int fn = safeInt(cm, posIndex, negIndex);
        int fp = safeInt(cm, negIndex, posIndex);
        int tn = safeInt(cm, negIndex, negIndex);

        double precision = eval.precision(posIndex);
        double recall = eval.recall(posIndex);
        double f1 = eval.fMeasure(posIndex);
        double auc = eval.areaUnderROC(posIndex);
        double kappa = eval.kappa();
        double mcc = eval.matthewsCorrelationCoefficient(posIndex);

        double specificity = (tn + fp) > 0 ? ((double) tn / (double) (tn + fp)) : 0.0;
        double balancedAcc = (recall + specificity) / 2.0;
        double gMean = Math.sqrt(Math.max(0.0, recall) * Math.max(0.0, specificity));

        ClassifierEvaluation ce = new ClassifierEvaluation(projectName, iteration);
        ce.setTrainingSize(trainingSet.numInstances());
        ce.setTestingSize(testingSet.numInstances());
        ce.setClassifier(modelName);
        ce.setFeatureSelection(featureSelection);
        ce.setSampling(sampling);
        ce.setCostSensitive(costSensitive);

        // nessun tuning soglia (default)
        ce.setThresholdStrategy("DEFAULT");
        ce.setThreshold(0.5);

        ce.setPrecision(precision);
        ce.setRecall(recall);
        ce.setF1(f1);
        ce.setAuc(auc);
        ce.setKappa(kappa);
        ce.setMcc(mcc);
        ce.setSpecificity(specificity);
        ce.setBalancedAccuracy(balancedAcc);
        ce.setGMean(gMean);
        ce.setTp(tp);
        ce.setFp(fp);
        ce.setTn(tn);
        ce.setFn(fn);

        return ce;
    }

    private static int safeInt(double[][] m, int r, int c) {
        if (m == null) return 0;
        if (r < 0 || c < 0) return 0;
        if (r >= m.length) return 0;
        if (c >= m[r].length) return 0;
        return (int) Math.round(m[r][c]);
    }

    /* =========================================================
       =                 WRAPPERS / FILTERS                    =
       ========================================================= */

    // normalizziamooo
    private static Instances[] normalizeTrainTestNoLeakage(Instances train, Instances test) throws Exception {
        Normalize norm = new Normalize();
        norm.setInputFormat(train);

        Instances trainOut = applyTrainedFilter(norm, train);

        Filter normCopy = Filter.makeCopy(norm);
        Instances testOut = applyTrainedFilter(normCopy, test);

        trainOut.setClassIndex(trainOut.numAttributes() - 1);
        testOut.setClassIndex(testOut.numAttributes() - 1);
        return new Instances[]{trainOut, testOut};
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



    private static Classifier createRandomForest() {
        RandomForest rf = new RandomForest();
        rf.setSeed(1);
        return rf;
    }

    private static Classifier createNaiveBayes() {
        return new NaiveBayes();
    }

    private static Classifier createIBk() {
        return new IBk();
    }

    private static FilteredClassifier wrapWithFeatureSelection(Classifier base) {
        FilteredClassifier fc = new FilteredClassifier();
        fc.setFilter(createFeatureSelectionFilter());
        fc.setClassifier(base);
        return fc;
    }

    private static FilteredClassifier wrapWithSmote(Instances trainingSet, Classifier base) {
        FilteredClassifier fc = new FilteredClassifier();
        fc.setFilter(createSmoteFilter(trainingSet));
        fc.setClassifier(base);
        return fc;
    }

    private static CostSensitiveClassifier wrapWithCostSensitive(Classifier base) {
        CostSensitiveClassifier cs = new CostSensitiveClassifier();
        cs.setClassifier(base);
        cs.setCostMatrix(createCostMatrix());
        cs.setMinimizeExpectedCost(false);
        return cs;
    }
    private static FilteredClassifier wrapWithFeatureSelectionAndSmote(Instances trainingSet, Classifier base) {
        FilteredClassifier fc = new FilteredClassifier();
        fc.setFilter(createFeatureSelectionAndSmoteFilter(trainingSet));
        fc.setClassifier(base);
        return fc;
    }

    private static Filter createFeatureSelectionAndSmoteFilter(Instances trainingSet) {
        MultiFilter mf = new MultiFilter();
        mf.setFilters(new Filter[] {
                createFeatureSelectionFilter(),
                createSmoteFilter(trainingSet)
        });
        return mf;
    }




    private static Filter createFeatureSelectionFilter() {
        AttributeSelection attributeSelection = new AttributeSelection();
        attributeSelection.setEvaluator(new CfsSubsetEval());

        BestFirst bestFirst = new BestFirst();
        try {
            // -D 0 => backward (come reference)
            bestFirst.setOptions(new String[]{"-D", "0"});
        } catch (Exception ignored) {
            // fallback: default BestFirst
        }
        attributeSelection.setSearch(bestFirst);
        return attributeSelection;
    }

    private static Filter createSmoteFilter(Instances trainingSet) {
        // In caso di dataset non nominale o senza entrambe le classi, fallback a Resample
        if (trainingSet == null || trainingSet.classIndex() < 0) {
            return new Resample();
        }

        AttributeStats stats = trainingSet.attributeStats(trainingSet.classIndex());
        int[] counts = (stats != null) ? stats.nominalCounts : null;
        if (counts == null || counts.length < 2) {
            return new Resample();
        }

        int majority = Math.max(counts[0], counts[1]);
        int minority = Math.min(counts[0], counts[1]);
        if (minority <= 0) {
            return new Resample();
        }

        double smotePercentage = ((double) (majority - minority) / (double) minority) * 100.0;
        if (smotePercentage <= 0.0) {
            return new Resample();
        }

        SMOTE smote = new SMOTE();
        smote.setPercentage(smotePercentage);
        smote.setRandomSeed(1);
        // -C default 0: auto-detect non-empty minority class
        return smote;
    }

    private static CostMatrix createCostMatrix() {
        CostMatrix cm = new CostMatrix(2);
        // no error on correct classification
        cm.setCell(0, 0, 0.0);
        cm.setCell(1, 1, 0.0);
        // FP cost
        cm.setCell(0, 1, 1.0);
        // FN cost (miss a buggy method) â€“ fixed 10 as reference
        cm.setCell(1, 0, 10.0);
        return cm;
    }

    /* =========================================================
       =                    CSV OUTPUT                         =
       ========================================================= */

    private void saveEvaluationCSVs(List<ClassifierEvaluation> evaluations) {
        try {
            Path outDir = Paths.get("output", "csv", projectName.toUpperCase());
            Files.createDirectories(outDir);

            // dettagli
            new EvaluationFile(projectName, evaluations, "details")
                    .createANewFile(outDir.resolve("weka_walkforward_details.csv").toString());

            // senza TR/TE size
            new EvaluationFile(projectName, evaluations, "summary")
                    .createANewFile(outDir.resolve("weka_walkforward.csv").toString());
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "Could not write evaluation CSV files", e);
        }
    }
    /* =========================================================
       =                    FOR 'WHAT IF' ANALYSIS                        =
       ========================================================= */

    public record ModelSpec(String classifier, String featureSelection, String sampling, String costSensitive) {

        @Override
        public String toString() {
            return classifier + " | FS=" + featureSelection + " | Sampling=" + sampling + " | Cost=" + costSensitive;
        }
    }

    public static ModelSpec pickBestSpec(List<org.example.model.ClassifierEvaluation> evals) {
        if (evals == null || evals.isEmpty()) {
            return new ModelSpec("RandomForest", FS_NONE, SAMPLING_NONE, COST_NONE);
        }

        record Agg(double aucSum, int aucN, double mccSum, int mccN) {}
        java.util.Map<String, Agg> m = new java.util.HashMap<>();

        for (org.example.model.ClassifierEvaluation e : evals) {
            String key = String.join("|",
                    nz(e.getClassifier()),
                    nz(e.getFeatureSelection()),
                    nz(e.getSampling()),
                    nz(e.getCostSensitive())
            );

            Agg a = m.getOrDefault(key, new Agg(0, 0, 0, 0));
            double auc = parseOrNaN(e.getAuc());
            double mcc = parseOrNaN(e.getMcc());

            double aucSum = a.aucSum + (Double.isNaN(auc) ? 0 : auc);
            int aucN = a.aucN + (Double.isNaN(auc) ? 0 : 1);

            double mccSum = a.mccSum + (Double.isNaN(mcc) ? 0 : mcc);
            int mccN = a.mccN + (Double.isNaN(mcc) ? 0 : 1);

            m.put(key, new Agg(aucSum, aucN, mccSum, mccN));
        }

        String bestKey = null;
        double bestScore = Double.NEGATIVE_INFINITY;

        for (var ent : m.entrySet()) {
            Agg a = ent.getValue();
            double meanAuc = (a.aucN > 0) ? (a.aucSum / a.aucN) : Double.NaN;
            double meanMcc = (a.mccN > 0) ? (a.mccSum / a.mccN) : Double.NaN;

            double score = !Double.isNaN(meanAuc) ? meanAuc
                    : (!Double.isNaN(meanMcc) ? meanMcc : Double.NEGATIVE_INFINITY);

            if (score > bestScore) {
                bestScore = score;
                bestKey = ent.getKey();
            }
        }

        if (bestKey == null) return new ModelSpec("RandomForest", FS_NONE, SAMPLING_NONE, COST_NONE);

        String[] p = bestKey.split("\\|", -1);
        return new ModelSpec(p[0], p[1], p[2], p[3]);
    }

    public weka.classifiers.Classifier buildClassifier(weka.core.Instances training, ModelSpec spec) {
        weka.classifiers.Classifier base;
        switch (spec.classifier) {
            case "NaiveBayes" -> base = createNaiveBayes();
            case "IBk" -> base = createIBk();
            default -> base = createRandomForest();
        }

        weka.classifiers.Classifier model = base;

        boolean fs = FS_BEST_FIRST.equals(spec.featureSelection);
        boolean smote = SAMPLING_SMOTE.equals(spec.sampling);
        boolean cs = COST_SENSITIVE.equals(spec.costSensitive);

        if (fs && smote) model = wrapWithFeatureSelectionAndSmote(training, model);
        else if (fs) model = wrapWithFeatureSelection(model);
        else if (smote) model = wrapWithSmote(training, model);

        if (cs) model = wrapWithCostSensitive(model);

        return model;
    }

    private static String nz(String s) { return (s == null) ? "" : s; }
    private static double parseOrNaN(String s) {
        try { return Double.parseDouble(s); } catch (Exception ex) { return Double.NaN; }
    }

}