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

import com.github.dockerjava.api.DockerClient
import com.github.dockerjava.core.DefaultDockerClientConfig
import com.github.dockerjava.core.DockerClientBuilder
import com.github.dockerjava.netty.NettyDockerCmdExecFactory
import org.gradle.api.*
import org.gradle.api.internal.file.FileCollectionFactory
import org.gradle.api.internal.project.ProjectInternal
import org.gradle.api.logging.Logger
import org.gradle.api.logging.Logging
import org.gradle.api.tasks.testing.Test
import org.gradle.initialization.DefaultBuildCancellationToken
import org.gradle.internal.concurrent.DefaultExecutorFactory
import org.gradle.internal.concurrent.ExecutorFactory
import org.gradle.internal.operations.BuildOperationExecutor
import org.gradle.internal.remote.Address
import org.gradle.internal.remote.ConnectionAcceptor
import org.gradle.internal.remote.MessagingServer
import org.gradle.internal.remote.ObjectConnection
import org.gradle.internal.remote.internal.ConnectCompletion
import org.gradle.internal.remote.internal.IncomingConnector
import org.gradle.internal.remote.internal.hub.MessageHubBackedObjectConnection
import org.gradle.internal.remote.internal.hub.MessageHubBackedServer
import org.gradle.internal.remote.internal.inet.InetAddressFactory
import org.gradle.internal.remote.internal.inet.MultiChoiceAddress
import org.gradle.internal.time.Clock
import org.gradle.process.JavaDebugOptions
import org.gradle.process.internal.DefaultJavaDebugOptions
import org.gradle.process.internal.DefaultJavaForkOptions
import org.gradle.process.internal.JavaExecHandleFactory
import org.gradle.process.internal.JavaForkOptionsFactory
import org.gradle.process.internal.health.memory.MemoryManager
import org.gradle.process.internal.worker.DefaultWorkerProcessFactory
import org.apache.commons.lang3.SystemUtils
import org.apache.maven.artifact.versioning.ComparableVersion

import javax.inject.Inject

class DockerizedTestPlugin implements Plugin<Project> {
    private static final Logger LOGGER = Logging.getLogger(DockerizedTestPlugin.class)

    String supportedVersion = '6.2.1'
    String currentUser
    MessageServer messagingServer
    def static workerSemaphore = new DefaultWorkerSemaphore()
    MemoryManager memoryManager = new NoMemoryManager()
    FileCollectionFactory fileCollectionFactory
    JavaForkOptionsFactory javaForkOptionsFactory
    InetAddressFactory inetAddressFactory

    @Inject
    DockerizedTestPlugin(MessagingServer messagingServer, FileCollectionFactory fileCollectionFactory, JavaForkOptionsFactory javaForkOptionsFactory, InetAddressFactory inetAddressFactory) {
        this.currentUser = SystemUtils.IS_OS_WINDOWS ? "0" : "id -u".execute().text.trim()
        MessageHubBackedServer backendServer = messagingServer as MessageHubBackedServer
        this.messagingServer = new MessageServer(backendServer.connector, backendServer.executorFactory)
        this.fileCollectionFactory = fileCollectionFactory
        this.javaForkOptionsFactory = javaForkOptionsFactory
        this.inetAddressFactory = inetAddressFactory
    }

    void configureTest(Project project, Test test) {
        LOGGER.debug("Configuring 'docker' extension in ${test.path}")
        def ext = test.extensions.create("docker", DockerizedTestExtension, [] as Object[])
        def startParameter = project.gradle.startParameter
        ext.volumes = [ "$startParameter.gradleUserHomeDir": "$startParameter.gradleUserHomeDir",
                        "$project.projectDir":"$project.projectDir"]
        ext.user = currentUser
        test.doFirst {
            def extension = test.extensions.docker

            if (extension?.image)
            {
                workerSemaphore.applyTo(test.project)
                def workerFactory = newProcessBuilderFactory(project,
                        extension as DockerizedTestExtension,
                        test)

                test.testExecuter = new TestExecuter(workerFactory,
                        test.actorFactory,
                        test.moduleRegistry,
                        test.services.get(BuildOperationExecutor),
                        test.services.get(Clock))

                if (!extension.client)
                {
                    extension.client = createDefaultClient()
                }
            }

        }
    }

    @SuppressWarnings("GrMethodMayBeStatic")
    DockerClient createDefaultClient() {
        DockerClientBuilder.getInstance(DefaultDockerClientConfig.createDefaultConfigBuilder())
                    .withDockerCmdExecFactory(new NettyDockerCmdExecFactory())
                    .build()
    }

    void apply(Project project) {

        if (new ComparableVersion(project.gradle.gradleVersion) < new ComparableVersion(supportedVersion))
            throw new GradleException("dockerized-test plugin requires Gradle ${supportedVersion}")

        project.tasks.withType(Test).each { test ->
            configureTest(project, test)
        }
        project.tasks.whenTaskAdded { task ->
            if (task instanceof Test)
                configureTest(project, task)
        }
    }

    def newProcessBuilderFactory(Project project, DockerizedTestExtension extension, Test test) {

        def executorFactory = new DefaultExecutorFactory()
        def executor = executorFactory.create("Docker container link")
        def buildCancellationToken = new DefaultBuildCancellationToken()

        def defaultProcessBuilderFactory = test.processBuilderFactory as DefaultWorkerProcessFactory
        def execHandleFactory = [newJavaExec: { ->
            GradleBridge.checkBindAddress(inetAddressFactory)

            def fileResolver = (project as ProjectInternal).fileResolver
            def forkOptions = new DefaultJavaForkOptions(fileResolver, fileCollectionFactory, copyDebugOptions(test.debugOptions))
            LOGGER.debug("Creating JavaExecHandleBuilder for 'docker' extension in ${test.path}")
            new DockerizedJavaExecHandleBuilder(extension, fileCollectionFactory, fileResolver, executor, buildCancellationToken, workerSemaphore, forkOptions, test)
        }] as JavaExecHandleFactory

        new DefaultWorkerProcessFactory(defaultProcessBuilderFactory.loggingManager,
                                        messagingServer,
                                        defaultProcessBuilderFactory.workerImplementationFactory.classPathRegistry,
                                        defaultProcessBuilderFactory.idGenerator,
                                        defaultProcessBuilderFactory.gradleUserHomeDir,
                                        defaultProcessBuilderFactory.workerImplementationFactory.temporaryFileProvider,
                                        execHandleFactory,
                                        new DockerizedJvmVersionDetector(extension),
                                        defaultProcessBuilderFactory.outputEventListener,
                                        memoryManager
                                        )
    }

    private static JavaDebugOptions copyDebugOptions(JavaDebugOptions source) {
        def debugOpts = new DefaultJavaDebugOptions()
        debugOpts.port.set(source.port.get())
        debugOpts.server.set(source.server.get())
        debugOpts.suspend.set(source.suspend.get())
        debugOpts.enabled.set(source.enabled.get())
        debugOpts
    }

    class MessageServer implements MessagingServer {
        IncomingConnector connector
        ExecutorFactory executorFactory

        MessageServer(IncomingConnector connector, ExecutorFactory executorFactory) {
            this.connector = connector
            this.executorFactory = executorFactory
        }

        ConnectionAcceptor accept(Action<ObjectConnection> action) {
            return new ConnectionAcceptorDelegate(connector.accept(new ConnectEventAction(action, executorFactory), true))
        }
    }

    class ConnectEventAction implements Action<ConnectCompletion> {
        def action
        ExecutorFactory executorFactory

        ConnectEventAction(Action<ObjectConnection> action, ExecutorFactory executorFactory) {
            this.executorFactory = executorFactory
            this.action = action
        }

        void execute(ConnectCompletion completion) {
            action.execute(new MessageHubBackedObjectConnection(executorFactory, completion))
        }
    }

    class ConnectionAcceptorDelegate implements ConnectionAcceptor {

        MultiChoiceAddress address

        @Delegate
        ConnectionAcceptor delegate

        ConnectionAcceptorDelegate(ConnectionAcceptor delegate) {
            this.delegate = delegate
        }

        Address getAddress() {
            synchronized (delegate)
            {
                if (address == null)
                {
                    def remoteAddresses = NetworkInterface.networkInterfaces.findAll { it.up && !it.loopback }*.inetAddresses*.collect { it }.flatten() as List<InetAddress>
                    def original = delegate.address as MultiChoiceAddress
                    address = new MultiChoiceAddress(original.canonicalAddress, original.port, remoteAddresses)
                }
            }
            address
        }
    }

}
