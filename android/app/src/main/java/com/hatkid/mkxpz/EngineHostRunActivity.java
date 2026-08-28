package com.hatkid.mkxpz;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Toast;

import java.io.File;

/** Routes one enginehost family contract to the bundled RPG Maker runtimes. */
public class EngineHostRunActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        String path = getIntent().getStringExtra("path");
        String context = getIntent().getStringExtra("engineContext");
        if (path == null || !new File(path).isDirectory()) {
            fail("enginehost did not provide a valid RPG Maker game folder");
            return;
        }

        Class<?> runtime;
        if ("xp".equals(context) || "vx".equals(context) || "vxace".equals(context)) {
            runtime = MainActivity.class;
        } else if ("mv".equals(context) || "mz".equals(context)) {
            runtime = WebGameActivity.class;
        } else {
            fail("Unsupported RPG Maker engineContext: " + context);
            return;
        }

        Intent intent = new Intent(this, runtime);
        intent.putExtras(getIntent());
        startActivity(intent);
        finish();
    }

    private void fail(String message) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
        finish();
    }
}
