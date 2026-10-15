package com.smarteye.app;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Vibrator;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.text.InputType;
import android.view.KeyEvent;
import android.webkit.CookieManager;
import android.webkit.HttpAuthHandler;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Toast;

import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;

public class MainActivity extends Activity {

    private static final String DEFAULT_CLOUD_RUN_URL = "https://smart-eye-651852203203.us-central1.run.app";
    private static final String REMOTE_CONFIG_URL = "https://raw.githubusercontent.com/royanic26-cmyk-cmyk/smart-eye-app/master/config.json";

    private static final String PREFS_NAME = "SmartEyeAuthPrefs";
    private static final String KEY_USER = "auth_username";
    private static final String KEY_PASS = "auth_password";
    private static final String KEY_SERVER_URL = "server_url";
    private static final int PERMISSION_REQ_CODE = 1001;

    private WebView webView;
    private SpeechRecognizer speechRecognizer;
    private Intent speechIntent;
    private Vibrator vibrator;
    private boolean isListening = false;
    private boolean isAuthRetry = false;
    private long lastTriggerTime = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        webView = findViewById(R.id.webView);
        vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);

        setupWebView();
        initSpeech();
        fetchRemoteServerUrlAndLoad();
    }

    private void fetchRemoteServerUrlAndLoad() {
        new Thread(() -> {
            try {
                URL url = new URL(REMOTE_CONFIG_URL + "?t=" + System.currentTimeMillis());
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(3500);
                conn.setReadTimeout(3500);
                if (conn.getResponseCode() == 200) {
                    BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) sb.append(line);
                    reader.close();

                    JSONObject json = new JSONObject(sb.toString());
                    String newUrl = json.optString("server_url", "").trim();
                    if (newUrl.startsWith("https://")) {
                        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                            .edit()
                            .putString(KEY_SERVER_URL, newUrl)
                            .apply();
                    }
                }
            } catch (Exception ignored) {}
            runOnUiThread(this::checkPermissionsAndLoad);
        }).start();
    }

    private String getActiveServerUrl() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        return prefs.getString(KEY_SERVER_URL, DEFAULT_CLOUD_RUN_URL);
    }

    private void checkPermissionsAndLoad() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED ||
                checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{
                    Manifest.permission.CAMERA,
                    Manifest.permission.RECORD_AUDIO
                }, PERMISSION_REQ_CODE);
                return;
            }
        }
        webView.loadUrl(getActiveServerUrl());
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQ_CODE) {
            webView.loadUrl(getActiveServerUrl());
        }
    }

    private void setupWebView() {
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setUserAgentString(settings.getUserAgentString() + " Chrome/120.0.0.0 Mobile Safari/537.36");

        CookieManager cookieManager = CookieManager.getInstance();
        cookieManager.setAcceptCookie(true);
        cookieManager.setAcceptThirdPartyCookies(webView, true);

        webView.addJavascriptInterface(new WebInterface(), "AndroidNative");

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onPermissionRequest(final PermissionRequest request) {
                runOnUiThread(() -> request.grant(request.getResources()));
            }
        });

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                isAuthRetry = false;
                webView.evaluateJavascript(
                    "window.startVoicePrompt = function() { AndroidNative.triggerVoice(); };",
                    null
                );
            }

            @Override
            public void onReceivedHttpAuthRequest(WebView view, HttpAuthHandler handler, String host, String realm) {
                SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
                String savedUser = prefs.getString(KEY_USER, "smart");
                String savedPass = prefs.getString(KEY_PASS, "eye123456");

                if (!savedUser.isEmpty() && !savedPass.isEmpty() && !isAuthRetry) {
                    isAuthRetry = true;
                    handler.proceed(savedUser, savedPass);
                } else {
                    runOnUiThread(() -> showLoginDialog(handler));
                }
            }
        });
    }

    private void showLoginDialog(final HttpAuthHandler handler) {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("🔐 ورود به چشم هوشمند");

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(50, 40, 50, 10);

        final EditText userInput = new EditText(this);
        userInput.setHint("نام کاربری (Username)");
        userInput.setText(prefs.getString(KEY_USER, "smart"));
        layout.addView(userInput);

        final EditText passInput = new EditText(this);
        passInput.setHint("رمز عبور (Password)");
        passInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        passInput.setText(prefs.getString(KEY_PASS, "eye123456"));
        layout.addView(passInput);

        builder.setView(layout);
        builder.setCancelable(false);

        builder.setPositiveButton("ورود و ذخیره", (dialog, which) -> {
            String u = userInput.getText().toString().trim();
            String p = passInput.getText().toString().trim();

            SharedPreferences.Editor editor = prefs.edit();
            editor.putString(KEY_USER, u);
            editor.putString(KEY_PASS, p);
            editor.apply();

            isAuthRetry = true;
            handler.proceed(u, p);
        });

        builder.setNegativeButton("لغو", (dialog, which) -> {
            handler.cancel();
            Toast.makeText(MainActivity.this, "نام کاربری و رمز عبور را وارد کنید.", Toast.LENGTH_LONG).show();
        });

        builder.show();
    }

    private void initSpeech() {
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this);
        speechIntent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        speechIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        speechIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "fa-IR");

        speechRecognizer.setRecognitionListener(new RecognitionListener() {
            @Override
            public void onReadyForSpeech(Bundle params) {
                isListening = true;
                if (vibrator != null) vibrator.vibrate(80);
            }

            @Override public void onBeginningOfSpeech() {}
            @Override public void onRmsChanged(float rmsdB) {}
            @Override public void onBufferReceived(byte[] buffer) {}
            @Override public void onEndOfSpeech() { isListening = false; }
            @Override public void onError(int error) { isListening = false; }

            @Override
            public void onResults(Bundle results) {
                isListening = false;
                ArrayList<String> matches = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                if (matches != null && !matches.isEmpty()) {
                    String text = matches.get(0).replace("'", "\\'");
                    webView.evaluateJavascript("captureAndSend('" + text + "');", null);
                }
            }

            @Override public void onPartialResults(Bundle partialResults) {}
            @Override public void onEvent(int eventType, Bundle params) {}
        });
    }

    private void startListening() {
        runOnUiThread(() -> {
            try {
                if (isListening) speechRecognizer.cancel();
                speechRecognizer.startListening(speechIntent);
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
    }

    public class WebInterface {
        @JavascriptInterface
        public void triggerVoice() {
            startListening();
        }
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int keyCode = event.getKeyCode();
        int action = event.getAction();

        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_ENTER) {
            if (action == KeyEvent.ACTION_DOWN) {
                long now = System.currentTimeMillis();
                if (now - lastTriggerTime > 1200) {
                    lastTriggerTime = now;
                    startListening();
                }
            }
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (speechRecognizer != null) {
            speechRecognizer.destroy();
        }
    }
}
