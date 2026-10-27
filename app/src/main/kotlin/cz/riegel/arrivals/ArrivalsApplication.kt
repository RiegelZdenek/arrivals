package cz.riegel.arrivals

import android.app.Application
import cz.riegel.arrivals.widget.WidgetCountdownTicker

class ArrivalsApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        WidgetCountdownTicker.ensureRegistered(this)
    }
}
