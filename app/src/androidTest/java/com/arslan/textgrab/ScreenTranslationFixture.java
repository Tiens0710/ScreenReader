package com.arslan.textgrab;

import android.app.Activity;
import android.os.Bundle;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Standalone synthetic screen; Java avoids relying on the target app's test classloader. */
public class ScreenTranslationFixture extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(50, 200, 50, 50);
        root.setBackgroundColor(android.graphics.Color.WHITE);
        for (String line : new String[] { "Welcome", "Good morning", "Thank you for your help" }) {
            TextView text = new TextView(this);
            text.setText(line);
            text.setTextSize(28);
            text.setTextColor(android.graphics.Color.BLACK);
            text.setPadding(0, 30, 0, 30);
            root.addView(text);
        }
        setContentView(root);
    }
}
