package org.example.controller;

import org.example.model.ClassifierEvaluation;
import org.example.model.EvaluationFile;
import org.example.model.Method;
import org.example.utilities.ArffExporter;
import weka.attributeSelection.BestFirst;
import weka.attributeSelection.CfsSubsetEval;
import weka.classifiers.Classifier;
import weka.classifiers.CostMatrix;
import weka.classifiers.Evaluation;
import weka.classifiers.bayes.NaiveBayes;
import weka.classifiers.lazy.IBk;
import weka.classifiers.meta.CostSensitiveClassifier;
import weka.classifiers.meta.FilteredClassifier;
import weka.classifiers.trees.RandomForest;
import weka.core.Instances;
import weka.filters.Filter;
import weka.filters.MultiFilter;
import weka.filters.supervised.attribute.AttributeSelection;
import weka.filters.supervised.instance.SMOTE;
import weka.filters.unsupervised.attribute.Remove;
import weka.filters.unsupervised.attribute.Standardize;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

/**
 * WekaProcessor (refactor "stile WekaClassification"):
 * - Walk-forward temporale iterativo (come la tua versione)
 * - Per ogni iterazione testa più configurazioni:
 *   * classifier: IBk / NaiveBayes / RandomForest
 *   * feature selection: none / CfsSubsetEval+BestFirst
 *   * sampling: none / SMOTE (dinamico)
 *   * cost-sensitive: none / FP-weighted (penalizza i falsi positivi)
 *
 * Naming e descrittori in stile WekaClassification (featureSelection/sampling/costSensitive),
 * così da poter aggregare e confrontare facilmente. :contentReference[oaicite:2]{index=2}
 */
public class WekaProcessor {

    private static final String FEATURE_SELECTION_NONE = "none";
    private static final String FEATURE_SELECTION_BESTFIRST = "CfsSubsetEval+BestFirst";

    private static final String SAMPLING_NONE = "none";
    private static final String SAMPLING_SMOTE = "SMOTE";

    private static final String COST_NONE = "none";
    private static final String COST_FP_X2 = "FPx2"; // penalizza FP: utile per alzare precision

    private static final String THRESHOLD_STRATEGY = "Default";
    private static final double THRESHOLD_VALUE = 0.5;

    private static final String[] BASE_CLASSIFIERS = {"IBk", "NaiveBayes", "RandomForest"};

    // Outlier detection (come il tuo processor)
    private static final double OUTLIER_BUGGY_THRESHOLD = 0.15; // 15%
    private static final double OUTLIER_CLEAN_THRESHOLD = 0.01; // 1%

    private final List<Method> methodList;
    private final String projName;

    public WekaProcessor(String projName, List<Method> methodList) {
        this.projName = (projName == null || projName.isBlank()) ? "PROJECT" : projName;
        this.methodList = methodList;
    }

    public List<ClassifierEvaluation> runPredictionPipeline() {
        List<ClassifierEvaluation> allEvaluations = new ArrayList<>();

        if (methodList == null || methodList.isEmpty()) {
            System.err.println("WekaProcessor: lista di metodi vuota, niente da processare.");
            return allEvaluations;
        }

        try {
            allEvaluations.addAll(runIterativeTemporalValidation());

            String csvBaseDir = "output/csv/" + projName.toUpperCase();
            File csvDir = new File(csvBaseDir);
            if (!csvDir.exists() && !csvDir.mkdirs() && !csvDir.exists()) {
                throw new IOException("Impossibile creare la cartella CSV: " + csvBaseDir);
            }

            EvaluationFile iterativeEvalFile =
                    new EvaluationFile(projName, allEvaluations, "details");
            iterativeEvalFile.createANewFile(csvBaseDir + "/iterative_temporal_evaluations.csv");

            if (!allEvaluations.isEmpty()) {
                String bestModelName = selectBestModelByF1(allEvaluations);
                System.out.println("\n✓ Miglior configurazione (F1 medio): " + bestModelName);
                System.out.println("✓ Pipeline completata con " + allEvaluations.size() + " valutazioni.");
            }

        } catch (Exception e) {
            e.printStackTrace();
        }

        return allEvaluations;
    }

    /* =========================================================
     *        WALK-FORWARD TEMPORALE ITERATIVO
     * ========================================================= */

    private List<ClassifierEvaluation> runIterativeTemporalValidation() throws Exception {
        List<ClassifierEvaluation> evaluations = new ArrayList<>();

        int numVersions = (int) methodList.stream()
                .map(m -> m.getVersion().getIndex())
                .distinct()
                .count();

        System.out.println("\n========================================");
        System.out.println("ITERATIVE TEMPORAL VALIDATION (Config-based)");
        System.out.println("========================================");
        System.out.println("Numero di versioni disponibili: " + numVersions);
        System.out.println("Numero di iterazioni previste: " + (numVersions - 1));

        String arffBaseDir = "output/arff/" + projName.toUpperCase() + "/temporal";
        new File(arffBaseDir).mkdirs();

        for (int i = 1; i < numVersions; i++) {
            final int currentVersionIndex = i;

            System.out.println("\n--- Iteration " + i + " / " + (numVersions - 1) + " ---");

            List<Method> trainingMethods = methodList.stream()
                    .filter(m -> m.getVersion().getIndex() <= currentVersionIndex)
                    .collect(Collectors.toList());

            List<Method> testingMethods = methodList.stream()
                    .filter(m -> m.getVersion().getIndex() == currentVersionIndex + 1)
                    .collect(Collectors.toList());

            if (trainingMethods.isEmpty() || testingMethods.isEmpty()) {
                System.out.println("⚠️  Skipping iteration " + i + ": empty training or testing set");
                continue;
            }

            System.out.println("Training set: " + trainingMethods.size() + " methods (versions 1-" + i + ")");
            System.out.println("Testing set: " + testingMethods.size() + " methods (version " + (i + 1) + ")");

            Instances trainingSet = ArffExporter.methodsToInstances(
                    trainingMethods,
                    projName + "_train_iter_" + i
            );
            Instances testingSet = ArffExporter.methodsToInstances(
                    testingMethods,
                    projName + "_test_iter_" + i
            );

            trainingSet.setClassIndex(trainingSet.numAttributes() - 1);
            testingSet.setClassIndex(testingSet.numAttributes() - 1);

            String iterDir = arffBaseDir + "/iteration_" + i;
            new File(iterDir).mkdirs();
            ArffExporter.saveInstancesAsArff(trainingSet, iterDir + "/training.arff");
            ArffExporter.saveInstancesAsArff(testingSet, iterDir + "/testing.arff");

            double buggyRatio = calculateBuggyRatio(trainingSet);
            boolean isOutlierVersion = (buggyRatio > OUTLIER_BUGGY_THRESHOLD || buggyRatio < OUTLIER_CLEAN_THRESHOLD);

            System.out.println("Training set buggy ratio: " + String.format("%.2f%%", buggyRatio * 100));
            if (isOutlierVersion) System.out.println("⚠️  Outlier version detected");

            // Config list "stile WekaClassification": più combinazioni e naming coerente
            List<ModelConfig> configs = buildClassifierConfigs();

            // SMOTE calcolato 1 volta e riusato solo se serve
            Instances smoteTrainCached = null;

            for (ModelConfig cfg : configs) {
                try {
                    Instances trainForThisModel = trainingSet;

                    if (SAMPLING_SMOTE.equals(cfg.sampling)) {
                        if (smoteTrainCached == null) {
                            smoteTrainCached = applyDynamicSmote(trainingSet, isOutlierVersion);
                            System.out.println("Balanced training set: " + smoteTrainCached.numInstances() + " instances");
                        }
                        trainForThisModel = smoteTrainCached;
                    }

                    Classifier model = buildConfiguredClassifier(trainForThisModel, cfg);

                    ClassifierEvaluation eval = evaluateClassifier(
                            model,
                            trainingSet, // struttura Evaluation (come in WekaClassification: Evaluation(train))
                            testingSet,
                            cfg.fullName(),
                            i
                    );

                    // descrittori coerenti con WekaClassification
                    eval.setFeatureSelection(cfg.featureSelection);
                    eval.setSampling(cfg.sampling);
                    eval.setCostSensitive(cfg.costSensitive);

                    evaluations.add(eval);

                    System.out.println(String.format("  %s → P: %.3f R: %.3f F1: %.3f",
                            cfg.fullName(), eval.precision(), eval.recall(), eval.f1()));

                } catch (Exception e) {
                    System.err.println("Errore configurazione " + cfg.fullName() + ": " + e.getMessage());
                }
            }
        }

        System.out.println("\n========================================");
        System.out.println("TEMPORAL VALIDATION COMPLETATA");
        System.out.println("Totale valutazioni: " + evaluations.size());
        System.out.println("========================================\n");

        return evaluations;
    }

    /* =========================================================
     *        STRATEGIA "CONFIG-BASED" (stile WekaClassification)
     * ========================================================= */

    private List<ModelConfig> buildClassifierConfigs() {
        List<ModelConfig> out = new ArrayList<>();

        for (String base : BASE_CLASSIFIERS) {
            for (String fs : new String[]{FEATURE_SELECTION_NONE, FEATURE_SELECTION_BESTFIRST}) {
                for (String sampling : new String[]{SAMPLING_NONE, SAMPLING_SMOTE}) {
                    for (String cost : new String[]{COST_NONE, COST_FP_X2}) {
                        out.add(new ModelConfig(base, fs, sampling, cost));
                    }
                }
            }
        }
        return out;
    }

    private Classifier buildConfiguredClassifier(Instances trainingData, ModelConfig cfg) throws Exception {
        Classifier base = buildBaseClassifier(cfg.baseName);

        // Optional cost-sensitive wrapper (penalizza FP per alzare precision)
        if (!COST_NONE.equals(cfg.costSensitive)) {
            base = wrapCostSensitive(base, trainingData);
        }

        // Remove VersionIndex (sempre)
        Remove removeFilter = new Remove();
        removeFilter.setAttributeIndices("1"); // VersionIndex è il primo (1-based)
        removeFilter.setInputFormat(trainingData);

        List<Filter> filters = new ArrayList<>();
        filters.add(removeFilter);

        // Standardize SOLO per IBk (KNN) per distanze sensate
        if ("IBk".equals(cfg.baseName)) {
            Standardize std = new Standardize();
            std.setInputFormat(trainingData);
            filters.add(std);
        }

        // Feature selection opzionale
        if (!FEATURE_SELECTION_NONE.equals(cfg.featureSelection)) {
            AttributeSelection fsFilter = new AttributeSelection();
            CfsSubsetEval eval = new CfsSubsetEval();
            BestFirst search = new BestFirst();
            fsFilter.setEvaluator(eval);
            fsFilter.setSearch(search);
            filters.add(fsFilter);
        }

        MultiFilter multiFilter = new MultiFilter();
        multiFilter.setFilters(filters.toArray(new Filter[0]));

        FilteredClassifier filtered = new FilteredClassifier();
        filtered.setClassifier(base);
        filtered.setFilter(multiFilter);
        filtered.buildClassifier(trainingData);

        return filtered;
    }

    private Classifier buildBaseClassifier(String modelName) {
        switch (modelName) {
            case "IBk":
                IBk ibk = new IBk();
                ibk.setKNN(5);
                return ibk;
            case "NaiveBayes":
                return new NaiveBayes();
            case "RandomForest":
                RandomForest rf = new RandomForest();
                rf.setNumIterations(100);
                return rf;
            default:
                throw new IllegalArgumentException("Classificatore sconosciuto: " + modelName);
        }
    }

    private Classifier wrapCostSensitive(Classifier base, Instances trainingData) throws Exception {
        // Classe: {false,true} (da ArffExporter) :contentReference[oaicite:3]{index=3}
        int idxFalse = trainingData.classAttribute().indexOfValue("false");
        int idxTrue = trainingData.classAttribute().indexOfValue("true");
        if (idxFalse < 0 || idxTrue < 0) {
            // fallback: non wrappare se non troviamo i label attesi
            return base;
        }

        CostMatrix cm = new CostMatrix(2);
        // diagonal = 0
        cm.setCell(idxFalse, idxFalse, 0.0);
        cm.setCell(idxTrue, idxTrue, 0.0);
        // costi:
        // FP: pred true quando è false (penalizza)
        cm.setCell(idxFalse, idxTrue, 2.0);
        // FN: pred false quando è true
        cm.setCell(idxTrue, idxFalse, 1.0);

        CostSensitiveClassifier csc = new CostSensitiveClassifier();
        csc.setClassifier(base);
        csc.setCostMatrix(cm);
        csc.setMinimizeExpectedCost(true);

        return csc;
    }

    /* =========================================================
     *              SMOTE DINAMICO (come il tuo)
     * ========================================================= */

    private Instances applyDynamicSmote(Instances train, boolean isOutlier) {
        try {
            SMOTE smote = new SMOTE();
            smote.setInputFormat(train);

            int[] nominalCounts = train.attributeStats(train.classIndex()).nominalCounts;
            if (nominalCounts == null || nominalCounts.length < 2) {
                System.err.println("⚠️  Impossibile applicare SMOTE: attributo classe non valido");
                return train;
            }

            int cleanCount = nominalCounts[0];  // "false"
            int buggyCount = nominalCounts[1];  // "true"

            double majoritySize = Math.max(cleanCount, buggyCount);
            double minoritySize = Math.min(cleanCount, buggyCount);

            if (minoritySize == 0) {
                System.err.println("⚠️  Nessuna istanza della classe minoritaria - SMOTE skipped");
                return train;
            }

            double targetPercentage = (majoritySize - minoritySize) / minoritySize * 100.0;

            if (isOutlier && targetPercentage > 300) {
                System.out.println("⚠️  Reducing SMOTE percentage for outlier version: " +
                        String.format("%.1f%% → 200%%", targetPercentage));
                targetPercentage = 200.0;
            } else if (targetPercentage > 500) {
                System.out.println("⚠️  Capping extreme SMOTE percentage: " +
                        String.format("%.1f%% → 500%%", targetPercentage));
                targetPercentage = 500.0;
            }

            // NN <= minority-1 per evitare errori su minority piccolissima
            int k = Math.min(5, (int) minoritySize - 1);
            k = Math.max(1, k);

            smote.setPercentage(targetPercentage);
            smote.setNearestNeighbors(k);

            System.out.println(String.format("SMOTE config: clean=%d, buggy=%d, percentage=%.1f%%, k=%d",
                    cleanCount, buggyCount, targetPercentage, k));

            return Filter.useFilter(train, smote);

        } catch (Exception e) {
            System.err.println("SMOTE fallito: " + e.getMessage());
            return train;
        }
    }

    /* =========================================================
     *                 VALUTAZIONE
     * ========================================================= */

    private ClassifierEvaluation evaluateClassifier(Classifier model,
                                                    Instances trainStructure,
                                                    Instances testData,
                                                    String modelName,
                                                    int iterationIndex) throws Exception {

        Evaluation evaluation = new Evaluation(trainStructure);
        evaluation.evaluateModel(model, testData);

        int positiveIndex = testData.classAttribute().indexOfValue("true");
        if (positiveIndex < 0) {
            positiveIndex = testData.classAttribute().numValues() - 1;
        }

        int tp = (int) Math.round(evaluation.numTruePositives(positiveIndex));
        int fp = (int) Math.round(evaluation.numFalsePositives(positiveIndex));
        int fn = (int) Math.round(evaluation.numFalseNegatives(positiveIndex));
        int tn = (int) Math.round(evaluation.numTrueNegatives(positiveIndex));

        double precision = safe(evaluation.precision(positiveIndex));
        double recall = safe(evaluation.recall(positiveIndex));
        double f1 = safe(evaluation.fMeasure(positiveIndex));
        double specificity = safe(evaluation.trueNegativeRate(positiveIndex));
        double balancedAccuracy = (recall + specificity) / 2.0;
        double gMean = Math.sqrt(recall * specificity);
        double mcc = safe(evaluation.matthewsCorrelationCoefficient(positiveIndex));
        double kappa = safe(evaluation.kappa());
        double auc = safe(evaluation.areaUnderROC(positiveIndex));

        ClassifierEvaluation ce = new ClassifierEvaluation(projName, iterationIndex);
        ce.setTrainingSize(trainStructure.numInstances());
        ce.setTestingSize(testData.numInstances());
        ce.setClassifier(modelName);

        ce.setThresholdStrategy(THRESHOLD_STRATEGY);
        ce.setThreshold(THRESHOLD_VALUE);

        ce.setPrecision(precision);
        ce.setRecall(recall);
        ce.setF1(f1);
        ce.setSpecificity(specificity);
        ce.setBalancedAccuracy(balancedAccuracy);
        ce.setGMean(gMean);
        ce.setMcc(mcc);
        ce.setKappa(kappa);
        ce.setAuc(auc);

        ce.setTp(tp);
        ce.setFp(fp);
        ce.setTn(tn);
        ce.setFn(fn);

        return ce;
    }

    private static double safe(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) return 0.0;
        return v;
    }

    private double calculateBuggyRatio(Instances data) {
        int buggyIndex = data.classAttribute().indexOfValue("true");
        if (buggyIndex < 0) buggyIndex = data.classAttribute().numValues() - 1;

        int buggy = 0;
        for (int i = 0; i < data.numInstances(); i++) {
            if ((int) data.instance(i).classValue() == buggyIndex) buggy++;
        }
        return data.numInstances() == 0 ? 0.0 : (double) buggy / (double) data.numInstances();
    }

    private String selectBestModelByF1(List<ClassifierEvaluation> evaluations) {
        Map<String, List<Double>> f1ByModel = new HashMap<>();
        for (ClassifierEvaluation e : evaluations) {
            f1ByModel.computeIfAbsent(e.getModel(), k -> new ArrayList<>()).add(Double.valueOf(e.getF1()));
        }

        String best = null;
        double bestAvg = -1.0;

        for (Map.Entry<String, List<Double>> entry : f1ByModel.entrySet()) {
            double avg = entry.getValue().stream().mapToDouble(x -> x).average().orElse(0.0);
            if (avg > bestAvg) {
                bestAvg = avg;
                best = entry.getKey();
            }
        }
        return best == null ? "N/A" : best;
    }

    /* =========================================================
     *                 CONFIG CON NAMING
     * ========================================================= */

    private static final class ModelConfig {
        final String baseName;
        final String featureSelection;
        final String sampling;
        final String costSensitive;

        ModelConfig(String baseName, String featureSelection, String sampling, String costSensitive) {
            this.baseName = baseName;
            this.featureSelection = featureSelection;
            this.sampling = sampling;
            this.costSensitive = costSensitive;
        }

        String fullName() {
            // Stile WekaClassification: nome + suffissi in base alle scelte :contentReference[oaicite:4]{index=4}
            StringBuilder sb = new StringBuilder(baseName);
            if (!FEATURE_SELECTION_NONE.equals(featureSelection)) sb.append("_BestFirst");
            if (!SAMPLING_NONE.equals(sampling)) sb.append("_").append(sampling);
            if (!COST_NONE.equals(costSensitive)) sb.append("_CostSensitive");
            return sb.toString();
        }
    }
}
