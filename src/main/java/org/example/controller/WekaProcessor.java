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
import weka.classifiers.meta.FilteredClassifier;
import weka.classifiers.trees.RandomForest;
import weka.core.Instances;
import weka.filters.Filter;
import weka.filters.supervised.attribute.AttributeSelection;
import weka.filters.supervised.instance.SMOTE;

import java.io.File;
import java.io.IOException;
import java.util.*;

/**
 * Costruisce l'intera pipeline di predizione usando WEKA:
 *
 *  1. Converte la lista di Method in un dataset WEKA (via ArffExporter).
 *  2. Divide il dataset 70/30 in TRAIN (per walk-forward) e TEST (hold-out finale).
 *  3. Sul TRAIN applica validazione walk-forward basata sul VersionIndex:
 *     - ad ogni iterazione: train = tutte le versioni precedenti,
 *       validation = versione corrente.
 *  4. Per ogni iterazione confronta tre modelli:
 *       - IBk
 *       - NaiveBayes
 *       - RandomForest
 *     ciascuno allenato su:
 *       - TRAIN bilanciato con SMOTE
 *       - Feature selection CfsSubsetEval + BestFirst
 *  5. Salva i risultati del walk-forward in:
 *       output/csv/<PROJ>/training_evaluations.csv
 *  6. Sceglie il modello con F1 medio migliore sul walk-forward.
 *  7. Allena quel modello su tutto il TRAIN (70%) e lo valuta sul TEST (30%).
 *     Salva i risultati in:
 *       output/csv/<PROJ>/test_evaluations.csv
 */
public class WekaProcessor {

    private static final String FEATURE_SELECTION_DESC = "CfsSubsetEval+BestFirst";
    private static final String BALANCING_DESC = "SMOTE";
    private static final String COST_SENSITIVE_DESC = "None";
    private static final String THRESHOLD_STRATEGY = "Default";
    private static final double THRESHOLD_VALUE = 0.5;

    private static final String[] CLASSIFIER_NAMES = {"IBk", "NaiveBayes", "RandomForest"};

    private final List<Method> methodList;
    private final String projName;

    /**
     * Costruttore consigliato, con nome progetto esplicito
     * (es. "BOOKKEEPER").
     */
    public WekaProcessor(String projName, List<Method> methodList) {
        this.projName = (projName == null || projName.isBlank()) ? "PROJECT" : projName;
        this.methodList = methodList;
    }

    /**
     * Esegue l'intera pipeline e restituisce l'elenco di tutte le
     * valutazioni (walk-forward + test finale).
     */
    public List<ClassifierEvaluation> runPredictionPipeline() {
        List<ClassifierEvaluation> allEvaluations = new ArrayList<>();

        if (methodList == null || methodList.isEmpty()) {
            System.err.println("WekaProcessor: lista di metodi vuota, niente da processare.");
            return allEvaluations;
        }

        try {
            // 1) Dataset WEKA completo
            Instances fullData = ArffExporter.methodsToInstances(
                    methodList,
                    projName.toUpperCase() + "_METHODS"
            );

            // Salvataggio opzionale degli ARFF (utile per debug)
            String arffBaseDir = "output/arff/" + projName.toUpperCase();
            ArffExporter.saveInstancesAsArff(fullData, arffBaseDir + "/full_dataset.arff");

            // 2) Split 70/30 preservando l'ordine (già ordinato per VersionIndex)
            int totalSize = fullData.numInstances();
            if (totalSize < 2) {
                System.err.println("WekaProcessor: dataset troppo piccolo per uno split 70/30.");
                return allEvaluations;
            }

            int trainSize = (int) Math.round(totalSize * 0.7);
            if (trainSize <= 0) trainSize = 1;
            if (trainSize >= totalSize) trainSize = totalSize - 1;

            Instances trainAll = new Instances(fullData, 0, trainSize);
            Instances testAll = new Instances(fullData, trainSize, totalSize - trainSize);
            trainAll.setClassIndex(fullData.classIndex());
            testAll.setClassIndex(fullData.classIndex());

            ArffExporter.saveInstancesAsArff(trainAll, arffBaseDir + "/train_70.arff");
            ArffExporter.saveInstancesAsArff(testAll, arffBaseDir + "/test_30.arff");

            // 3) Walk-forward sul solo train (70%)
            int versionAttrIndex = trainAll.attribute("VersionIndex").index();
            List<Integer> boundaries = computeVersionBoundaries(trainAll, versionAttrIndex);

            if (boundaries.size() < 2) {
                System.err.println("WekaProcessor: non ci sono abbastanza versioni per il walk-forward.");
            } else {
                allEvaluations.addAll(runWalkForward(trainAll, boundaries));
            }

            // 4) Salvataggio risultati walk-forward (training)
            String csvBaseDir = "output/csv/" + projName.toUpperCase();
            File csvDir = new File(csvBaseDir);
            if (!csvDir.exists() && !csvDir.mkdirs() && !csvDir.exists()) {
                throw new IOException("Impossibile creare la cartella CSV: " + csvBaseDir);
            }

            EvaluationFile trainingEvalFile =
                    new EvaluationFile(projName, allEvaluations, "details");
            trainingEvalFile.createANewFile(csvBaseDir + "/training_evaluations.csv");

            // 5) Scelta del modello migliore e valutazione sul test set
            if (!allEvaluations.isEmpty() && testAll.numInstances() > 0) {
                String bestModelName = selectBestModelByF1(allEvaluations);
                if (bestModelName != null) {
                    ClassifierEvaluation testEval =
                            evaluateBestOnTest(trainAll, testAll, bestModelName);

                    List<ClassifierEvaluation> testList = new ArrayList<>();
                    testList.add(testEval);

                    EvaluationFile testEvalFile =
                            new EvaluationFile(projName, testList, "test");
                    testEvalFile.createANewFile(csvBaseDir + "/test_evaluations.csv");

                    // aggiungo anche alla lista complessiva ritornata
                    allEvaluations.add(testEval);
                }
            }

        } catch (Exception e) {
            e.printStackTrace();
        }

        return allEvaluations;
    }

    /* =========================================================
     *                     WALK-FORWARD
     * ========================================================= */

    /**
     * Calcola gli "endpoint" delle versioni nel dataset (già
     * ordinato per VersionIndex).
     * boundaries contiene indici esclusivi:
     * es: [3, 7, 10] significa:
     *   v1 -> istanze [0..2]
     *   v2 -> [3..6]
     *   v3 -> [7..9]
     */
    private List<Integer> computeVersionBoundaries(Instances data, int versionAttrIndex) {
        List<Integer> boundaries = new ArrayList<>();
        if (data.numInstances() == 0) {
            return boundaries;
        }

        double currentVersion = data.instance(0).value(versionAttrIndex);
        for (int i = 1; i < data.numInstances(); i++) {
            double v = data.instance(i).value(versionAttrIndex);
            if (Double.compare(v, currentVersion) != 0) {
                boundaries.add(i);
                currentVersion = v;
            }
        }
        // ultimo endpoint = numero totale di istanze
        boundaries.add(data.numInstances());
        return boundaries;
    }

    /**
     * Esegue il walk-forward sul train:
     * per ogni versione i (a partire dalla seconda) fa:
     *   train = tutte le versioni < i
     *   validation = versione i
     */
    private List<ClassifierEvaluation> runWalkForward(Instances trainAll,
                                                      List<Integer> boundaries) throws Exception {
        List<ClassifierEvaluation> evaluations = new ArrayList<>();

        int wfIndex = 1; // indice iterazione walk-forward
        for (int b = 1; b < boundaries.size(); b++) {
            int trainEnd = boundaries.get(b - 1); // esclusivo
            int valEnd = boundaries.get(b);       // esclusivo

            int trainLen = trainEnd;
            int valLen = valEnd - trainEnd;

            if (trainLen <= 0 || valLen <= 0) {
                continue;
            }

            Instances wfTrain = new Instances(trainAll, 0, trainLen);
            Instances wfVal = new Instances(trainAll, trainEnd, valLen);
            wfTrain.setClassIndex(trainAll.classIndex());
            wfVal.setClassIndex(trainAll.classIndex());

            // bilanciamento solo sul train
            Instances balancedTrain = applySmoteBalancing(wfTrain);

            for (String modelName : CLASSIFIER_NAMES) {
                Classifier model = buildFilteredClassifier(balancedTrain, modelName);
                ClassifierEvaluation eval = evaluateClassifier(
                        model,
                        wfTrain,    // struttura / per Evaluation
                        wfVal,
                        modelName,
                        wfIndex
                );
                evaluations.add(eval);
            }

            wfIndex++;
        }

        return evaluations;
    }

    /* =========================================================
     *                 COSTRUZIONE MODELLI
     * ========================================================= */

    /**
     * Applica SMOTE al training set (se possibile).
     * In caso di errore o dataset troppo piccolo torna il train originale.
     */
    private Instances applySmoteBalancing(Instances train) {
        try {
            SMOTE smote = new SMOTE();
            smote.setInputFormat(train);
            return Filter.useFilter(train, smote);
        } catch (Exception e) {
            System.err.println("SMOTE fallito, uso il training originale: " + e.getMessage());
            return train;
        }
    }

    /**
     * Costruisce un FilteredClassifier che applica:
     *   - Feature selection (CfsSubsetEval + BestFirst)
     *   - Classificatore di base scelto tra IBk, NaiveBayes, RandomForest
     */
    private Classifier buildFilteredClassifier(Instances trainingData, String modelName) throws Exception {
        Classifier base;
        switch (modelName) {
            case "IBk":
                IBk ibk = new IBk();
                ibk.setKNN(5); // valore ragionevole di default
                base = ibk;
                break;
            case "NaiveBayes":
                base = new NaiveBayes();
                break;
            case "RandomForest":
                RandomForest rf = new RandomForest();
                rf.setNumIterations(100);
                base = rf;
                break;
            default:
                throw new IllegalArgumentException("Classificatore sconosciuto: " + modelName);
        }

        // filtro di feature selection
        AttributeSelection fsFilter = new AttributeSelection();
        CfsSubsetEval eval = new CfsSubsetEval();
        BestFirst search = new BestFirst();
        fsFilter.setEvaluator(eval);
        fsFilter.setSearch(search);

        FilteredClassifier filtered = new FilteredClassifier();
        filtered.setClassifier(base);
        filtered.setFilter(fsFilter);
        filtered.buildClassifier(trainingData);

        return filtered;
    }

    /* =========================================================
     *                     VALUTAZIONI
     * ========================================================= */

    /**
     * Valuta un modello su un certo test set e costruisce l'oggetto
     * ClassifierEvaluation popolando tutte le metriche richieste.
     */
    private ClassifierEvaluation evaluateClassifier(Classifier model,
                                                    Instances trainStructure,
                                                    Instances testData,
                                                    String modelName,
                                                    int wfIndex) throws Exception {

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

        ClassifierEvaluation ce = new ClassifierEvaluation(projName, wfIndex);
        ce.setTrainingSize(trainStructure.numInstances());
        ce.setTestingSize(testData.numInstances());
        ce.setClassifier(modelName);
        ce.setFeatureSelection(FEATURE_SELECTION_DESC);
        ce.setSampling(BALANCING_DESC);
        ce.setCostSensitive(COST_SENSITIVE_DESC);
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

    /**
     * Se la metrica è NaN o infinita (tipico in casi degeneri),
     * la riporta a 0.0 per evitare problemi downstream.
     */
    private static double safe(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return 0.0;
        }
        return value;
    }

    /**
     * Sceglie il modello con F1 medio migliore sulle iterazioni
     * di walk-forward.
     */
    private String selectBestModelByF1(List<ClassifierEvaluation> evals) {
        if (evals == null || evals.isEmpty()) {
            return null;
        }

        Map<String, Double> sumF1 = new HashMap<>();
        Map<String, Integer> count = new HashMap<>();

        for (ClassifierEvaluation e : evals) {
            String model = e.getModel();
            if (model == null) continue;
            double f1 = e.f1(); // getter numerico
            sumF1.merge(model, f1, Double::sum);
            count.merge(model, 1, Integer::sum);
        }

        String bestModel = null;
        double bestScore = Double.NEGATIVE_INFINITY;

        for (Map.Entry<String, Double> entry : sumF1.entrySet()) {
            String model = entry.getKey();
            double avgF1 = entry.getValue() / count.get(model);
            if (avgF1 > bestScore) {
                bestScore = avgF1;
                bestModel = model;
            }
        }

        System.out.println("Miglior modello (F1 medio WF): " + bestModel +
                " con F1 medio = " + bestScore);
        return bestModel;
    }

    /**
     * Allena il modello migliore su tutto il train (70%) e lo
     * valuta sul test (30%).
     */
    private ClassifierEvaluation evaluateBestOnTest(Instances trainAll,
                                                    Instances testAll,
                                                    String bestModelName) throws Exception {

        Instances balancedTrain = applySmoteBalancing(trainAll);
        Classifier bestModel = buildFilteredClassifier(balancedTrain, bestModelName);

        // WF index 0 per indicare "test finale"
        return evaluateClassifier(bestModel, trainAll, testAll, bestModelName, 0);
    }
}
