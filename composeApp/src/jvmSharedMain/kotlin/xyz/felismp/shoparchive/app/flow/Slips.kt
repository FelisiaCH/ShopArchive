package xyz.felismp.shoparchive.app.flow

/** The platform's compressor: Android's bitmap classes, or javax.imageio on the desktop. */
expect fun createSlipCompressor(): SlipCompressor

/** The platform's reader: ML Kit on Android; the desktop has none. */
expect fun createSlipReader(): SlipReader
