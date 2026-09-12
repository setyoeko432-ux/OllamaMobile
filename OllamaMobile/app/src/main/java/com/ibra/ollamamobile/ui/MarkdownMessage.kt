package com.ibra.ollamamobile.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.text.util.Linkify
import android.view.View
import android.widget.TextView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import io.noties.markwon.AbstractMarkwonPlugin
import io.noties.markwon.Markwon
import io.noties.markwon.MarkwonConfiguration
import io.noties.markwon.ext.latex.JLatexMathPlugin
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin
import io.noties.markwon.ext.tables.TablePlugin
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
        Markwon.builder(context)
            .usePlugin(StrikethroughPlugin.create())
            .usePlugin(TablePlugin.create(context))
            .usePlugin(LinkifyPlugin.create(Linkify.WEB_URLS))
            .usePlugin(JLatexMathPlugin.create(16f))
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
                            } catch (e: Exception) {
                                // Fallback if no browser app is available
                            }
                        }
                    }
                }
            })
            .build()
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
            markwon.setMarkdown(textView, markdown)
        }
    )
}
