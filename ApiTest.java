import java.io.*;
import java.net.*;

public class ApiTest {
    static String get(String urlStr) throws Exception {
        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection(Proxy.NO_PROXY);
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(5000);
        conn.setRequestMethod("GET");
        int code = conn.getResponseCode();
        InputStream is = (code >= 200 && code < 400) ? conn.getInputStream() : conn.getErrorStream();
        BufferedReader br = new BufferedReader(new InputStreamReader(is, "UTF-8"));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = br.readLine()) != null) sb.append(line);
        br.close();
        return "HTTP " + code + " | " + sb.substring(0, Math.min(400, sb.length()));
    }
    public static void main(String[] args) throws Exception {
        String[] urls = {
            "http://localhost:8080/api/statistics",
            "http://localhost:8080/api/config/scheduler-logs",
            "http://localhost:8080/api/statistics/by-city",
            "http://localhost:8080/api/statistics/by-type"
        };
        for (String u : urls) {
            try { System.out.println("[OK] " + u + "\n  " + get(u)); }
            catch (Exception e) { System.out.println("[FAIL] " + u + "\n  " + e.getClass().getSimpleName() + ": " + e.getMessage()); }
        }
    }
}
