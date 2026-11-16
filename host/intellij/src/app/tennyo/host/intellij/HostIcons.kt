package app.tennyo.host.intellij

import com.intellij.openapi.util.IconLoader
import javax.swing.Icon

object HostIcons {
  private fun load(path: String): Icon = IconLoader.getIcon(path, HostIcons::class.java.classLoader)

  @JvmField
  val host: Icon = load("/icons/host.svg")

  @JvmField
  val connected: Icon = load("/icons/hostConnected.svg")

  @JvmField
  val pending: Icon = load("/icons/hostPending.svg")

  @JvmField
  val waiting: Icon = load("/icons/hostWaiting.svg")

  @JvmField
  val discovery: Icon = load("/icons/hostDiscovery.svg")
}
