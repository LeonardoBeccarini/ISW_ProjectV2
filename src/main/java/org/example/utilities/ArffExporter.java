package org.example.utilities;

import org.example.model.Method;
import org.example.model.Metrics;
import weka.core.Attribute;
import weka.core.DenseInstance;
import weka.core.Instances;
import weka.core.converters.ArffSaver;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * Utility per costruire e salvare un dataset WEKA
 * a partire dalla lista di Method.
 * NOTA:
 * - Il dataset NON include VersionIndex come feature.
 * - La classe è nominale {no, yes} con attributo "IsBuggy".
 */
public final class ArffExporter {

    private ArffExporter() {
        // utility class
    }

    /**
     * Converte la lista di metodi in un oggetto Instances WEKA.
     * Features:
     *  0  LOC
     *  1  NumParameters
     *  2  NumBranches
     *  3  NestingDepth
     *  4  NumCodeSmells
     *  5  NumLocalVariables
     *  6  NumRevisions
     *  7  NumAuthors
     *  8  TotalStmtAdded
     *  9  TotalStmtDeleted
     *  10 MaxChurn
     *  11 AvgChurn
     *  12 HasFixHistory
     *  13 IsBuggy ({no, yes})
     */
    public static Instances methodsToInstances(List<Method> methods, String relationName) {
        if (methods == null || methods.isEmpty()) {
            throw new IllegalArgumentException("La lista di metodi è nulla o vuota.");
        }
        if (relationName == null || relationName.isBlank()) {
            relationName = "methods_dataset";
        }

        // Ordinamento temporale (utile solo per debug/consistenza su export)
        List<Method> sorted = new ArrayList<>(methods);
        sorted.sort(Comparator.comparingInt(m -> (m.getVersion() != null) ? m.getVersion().getIndex() : Integer.MAX_VALUE));

        ArrayList<Attribute> attributes = new ArrayList<>();
        attributes.add(new Attribute("LOC"));
        attributes.add(new Attribute("NumParameters"));
        attributes.add(new Attribute("NumBranches"));
        attributes.add(new Attribute("NestingDepth"));
        attributes.add(new Attribute("NumCodeSmells"));
        attributes.add(new Attribute("NumLocalVariables"));
        attributes.add(new Attribute("NumRevisions"));
        attributes.add(new Attribute("NumAuthors"));
        attributes.add(new Attribute("TotalStmtAdded"));
        attributes.add(new Attribute("TotalStmtDeleted"));
        attributes.add(new Attribute("MaxChurn"));
        attributes.add(new Attribute("AvgChurn"));
        attributes.add(new Attribute("HasFixHistory"));

        List<String> classValues = Arrays.asList("no", "yes");
        attributes.add(new Attribute("IsBuggy", classValues));

        Instances data = new Instances(relationName, attributes, sorted.size());
        data.setClassIndex(data.numAttributes() - 1);

        for (Method m : sorted) {
            double[] values = new double[data.numAttributes()];

            Metrics metrics = m.getMetrics();
            if (metrics != null) {
                values[0] = metrics.getLoc();
                values[1] = metrics.getParameterCount();
                values[2] = metrics.getNumBranches();
                values[3] = metrics.getNestingDepth();
                values[4] = metrics.getNumCodeSmells();
                values[5] = metrics.getNumLocalVariables();
                values[6] = metrics.getNumRevisions();
                values[7] = metrics.getNumAuthors();
                values[8] = metrics.getTotalStmtAdded();
                values[9] = metrics.getTotalStmtDeleted();
                values[10] = metrics.getMaxChurn();
                values[11] = metrics.getAvgChurn();
                values[12] = metrics.getHasFixHistory();
            } else {
                // nel dubbio: 0 per tutte le feature numeriche
                for (int i = 0; i < data.numAttributes() - 1; i++) {
                    values[i] = 0.0;
                }
            }

            values[data.classIndex()] = m.isBuggy() ? 1.0 : 0.0; // yes=1, no=0
            data.add(new DenseInstance(1.0, values));
        }

        return data;
    }

    /** Salva un oggetto Instances in formato .arff nel percorso indicato. */
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
