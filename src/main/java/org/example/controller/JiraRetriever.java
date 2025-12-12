package org.example.controller;

import org.example.model.Ticket;
import org.example.model.Version;
import org.example.utilities.JiraUtils;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static org.example.utilities.JsonUtilities.readJsonFromUrl;

public class JiraRetriever {
    private String projectName;

    public JiraRetriever(String projectName) {
        this.projectName = projectName;
    }

    public List<Version> retrieveVersions() throws JSONException, IOException {
        List<Version> out = new ArrayList<>();
        String url = "https://issues.apache.org/jira/rest/api/2/project/" + projectName;
        JSONObject json = readJsonFromUrl(url);
        JSONArray versions = json.getJSONArray("versions");

        for (int i = 0; i < versions.length(); i++) {
            JSONObject vj = versions.getJSONObject(i);
            if (!vj.optBoolean("released", false)) continue;
            if (!vj.has("releaseDate")) continue;

            String name = vj.optString("name", "");
            String id   = vj.optString("id", name);
            LocalDate date = LocalDate.parse(vj.getString("releaseDate"));
            out.add(new Version(id, name, date));
        }

        // Ordina dalla più vecchia alla più recente
        out.sort(Comparator.comparing(Version::getDate));
        int j=0;
        for(Version v:out){
            v.setIndex(++j);
        }
        return out;
    }


    public List<Ticket> retrieveTickets(List<Version> versionList) {
        List<Ticket> retrievedTickets = new ArrayList<>();
        int i = 0, total;

        do {
            // Paginazione: scarica 1000 ticket alla volta
            int j = i + 1000;
            String url = "https://issues.apache.org/jira/rest/api/2/search?jql=" +
                    "project%3D%22" + projectName + "%22%20AND%20" +
                    "issuetype%3DBug%20AND%20" +
                    "(status%3DClosed%20OR%20status%3DResolved)%20AND%20" +
                    "resolution%3DFixed&fields=key,resolutiondate,versions,created&startAt=" + i + "&maxResults=" + j;

            try {
                JSONObject json = readJsonFromUrl(url);
                JSONArray issues = json.getJSONArray("issues");
                total = json.getInt("total");

                for (; i < total && i < j; i++) {
                    JSONObject o = issues.getJSONObject(i % 1000);
                    String key = o.getString("key");

                    String created = o.getJSONObject("fields").optString("created", null);
                    String resolved = o.getJSONObject("fields").optString("resolutiondate", null);

                    // 1. Scarta ticket senza date fondamentali
                    if (created == null || resolved == null) continue;

                    LocalDate creation = LocalDate.parse(created.substring(0, 10));
                    LocalDate resolution = LocalDate.parse(resolved.substring(0, 10));

                    Version ov = JiraUtils.getReleaseAfterOrEqualDate(creation, versionList);
                    Version fv = JiraUtils.getReleaseAfterOrEqualDate(resolution, versionList);

                    // 2. Scarta ticket se OV o FV non sono state trovate nella lista delle release
                    if (ov == null || fv == null) continue;

                    // 3. Controllo di coerenza base: Il ticket non può essere risolto prima di essere aperto
                    // Timeline violata: OV > FV
                    if (ov.getDate().isAfter(fv.getDate())) continue;

                    // Gestione Affected Versions (AV)
                    JSONArray affectedVersionList = o.getJSONObject("fields").getJSONArray("versions");
                    List<Version> av = JiraUtils.getAffectedVersions(affectedVersionList, versionList);

                    // 4. Controllo di coerenza sulle Affected Versions (SOLO se presenti)
                    if (!av.isEmpty()) {
                        Version firstAV = av.getFirst();
                        Version lastAV = av.getLast();

                        // Controllo A: L'Affected Version deve esistere PRIMA (o uguale) dell'apertura del ticket.
                        // Se OV < AV, stiamo dicendo che il bug affetta una versione futura che non esiste ancora.
                        if (ov.getDate().isBefore(firstAV.getDate())) {
                            continue;
                        }

                        // Controllo B: La Fixed Version deve essere successiva all'ultima versione affetta.
                        // Se FV è prima o uguale all'ultima AV, c'è un'incongruenza temporale.
                        if (!fv.getDate().isAfter(lastAV.getDate())) {
                            continue;
                        }
                    }

                    Ticket t = new Ticket();
                    t.setKey(key);
                    t.setCreationDate(creation);
                    t.setResolutionDate(resolution);
                    t.setOpeningVersion(ov);
                    t.setFixedVersion(fv);
                    t.setAffectedVersions(av);
                    t.setInjectedVersionTemp();
                    t.setAssociatedCommits(new ArrayList<>());

                    if (ov.getId() != versionList.getFirst().getId()) {
                        retrievedTickets.add(t);
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
                // In caso di errore nel fetch, proviamo a uscire dal loop o gestire l'eccezione
                // Impostare total = i forza l'uscita dal while esterno
                total = i;
            }
        } while (i < total);

        // Ordina per data di risoluzione (utile per processare cronologicamente)
        retrievedTickets.sort(Comparator.comparing(Ticket::getResolutionDate));
        return retrievedTickets;
    }
}
