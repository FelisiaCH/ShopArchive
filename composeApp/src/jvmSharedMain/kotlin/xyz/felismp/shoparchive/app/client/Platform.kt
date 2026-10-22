package xyz.felismp.shoparchive.app.client

/** What a platform needs to place its store: the Android `Context`; nothing on desktop. */
expect abstract class PlatformContext

/** The platform's store: Keystore-wrapped file on Android, DPAPI-protected file on Windows. */
expect fun createCredentialStore(context: PlatformContext): CredentialStore

/** The platform's place for [FilePreferencesStore]. */
expect fun createPreferencesStore(context: PlatformContext): PreferencesStore

/** The platform's place for [FileOutboxStore]: a folder in the app's own storage. */
expect fun createOutboxStore(context: PlatformContext): OutboxStore

/** The platform's discovery: Android's network service discovery, JmDNS on desktop. */
expect fun createServerDiscovery(context: PlatformContext): ServerDiscovery
