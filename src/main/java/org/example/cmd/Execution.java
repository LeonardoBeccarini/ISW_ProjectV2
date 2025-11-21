package org.example.cmd;

import org.eclipse.jgit.api.errors.GitAPIException;
import org.example.controller.GitRetriever;
import org.example.controller.JiraRetriever;
import org.example.controller.Proportion;
import org.example.model.Method;
import org.example.model.Ticket;
import org.example.model.Version;
import org.example.utilities.CsvExporter;
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

            // 4) Esempio: stampa quanti metodi hai estratto
            System.out.println("Metodi estratti: " + methods.size());

            // 5) Esportazione CSV (versioni, commit, ticket, dataset completo)
            CsvExporter.exportAll(projectName, versionList, finalTicketList, methods);
            System.out.println("CSV esportati in output/csv/" + projectName.toUpperCase());

        } catch (JSONException | IOException | GitAPIException e) {
            e.printStackTrace();
        }
    }
}
