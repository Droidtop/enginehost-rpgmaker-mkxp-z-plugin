package com.hatkid.mkxpz;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import java.io.File;
import java.io.IOException;

/** In-place HTML5 runtime for RPG Maker MV and MZ deployments. */
public class WebGameActivity extends Activity {
    private WebView webView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().getDecorView().setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_FULLSCREEN |
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        );

        File entry;
        try {
            entry = resolveEntryPoint();
        } catch (IOException | IllegalArgumentException exception) {
            Toast.makeText(this, exception.getMessage(), Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        webView = new WebView(this);
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(false);
        // RPG Maker loads JSON, plugins, audio, and images from sibling files.
        settings.setAllowFileAccessFromFileURLs(true);
        settings.setAllowUniversalAccessFromFileURLs(false);
        settings.setMediaPlaybackRequiresUserGesture(false);
        webView.setWebViewClient(new WebViewClient());
        webView.setWebChromeClient(new WebChromeClient());
        setContentView(webView);
        webView.loadUrl(entry.toURI().toString());
    }

    private File resolveEntryPoint() throws IOException {
        File root = new File(getIntent().getStringExtra("path")).getCanonicalFile();
        String requested = getIntent().getStringExtra("execFile");
        File entry;
        if (requested != null && !requested.trim().isEmpty()) {
            entry = new File(root, requested).getCanonicalFile();
        } else {
            File nested = new File(root, "www/index.html");
            entry = nested.isFile() ? nested.getCanonicalFile() : new File(root, "index.html").getCanonicalFile();
        }
        String rootPrefix = root.getPath() + File.separator;
        if (!entry.getPath().startsWith(rootPrefix) || !entry.isFile()) {
            throw new IllegalArgumentException("RPG Maker MV/MZ index.html was not found inside the game folder");
        }
        return entry;
    }

    @Override
    protected void onDestroy() {
        if (webView != null) webView.destroy();
        super.onDestroy();
    }
}
