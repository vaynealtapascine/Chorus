package garden.vayne.chorus.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.material3.Text
import garden.vayne.chorus.R
import garden.vayne.chorus.designsystem.HankenGrotesk

/*
 * The welcome screen's art (F1.1): the four brand colours as large soft shapes on teal, and the
 * CHORUS wordmark with the mark in place of the O, outlined from the owner's logo file.
 */

internal object Welcome {
    val Teal = Color(0xFF42A397)
    val DeepTeal = Color(0xFF34968A)
    val Lime = Color(0xFF8DBE2B)
    val Sun = Color(0xFFF0BD1B)
    val Mustard = Color(0xFFE0AE20)
    val Cream = Color(0xFFFBF3E5)
    val Ink = Color(0xFF1F5E57)
}

/** The CHORUS wordmark and its [line] (the tagline unless a setup step asks its question), in cream. */
@Composable
internal fun ChorusWordmark(modifier: Modifier = Modifier, line: AnnotatedString = AnnotatedString("VOICE FOR THE MANY")) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Image(painterResource(R.drawable.chorus_wordmark), "Chorus", Modifier.width(272.dp).semantics { heading() })
        Text(line, color = Welcome.Cream, fontFamily = HankenGrotesk, fontWeight = FontWeight.Bold, fontSize = 15.sp,
            letterSpacing = if (line.text.all { !it.isLowerCase() }) 1.sp else 0.sp)
    }
}

/** F1.1's background: overlapping blobs of lime, sunflower and teal, sized to the screen; [compact]
 * keeps them to the top, behind the wordmark (F1.3, F1.4). */
@Composable
internal fun WelcomeBlobs(modifier: Modifier = Modifier, compact: Boolean = false) {
    Canvas(modifier.fillMaxSize()) {
        drawRect(Welcome.Teal)
        val w = size.width
        if (compact) {
            val h = size.height * .3f
            blob(Welcome.Mustard, Offset(-w * .35f, -h * .1f), Size(w * .9f, h * .9f), -10f)
            blob(Welcome.Lime, Offset(w * .2f, -h * .45f), Size(w * .9f, h * 1.1f), 12f)
            blob(Welcome.Mustard, Offset(w * .55f, -h * .5f), Size(w * .7f, h * .9f), 0f)
            blob(Welcome.Lime, Offset(w * .78f, h * .3f), Size(w * .5f, h * 1.1f), 0f)
            blob(Welcome.Teal, Offset(-w * .2f, h * .35f), Size(w * .55f, h * .5f), 0f)
            return@Canvas
        }
        val h = size.height
        blob(Welcome.Sun, Offset(w * .55f, -h * .08f), Size(w * 1.1f, h * .32f), -12f)
        blob(Welcome.Lime, Offset(-w * .45f, -h * .05f), Size(w * 1.2f, h * .62f), 18f)
        blob(Welcome.DeepTeal, Offset(-w * .25f, h * .14f), Size(w * .85f, h * .46f), 0f)
        blob(Welcome.Mustard, Offset(w * .42f, h * .2f), Size(w * .95f, h * .62f), -20f)
        blob(Welcome.Teal, Offset(w * .68f, h * .08f), Size(w * .6f, h * .3f), 0f)
        blob(Welcome.Lime, Offset(w * .25f, h * .62f), Size(w * 1.1f, h * .5f), 10f)
        blob(Welcome.Mustard, Offset(-w * .55f, h * .82f), Size(w * 1.3f, h * .4f), -8f)
        blob(Welcome.DeepTeal, Offset(w * .55f, h * .8f), Size(w * .9f, h * .4f), 0f)
    }
}

private fun DrawScope.blob(color: Color, topLeft: Offset, size: Size, degrees: Float) {
    rotate(degrees, Offset(topLeft.x + size.width / 2, topLeft.y + size.height / 2)) { drawOval(color, topLeft, size) }
}
