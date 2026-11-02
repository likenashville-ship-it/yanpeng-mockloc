package com.yanpeng.mockloc;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {

    private static final int REQ_PERMS = 42;
    private static final String[] CITY_CHIPS = {"北京", "上海", "广州", "深圳", "杭州", "绍兴"};

    private WebView webMap;
    private EditText etSearch, etLat, etLng;
    private TextView tvCoord, tvStatus;
    private Button btnToggle, btnSearch, btnApplyCoord;
    private ListView lvResults;
    private LinearLayout chipRow;

    private double gcjLat = 30.2741; // Hangzhou default-ish / will set Shanghai-ish
    private double gcjLng = 120.1551;
    private boolean mocking = false;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<SearchItem> searchItems = new ArrayList<>();
    private ArrayAdapter<String> resultsAdapter;

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        webMap = findViewById(R.id.webMap);
        etSearch = findViewById(R.id.etSearch);
        etLat = findViewById(R.id.etLat);
        etLng = findViewById(R.id.etLng);
        tvCoord = findViewById(R.id.tvCoord);
        tvStatus = findViewById(R.id.tvStatus);
        btnToggle = findViewById(R.id.btnToggle);
        btnSearch = findViewById(R.id.btnSearch);
        btnApplyCoord = findViewById(R.id.btnApplyCoord);
        lvResults = findViewById(R.id.lvResults);
        chipRow = findViewById(R.id.chipRow);

        resultsAdapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, new ArrayList<>());
        lvResults.setAdapter(resultsAdapter);
        lvResults.setOnItemClickListener((parent, view, position, id) -> {
            if (position >= 0 && position < searchItems.size()) {
                SearchItem it = searchItems.get(position);
                setGcj(it.lat, it.lng, true);
                hideResults();
            }
        });

        setupChips();
        setupWebView();
        updateCoordText();

        btnSearch.setOnClickListener(v -> doSearch());
        etSearch.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH
                    || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER)) {
                doSearch();
                return true;
            }
            return false;
        });
        btnApplyCoord.setOnClickListener(v -> applyManual());
        btnToggle.setOnClickListener(v -> toggleMock());

        requestNeededPermissions();
    }

    private void setupChips() {
        for (String city : CITY_CHIPS) {
            TextView chip = new TextView(this);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
            );
            lp.setMargins(0, 0, 16, 0);
            chip.setLayoutParams(lp);
            chip.setText(city);
            chip.setTextColor(ContextCompat.getColor(this, R.color.primary));
            chip.setTextSize(13f);
            chip.setPadding(28, 12, 28, 12);
            chip.setBackgroundResource(R.drawable.bg_chip);
            chip.setOnClickListener(v -> {
                etSearch.setText(city);
                doSearch();
            });
            chipRow.addView(chip);
        }
    }

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    private void setupWebView() {
        WebSettings s = webMap.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        s.setAllowFileAccess(true);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        webMap.setWebChromeClient(new WebChromeClient());
        webMap.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                moveMapJs(gcjLat, gcjLng, 12);
            }
        });
        webMap.addJavascriptInterface(new MapBridge(), "AndroidBridge");
        webMap.loadUrl("file:///android_asset/map.html");
    }

    private void requestNeededPermissions() {
        List<String> need = new ArrayList<>();
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            need.add(Manifest.permission.ACCESS_FINE_LOCATION);
            need.add(Manifest.permission.ACCESS_COARSE_LOCATION);
        }
        if (Build.VERSION.SDK_INT >= 33
                && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            need.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        if (!need.isEmpty()) {
            ActivityCompat.requestPermissions(this, need.toArray(new String[0]), REQ_PERMS);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
    }

    private void applyManual() {
        try {
            double lat = Double.parseDouble(etLat.getText().toString().trim());
            double lng = Double.parseDouble(etLng.getText().toString().trim());
            if (lat < -90 || lat > 90 || lng < -180 || lng > 180) {
                toast("经纬度超出范围");
                return;
            }
            setGcj(lat, lng, true);
        } catch (Exception e) {
            toast("请输入有效数字坐标 (GCJ-02)");
        }
    }

    private void setGcj(double lat, double lng, boolean moveMap) {
        gcjLat = lat;
        gcjLng = lng;
        etLat.setText(String.format(java.util.Locale.US, "%.6f", lat));
        etLng.setText(String.format(java.util.Locale.US, "%.6f", lng));
        updateCoordText();
        if (moveMap) moveMapJs(lat, lng, 14);
        if (mocking) {
            // refresh running mock with new point
            startMockInternal();
        }
    }

    private void updateCoordText() {
        double[] wgs = CoordTransform.gcj02ToWgs84(gcjLat, gcjLng);
        tvCoord.setText(String.format(java.util.Locale.US,
                "GCJ-02: %.6f , %.6f\nWGS84: %.6f , %.6f",
                gcjLat, gcjLng, wgs[0], wgs[1]));
    }

    private void moveMapJs(double lat, double lng, int zoom) {
        String js = String.format(java.util.Locale.US,
                "if(window.setCenter){setCenter(%f,%f,%d);}", lat, lng, zoom);
        webMap.evaluateJavascript(js, null);
    }

    private void doSearch() {
        String q = etSearch.getText().toString().trim();
        if (TextUtils.isEmpty(q)) {
            toast("请输入城市或地点");
            return;
        }
        tvStatus.setText("搜索中…");
        io.execute(() -> {
            List<SearchItem> items = searchNominatim(q);
            if (items.isEmpty()) {
                // try as city with China suffix
                items = searchNominatim(q + " 中国");
            }
            List<SearchItem> finalItems = items;
            main.post(() -> {
                searchItems.clear();
                searchItems.addAll(finalItems);
                List<String> labels = new ArrayList<>();
                for (SearchItem it : finalItems) labels.add(it.display);
                resultsAdapter.clear();
                resultsAdapter.addAll(labels);
                resultsAdapter.notifyDataSetChanged();
                if (finalItems.isEmpty()) {
                    hideResults();
                    tvStatus.setText("未找到结果，可手输 GCJ-02 经纬度");
                    toast("未找到，请手输经纬度或换关键词");
                } else {
                    lvResults.setVisibility(View.VISIBLE);
                    tvStatus.setText("找到 " + finalItems.size() + " 条，点选即可");
                    // auto jump to first
                    SearchItem first = finalItems.get(0);
                    setGcj(first.lat, first.lng, true);
                }
            });
        });
    }

    /**
     * Nominatim returns WGS84. Convert to GCJ-02 for map display.
     */
    private List<SearchItem> searchNominatim(String query) {
        List<SearchItem> out = new ArrayList<>();
        HttpURLConnection conn = null;
        try {
            String url = "https://nominatim.openstreetmap.org/search?format=json&limit=8&accept-language=zh-CN&q="
                    + URLEncoder.encode(query, "UTF-8");
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(12000);
            conn.setReadTimeout(12000);
            conn.setRequestProperty("User-Agent", "GuanJiaMockLoc/1.0 (Android; personal use)");
            conn.setRequestProperty("Accept", "application/json");
            int code = conn.getResponseCode();
            if (code != 200) return out;
            BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
            br.close();
            JSONArray arr = new JSONArray(sb.toString());
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                double wLat = o.getDouble("lat");
                double wLng = o.getDouble("lon");
                double[] gcj = CoordTransform.wgs84ToGcj02(wLat, wLng);
                String name = o.optString("display_name", query);
                SearchItem it = new SearchItem();
                it.lat = gcj[0];
                it.lng = gcj[1];
                it.display = name.length() > 60 ? name.substring(0, 60) + "…" : name;
                out.add(it);
            }
        } catch (Exception e) {
            // ignore; caller shows empty
        } finally {
            if (conn != null) conn.disconnect();
        }
        return out;
    }

    private void hideResults() {
        lvResults.setVisibility(View.GONE);
    }

    private void toggleMock() {
        if (mocking) {
            stopMockInternal();
        } else {
            if (!MockLocationService.isMockAppSelected(this)) {
                toast(getString(R.string.need_mock_app));
                try {
                    startActivity(new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS));
                } catch (Exception e) {
                    try {
                        startActivity(new Intent(Settings.ACTION_DEVICE_INFO_SETTINGS));
                    } catch (Exception ignored) {
                    }
                }
                return;
            }
            startMockInternal();
        }
    }

    private void startMockInternal() {
        double[] wgs = CoordTransform.gcj02ToWgs84(gcjLat, gcjLng);
        Intent i = new Intent(this, MockLocationService.class);
        i.setAction(MockLocationService.ACTION_START);
        i.putExtra(MockLocationService.EXTRA_LAT, wgs[0]);
        i.putExtra(MockLocationService.EXTRA_LNG, wgs[1]);
        i.putExtra(MockLocationService.EXTRA_GCJ_LAT, gcjLat);
        i.putExtra(MockLocationService.EXTRA_GCJ_LNG, gcjLng);
        ContextCompat.startForegroundService(this, i);
        mocking = true;
        btnToggle.setText(R.string.stop_mock);
        btnToggle.setBackgroundTintList(ContextCompat.getColorStateList(this, R.color.danger));
        tvStatus.setText("模拟中 · 已注入 GPS + NETWORK (WGS84)");
        toast("已开始模拟");
    }

    private void stopMockInternal() {
        Intent i = new Intent(this, MockLocationService.class);
        i.setAction(MockLocationService.ACTION_STOP);
        startService(i);
        mocking = false;
        btnToggle.setText(R.string.start_mock);
        btnToggle.setBackgroundTintList(ContextCompat.getColorStateList(this, R.color.accent));
        tvStatus.setText("未模拟");
        toast("已停止模拟");
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    @Override
    protected void onDestroy() {
        io.shutdownNow();
        super.onDestroy();
    }

    public class MapBridge {
        @JavascriptInterface
        public void onMapClick(double lat, double lng) {
            main.post(() -> {
                setGcj(lat, lng, false);
                hideResults();
            });
        }

        @JavascriptInterface
        public void onMapMove(double lat, double lng) {
            main.post(() -> {
                gcjLat = lat;
                gcjLng = lng;
                etLat.setText(String.format(java.util.Locale.US, "%.6f", lat));
                etLng.setText(String.format(java.util.Locale.US, "%.6f", lng));
                updateCoordText();
            });
        }
    }

    private static class SearchItem {
        double lat;
        double lng;
        String display;
    }
}
