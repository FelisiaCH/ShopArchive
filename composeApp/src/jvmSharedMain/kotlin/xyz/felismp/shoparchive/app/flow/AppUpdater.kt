package xyz.felismp.shoparchive.app.flow

import xyz.felismp.shoparchive.app.client.PlatformContext

/** The updater of this platform, or null where installing an update from the app is not possible (then the app neither looks for updates nor shows anything about them). */
expect fun createAppUpdater(context: PlatformContext): AppUpdater?
