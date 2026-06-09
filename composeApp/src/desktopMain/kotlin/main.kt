import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import com.example.FFTT04M.App

fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "FFTT04M",
    ) {
        App()
    }
}
