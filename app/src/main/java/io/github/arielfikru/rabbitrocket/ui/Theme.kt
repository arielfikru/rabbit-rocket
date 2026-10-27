package io.github.arielfikru.rabbitrocket.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import io.github.arielfikru.rabbitrocket.R

/** Palette from the "Rabbit Rocket Mobile UI" canvas. */
object RR {
    val NightSky = Color(0xFF16143B)
    val OrbitDeck = Color(0xFF221F52)
    val Violet = Color(0xFF5B4BDB)
    val VioletGlow = Color(0xFF6A5BE6)
    val Track = Color(0xFF3E31A8)
    val Flame = Color(0xFFFF7A3D)
    val Pink = Color(0xFFFFB3C7)
    val Cream = Color(0xFFFFF6E9)
    val Muted = Color(0xFFB9B4E6)
    val Ring = Color(0xFF6F69B8)
    val Danger = Color(0xFFFF8A80)
}

@OptIn(ExperimentalTextApi::class)
private fun variable(res: Int, weight: Int) =
    Font(res, FontWeight(weight), variationSettings = FontVariation.Settings(FontVariation.weight(weight)))

val Fredoka = FontFamily(
    variable(R.font.fredoka, 500),
    variable(R.font.fredoka, 600),
    variable(R.font.fredoka, 700),
)

val SpaceGrotesk = FontFamily(
    variable(R.font.space_grotesk, 400),
    variable(R.font.space_grotesk, 500),
    variable(R.font.space_grotesk, 600),
)

object Type {
    val title = TextStyle(fontFamily = Fredoka, fontWeight = FontWeight.SemiBold, fontSize = 26.sp, color = RR.Cream)
    val section = TextStyle(fontFamily = Fredoka, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, color = RR.Cream)
    val body = TextStyle(fontFamily = SpaceGrotesk, fontSize = 15.sp, color = RR.Cream)
    val strong = body.copy(fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
    val small = TextStyle(
        fontFamily = SpaceGrotesk, fontSize = 13.sp, color = RR.Muted, fontFeatureSettings = "tnum",
    )
    val label = small.copy(fontWeight = FontWeight.Medium, color = Color.White)
    val button = TextStyle(fontFamily = SpaceGrotesk, fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
}

@Composable
fun RabbitRocketTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = RR.Flame,
            onPrimary = RR.NightSky,
            secondary = RR.Violet,
            background = RR.NightSky,
            surface = RR.OrbitDeck,
            onSurface = RR.Cream,
            onBackground = RR.Cream,
            error = RR.Danger,
        ),
        content = content,
    )
}
