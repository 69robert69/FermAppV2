package com.fermapp.api.realtime;

/** The events sent to the app, with their wire names. */
public enum RealtimeEvent {
    WORK_PROGRESS("work:progress"),
    WORK_UPDATED("work:updated"),
    IRRIGATION_PROGRESS("irrigation:progress"),
    IRRIGATION_UPDATED("irrigation:updated"),
    TASK_UPDATED("task:updated"),
    ALERT_NEW("alert:new"),
    NOTIFICATION_NEW("notification:new"),
    IOT_READING("iot:reading"),
    MACHINE_LOCATION("machine:location"),
    PARCEL_UPDATED("parcel:updated");

    private final String wireName;

    RealtimeEvent(String wireName) {
        this.wireName = wireName;
    }

    public String wireName() {
        return wireName;
    }
}
