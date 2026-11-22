package cn.local.actionrelay.updates;

import org.json.JSONArray;
import org.json.JSONObject;

/** Check the real release API contract and numeric version / download selection. */
public final class ReleaseUpdateTest {
    private static int assertions;
    static void check(boolean ok, String message) { assertions++; if(!ok)throw new AssertionError(message); }
    static void rejects(Runnable action) {
        assertions++;
        try { action.run(); throw new AssertionError("Invalid release accepted"); }
        catch (IllegalArgumentException | org.json.JSONException expected) {}
    }
    static JSONObject release(String version) {
        String base=ReleaseUpdate.REPOSITORY+"/releases/download/v"+version+"/";
        return new JSONObject().put("tag_name","v"+version).put("draft",false).put("prerelease",false)
            .put("body","新功能\n修复停止确认").put("published_at","2026-11-21T04:00:00Z")
            .put("assets",new JSONArray()
                .put(new JSONObject().put("name","ActionRelay-"+version+".apk").put("browser_download_url",base+"ActionRelay-"+version+".apk"))
                .put(new JSONObject().put("name","ActionRelay-Watch-"+version+".rpk").put("browser_download_url",base+"ActionRelay-Watch-"+version+".rpk")));
    }
    public static void main(String[] args) {
        check(AppVersion.parse("0.4.10").compareTo(AppVersion.parse("0.4.9"))>0,"numeric patch comparison");
        check(AppVersion.parse("0.10.0").compareTo(AppVersion.parse("0.9.99"))>0,"numeric minor comparison");
        check(AppVersion.parse("1.0.0").compareTo(AppVersion.parse("0.99.99"))>0,"major comparison");
        check(AppVersion.parse("v0.4.8").equals(AppVersion.parse("0.4.8")),"tag prefix");
        ReleaseUpdate newer=ReleaseUpdate.parse("0.4.8",release("0.4.10").toString());
        check(newer.isNewer && newer.version.equals("0.4.10"),"new release found");
        check(newer.apkUrl.endsWith("/v0.4.10/ActionRelay-0.4.10.apk"),"matching phone package");
        check(newer.watchUrl.endsWith("/v0.4.10/ActionRelay-Watch-0.4.10.rpk"),"matching watch package");
        check(newer.notes.contains("停止确认") && newer.publishedDate.equals("2026-11-21"),"notes and date");
        check(!ReleaseUpdate.parse("0.4.8",release("0.4.8").toString()).isNewer,"equal version");
        check(!ReleaseUpdate.parse("0.5.0",release("0.4.8").toString()).isNewer,"newer installed version is never downgraded");
        JSONObject missing=release("0.4.9").put("assets",new JSONArray());
        ReleaseUpdate absent=ReleaseUpdate.parse("0.4.8",missing.toString());
        check(absent.apkUrl==null && absent.watchUrl==null && absent.releaseUrl.endsWith("/releases/tag/v0.4.9"),"missing assets keep release page");
        JSONObject wrong=release("0.4.9");
        wrong.getJSONArray("assets").getJSONObject(0).put("browser_download_url","https://example.com/ActionRelay-0.4.9.apk");
        check(ReleaseUpdate.parse("0.4.8",wrong.toString()).apkUrl==null,"unrelated host rejected");
        JSONObject stale=release("0.4.9");
        stale.getJSONArray("assets").getJSONObject(1).put("browser_download_url",ReleaseUpdate.REPOSITORY+"/releases/download/v0.4.8/ActionRelay-Watch-0.4.8.rpk");
        check(ReleaseUpdate.parse("0.4.8",stale.toString()).watchUrl==null,"mixed-version package rejected");
        check(ReleaseUpdate.parse("0.4.8",release("0.4.9").put("body",JSONObject.NULL).toString()).notes.isEmpty(),"null notes");
        rejects(()->ReleaseUpdate.parse("0.4.8",release("0.4.9").put("draft",true).toString()));
        rejects(()->ReleaseUpdate.parse("0.4.8",release("0.4.9").put("prerelease",true).toString()));
        rejects(()->ReleaseUpdate.parse("0.4.8","not JSON"));
        rejects(()->AppVersion.parse("0.4.9-beta"));
        rejects(()->AppVersion.parse("0.04.9"));
        rejects(()->AppVersion.parse("0.4.99999999999999"));
        rejects(()->AppVersion.parse("0.4.9/../../other"));
        rejects(()->ReleaseUpdate.parse("unknown",release("0.4.9").toString()));
        System.out.println("PASS: "+assertions+" release update checks (versions, API data, matching APK/RPK and invalid responses)");
    }
}
