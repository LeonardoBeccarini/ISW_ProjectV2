package org.example.model;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.List;

/** File finale per ogni classificatore */
public class EvaluationFile {

    private final String projName;
    private final List<ClassifierEvaluation> evaluationsList;
    private final String description;

    public EvaluationFile(String projName, List<ClassifierEvaluation> evaluationsList, String description) {
        this.projName = projName;
        this.evaluationsList = evaluationsList;
        this.description = description;
    }

    public void createANewFile(String pathFile) throws IOException {
        File file = new File(pathFile);
        File dir = file.getParentFile();
        if (dir != null && !dir.exists() && !dir.mkdirs() && !dir.exists()) {
            throw new IOException("Impossibile creare la cartella: " + dir);
        }

        try (FileWriter fw = new FileWriter(file, false)) {
            // Header senza virgola finale
            StringBuilder h = new StringBuilder();
            h.append("PROJ,WF_ITER,");
            if (this.description.equals("details")) {
                h.append("TR_SIZE,TE_SIZE,");
            }
            h.append("MODEL,FEATURE_SELECTION,BALANCING,COST_SENSITIVE,THRESHOLD_STRATEGY,THRESHOLD,")
                    .append("PRECISION,RECALL,F1,SPECIFICITY,BALANCED_ACCURACY,G_MEAN,MCC,KAPPA,AUC,TP,FP,TN,FN");
            fw.write(h.toString());
            fw.write("\n");

            for (ClassifierEvaluation e : this.evaluationsList) {
                StringBuilder r = new StringBuilder();
                // campi comuni
                r.append(csv(projName)).append(",");
                r.append(e.getWalkForwardIterationIndex()).append(",");
                if (this.description.equals("details")) {
                    r.append(e.getTrainingSize()).append(",").append(e.getTestingSize()).append(",");
                }
                r.append(csv(e.getClassifier())).append(",")
                        .append(csv(e.getFeatureSelection())).append(",")
                        .append(csv(e.getSampling())).append(",")
                        .append(csv(e.getCostSensitive())).append(",")
                        .append(csv(e.getThresholdStrategy())).append(",")
                        .append(csv(e.getThreshold())).append(",")
                        .append(csv(e.getPrecision())).append(",")
                        .append(csv(e.getRecall())).append(",")
                        .append(csv(e.getF1())).append(",")
                        .append(csv(e.getSpecificity())).append(",")
                        .append(csv(e.getBalancedAccuracy())).append(",")
                        .append(csv(e.getGMean())).append(",")
                        .append(csv(e.getMcc())).append(",")
                        .append(csv(e.getKappa())).append(",")
                        .append(csv(e.getAuc())).append(",")
                        .append(csv(e.getTp())).append(",")
                        .append(csv(e.getFp())).append(",")
                        .append(csv(e.getTn())).append(",")
                        .append(csv(e.getFn()));

                fw.write(r.toString());
                fw.write("\n");
            }
        }
    }

    /** Escaping CSV: quota se contiene virgole/virgolette/newline. */
    private static String csv(String s) {
        if (s == null) return "";
        boolean need = s.indexOf(',') >= 0 || s.indexOf('"') >= 0 || s.indexOf('\n') >= 0 || s.indexOf('\r') >= 0;
        if (!need) return s;
        return "\"" + s.replace("\"", "\"\"") + "\"";
    }
}
