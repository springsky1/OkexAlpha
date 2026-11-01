package com.okexalpha.domain;

public enum JobStatus {
    PENDING, RUNNING, PAUSED, DONE, FAILED, CANCELED;

    public boolean terminal() {
        return this == DONE || this == CANCELED;
    }
}
