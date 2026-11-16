package app.tennyo.host.engine.session

import app.tennyo.contract.ActiveSurface
import app.tennyo.host.engine.platform.HostMonitor
import app.tennyo.host.engine.platform.guarded

class ActivePaneTracker {
    private val lock = HostMonitor()
    private var currentSurface: ActiveSurface? = null
    private var queuedSurface: ActiveSurface? = null

    fun get(): ActiveSurface? =
        lock.guarded { currentSurface }

    fun queue(next: ActiveSurface): Boolean =
        lock.guarded {
            if (next == currentSurface || next == queuedSurface) {
                false
            } else {
                queuedSurface = next
                true
            }
        }

    fun applyQueued(next: ActiveSurface): Boolean =
        lock.guarded {
            if (queuedSurface != next) {
                false
            } else {
                queuedSurface = null
                if (currentSurface == next) {
                    false
                } else {
                    currentSurface = next
                    true
                }
            }
        }
}
