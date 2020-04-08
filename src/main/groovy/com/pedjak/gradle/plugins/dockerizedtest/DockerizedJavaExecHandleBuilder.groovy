/*
 * Copyright 2015 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.pedjak.gradle.plugins.dockerizedtest

import org.gradle.api.Task
import org.gradle.api.internal.file.FileCollectionFactory
import org.gradle.api.internal.file.FileResolver
import org.gradle.api.logging.Logger
import org.gradle.api.logging.Logging
import org.gradle.initialization.BuildCancellationToken
import org.gradle.internal.remote.internal.inet.InetAddressFactory
import org.gradle.process.JavaForkOptions
import org.gradle.process.internal.*
import org.gradle.process.internal.streams.OutputStreamsForwarder

import java.util.concurrent.Executor

class DockerizedJavaExecHandleBuilder extends JavaExecHandleBuilder {
    private static final Logger LOGGER = Logging.getLogger(DockerizedJavaExecHandleBuilder.class)

    StreamsHandler streamsHandler
    Task task
    Executor executor
    BuildCancellationToken buildCancellationToken
    private final DockerizedTestExtension extension
    private final WorkerSemaphore workersSemaphore
    private final InetAddressFactory inetAddressFactory

    DockerizedJavaExecHandleBuilder(DockerizedTestExtension extension, FileCollectionFactory fileCollectionFactory, FileResolver fileResolver,
                                    Executor executor, BuildCancellationToken buildCancellationToken, WorkerSemaphore workersSemaphore,
                                    JavaForkOptions javaForkOptions, Task task, InetAddressFactory inetAddressFactory) {
        super(fileResolver, fileCollectionFactory, executor, buildCancellationToken, javaForkOptions)
        this.extension = extension
        this.executor = executor
        this.buildCancellationToken = buildCancellationToken
        this.workersSemaphore = workersSemaphore
        this.task = task
        this.inetAddressFactory = inetAddressFactory
    }

    StreamsHandler getStreamsHandler() {
        StreamsHandler effectiveHandler
        if (this.streamsHandler != null) {
            effectiveHandler = this.streamsHandler
        } else {
            boolean shouldReadErrorStream = !redirectErrorStream
            effectiveHandler = new OutputStreamsForwarder(standardOutput, errorOutput, shouldReadErrorStream)
            this.streamsHandler = effectiveHandler
        }
        return effectiveHandler
    }

    ExecHandle build() {
        LOGGER.debug("""Creating ExecHandle for ${task.path} with
    displayName=${displayName}
    workingDir="${workingDir}
    arguments=$allArguments
    streamsHandler=${getStreamsHandler()}
    inputHandler=${inputHandler}
    redirectErrorStream=$redirectErrorStream,
    timeoutMillis=$timeoutMillis
    daemon=$daemon
    executor=$executor
    buildCancellationToken=$buildCancellationToken
""")

        ExecHandle execHandle = new DockerizedExecHandle(extension,
                displayName,
                workingDir,
                'java',
                allArguments,
                getActualEnvironment(),
                getStreamsHandler(),
                inputHandler,
                listeners,
                redirectErrorStream,
                timeoutMillis,
                daemon,
                executor,
                buildCancellationToken,
                inetAddressFactory)

        return new ExitCodeTolerantExecHandle(execHandle, workersSemaphore)

    }

    def timeoutMillis = Integer.MAX_VALUE
    @Override
    AbstractExecHandleBuilder setTimeout(int timeoutMillis) {
        this.timeoutMillis = timeoutMillis
        return super.setTimeout(timeoutMillis)
    }

    boolean redirectErrorStream
    @Override
    AbstractExecHandleBuilder redirectErrorStream() {
        redirectErrorStream = true
        return super.redirectErrorStream()
    }

    def listeners = []
    @Override
    AbstractExecHandleBuilder listener(ExecHandleListener listener) {
        listeners << listener
        return super.listener(listener)
    }

}
