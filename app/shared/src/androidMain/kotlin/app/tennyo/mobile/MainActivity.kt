package app.tennyo.mobile

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.view.WindowCompat
import app.tennyo.mobile.root.RootComponent
import com.arkivanov.decompose.defaultComponentContext
import org.koin.android.ext.android.get
import org.koin.core.parameter.parametersOf

class MainActivity : ComponentActivity() {
    private lateinit var rootComponent: RootComponent

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        rootComponent = get { parametersOf(defaultComponentContext()) }

        setContent {
            TennyoApp(rootComponent)
        }
    }
}
