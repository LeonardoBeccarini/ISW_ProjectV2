package org.example.cmd;

import org.eclipse.jgit.api.errors.GitAPIException;
import org.example.controller.GitRetriever;
import org.example.controller.JiraRetriever;
import org.example.controller.Proportion;
import org.example.controller.WekaProcessor;
import org.example.model.ClassifierEvaluation;
import org.example.model.Method;
import org.example.model.Ticket;
import org.example.model.Version;
import org.example.utilities.CsvExporter;
import org.example.utilities.DatasetDiagnostics;
import org.json.JSONException;

import java.io.IOException;
import java.util.List;

public class Execution {
    public static void analyzeProject(String projectName){
        try{
            JiraRetriever jiraRetriever = new JiraRetriever(projectName);
            Proportion proportion = new Proportion();
            List<Version> versionList = jiraRetriever.retrieveVersions();
            List<Ticket> tempTicketList = jiraRetriever.retrieveTickets(versionList);
            List<Ticket> finalTicketList = proportion.processProportion(tempTicketList,versionList);

            // 2) URL del repo Git (es. progetti Apache)
            String repoUrl = "https://github.com/apache/" + projectName.toLowerCase() + ".git";

            // 3) Estrazione metodi + metriche + labeling
            GitRetriever gitRetriever = new GitRetriever(projectName, repoUrl, versionList, finalTicketList);
            List<Method> methods = gitRetriever.extractMethodsAndMetrics();

            // 4) Stampa quanti metodi sono stati estratti
            System.out.println("Metodi estratti: " + methods.size());

            DatasetDiagnostics.analyzeLabelingQuality(projectName, methods, finalTicketList, versionList);

            // 5) Esportazione CSV (versioni, commit, ticket, dataset completo)
            CsvExporter.exportAll(projectName, versionList, finalTicketList, methods);
            System.out.println("CSV esportati in output/csv/" + projectName.toUpperCase());

            // 6) Predizione
            WekaProcessor wekaProcessor = new WekaProcessor(projectName, methods);
            List<ClassifierEvaluation> evaluations = wekaProcessor.runPredictionPipeline();
            System.out.println("Pipeline WEKA completata. Numero valutazioni generate: "
                    + evaluations.size());
            System.out.println("Risultati salvati in output/csv/" + projectName.toUpperCase()
                    + " e output/arff/" + projectName.toUpperCase());

        } catch (JSONException | IOException | GitAPIException e) {
            e.printStackTrace();
        }
    }
}
