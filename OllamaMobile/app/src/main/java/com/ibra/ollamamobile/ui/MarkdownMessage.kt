package com.ibra.ollamamobile.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.text.util.Linkify
import android.view.View
import android.widget.TextView
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import android.util.Log
import io.noties.markwon.AbstractMarkwonPlugin
import io.noties.markwon.Markwon
import io.noties.markwon.MarkwonConfiguration
import io.noties.markwon.ext.latex.JLatexMathPlugin
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin
import io.noties.markwon.ext.tables.TablePlugin
import io.noties.markwon.inlineparser.MarkwonInlineParserPlugin
import io.noties.markwon.linkify.LinkifyPlugin
import android.graphics.Color as AndroidColor

@Composable
fun MarkdownMessage(
    markdown: String,
    isStreaming: Boolean,
    modifier: Modifier = Modifier,
    onLinkClick: (String) -> Unit = {}
) {
    val context = LocalContext.current
    val markwon = remember(context) {
        val latexTextSizePx = context.resources.displayMetrics.scaledDensity * 16f
        
        Markwon.builder(context)
            .usePlugin(StrikethroughPlugin.create())
            .usePlugin(TablePlugin.create(context))
            .usePlugin(LinkifyPlugin.create(Linkify.WEB_URLS))
            .usePlugin(MarkwonInlineParserPlugin.create())
            .usePlugin(JLatexMathPlugin.create(latexTextSizePx) { builder ->
                builder
                    .inlinesEnabled(true)
                    .blocksEnabled(true)
                    .errorHandler { latex, error ->
                        Log.e("OllamaMobileLatex", "Latex error for: $latex", error)
                        null
                    }
            })
            .usePlugin(object : AbstractMarkwonPlugin() {
                override fun configureConfiguration(builder: MarkwonConfiguration.Builder) {
                    builder.linkResolver { view, link ->
                        val uri = Uri.parse(link)
                        val scheme = uri.scheme?.lowercase()
                        if (scheme == "http" || scheme == "https") {
                            val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                            }
                            try {
                                view.context.startActivity(intent)
                                onLinkClick(link)
                            } catch (e: Exception) {}
                        }
                    }
                }
            })
            .build()
    }

    val displayMarkdown = remember(markdown, isStreaming) {
        prepareStreamingMarkdown(markdown, isStreaming)
    }

    AndroidView(
        factory = { ctx ->
            TextView(ctx).apply {
                setTextSize(16f)
                setTextColor(AndroidColor.parseColor("#242422"))
                setBackgroundColor(AndroidColor.TRANSPARENT)
                setTextIsSelectable(true)
                setLineSpacing(4f, 1.2f)
                setLinkTextColor(AndroidColor.parseColor("#2783DE"))
                setPadding(0, 0, 0, 0)
            }
        },
        modifier = modifier,
        update = { textView ->
            markwon.setMarkdown(textView, displayMarkdown)
        }
    )
}

fun prepareStreamingMarkdown(
    markdown: String,
    isStreaming: Boolean
): String {
    if (!isStreaming) return markdown

    // Fix unclosed code fences
    var result = markdown
    val codeFenceCount = countOccurrences(result, "```")
    if (codeFenceCount % 2 != 0) {
        result += "\n```"
    }

    // Fix unclosed LaTeX delimiters
    val doubleDollarCount = countOccurrences(result, "$$")
    val singleDollarCount = result.count { it == '$' } - (doubleDollarCount * 2)

    if (doubleDollarCount % 2 != 0) {
        // Incomplete block LaTeX
    }
    
    if (singleDollarCount % 2 != 0) {
        // Incomplete inline LaTeX
    }

    return result
}

private fun countOccurrences(text: String, substring: String): Int {
    var count = 0
    var index = 0
    while (true) {
        index = text.indexOf(substring, index)
        if (index == -1) break
        count++
        index += substring.length
    }
    return count
}

@androidx.compose.ui.tooling.preview.Preview(showBackground = true)
@Composable
fun MarkdownMessagePreview() {
    val sampleContent = """
        ## Contoh Fisika
        
        Frekuensi sudut dihitung menggunakan:
        
        ${"$$"}
        \omega = \frac{2\pi}{T}
        ${"$$"}
        
        Dengan:
        
        - ${"$"}\omega${"$"}$ adalah **frekuensi sudut**
        - ${"$"}${"T"}${"$"}$ adalah periode
        - ${"$"}\pi${"$"}$ adalah konstanta pi
        
        Energi kinetik:
        
        ${"$$"}
        E_k = \frac{1}{2}mv^2
        ${"$$"}
        
        Kode contoh:
        
        ```kotlin
        val omega = 2 * Math.PI / period
        ```
    """.trimIndent()
    
    androidx.compose.material3.MaterialTheme {
        androidx.compose.foundation.layout.Column(
            modifier = androidx.compose.ui.Modifier
                .fillMaxSize()
                .padding(16.dp)
        ) {
            MarkdownMessage(markdown = sampleContent, isStreaming = false)
        }
    }
}

