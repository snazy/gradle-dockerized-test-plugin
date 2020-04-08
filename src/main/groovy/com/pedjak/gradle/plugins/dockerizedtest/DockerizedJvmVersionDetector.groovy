package com.pedjak.gradle.plugins.dockerizedtest

import com.github.dockerjava.api.model.Frame
import com.github.dockerjava.api.model.StreamType
import com.github.dockerjava.core.command.AttachContainerResultCallback
import com.github.dockerjava.core.command.WaitContainerResultCallback
import com.sun.xml.internal.messaging.saaj.util.ByteOutputStream
import org.gradle.api.GradleException
import org.gradle.api.JavaVersion
import org.gradle.api.logging.Logger
import org.gradle.api.logging.Logging
import org.gradle.internal.jvm.JavaInfo
import org.gradle.internal.jvm.inspection.JvmVersionDetector

import java.util.concurrent.locks.Lock
import java.util.concurrent.locks.ReentrantLock
import java.util.regex.Matcher
import java.util.regex.Pattern

class DockerizedJvmVersionDetector implements JvmVersionDetector
{
    static final Logger LOGGER = Logging.getLogger(DockerizedJvmVersionDetector.class)

    private static final Lock lock = new ReentrantLock()

    final DockerizedTestExtension testExtension

    private static final imageJvms = [:]

    DockerizedJvmVersionDetector(DockerizedTestExtension testExtension)
    {
        this.testExtension = testExtension
    }

    @Override
    JavaVersion getJavaVersion(JavaInfo javaInfo)
    {
        getVersion()
    }

    @Override
    JavaVersion getJavaVersion(String s)
    {
        getVersion()
    }

    JavaVersion getVersion() {
        LOGGER.trace("Get Java version for Docker container image ${testExtension.image}")

        for (int i=0; i<10; i++)
        {
            lock.lock()
            try {
                def v = imageJvms[(testExtension.image)]
                if (v)
                    return v as JavaVersion

                def client = testExtension.client
                def createCmd = client.createContainerCmd(testExtension.image.toString())
                        .withTty(false)
                        .withCmd(['java', '-version'])

                LOGGER.debug("Executing 'java -version' in Docker container for container image ${testExtension.image}")

                def containerId = createCmd.exec().id
                client.startContainerCmd(containerId).exec()
                def w = new ByteOutputStream()
                client.attachContainerCmd(containerId)
                        .withFollowStream(true)
                        .withStdErr(true)
                        .exec(new AttachContainerResultCallback() {

                            void onNext(Frame frame) {
                                try {
                                    if (frame.streamType == StreamType.STDERR) {
                                        w.write(frame.getPayload())
                                    }
                                } catch (Exception e) {
                                    LOGGER.error("Error while writing to stream:", e)
                                }
                                super.onNext(frame)
                            }
                        })
                client.waitContainerCmd(containerId).exec(new WaitContainerResultCallback()).awaitStatusCode()
                LOGGER.trace("Container command for 'java -version' finished for container image ${testExtension.image}")
                client.removeContainerCmd(containerId).withForce(true).exec()

                // copied from org.gradle.internal.jvm.inspection.DefaultJvmVersionDetector.parseJavaVersionCommandOutput
                String completeResult = new String(w.getBytes())
                LOGGER.trace("Container command for 'java -version' for container image ${testExtension.image} returned\n${completeResult}")
                def reader = new BufferedReader(new StringReader(completeResult))

                String versionStr
                while ((versionStr = reader.readLine()) != null) {
                    LOGGER.trace("Checking line '$versionStr' from container image ${testExtension.image}")
                    Matcher matcher = Pattern.compile("(?:java|openjdk) version \"(.+?)\"( \\d{4}-\\d{2}-\\d{2}( LTS)?)?").matcher(versionStr)
                    if (matcher.matches()) {
                        v = JavaVersion.toVersion(matcher.group(1))
                        imageJvms[(testExtension.image)] = v
                        LOGGER.debug("Got Java version ${v} for container image ${testExtension.image}")
                        return v
                    }
                }
            } catch (Exception e) {
                throw new GradleException("Could not determine Java version in Docker image ${testExtension.image}", e)
            } finally {
                lock.unlock()
            }
        }
        throw new GradleException("Could not determine Java version in Docker image ${testExtension.image}")

    }
}
