package com.turkms

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.text.InputType
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast

private fun Context.dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

fun showSettingsDialog(context: Context) {
    val layout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(context.dp(20), context.dp(8), context.dp(20), context.dp(8))
    }

    val info = TextView(context).apply {
        text = "TMDB v3 anahtarınızı girin. Boş bırakırsanız yerleşik demo anahtar kullanılır. " +
            "Kişisel anahtar önerilir: themoviedb.org/settings/api"
        textSize = 12f
        setTextColor(Color.GRAY)
        setPadding(0, 0, 0, context.dp(12))
    }
    layout.addView(info)

    val keyInput = EditText(context).apply {
        hint = "TMDB API Anahtarı (v3)"
        setSingleLine()
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        setText(TurkMSSettings.tmdbApiKey)
    }
    layout.addView(keyInput)

    val sourcesTitle = TextView(context).apply {
        text = "Kaynaklar"
        textSize = 15f
        setPadding(0, context.dp(16), 0, context.dp(4))
    }
    layout.addView(sourcesTitle)

    val canli = CheckBox(context).apply {
        text = "CanlıDizi14 (canlidizi14.net)"
        isChecked = TurkMSSettings.sourceCanliDizi14
    }
    val ddizi = CheckBox(context).apply {
        text = "Ddizi (ddizi.tel)"
        isChecked = TurkMSSettings.sourceDdizi
    }
    val now = CheckBox(context).apply {
        text = "Diziizle (diziizle.now / diziizle.you)"
        isChecked = TurkMSSettings.sourceDiziIzleNow
    }
    val tr = CheckBox(context).apply {
        text = "Trdiziizle (trdiziizle.tv)"
        isChecked = TurkMSSettings.sourceTrDiziIzle
    }
    val youtube = CheckBox(context).apply {
        text = "YouTube (film araması)"
        isChecked = TurkMSSettings.sourceYoutube
    }
    layout.addView(canli)
    layout.addView(ddizi)
    layout.addView(now)
    layout.addView(tr)
    layout.addView(youtube)

    val qualityTitle = TextView(context).apply {
        text = "Tercih edilen kalite"
        textSize = 15f
        setPadding(0, context.dp(16), 0, context.dp(4))
    }
    layout.addView(qualityTitle)

    val qualities = listOf(1080, 720, 2160)
    val qualityLabels = listOf("1080p (önerilen)", "720p", "4K / 2160p")
    val spinner = Spinner(context).apply {
        adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, qualityLabels)
        setSelection(qualities.indexOf(TurkMSSettings.preferredQuality).coerceAtLeast(0))
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }
    layout.addView(spinner)

    val note = TextView(context).apply {
        text = "Not: TurkMS içerik barındırmaz. Tüm videolar üçüncü taraf sitelerden alınır. " +
            "Bir kaynak açılmıyorsa ayarlardan kapatabilirsiniz."
        textSize = 11f
        setTextColor(Color.GRAY)
        setPadding(0, context.dp(14), 0, 0)
    }
    layout.addView(note)

    val scroll = ScrollView(context).apply { addView(layout) }

    val dialog = AlertDialog.Builder(context)
        .setTitle("TurkMS Ayarları")
        .setView(scroll)
        .setPositiveButton("Kaydet", null)
        .setNegativeButton("İptal", null)
        .create()

    dialog.setOnShowListener {
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val quality = qualities[spinner.selectedItemPosition.coerceIn(qualities.indices)]
            TurkMSSettings.save(
                context = context,
                tmdbKey = keyInput.text.toString(),
                canli = canli.isChecked,
                ddizi = ddizi.isChecked,
                now = now.isChecked,
                tr = tr.isChecked,
                youtube = youtube.isChecked,
                quality = quality,
                timeout = TurkMSSettings.requestTimeout,
            )
            Toast.makeText(context, "TurkMS ayarları kaydedildi", Toast.LENGTH_SHORT).show()
            dialog.dismiss()
        }
    }
    dialog.show()
}
