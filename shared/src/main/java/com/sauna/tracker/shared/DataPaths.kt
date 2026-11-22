package com.sauna.tracker.shared

/**
 * Paths used between the watch and the phone.
 *
 * Privacy: only MessageClient / ChannelClient are used, and only towards *nearby*
 * (directly connected) nodes. The DataClient is deliberately not used, because DataItems
 * are backed up to Google's cloud. Data therefore never leaves the two devices
 * unless the user shares a CSV themselves.
 */
object DataPaths {
    /** Channel watch -> phone, one per session. Payload: gzip'ed [SessionCodec] JSON. */
    const val SESSION_PREFIX = "/session/"
    /** Message, both directions: "timeLimitMin,hrLimit,updatedAt". Newest wins. */
    const val SETTINGS = "/settings"
    /** Message, both directions: comma-separated ids deleted on the sender. */
    const val DELETED = "/deleted"
    /** Message phone -> watch: session stored, the watch may mark it synced. */
    const val ACK_PREFIX = "/ack/"
    /** Message phone -> watch: resend everything not yet synced. */
    const val SYNC_REQUEST = "/sync-request"

    /** Capabilities declared in res/values/wear.xml; their reachability changes on (re)connect. */
    const val CAPABILITY_PHONE = "sauna_phone"
    const val CAPABILITY_WATCH = "sauna_watch"
}
