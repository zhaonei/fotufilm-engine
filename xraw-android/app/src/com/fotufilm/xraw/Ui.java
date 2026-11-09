package com.fotufilm.xraw;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;

/** 极简暗色 UI 构件库（无资源、无 AndroidX，全部代码构建） */
public final class Ui {
    public static final int BG = 0xFF111214;
    public static final int PANEL = 0xFF1A1C1F;
    public static final int PANEL2 = 0xFF20232A;
    public static final int LINE = 0xFF2A2D31;
    public static final int TEXT = 0xFFE6E6E6;
    public static final int TEXT_DIM = 0xFF8A8F96;
    public static final int ACCENT = 0xFFE8B44A;

    public interface OnInt { void run(int value); }

    private Ui() { }

    public static int dp(Context c, float v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    public static GradientDrawable bg(int color, int radiusDp, Context c) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(radiusDp <= 0 ? 0 : dp(c, radiusDp));
        return d;
    }

    public static GradientDrawable bg(int color, int radiusDp, int strokeColor, Context c) {
        GradientDrawable d = bg(color, radiusDp, c);
        d.setStroke(dp(c, 1), strokeColor);
        return d;
    }

    public static LinearLayout column(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    public static LinearLayout row(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }

    public static TextView text(Context c, String s, int color, float sp, boolean bold) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextColor(color);
        t.setTextSize(sp);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    public static TextView section(Context c, String title) {
        TextView t = text(c, title, ACCENT, 12.5f, true);
        t.setPadding(dp(c, 12), dp(c, 12), dp(c, 12), dp(c, 4));
        return t;
    }

    public static View divider(Context c) {
        View v = new View(c);
        v.setBackgroundColor(LINE);
        v.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(c, 0.6f))));
        return v;
    }

    public static Button button(Context c, String label, boolean primary) {
        Button b = new Button(c);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(13f);
        b.setTextColor(primary ? 0xFF16181B : TEXT);
        b.setBackground(bg(primary ? ACCENT : PANEL2, 8, primary ? ACCENT : LINE, c));
        b.setPadding(dp(c, 12), dp(c, 6), dp(c, 12), dp(c, 6));
        b.setMinHeight(dp(c, 36));
        b.setMinimumHeight(dp(c, 36));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(dp(c, 4), dp(c, 4), dp(c, 4), dp(c, 4));
        b.setLayoutParams(lp);
        return b;
    }

    /**
     * 一行滑杆：标题 + 数值 + SeekBar（整数刻度，必要时由调用方换算成 0.5/0.1 步）
     */
    public static final class SeekRow extends LinearLayout {
        public final SeekBar bar;
        public final TextView valueText;
        private final OnInt onChange;
        private final java.util.Locale fmtLocale = java.util.Locale.US;

        public SeekRow(Context c, String label, int min, int max, int initial,
                       String suffix, boolean signedTenths, OnInt onChange, Runnable onRelease) {
            super(c);
            this.onChange = onChange;
            setOrientation(VERTICAL);
            setPadding(dp(c, 12), dp(c, 6), dp(c, 12), dp(c, 2));

            LinearLayout head = row(c);
            TextView name = text(c, label, TEXT, 13f, false);
            head.addView(name, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            valueText = text(c, "", ACCENT, 13f, true);
            head.addView(valueText);
            addView(head);

            bar = new SeekBar(c);
            bar.setMax(max - min);
            bar.setProgress(Math.max(0, Math.min(max - min, initial - min)));
            addView(bar);

            final String sfx = suffix == null ? "" : suffix;
            final boolean tenths = signedTenths;
            final int lo = min;
            Runnable refresh = () -> {
                int v = lo + bar.getProgress();
                String shown;
                if (tenths) {
                    shown = String.format(fmtLocale, "%s%.1f", (v > 0 ? "+" : ""), v / 10.0);
                } else {
                    shown = (v > 0 ? "+" : "") + v;
                }
                valueText.setText(shown + sfx);
            };
            refresh.run();

            bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                    refresh.run();
                    if (fromUser) onChange.run(lo + progress);
                }
                @Override public void onStartTrackingTouch(SeekBar sb) { }
                @Override public void onStopTrackingTouch(SeekBar sb) {
                    if (onRelease != null) onRelease.run();
                }
            });
        }

        public void setValue(int v, int min) {
            bar.setProgress(Math.max(0, Math.min(bar.getMax(), v - min)));
        }
    }

    /** 一行下拉：标题 + Spinner */
    public static final class ChoiceRow extends LinearLayout {
        public final Spinner spinner;

        public ChoiceRow(Context c, String label, String[] names, int selected, OnInt onPick) {
            super(c);
            setOrientation(VERTICAL);
            setPadding(dp(c, 12), dp(c, 6), dp(c, 12), dp(c, 2));
            addView(text(c, label, TEXT, 13f, false));

            spinner = new Spinner(c, Spinner.MODE_DROPDOWN);
            ArrayAdapter<String> ad = new ArrayAdapter<>(c, android.R.layout.simple_spinner_item, names);
            ad.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            spinner.setAdapter(ad);
            spinner.setSelection(Math.max(0, Math.min(names.length - 1, selected)), false);
            if (spinner.getBackground() != null) spinner.getBackground().setTint(LINE);
            addView(spinner, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                    onPick.run(position);
                }
                @Override public void onNothingSelected(AdapterView<?> parent) { }
            });
        }

        public void setIndex(int i) {
            spinner.setSelection(Math.max(0, Math.min(spinner.getCount() - 1, i)), false);
        }
    }

    public static ScrollView scroller(Context c, View child, int bgColor) {
        ScrollView s = new ScrollView(c);
        s.setBackgroundColor(bgColor);
        s.addView(child, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        s.setVerticalScrollBarEnabled(true);
        return s;
    }

    public static LinearLayout card(Context c) {
        LinearLayout l = column(c);
        l.setBackground(bg(PANEL, 10, LINE, c));
        l.setPadding(dp(c, 6), dp(c, 6), dp(c, 6), dp(c, 6));
        return l;
    }
}
