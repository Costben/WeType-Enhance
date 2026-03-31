package com.xposed.miuiime

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.widget.EditText
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    private var lightColor = WeTypeSettings.DEFAULT_LIGHT_COLOR
    private var darkColor = WeTypeSettings.DEFAULT_DARK_COLOR
    private var currentModeIsDark = false

    private lateinit var modeGroup: RadioGroup
    private lateinit var colorInput: EditText
    private lateinit var alphaSeekBar: SeekBar
    private lateinit var alphaValue: TextView
    private lateinit var blurSeekBar: SeekBar
    private lateinit var blurValue: TextView
    private lateinit var previewCard: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        modeGroup = findViewById(R.id.mode_group)
        colorInput = findViewById(R.id.color_input)
        alphaSeekBar = findViewById(R.id.alpha_seekbar)
        alphaValue = findViewById(R.id.alpha_value)
        blurSeekBar = findViewById(R.id.blur_seekbar)
        blurValue = findViewById(R.id.blur_value)
        previewCard = findViewById(R.id.preview_card)

        lightColor = WeTypeSettings.getLightColor(this)
        darkColor = WeTypeSettings.getDarkColor(this)
        blurSeekBar.progress = WeTypeSettings.getBlurRadius(this)
        blurValue.text = blurSeekBar.progress.toString()

        currentModeIsDark = resources.configuration.uiMode and 0x30 == 0x20
        modeGroup.check(if (currentModeIsDark) R.id.mode_dark else R.id.mode_light)
        bindCurrentMode()

        modeGroup.setOnCheckedChangeListener { _, checkedId ->
            persistCurrentEditorState()
            currentModeIsDark = checkedId == R.id.mode_dark
            bindCurrentMode()
        }

        colorInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                updatePreview()
            }
        })

        alphaSeekBar.setOnSeekBarChangeListener(simpleSeekBarListener { progress ->
            alphaValue.text = progress.toString()
            updatePreview()
        })

        blurSeekBar.setOnSeekBarChangeListener(simpleSeekBarListener { progress ->
            blurValue.text = progress.toString()
        })

        findViewById<TextView>(R.id.save_button).setOnClickListener {
            val color = parseCurrentColor() ?: run {
                Toast.makeText(this, R.string.settings_invalid_color, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (currentModeIsDark) {
                darkColor = color
            } else {
                lightColor = color
            }
            WeTypeSettings.save(this, lightColor, darkColor, blurSeekBar.progress)
            Toast.makeText(this, R.string.settings_saved, Toast.LENGTH_SHORT).show()
        }

        findViewById<TextView>(R.id.reset_button).setOnClickListener {
            lightColor = WeTypeSettings.DEFAULT_LIGHT_COLOR
            darkColor = WeTypeSettings.DEFAULT_DARK_COLOR
            blurSeekBar.progress = WeTypeSettings.DEFAULT_BLUR_RADIUS
            blurValue.text = blurSeekBar.progress.toString()
            bindCurrentMode()
        }
    }

    private fun bindCurrentMode() {
        val currentColor = if (currentModeIsDark) darkColor else lightColor
        colorInput.setText(String.format("#%06X", currentColor and 0xFFFFFF))
        alphaSeekBar.progress = Color.alpha(currentColor)
        alphaValue.text = alphaSeekBar.progress.toString()
        updatePreview()
    }

    private fun persistCurrentEditorState() {
        parseCurrentColor()?.let { color ->
            if (currentModeIsDark) {
                darkColor = color
            } else {
                lightColor = color
            }
        }
    }

    private fun parseCurrentColor(): Int? {
        val rgb = colorInput.text.toString().trim()
        if (!rgb.matches(Regex("^#?[0-9a-fA-F]{6}$"))) return null
        val normalized = if (rgb.startsWith("#")) rgb else "#$rgb"
        val opaque = Color.parseColor(normalized)
        return Color.argb(alphaSeekBar.progress, Color.red(opaque), Color.green(opaque), Color.blue(opaque))
    }

    private fun updatePreview() {
        val color = parseCurrentColor() ?: return
        previewCard.setBackgroundColor(color)
        previewCard.text = String.format("#%08X", color)
        previewCard.setTextColor(if (isLightColor(color)) Color.BLACK else Color.WHITE)
    }

    private fun isLightColor(color: Int): Boolean {
        val luminance =
            (Color.red(color) * 0.299 + Color.green(color) * 0.587 + Color.blue(color) * 0.114) / 255
        return luminance > 0.5
    }

    private fun simpleSeekBarListener(onChanged: (Int) -> Unit): SeekBar.OnSeekBarChangeListener {
        return object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                onChanged(progress)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        }
    }
}
