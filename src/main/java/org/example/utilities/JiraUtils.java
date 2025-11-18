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


    public static Version getReleaseAfterOrEqualDate(LocalDate specificDate, List<Version> versionList) {

        //sorting the releases by their date
        versionList.sort(Comparator.comparing(Version::getDate));

        //the first release which has a date after or equal to the one given is returned
        for (Version version : versionList) {
            if (!version.getDate().isBefore(specificDate)) {
                return version;
            }
        }
        return null;
    }


    public static List<Version> getAffectedVersions(JSONArray affectedVersionsArray, List<Version> versionList) throws JSONException {
        List<Version> existingAffectedVersions = new ArrayList<>();

        //iterating through the names of the affected versions
        for (int i = 0; i < affectedVersionsArray.length(); i++) {
            String affectedVersionName = affectedVersionsArray.getJSONObject(i).get("name").toString();

            //iterating through the releases to find the corresponding one
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
