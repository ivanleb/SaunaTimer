package com.sauna.tracker.shared

/** Wearable Data Layer paths shared by the watch and the phone app. */
object DataPaths {
    /** DataItem per finished session (watch -> phone). Asset [KEY_DATA] holds [SessionCodec] JSON. */
    const val SESSION_PREFIX = "/session/"
    /** DataItem with the limits; each device publishes its own, newest [KEY_UPDATED_AT] wins. */
    const val SETTINGS = "/settings"
    /** DataItem with ids this device deleted; the other device deletes them too. */
    const val DELETED = "/deleted"
    /** Message phone -> watch: session stored, watch may mark it synced. */
    const val ACK_PREFIX = "/ack/"
    /** Message phone -> watch: resend everything not yet synced. */
    const val SYNC_REQUEST = "/sync-request"

    const val KEY_DATA = "data"
    const val KEY_SENT_AT = "sentAt"
    const val KEY_TIME_LIMIT = "timeLimitMin"
    const val KEY_HR_LIMIT = "hrLimit"
    const val KEY_UPDATED_AT = "updatedAt"
    const val KEY_IDS = "ids"
}
