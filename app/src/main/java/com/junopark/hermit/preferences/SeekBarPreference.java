package com.junopark.hermit.preferences;

import android.app.AlertDialog;
import android.content.Context;
import android.os.Bundle;
import android.preference.DialogPreference;
import android.util.AttributeSet;
import android.util.Log;
import android.view.Gravity;
import android.text.InputType;
import android.view.KeyEvent;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import com.junopark.hermit.R;

import java.util.Locale;

// Based on a Stack Overflow example: http://stackoverflow.com/questions/1974193/slider-on-my-preferencescreen
public class SeekBarPreference extends DialogPreference
{
    private static final String ANDROID_SCHEMA_URL = "http://schemas.android.com/apk/res/android";
    private static final String SEEKBAR_SCHEMA_URL = "https://github.com/junopark00/hermit-android/seekbar";

    private SeekBar seekBar;
    private EditText valueText;
    // Hermit: a value typed into the field, kept exactly instead of snapping to the step size
    private int typedValue = -1;
    private final Context context;

    private final String dialogMessage;
    private final String suffix;
    private final int defaultValue;
    private final int maxValue;
    private final int minValue;
    private final int stepSize;
    private final int keyStepSize;
    private final int divisor;
    private int currentValue;

    public SeekBarPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
        this.context = context;

        // Read the message from XML
        int dialogMessageId = attrs.getAttributeResourceValue(ANDROID_SCHEMA_URL, "dialogMessage", 0);
        if (dialogMessageId == 0) {
            dialogMessage = attrs.getAttributeValue(ANDROID_SCHEMA_URL, "dialogMessage");
        }
        else {
            dialogMessage = context.getString(dialogMessageId);
        }

        // Get the suffix for the number displayed in the dialog
        int suffixId = attrs.getAttributeResourceValue(ANDROID_SCHEMA_URL, "text", 0);
        if (suffixId == 0) {
            suffix = attrs.getAttributeValue(ANDROID_SCHEMA_URL, "text");
        }
        else {
            suffix = context.getString(suffixId);
        }

        // Get default, min, and max seekbar values
        defaultValue = attrs.getAttributeIntValue(ANDROID_SCHEMA_URL, "defaultValue", PreferenceConfiguration.getDefaultBitrate(context));
        maxValue = attrs.getAttributeIntValue(ANDROID_SCHEMA_URL, "max", 100);
        minValue = attrs.getAttributeIntValue(SEEKBAR_SCHEMA_URL, "min", 1);
        stepSize = attrs.getAttributeIntValue(SEEKBAR_SCHEMA_URL, "step", 1);
        divisor = attrs.getAttributeIntValue(SEEKBAR_SCHEMA_URL, "divisor", 1);
        keyStepSize = attrs.getAttributeIntValue(SEEKBAR_SCHEMA_URL, "keyStep", 0);
    }

    @Override
    protected View onCreateDialogView() {

        LinearLayout.LayoutParams params;
        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(6, 6, 6, 6);

        TextView splashText = new TextView(context);
        splashText.setPadding(30, 10, 30, 10);
        if (dialogMessage != null) {
            splashText.setText(dialogMessage);
        }
        layout.addView(splashText);

        // Hermit: the value is an input field (exact values are hard to hit with the slider),
        // with the unit next to it.
        LinearLayout valueRow = new LinearLayout(context);
        valueRow.setOrientation(LinearLayout.HORIZONTAL);
        valueRow.setGravity(Gravity.CENTER);
        valueText = new EditText(context);
        valueText.setGravity(Gravity.CENTER);
        valueText.setTextSize(32);
        valueText.setMinEms(3);
        valueText.setSelectAllOnFocus(true);
        valueText.setInputType(divisor != 1 ?
                InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL :
                InputType.TYPE_CLASS_NUMBER);
        valueText.setImeOptions(EditorInfo.IME_ACTION_DONE);
        // Default text for value; hides bug where OnSeekBarChangeListener isn't called when opacity is 0%
        valueText.setText("0");
        valueText.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE ||
                    (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER)) {
                applyTypedValue();
            }
            return false;
        });
        valueText.setOnFocusChangeListener((v, hasFocus) -> {
            if (!hasFocus) {
                applyTypedValue();
            }
        });
        valueRow.addView(valueText);
        if (suffix != null) {
            TextView suffixText = new TextView(context);
            suffixText.setTextSize(20);
            suffixText.setText(suffix);
            suffixText.setPadding(12, 0, 0, 0);
            valueRow.addView(suffixText);
        }
        params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        layout.addView(valueRow, params);

        seekBar = new SeekBar(context);
        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int value, boolean b) {
                if (value < minValue) {
                    seekBar.setProgress(minValue);
                    return;
                }

                int roundedValue = value == typedValue ? value : ((value + (stepSize - 1))/stepSize)*stepSize;
                if (roundedValue != value) {
                    seekBar.setProgress(roundedValue);
                    return;
                }
                if (b) {
                    typedValue = -1; // the slider moved: back to its steps
                    valueText.clearFocus(); // and its value wins over text being typed
                }

                if (!valueText.hasFocus()) {
                    valueText.setText(formatValue(roundedValue));
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        layout.addView(seekBar, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        // Hermit: recommended bitrate for the chosen resolution, frame rate and codec
        if (PreferenceConfiguration.BITRATE_PREF_STRING.equals(getKey())) {
            TextView recommendation = new TextView(context);
            recommendation.setPadding(30, 16, 30, 10);
            recommendation.setTextSize(13);
            recommendation.setTextColor(context.getResources().getColor(R.color.hermit_text_helper));
            recommendation.setText(PreferenceConfiguration.getBitrateRecommendationText(context));
            layout.addView(recommendation);
        }

        if (shouldPersist()) {
            currentValue = getPersistedInt(defaultValue);
        }
        if (currentValue % stepSize != 0) {
            typedValue = currentValue; // an exact value typed earlier
        }

        seekBar.setMax(maxValue);
        if (keyStepSize != 0) {
            seekBar.setKeyProgressIncrement(keyStepSize);
        }
        seekBar.setProgress(currentValue);

        return layout;
    }

    @Override
    protected void onBindDialogView(View v) {
        super.onBindDialogView(v);
        seekBar.setMax(maxValue);
        if (keyStepSize != 0) {
            seekBar.setKeyProgressIncrement(keyStepSize);
        }
        seekBar.setProgress(currentValue);
    }

    @Override
    protected void onSetInitialValue(boolean restore, Object defaultValue)
    {
        super.onSetInitialValue(restore, defaultValue);
        if (restore) {
            currentValue = shouldPersist() ? getPersistedInt(this.defaultValue) : 0;
        }
        else {
            currentValue = (Integer) defaultValue;
        }
    }

    private String formatValue(int value) {
        if (divisor != 1) {
            // At least one decimal, more only when needed (a typed 12.345 stays 12.345)
            String text = new java.math.BigDecimal(value).divide(new java.math.BigDecimal(divisor))
                    .stripTrailingZeros().toPlainString();
            return text.contains(".") ? text : text + ".0";
        }
        return String.valueOf(value);
    }

    // Hermit: takes the typed number (in display units, e.g. Mbps), clamped to the slider range.
    private void applyTypedValue() {
        if (valueText == null || seekBar == null) {
            return;
        }
        String text = valueText.getText().toString().trim().replace(',', '.');
        try {
            float typed = Float.parseFloat(text);
            int value = Math.round(typed * divisor);
            value = Math.max(minValue, Math.min(maxValue, value));
            typedValue = value;
            seekBar.setProgress(value);
            valueText.setText(formatValue(value));
        } catch (NumberFormatException e) {
            valueText.setText(formatValue(seekBar.getProgress()));
        }
    }

    public void setProgress(int progress) {
        this.currentValue = progress;
        if (seekBar != null) {
            seekBar.setProgress(progress);
        }
    }
    public int getProgress() {
        return currentValue;
    }

    @Override
    public void showDialog(Bundle state) {
        super.showDialog(state);

        Button positiveButton = ((AlertDialog) getDialog()).getButton(AlertDialog.BUTTON_POSITIVE);
        positiveButton.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View view) {
                // A value still being typed counts too
                if (valueText != null && valueText.hasFocus()) {
                    applyTypedValue();
                }
                if (shouldPersist()) {
                    currentValue = seekBar.getProgress();
                    persistInt(seekBar.getProgress());
                    callChangeListener(seekBar.getProgress());
                }

                getDialog().dismiss();
            }
        });
    }
}
