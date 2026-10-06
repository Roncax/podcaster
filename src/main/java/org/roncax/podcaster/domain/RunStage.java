package org.roncax.podcaster.domain;

public enum RunStage {
    INGEST, SELECT, SCRIPT, TTS, PUBLISH;

    public RunStage next() {
        if (this == PUBLISH) throw new IllegalStateException("PUBLISH is the last stage");
        return values()[ordinal() + 1];
    }
}
