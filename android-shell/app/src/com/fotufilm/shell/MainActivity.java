package com.fotufilm.shell;

import android.app.Activity;
import android.os.Bundle;
import android.util.Log;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * The whole app: load libfotufilm.so, call it through JNI, put what it said on the screen and in
 * logcat. No resources, no theme -- the manifest asks for no icon and no label resource, which is
 * also why the APK needs no resources.arsc.
 */
public final class MainActivity extends Activity {
    private static final String TAG = "FotufilmShell";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        StringBuilder report = new StringBuilder();
        try {
            report.append("library: libfotufilm.so loaded\n");
            report.append("fotufilm_api_version(): ").append(Shell.nativeApiVersion()).append('\n');
            report.append("fotufilm_capabilities(): ").append(Shell.nativeCapabilities()).append('\n');
            report.append("fotufilm_engine_create(): ").append(Shell.nativeCreateEngine()).append('\n');
            report.append("fotufilm_render(): ").append(Shell.nativeRenderProbe()).append('\n');
        } catch (Throwable failure) {
            report.append("failed: ").append(failure).append('\n');
            Log.e(TAG, "shell failed", failure);
        }

        for (String line : report.toString().split("\n")) {
            Log.i(TAG, line);
        }

        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        column.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("Fotufilm engine shell (arm64 JNI)");
        title.setTextSize(20);
        column.addView(title);

        TextView body = new TextView(this);
        body.setText(report.toString());
        body.setTextSize(13);
        body.setTextIsSelectable(true);
        body.setPadding(0, pad, 0, 0);
        column.addView(body);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(column);
        setContentView(scroll);
        setTitle("Fotufilm Shell");
        Log.i(TAG, "onCreate finished");
    }
}
