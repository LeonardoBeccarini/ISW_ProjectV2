package org.example.utilities;

import org.example.model.Method;
import org.example.model.Metrics;
import org.example.model.Version;
import weka.core.Attribute;
import weka.core.DenseInstance;
import weka.core.Instances;
import weka.core.converters.ArffSaver;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Utility per costruire (e opzionalmente salvare) un dataset WEKA
 * a partire dalla lista di Method.
 *
 * - Ogni Method diventa una istanza.
 * - Si usano SOLO feature numeriche + label Buggy.
 * - I metodi sono ordinati per VersionIndex per preservare la
 *   dimensione temporale (necessaria al walk-forward).
 */
public final class ArffExporter {

    private ArffExporter() {
        // utility class, nessuna istanza
    }

    /**
     * Converte la lista di metodi in un oggetto Instances WEKA.
     * <p>
     * Attributi:
     *   0: VersionIndex
     *   1: Loc
     *   2: CyclomaticComplexity
     *   3: NestingDepth
     *   4: NumCodeSmells
     *   5: ParameterCount
     *   6: NumAuthors
     *   7: MaxChurn
     *   8: AvgChurn
     *   9: TotalStmtAdded
     *   10: TotalStmtDeleted
     *   11: NumRevisions
     *   12: HasFixHistory
     *   13: Buggy (classe nominale {false,true})
     */
    public static Instances methodsToInstances(List<Method> methods, String relationName) {
        if (methods == null || methods.isEmpty()) {
            throw new IllegalArgumentException("La lista di metodi è nulla o vuota.");
        }
        if (relationName == null || relationName.isBlank()) {
            relationName = "methods_dataset";
        }

        // Copia + ordinamento per versione (walk-forward)
        List<Method> sorted = new ArrayList<>(methods);
        sorted.sort(Comparator.comparingInt(m -> {
            Version v = m.getVersion();
            return (v != null) ? v.getIndex() : Integer.MAX_VALUE;
        }));

        // Definizione attributi
        ArrayList<Attribute> attributes = new ArrayList<>();

        attributes.add(new Attribute("VersionIndex"));          // 0
        attributes.add(new Attribute("Loc"));                   // 1
        attributes.add(new Attribute("CyclomaticComplexity"));  // 2
        attributes.add(new Attribute("NestingDepth"));          // 3
        attributes.add(new Attribute("NumCodeSmells"));         // 4
        attributes.add(new Attribute("ParameterCount"));        // 5
        attributes.add(new Attribute("NumAuthors"));            // 6
        attributes.add(new Attribute("MaxChurn"));              // 7
        attributes.add(new Attribute("AvgChurn"));              // 8
        attributes.add(new Attribute("TotalStmtAdded"));        // 9
        attributes.add(new Attribute("TotalStmtDeleted"));      // 10
        attributes.add(new Attribute("NumRevisions"));          // 11
        attributes.add(new Attribute("HasFixHistory"));         // 12

        // Attributo classe: Buggy {false, true}
        List<String> classValues = new ArrayList<>();
        classValues.add("false");
        classValues.add("true");
        Attribute classAttr = new Attribute("Buggy", classValues);
        attributes.add(classAttr);                              // 13

        Instances data = new Instances(relationName, attributes, sorted.size());
        data.setClassIndex(attributes.size() - 1);

        // Riempimento istanze
        for (Method m : sorted) {
            double[] values = new double[attributes.size()];

            int versionIndex = -1;
            Version v = m.getVersion();
            if (v != null) {
                versionIndex = v.getIndex();
            }
            values[0] = versionIndex;

            Metrics metrics = m.getMetrics();
            if (metrics != null) {
                values[1]  = metrics.getLoc();
                values[2]  = metrics.getCyclomaticComplexity();
                values[3]  = metrics.getNestingDepth();
                values[4]  = metrics.getNumCodeSmells();
                values[5]  = metrics.getParameterCount();
                values[6]  = metrics.getNumAuthors();
                values[7]  = metrics.getMaxChurn();
                values[8]  = metrics.getAvgChurn();
                values[9]  = metrics.getTotalStmtAdded();
                values[10] = metrics.getTotalStmtDeleted();
                values[11] = metrics.getNumRevisions();
                values[12] = metrics.getHasFixHistory();
            } else {
                // se per qualche motivo è null, mettiamo 0
                for (int i = 1; i <= 12; i++) {
                    values[i] = 0.0;
                }
            }

            // classe
            values[13] = classAttr.indexOfValue(m.isBuggy() ? "true" : "false");

            data.add(new DenseInstance(1.0, values));
        }

        return data;
    }

    /**
     * Salva un oggetto Instances in formato .arff nel percorso indicato.
     */
    public static void saveInstancesAsArff(Instances data, String filePath) throws IOException {
        if (data == null || filePath == null || filePath.isBlank()) {
            return;
        }

        File out = new File(filePath);
        File parent = out.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs() && !parent.exists()) {
            throw new IOException("Impossibile creare la directory: " + parent);
        }

        ArffSaver saver = new ArffSaver();
        saver.setInstances(data);
        saver.setFile(out);
        saver.writeBatch();
    }
}
