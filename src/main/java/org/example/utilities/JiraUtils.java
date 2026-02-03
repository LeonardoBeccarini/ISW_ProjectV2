package org.example.utilities;

import org.example.model.Version;
import org.json.JSONArray;
import org.json.JSONException;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

public class JiraUtils {

    private JiraUtils(){
        // Add a private constructor to hide the implicit public one.
    }

    public static Version getReleaseAfterOrEqualDate(LocalDate specificDate, List<Version> versionList) {
        if (specificDate == null || versionList == null || versionList.isEmpty()) return null;

        int lo = 0;
        int hi = versionList.size() - 1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            LocalDate d = versionList.get(mid).getDate();
            if (d.isBefore(specificDate)) lo = mid + 1;
            else hi = mid - 1;
        }
        return (lo < versionList.size()) ? versionList.get(lo) : null;
    }


    public static List<Version> getAffectedVersions(JSONArray affectedVersionsArray, List<Version> versionList) throws JSONException {
        List<Version> existingAffectedVersions = new ArrayList<>();

        //itera sui nomi
        for (int i = 0; i < affectedVersionsArray.length(); i++) {
            String affectedVersionName = affectedVersionsArray.getJSONObject(i).get("name").toString();

            //itera sulle release per trovarne una corrispondente
            for (Version release : versionList) {
                if (Objects.equals(affectedVersionName, release.getName())) {
                    existingAffectedVersions.add(release);
                    break;
                }
            }
        }
        existingAffectedVersions.sort(Comparator.comparing(Version::getDate));
        return existingAffectedVersions;
    }
}
