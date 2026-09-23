package garden.vayne.chorus

import android.app.Application
import garden.vayne.chorus.widget.QuickSwitchWidget

class ChorusApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // widgets redraw whenever the model changes (a sync from another device, an app switch)
        QuickSwitchWidget.install(this)
    }
}
