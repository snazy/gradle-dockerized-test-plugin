package com.pedjak.gradle.plugins.dockerizedtest;

import org.gradle.process.internal.StreamsHandler;
import org.gradle.process.internal.streams.ForwardStdinStreamsHandler;
import org.gradle.process.internal.streams.OutputStreamsForwarder;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.Executor;

class AllStreamsForwarder implements StreamsHandler {

    private final ForwardStdinStreamsHandler stdinForward;
    private final OutputStreamsForwarder outputForward;

    AllStreamsForwarder(OutputStream stdout, OutputStream stderr, InputStream stdin, boolean shouldReadErrorStream) {
        this.stdinForward = new ForwardStdinStreamsHandler(stdin);
        this.outputForward = new OutputStreamsForwarder(stdout, stderr, shouldReadErrorStream);
    }


    @Override
    public void connectStreams(Process process, String processName, Executor executor) {
        stdinForward.connectStreams(process, processName, executor);
        outputForward.connectStreams(process, processName, executor);
    }

    @Override
    public void start() {
        stdinForward.start();
        outputForward.start();
    }

    @Override
    public void stop() {
        stdinForward.stop();
        outputForward.stop();
    }
}
